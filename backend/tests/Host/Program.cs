using System.Security.Claims;
using Day21.Server;
using Npgsql;
using ProjectInterop.Sync;

// Local synthetic-data harness only. It is excluded from all application publishing.
var admin=Environment.GetEnvironmentVariable("INTEROP_TEST_DATABASE") ?? "Host=127.0.0.1;Port=25378;Username=postgres;Database=postgres";
var name="day21_qa_"+Guid.NewGuid().ToString("N");
await using(var db=new NpgsqlConnection(admin)) {await db.OpenAsync();await using var create=new NpgsqlCommand($"CREATE DATABASE {name}",db);await create.ExecuteNonQueryAsync();}
try {
    await using var data=NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(admin){Database=name}.ToString());
    var store=new SyncStore(data,"21day",new HashSet<string>{"habits","sleep_plans","sleep_logs","sleep_events","preferences","ui_layouts","chats","reviews"},new Day21Policy());await store.InitializeAsync();
    var builder=WebApplication.CreateBuilder(args);builder.WebHost.UseUrls("http://127.0.0.1:18769");builder.Logging.ClearProviders();
    builder.Services.AddAuthorization(o=>o.AddPolicy("project-sync",p=>p.RequireAuthenticatedUser()));
    var app=builder.Build();
    app.Use(async (context,next)=> {
        if(Guid.TryParse(context.Request.Headers["X-QA-Owner"],out var owner))context.User=new ClaimsPrincipal(new ClaimsIdentity([new Claim("sub",owner.ToString())],"local-qa"));
        var original=context.Response.Body;await using var buffer=new MemoryStream();context.Response.Body=buffer;
        try {await next(context);context.Response.Body=original;context.Response.ContentLength=buffer.Length;buffer.Position=0;await buffer.CopyToAsync(original);}
        finally{context.Response.Body=original;}
    });
    app.UseAuthorization();app.MapProjectSync(store,"/21day-api/v1");app.MapHabits(store);
    app.MapGet("/qa/health",()=>Results.Ok(new{synthetic=true}));app.MapPost("/qa/stop",(IHostApplicationLifetime life)=>{life.StopApplication();return Results.Ok();});
    Console.WriteLine("Synthetic 21day QA host: 127.0.0.1:18769");await app.RunAsync();
}finally{
    await using var db=new NpgsqlConnection(admin);await db.OpenAsync();await using var drop=new NpgsqlCommand($"DROP DATABASE {name} WITH (FORCE)",db);await drop.ExecuteNonQueryAsync();
}
