using System.Text.Json;
using System.Text.Json.Nodes;
using Npgsql;
using ProjectInterop.Sync;
using Day21.Server;

public sealed class SyncTests
{
    static readonly string Id = Guid.NewGuid().ToString();
    static readonly string Day = DateOnly.FromDateTime(DateTime.Today).ToString("yyyy-MM-dd");
    static JsonObject Habit(string input = "COUNT") => JsonSerializer.SerializeToNode(new {
        plan = new { id = Id, name = "测试", start = Day, mode = "AT_LEAST", unit = input == "TIMER" ? "分钟" : "次", input,
            rules = new[] { new { from = Day, days = new[] { 1,2,3,4,5,6,7 }, target = 1, reminder = (int?)null } }, archivedOn = (string?)null, smoking = false, visual = "WORK" },
        entries = new Dictionary<string,object>(), timer = (object?)null
    })!.AsObject();
    static JsonNode Entry(int value, object[]? sessions = null, int remainder = 0) => JsonSerializer.SerializeToNode(new { habitId = Id, date = Day, value, note = "", recordedAt = 1791158400000L, remainderSeconds = remainder, sessions = sessions ?? [] })!;
    static JsonElement Element(JsonNode node) => JsonSerializer.SerializeToElement(node);
    static SyncOperation Op(JsonNode data,long rev=0,JsonNode? baseline=null,string kind="replace") => new(Guid.NewGuid(),"habits",Id,rev,1,Element(data),false,baseline is null ? null : Element(baseline),kind);
    [Fact] public async Task IndependentCountsAreAddedAndLostResponseReplayIsIdempotent()
    {
        await using var s=await Scope.Create();var first=Habit();var owner=Guid.NewGuid();
        var saved=Assert.Single(await s.Store.PushAsync(owner,[Op(first)]));
        var a=first.DeepClone();a["entries"]![Day]=Entry(1);var b=a.DeepClone();
        var oa=Op(a,saved.Revision,first,"count");var ob=Op(b,saved.Revision,first,"count");
        Assert.Equal("applied",Assert.Single(await s.Store.PushAsync(owner,[oa])).Status);
        Assert.Equal("applied",Assert.Single(await s.Store.PushAsync(owner,[ob])).Status);
        await s.Store.PushAsync(owner,[oa,ob,oa]);
        var snapshot=await s.Store.SnapshotAsync(owner);Assert.Equal(3,snapshot.HighWatermark);
        Assert.Equal(2,snapshot.Entities.Single().Data!.Value.GetProperty("entries").GetProperty(Day).GetProperty("value").GetInt32());
    }
    [Fact] public async Task DifferentFieldsMergeButConcurrentTotalCorrectionsConflict()
    {
        await using var s=await Scope.Create();var first=Habit();var owner=Guid.NewGuid();
        var saved=Assert.Single(await s.Store.PushAsync(owner,[Op(first)]));
        var rename=first.DeepClone();rename["plan"]!["name"]="新名称";
        var count=first.DeepClone();count["entries"]![Day]=Entry(4);
        await s.Store.PushAsync(owner,[Op(rename,saved.Revision,first)]);
        var merged=Assert.Single(await s.Store.PushAsync(owner,[Op(count,saved.Revision,first)]));Assert.Equal("applied",merged.Status);
        Assert.Equal("新名称",merged.Current!.Value.GetProperty("plan").GetProperty("name").GetString());
        var other=first.DeepClone();other["entries"]![Day]=Entry(9);
        Assert.Equal("conflict",Assert.Single(await s.Store.PushAsync(owner,[Op(other,saved.Revision,first)])).Status);
    }
    [Fact] public async Task TwoOfflineTimersConflictAndFinishRetainsRemainder()
    {
        await using var s=await Scope.Create();var first=Habit("TIMER");var owner=Guid.NewGuid();
        var saved=Assert.Single(await s.Store.PushAsync(owner,[Op(first)]));
        var a=first.DeepClone();a["timer"]=JsonSerializer.SerializeToNode(new { id=Guid.NewGuid(),device=Guid.NewGuid(),day=Day,at=1791158400000L });
        var b=first.DeepClone();b["timer"]=JsonSerializer.SerializeToNode(new { id=Guid.NewGuid(),device=Guid.NewGuid(),day=Day,at=1791158400010L });
        var active=Assert.Single(await s.Store.PushAsync(owner,[Op(a,saved.Revision,first)]));Assert.Equal("applied",active.Status);
        Assert.Equal("conflict",Assert.Single(await s.Store.PushAsync(owner,[Op(b,saved.Revision,first)])).Status);
        var finish=a.DeepClone();finish["timer"]=null;finish["entries"]![Day]=Entry(1,[new {start=1791158400000L,end=1791158490000L}],30);
        var op=Op(finish,active.Revision,a,"timer_finish");
        Assert.Equal("applied",Assert.Single(await s.Store.PushAsync(owner,[op])).Status);await s.Store.PushAsync(owner,[op]);
        var e=(await s.Store.SnapshotAsync(owner)).Entities.Single().Data!.Value;
        Assert.Equal(JsonValueKind.Null,e.GetProperty("timer").ValueKind);Assert.Equal(30,e.GetProperty("entries").GetProperty(Day).GetProperty("remainderSeconds").GetInt32());
    }
    [Fact] public async Task AccountsAreIsolatedAndInvalidHabitsDoNotAdvanceCursor()
    {
        await using var s=await Scope.Create();var owner=Guid.NewGuid();var bad=Habit();bad["plan"]!["rules"]![0]!["target"]=-1;
        Assert.Equal("invalid",Assert.Single(await s.Store.PushAsync(owner,[Op(bad)])).Status);
        Assert.Equal(0,(await s.Store.PullAsync(owner,0)).HighWatermark);
        await s.Store.PushAsync(owner,[Op(Habit())]);Assert.Empty((await s.Store.SnapshotAsync(Guid.NewGuid())).Entities);
    }
    [Fact] public void ThreeWayMergePreservesUnknownFieldsAndDistinguishesDeletion()
    {
        var b=JsonNode.Parse("{\"a\":1,\"b\":2}");var l=JsonNode.Parse("{\"b\":2}");var r=JsonNode.Parse("{\"a\":1,\"b\":3,\"future\":true}");
        Assert.True(SyncMerge.TryMerge(b,l,r,out var merged));Assert.Null(merged!["a"]);Assert.Equal(3,merged["b"]!.GetValue<int>());Assert.True(merged["future"]!.GetValue<bool>());
        Assert.False(SyncMerge.TryMerge(b,l,JsonNode.Parse("{\"a\":9,\"b\":2}"),out _));
    }
    [Fact] public void SourceCommandsRejectFutureDatesUnrelatedChangesAndForeignTimerEnd()
    {
        var first=Habit();var desired=first.DeepClone();desired["entries"]![Day]=Entry(1);
        var command=new HabitCommand(Op(desired,1,first,"count"),"count",Day,TimeZoneInfo.Local.Id,Guid.NewGuid().ToString());
        HabitEndpoints.ValidateCommand(command,DateTimeOffset.Now);
        var altered=desired.DeepClone();altered["plan"]!["name"]="unrelated";
        Assert.Throws<ArgumentException>(()=>HabitEndpoints.ValidateCommand(command with {Operation=Op(altered,1,first,"count")},DateTimeOffset.Now));
        Assert.Throws<ArgumentException>(()=>HabitEndpoints.ValidateCommand(command with {Day=DateOnly.FromDateTime(DateTime.Today).AddDays(1).ToString("yyyy-MM-dd")},DateTimeOffset.Now));
        var timer=Habit("TIMER");timer["timer"]=JsonSerializer.SerializeToNode(new {at=DateTimeOffset.Now.AddMinutes(-2).ToUnixTimeMilliseconds(),day=Day,id=Guid.NewGuid(),device=Guid.NewGuid()});
        var cancel=timer.DeepClone();cancel["timer"]=null;
        Assert.Throws<ArgumentException>(()=>HabitEndpoints.ValidateCommand(new(Op(cancel,1,timer),"timer_cancel",Day,TimeZoneInfo.Local.Id,Guid.NewGuid().ToString()),DateTimeOffset.Now));
    }
    [Fact] public async Task VaultIsEncryptedAccountBoundAndRejectsLostUpdate()
    {
        await using var s=await Scope.Create();var master=System.Security.Cryptography.RandomNumberGenerator.GetBytes(32);var vault=new SecretVault(s.Database,master);await vault.InitializeAsync();
        var owner=Guid.NewGuid();var key=JsonSerializer.SerializeToElement(new {key="synthetic-key-123456789-only"});
        Assert.Equal(1,await vault.Write(owner,"deepseek",new(0,key)));Assert.Null(await vault.Write(owner,"deepseek",new(0,key)));
        Assert.Equal(key.GetRawText(),(await vault.Read(owner,"deepseek"))!.Value.GetRawText());Assert.Null(await vault.Read(Guid.NewGuid(),"deepseek"));
        await using var sql=s.Database.CreateCommand("SELECT payload FROM vault_values WHERE owner=$1");sql.Parameters.AddWithValue(owner);
        var bytes=(byte[])(await sql.ExecuteScalarAsync())!;Assert.DoesNotContain("synthetic-key",System.Text.Encoding.UTF8.GetString(bytes));
        var aad=System.Text.Encoding.UTF8.GetBytes("account-a");var sealedValue=SecretVault.Seal(master,[1,2,3],aad);sealedValue[^1]^=1;
        Assert.ThrowsAny<System.Security.Cryptography.CryptographicException>(()=>SecretVault.Open(master,sealedValue,aad));
        var valid=SecretVault.Seal(master,[1,2,3],aad);Assert.ThrowsAny<System.Security.Cryptography.CryptographicException>(()=>SecretVault.Open(master,valid,System.Text.Encoding.UTF8.GetBytes("account-b")));
    }
    [Fact] public async Task TimerTakeoverRequiresMatchingActiveSessionAndDoesNotChangeStart()
    {
        await using var s=await Scope.Create();var owner=Guid.NewGuid();var timer=Habit("TIMER");timer["timer"]=JsonSerializer.SerializeToNode(new {at=DateTimeOffset.Now.AddMinutes(-2).ToUnixTimeMilliseconds(),day=Day,id=Guid.NewGuid(),device=Guid.NewGuid()});
        var saved=Assert.Single(await s.Store.PushAsync(owner,[Op(timer)]));var next=timer.DeepClone();next["timer"]!["device"]=Guid.NewGuid().ToString();
        Assert.Equal("invalid",Assert.Single(await s.Store.PushAsync(owner,[Op(next,saved.Revision,timer)])).Status);
        var result=Assert.Single(await s.Store.PushAsync(owner,[Op(next,saved.Revision,timer,"timer_takeover")]));Assert.Equal("applied",result.Status);
        Assert.Equal(timer["timer"]!["at"]!.GetValue<long>(),result.Current!.Value.GetProperty("timer").GetProperty("at").GetInt64());
        var again=timer.DeepClone();again["timer"]!["device"]=Guid.NewGuid().ToString();Assert.Equal("conflict",Assert.Single(await s.Store.PushAsync(owner,[Op(again,saved.Revision,timer,"timer_takeover")])).Status);
    }
    [Fact] public async Task ArchiveReceiptReplaysAcrossMidnightButRejectsChangedPayload()
    {
        await using var s=await Scope.Create();var owner=Guid.NewGuid();var initial=Habit();
        var saved=Assert.Single(await s.Store.PushAsync(owner,[Op(initial)]));var archived=initial.DeepClone();archived["plan"]!["archivedOn"]=Day;
        var command=new HabitCommand(Op(archived,saved.Revision,initial),"archive",Day,TimeZoneInfo.Local.Id,Guid.NewGuid().ToString());
        var first=await HabitEndpoints.ExecuteCommand(s.Store,owner,command,DateTimeOffset.Now);
        Assert.Equal("applied",first.Status);
        var replay=await HabitEndpoints.ExecuteCommand(s.Store,owner,command,DateTimeOffset.Now.AddDays(1));
        Assert.Equal(first.Revision,replay.Revision);Assert.Equal(2,(await s.Store.SnapshotAsync(owner)).HighWatermark);
        var tampered=archived.DeepClone();tampered["plan"]!["name"]="unexpected";
        await Assert.ThrowsAsync<ArgumentException>(()=>HabitEndpoints.ExecuteCommand(s.Store,owner,command with{Operation=command.Operation with{Data=Element(tampered)}},DateTimeOffset.Now.AddDays(1)));
    }
    sealed class Scope:IAsyncDisposable
    {
        readonly string name="day21_test_"+Guid.NewGuid().ToString("N");
        readonly string admin=Environment.GetEnvironmentVariable("INTEROP_TEST_DATABASE") ?? "Host=127.0.0.1;Port=25378;Username=postgres;Database=postgres";
        NpgsqlDataSource database=null!;public SyncStore Store {get;private set;}=null!;
        public NpgsqlDataSource Database=>database;
        public static async Task<Scope> Create() {
            var s=new Scope();await using var db=new NpgsqlConnection(s.admin);await db.OpenAsync();await using var create=new NpgsqlCommand($"CREATE DATABASE {s.name}",db);await create.ExecuteNonQueryAsync();
            s.database=NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(s.admin){Database=s.name}.ToString());s.Store=new(s.database,"21day",new HashSet<string>{"habits"},new Day21Policy());await s.Store.InitializeAsync();return s;
        }
        public async ValueTask DisposeAsync() { await database.DisposeAsync();await using var db=new NpgsqlConnection(admin);await db.OpenAsync();await using var drop=new NpgsqlCommand($"DROP DATABASE {name} WITH (FORCE)",db);await drop.ExecuteNonQueryAsync(); }
    }
}
