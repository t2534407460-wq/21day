using Npgsql;
using ProjectInterop.Sync;
using Day21.Server;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(o => o.Limits.MaxRequestBodySize = 4 * 1024 * 1024);
builder.Services.AddProjectAuthentication("https://zhuisu.leadjet.com.cn/identity/v1", "21day");
await using var database = NpgsqlDataSource.Create(builder.Configuration.GetConnectionString("Day21") ?? throw new InvalidOperationException("21day database connection is required."));
var store = new SyncStore(database, "21day", new HashSet<string>(StringComparer.Ordinal)
{ "habits", "sleep_plans", "sleep_logs", "sleep_events", "preferences", "ui_layouts", "chats", "reviews" }, new Day21Policy());
await store.InitializeAsync();
SecretVault? vault=null;
var vaultFile=builder.Configuration["Vault:MasterKeyFile"];
if(!string.IsNullOrWhiteSpace(vaultFile)) {vault=new SecretVault(database,await File.ReadAllBytesAsync(vaultFile));await vault.InitializeAsync();}
var app = builder.Build(); app.UseAuthentication(); app.UseAuthorization();
app.MapGet("/health", () => Results.Ok(new { project = "21day", api = "v1", build = "0.2.0" }));
app.MapProjectSync(store, "/21day-api/v1");
app.MapHabits(store);
app.MapVault(vault);
await app.RunAsync();
