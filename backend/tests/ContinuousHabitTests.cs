using System.Text.Json;
using System.Text.Json.Nodes;
using Day21.Server;
using ProjectInterop.Sync;

public class ContinuousHabitTests
{
    static JsonObject Habit()
    {
        var id=Guid.NewGuid().ToString();
        return JsonSerializer.SerializeToNode(new {
            plan=new {id,name="连续习惯",start="2026-09-01",mode="AT_LEAST",unit="次",input="COUNT",
                rules=new[]{new {from="2026-09-01",days=new[]{1,2,3,4,5,6,7},target=1,reminder=(int?)540}},archivedOn=(string?)null,smoking=false,visual="WORK"},
            entries=new Dictionary<string,object>(),timer=(object?)null
        })!.AsObject();
    }
    [Fact] public void MonthsOfRecordsAndRuleHistoryRemainValid()
    {
        var data=Habit();var id=Day21Policy.Text(data["plan"]!,"id");
        for(var i=0;i<70;i++) {
            var day=new DateOnly(2026,9,1).AddDays(i).ToString("yyyy-MM-dd");
            data["entries"]![day]=JsonSerializer.SerializeToNode(new {habitId=id,date=day,value=1,note="",recordedAt=1791158400000L,remainderSeconds=0,sessions=Array.Empty<object>()});
            if(i>0)data["plan"]!["rules"]!.AsArray().Add(JsonSerializer.SerializeToNode(new {from=day,days=new[]{1,2,3,4,5,6,7},target=i+1,reminder=(int?)540}));
        }
        Day21Policy.Validate(data,id);
        Assert.True(Day21Policy.CanRecord(data["plan"]!,"2026-11-10"));
        Assert.False(Day21Policy.CanRecord(data["plan"]!,"2026-08-31"));
        data["plan"]!["archivedOn"]="2026-11-10";
        Assert.False(Day21Policy.CanRecord(data["plan"]!,"2026-11-11"));
    }
    [Fact] public void CountSourceCommandAndMergeWorkAfterDay21()
    {
        var baseline=Habit();var data=baseline.DeepClone();var id=Day21Policy.Text(data["plan"]!,"id");var day="2026-10-26";
        data["entries"]![day]=JsonSerializer.SerializeToNode(new {habitId=id,date=day,value=1,note="",recordedAt=1791158400000L,remainderSeconds=0,sessions=Array.Empty<object>()});
        var op=new SyncOperation(Guid.NewGuid(),"habits",id,1,1,JsonSerializer.SerializeToElement(data),false,JsonSerializer.SerializeToElement(baseline),"count");
        HabitEndpoints.ValidateCommand(new(op,"count",day,"Asia/Shanghai",Guid.NewGuid().ToString()),DateTimeOffset.Parse("2026-10-26T12:00:00+08:00"));
        var created=new Day21Policy().Resolve(op with {Kind="replace",BaseRevision=0},null);
        Assert.Equal("applied",created.Status);
        data["entries"]![day]!["value"]=-1;
        Assert.Throws<ArgumentException>(()=>Day21Policy.Validate(data.AsObject(),id));
    }
}
