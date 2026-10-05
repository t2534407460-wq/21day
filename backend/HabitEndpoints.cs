using System.Security.Claims;
using System.Text.Json;
using System.Text.Json.Nodes;
using ProjectInterop.Sync;

namespace Day21.Server;

public sealed record HabitCommand(SyncOperation Operation, string Action, string Day, string TimeZoneId, string DeviceId);

public static class HabitEndpoints
{
    public static void MapHabits(this IEndpointRouteBuilder endpoints, SyncStore store)
    {
        var group=endpoints.MapGroup("/21day-api/v1/habits").RequireAuthorization("project-sync");
        group.MapGet("/cards", async (ClaimsPrincipal user,string? timeZoneId,CancellationToken ct) =>
        {
            TimeZoneInfo zone;
            try { zone=TimeZoneInfo.FindSystemTimeZoneById(timeZoneId ?? "Asia/Shanghai"); }
            catch(TimeZoneNotFoundException) { return Results.BadRequest(new { message="时区无效。" }); }
            var day=DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow,zone).DateTime).ToString("yyyy-MM-dd");
            var snapshot=await store.SnapshotAsync(Guid.Parse(user.FindFirstValue("sub")!),ct);
            var cards=snapshot.Entities.Where(e=>e.EntityType=="habits" && !e.Deleted).Select(e=>
            {
                var data=SyncMerge.Node(e.Data)!;var plan=data["plan"]!;var start=DateOnly.ParseExact(Day21Policy.Text(plan,"start"),"yyyy-MM-dd");
                var inRound=string.CompareOrdinal(day,start.ToString("yyyy-MM-dd"))>=0 && string.CompareOrdinal(day,start.AddDays(20).ToString("yyyy-MM-dd"))<=0;
                var scheduled=inRound && Day21Policy.CanRecord(plan,day);
                return new { sourceProject="21day",sourceId=e.EntityId,sourceRevision=e.Revision,day,name=Day21Policy.Text(plan,"name"),
                    unit=Day21Policy.Text(plan,"unit"),input=Day21Policy.Text(plan,"input"),scheduled,
                    target=inRound ? Day21Policy.Rule(plan,day)["target"]!.GetValue<int>() : (int?)null,
                    entry=data["entries"]?[day],timer=data["timer"],data,
                    actions=data["timer"] is not null ? new[]{"timer_finish","timer_cancel","timer_takeover"} : scheduled ? Day21Policy.Text(plan,"input") switch {
                        "COUNT"=>new[]{"count","set_total","archive"},"TIMER"=>data["timer"] is null ? new[]{"timer_start","set_total","archive"}:new[]{"timer_finish","timer_cancel","timer_takeover"},
                        "DAILY"=>new[]{"confirm","set_total","archive"},_=>new[]{"set_total","archive"}
                    } : Array.Empty<string>() };
            }).ToArray();
            return Results.Ok(new { sourceProject="21day",cursor=snapshot.HighWatermark,cards });
        });
        group.MapPost("/commands",async (ClaimsPrincipal user,HabitCommand command,CancellationToken ct)=>
        {
            try {
                var result=await ExecuteCommand(store,Guid.Parse(user.FindFirstValue("sub")!),command,DateTimeOffset.UtcNow,ct);
                return Results.Ok(result);
            }
            catch(Exception e) when(e is ArgumentException or InvalidOperationException or FormatException or TimeZoneNotFoundException or JsonException or NullReferenceException or KeyNotFoundException) {
                return Results.BadRequest(new { message="操作与习惯规则不一致，请刷新来源记录后重试。" });
            }
        });
    }

    public static async Task<SyncResult> ExecuteCommand(SyncStore store,Guid owner,HabitCommand command,DateTimeOffset now,CancellationToken ct=default)
    {
        var replay=await store.ReplayAsync(owner,command.Operation,ct);
        if(replay is not null)return replay;
        ValidateCommand(command,now);
        return (await store.PushAsync(owner,[command.Operation],ct)).Single();
    }

    public static void ValidateCommand(HabitCommand command,DateTimeOffset now)
    {
        var op=command.Operation;
        if(op.EntityType!="habits" || op.Deleted || op.Baseline is null || op.Data is null || !Guid.TryParse(command.DeviceId,out _))throw new ArgumentException();
        var zone=TimeZoneInfo.FindSystemTimeZoneById(command.TimeZoneId);
        var today=DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(now,zone).DateTime);
        var day=DateOnly.ParseExact(command.Day,"yyyy-MM-dd");if(day>today)throw new ArgumentException();
        var baseline=SyncMerge.Node(op.Baseline)!.AsObject();var requested=SyncMerge.Node(op.Data)!.AsObject();
        Day21Policy.Validate(baseline,op.EntityId);Day21Policy.Validate(requested,op.EntityId);
        var expected=baseline.DeepClone();var plan=baseline["plan"]!;var current=baseline["entries"]?[command.Day];var next=requested["entries"]?[command.Day];
        if(!Day21Policy.CanRecord(plan,command.Day) && command.Action!="archive")throw new ArgumentException();
        if(command.Action=="archive") {
            if(baseline["timer"] is not null || plan["archivedOn"] is not null)throw new ArgumentException();
            expected["plan"]!["archivedOn"]=today.ToString("yyyy-MM-dd");
        } else if(command.Action=="timer_start") {
            var timer=requested["timer"] ?? throw new ArgumentException();
            if(Day21Policy.Text(plan,"input")!="TIMER" || baseline["timer"] is not null || Day21Policy.Text(timer,"device")!=command.DeviceId || Day21Policy.Text(timer,"day")!=command.Day || timer["at"]!.GetValue<long>()>now.AddMinutes(5).ToUnixTimeMilliseconds())throw new ArgumentException();
            expected["timer"]=timer.DeepClone();
        } else if(command.Action=="timer_takeover") {
            if(baseline["timer"] is null || requested["timer"] is null)throw new ArgumentException();
            expected["timer"]!["device"]=command.DeviceId;
        } else if(command.Action is "timer_finish" or "timer_cancel") {
            var timer=baseline["timer"] ?? throw new ArgumentException();
            if(Day21Policy.Text(timer,"device")!=command.DeviceId || Day21Policy.Text(timer,"day")!=command.Day)throw new ArgumentException("Timer belongs to another device.");
            expected["timer"]=null;
            if(command.Action=="timer_finish") {
                if(next is null || op.Kind!="timer_finish")throw new ArgumentException();
                var sessions=next["sessions"]!.AsArray();var previous=current?["sessions"]?.AsArray() ?? new JsonArray();
                if(sessions.Count!=previous.Count+1 || !sessions.Take(previous.Count).Zip(previous).All(p=>JsonNode.DeepEquals(p.First,p.Second)))throw new ArgumentException();
                var end=sessions[^1]!["end"]!.GetValue<long>();var start=timer["at"]!.GetValue<long>();
                if(sessions[^1]!["start"]!.GetValue<long>()!=start || end<start || end>now.AddMinutes(5).ToUnixTimeMilliseconds())throw new ArgumentException();
                var seconds=(current?["value"]?.GetValue<int>() ?? 0)*60L+(current?["remainderSeconds"]?.GetValue<int>() ?? 0)+(end-start)/1000;
                if(next["value"]!.GetValue<int>()!=seconds/60 || next["remainderSeconds"]!.GetValue<int>()!=seconds%60 || Day21Policy.Text(next,"note")!=(current is null ? "" : Day21Policy.Text(current,"note")))throw new ArgumentException();
                expected["entries"]![command.Day]=next.DeepClone();
            }
        } else {
            if(next is null)throw new ArgumentException();
            var input=Day21Policy.Text(plan,"input");var value=next["value"]!.GetValue<int>();
            if(command.Action=="count" && (input!="COUNT" || value!=(current?["value"]?.GetValue<int>() ?? 0)+1 || op.Kind!="count"))throw new ArgumentException();
            if(command.Action=="confirm" && (input!="DAILY" || value!=((plan["smoking"]?.GetValue<bool>() ?? false)?0:1)))throw new ArgumentException();
            if(command.Action is not ("count" or "confirm" or "set_total"))throw new ArgumentException();
            if(command.Action!="set_total" && (Day21Policy.Text(next,"note")!=(current is null ? "" : Day21Policy.Text(current,"note")) || next["sessions"]!.AsArray().Count!=0))throw new ArgumentException();
            if(command.Action=="set_total" && value!=(current?["value"]?.GetValue<int>() ?? -1) && (next["sessions"]!.AsArray().Count!=0 || next["remainderSeconds"]!.GetValue<int>()!=0))throw new ArgumentException();
            expected["entries"]![command.Day]=next.DeepClone();
        }
        var kind=command.Action switch {"count"=>"count","timer_finish"=>"timer_finish","timer_takeover"=>"timer_takeover",_=>"replace"};
        if(op.Kind!=kind || !JsonNode.DeepEquals(expected,requested))throw new ArgumentException();
    }
}
