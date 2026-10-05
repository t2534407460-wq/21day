using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Day21.Server;

public sealed record VaultWrite(long BaseRevision, JsonElement Value);
public sealed record VaultValue(long Revision, JsonElement Value);

/// <summary>Account data key wrapped by a host-mounted master key. This is server encryption, not end-to-end encryption.</summary>
public sealed class SecretVault(NpgsqlDataSource database,byte[] master)
{
    static readonly HashSet<string> Kinds=["deepseek","verification-points"];
    public async Task InitializeAsync(CancellationToken ct=default)
    {
        if(master.Length!=32)throw new InvalidOperationException("Vault requires a 32-byte mounted master key.");
        await using var cmd=database.CreateCommand("""
            CREATE TABLE IF NOT EXISTS vault_keys(owner uuid PRIMARY KEY,wrapped bytea NOT NULL);
            CREATE TABLE IF NOT EXISTS vault_values(owner uuid NOT NULL,kind text NOT NULL,revision bigint NOT NULL,payload bytea NOT NULL,PRIMARY KEY(owner,kind));
            """);await cmd.ExecuteNonQueryAsync(ct);
    }
    static byte[] Associated(Guid owner,string kind)=>Encoding.UTF8.GetBytes("21day/"+owner+"/"+kind);
    public static byte[] Seal(byte[] key,byte[] clear,byte[] associated)
    {
        var result=new byte[12+16+clear.Length];RandomNumberGenerator.Fill(result.AsSpan(0,12));
        using var aes=new AesGcm(key,16);aes.Encrypt(result.AsSpan(0,12),clear,result.AsSpan(28),result.AsSpan(12,16),associated);return result;
    }
    public static byte[] Open(byte[] key,byte[] sealedValue,byte[] associated)
    {
        if(sealedValue.Length<28)throw new CryptographicException();var clear=new byte[sealedValue.Length-28];
        using var aes=new AesGcm(key,16);aes.Decrypt(sealedValue.AsSpan(0,12),sealedValue.AsSpan(28),sealedValue.AsSpan(12,16),clear,associated);return clear;
    }
    public async Task<VaultValue?> Read(Guid owner,string kind,CancellationToken ct=default)
    {
        if(!Kinds.Contains(kind))throw new ArgumentException();
        await using var cmd=database.CreateCommand("SELECT k.wrapped,v.revision,v.payload FROM vault_keys k JOIN vault_values v USING(owner) WHERE k.owner=$1 AND v.kind=$2");cmd.Parameters.AddWithValue(owner);cmd.Parameters.AddWithValue(kind);
        await using var reader=await cmd.ExecuteReaderAsync(ct);if(!await reader.ReadAsync(ct))return null;
        var key=Open(master,reader.GetFieldValue<byte[]>(0),Associated(owner,"key"));byte[]? clear=null;
        try { clear=Open(key,reader.GetFieldValue<byte[]>(2),Associated(owner,kind));using var json=JsonDocument.Parse(clear);return new(reader.GetInt64(1),json.RootElement.Clone()); }
        finally { CryptographicOperations.ZeroMemory(key);if(clear is not null)CryptographicOperations.ZeroMemory(clear); }
    }
    public async Task<long?> Write(Guid owner,string kind,VaultWrite value,CancellationToken ct=default)
    {
        Validate(kind,value.Value);if(owner==Guid.Empty || value.BaseRevision<0)throw new ArgumentException();
        await using var db=await database.OpenConnectionAsync(ct);await using var tx=await db.BeginTransactionAsync(ct);
        var generated=RandomNumberGenerator.GetBytes(32);var wrapped=Seal(master,generated,Associated(owner,"key"));CryptographicOperations.ZeroMemory(generated);
        await using(var cmd=new NpgsqlCommand("INSERT INTO vault_keys(owner,wrapped) VALUES($1,$2) ON CONFLICT DO NOTHING",db,tx)) {cmd.Parameters.AddWithValue(owner);cmd.Parameters.AddWithValue(wrapped);await cmd.ExecuteNonQueryAsync(ct);}
        byte[] key;
        await using(var cmd=new NpgsqlCommand("SELECT wrapped FROM vault_keys WHERE owner=$1 FOR UPDATE",db,tx)) {cmd.Parameters.AddWithValue(owner);key=Open(master,(byte[])(await cmd.ExecuteScalarAsync(ct))!,Associated(owner,"key"));}
        byte[]? clear=null;
        try {
            long revision;
            await using(var cmd=new NpgsqlCommand("SELECT revision FROM vault_values WHERE owner=$1 AND kind=$2",db,tx)) {cmd.Parameters.AddWithValue(owner);cmd.Parameters.AddWithValue(kind);revision=(long?)(await cmd.ExecuteScalarAsync(ct)) ?? 0;}
            if(revision!=value.BaseRevision)return null;
            clear=Encoding.UTF8.GetBytes(value.Value.GetRawText());var encrypted=Seal(key,clear,Associated(owner,kind));
            await using(var cmd=new NpgsqlCommand("INSERT INTO vault_values(owner,kind,revision,payload) VALUES($1,$2,$3,$4) ON CONFLICT(owner,kind) DO UPDATE SET revision=excluded.revision,payload=excluded.payload",db,tx)) {cmd.Parameters.AddWithValue(owner);cmd.Parameters.AddWithValue(kind);cmd.Parameters.AddWithValue(revision+1);cmd.Parameters.AddWithValue(encrypted);await cmd.ExecuteNonQueryAsync(ct);}
            await tx.CommitAsync(ct);return revision+1;
        }finally{CryptographicOperations.ZeroMemory(key);if(clear is not null)CryptographicOperations.ZeroMemory(clear);}
    }
    static void Validate(string kind,JsonElement value)
    {
        if(!Kinds.Contains(kind) || value.ValueKind!=JsonValueKind.Object || value.GetRawText().Length>4096)throw new ArgumentException();
        var fields=value.EnumerateObject().Select(p=>p.Name).ToHashSet();
        if(kind=="deepseek") {
            if(!fields.SetEquals(["key"]))throw new ArgumentException();var key=value.GetProperty("key").GetString();
            if(key is null || key.Length is <16 or >512 || key.Any(c=>c is <'!' or >'~'))throw new ArgumentException();
        } else {
            if(!fields.SetEquals(["qrToken","nfcId"]))throw new ArgumentException();
            var qr=value.GetProperty("qrToken").GetString();var nfc=value.GetProperty("nfcId").GetString();
            if(qr is null || qr.Length>512 || !qr.StartsWith("rhythm://wake/",StringComparison.Ordinal) || nfc is null || nfc.Length>256)throw new ArgumentException();
        }
    }
}

public static class VaultEndpoints
{
    public static void MapVault(this IEndpointRouteBuilder endpoints,SecretVault? vault)
    {
        var group=endpoints.MapGroup("/21day-api/v1/vault").RequireAuthorization("project-sync");
        group.MapGet("/status",()=>Results.Ok(new {ready=vault is not null,encryption="server-envelope-aes-gcm",kinds=new[]{"deepseek","verification-points"}}));
        group.MapGet("/{kind}",async (ClaimsPrincipal user,string kind,HttpContext context,CancellationToken ct)=> {
            context.Response.Headers.CacheControl="no-store";if(vault is null)return Results.StatusCode(503);
            try {var data=await vault.Read(Guid.Parse(user.FindFirstValue("sub")!),kind,ct);return data is null ? Results.NotFound() : Results.Ok(data);}
            catch(ArgumentException){return Results.BadRequest();}
        });
        group.MapPut("/{kind}",async (ClaimsPrincipal user,string kind,VaultWrite request,HttpContext context,CancellationToken ct)=> {
            context.Response.Headers.CacheControl="no-store";if(vault is null)return Results.StatusCode(503);
            try {var rev=await vault.Write(Guid.Parse(user.FindFirstValue("sub")!),kind,request,ct);return rev is null ? Results.Conflict(new {message="云端保护配置已变化，请先恢复或核对。"}) : Results.Ok(new {revision=rev});}
            catch(ArgumentException){return Results.BadRequest();}
        });
    }
}
