using System.Text.Json;
using System.Text.Json.Nodes;
using ProjectInterop.Sync;

namespace Day21.Server;

public sealed class Day21Policy : ISyncPolicy
{
    public SyncResolution Resolve(SyncOperation op, SyncEntity? current)
    {
        try
        {
            if (op.EntityType != "habits") return SyncMerge.Resolve(op, current);
            if (op.Deleted) return SyncMerge.Resolve(op,current); // Source commands only archive; explicit phone data replacement uses a tombstone.
            var requested = SyncMerge.Node(op.Data)!.AsObject();
            Validate(requested, op.EntityId);
            var old = SyncMerge.Node(op.Baseline)?.AsObject();
            var authoritative = SyncMerge.Node(current?.Data)?.AsObject();
            if (current is null)
                return op.Kind == "replace" && op.BaseRevision == 0 ? new("applied", op.Data) : new("conflict", null);
            if (authoritative is null || current.Deleted) return new("deleted", null);
            if (op.Kind == "count")
            {
                if (old is null || !JsonNode.DeepEquals(old["plan"], requested["plan"]) || !JsonNode.DeepEquals(old["timer"], requested["timer"])) return new("invalid", current.Data);
                var changes = ChangedEntries(old, requested);
                if (changes.Count != 1) return new("invalid", current.Data);
                var day = changes[0]; var next = requested["entries"]![day]!.AsObject();
                var original = old["entries"]?[day]; var existing = authoritative["entries"]?[day];
                var plan = authoritative["plan"]!;
                if (Text(plan, "input") != "COUNT" || next["value"]!.GetValue<int>() != (original?["value"]?.GetValue<int>() ?? 0) + 1) return new("invalid", current.Data);
                if (!CanRecord(plan, day) || original is not null && existing is null) return new("conflict", current.Data);
                var updated = existing?.DeepClone() ?? next.DeepClone();
                updated["value"] = (existing?["value"]?.GetValue<int>() ?? 0) + 1;
                updated["recordedAt"] = next["recordedAt"]!.DeepClone();
                authoritative["entries"]![day] = updated;
                Validate(authoritative, op.EntityId);
                return Applied(authoritative);
            }
            if (op.Kind == "timer_finish")
            {
                var active = authoritative["timer"]; var started = old?["timer"];
                if (active is null || started is null || requested["timer"] is not null || !JsonNode.DeepEquals(active, started)) return new("conflict", current.Data);
                var day = Text(active, "day");
                if (!CanRecord(authoritative["plan"]!, day) || Text(authoritative["plan"]!, "input") != "TIMER") return new("conflict", current.Data);
                var prior = old!["entries"]?[day]; var next = requested["entries"]?[day];
                var sessions = next?["sessions"]?.AsArray();
                if (sessions is null || sessions.Count != (prior?["sessions"]?.AsArray().Count ?? 0) + 1 || ChangedEntries(old, requested).Any(d => d != day)) return new("invalid", current.Data);
                var session = sessions[^1]!; var start = session["start"]!.GetValue<long>(); var end = session["end"]!.GetValue<long>();
                if (start != active["at"]!.GetValue<long>() || end < start) return new("invalid", current.Data);
                var existing = authoritative["entries"]?[day];
                // A concurrent manual correction is not silently combined with an old timer.
                if (!JsonNode.DeepEquals(existing, prior)) return new("conflict", current.Data);
                authoritative["entries"]![day] = next!.DeepClone(); authoritative["timer"] = null;
                Validate(authoritative, op.EntityId); return Applied(authoritative);
            }
            if(op.Kind=="timer_takeover") {
                if(old?["timer"] is null || authoritative["timer"] is null || requested["timer"] is null || !JsonNode.DeepEquals(old["timer"],authoritative["timer"]))return new("conflict",current.Data);
                var expected=old.DeepClone();expected["timer"]!["device"]=requested["timer"]!["device"]!.DeepClone();
                if(!JsonNode.DeepEquals(expected,requested))return new("invalid",current.Data);
                authoritative["timer"]!["device"]=requested["timer"]!["device"]!.DeepClone();return Applied(authoritative);
            }
            if (op.Kind != "replace") return new("unsupported", current.Data);
            var result = SyncMerge.Resolve(op, current);
            if (result.Status != "applied") return result;
            var merged = SyncMerge.Node(result.Data)!.AsObject();
            if(old?["timer"] is not null && requested["timer"] is not null && Day21Policy.Text(old["timer"]!,"device")!=Day21Policy.Text(requested["timer"]!,"device"))return new("invalid",current.Data);
            // A remote edit cannot release an active timer or silently archive a running round.
            if (old is not null && old["timer"] is null && requested["timer"] is not null && authoritative["timer"] is not null && !JsonNode.DeepEquals(requested["timer"], authoritative["timer"])) return new("conflict", current.Data);
            Validate(merged, op.EntityId); return Applied(merged);
        }
        catch (Exception e) when (e is ArgumentException or InvalidOperationException or FormatException or NullReferenceException or KeyNotFoundException or OverflowException)
        { return new("invalid", current?.Data); }
    }

    static SyncResolution Applied(JsonNode data) => new("applied", JsonSerializer.SerializeToElement(data));
    static List<string> ChangedEntries(JsonObject before, JsonObject after)
    {
        var b = before["entries"]!.AsObject(); var a = after["entries"]!.AsObject();
        return b.Select(p => p.Key).Concat(a.Select(p => p.Key)).Distinct().Where(k => !JsonNode.DeepEquals(b[k], a[k])).ToList();
    }
    public static string Text(JsonNode node, string name) => node[name]?.GetValue<string>() ?? "";
    public static bool CanRecord(JsonNode plan, string day)
    {
        var date = DateOnly.ParseExact(day, "yyyy-MM-dd"); var start = DateOnly.ParseExact(Text(plan, "start"), "yyyy-MM-dd");
        if (plan["archivedOn"] is not null || date < start || date > start.AddDays(20)) return false;
        return Rule(plan, day)["days"]!.AsArray().Any(d => d!.GetValue<int>() == (date.DayOfWeek == DayOfWeek.Sunday ? 7 : (int)date.DayOfWeek));
    }
    public static JsonNode Rule(JsonNode plan, string day) => plan["rules"]!.AsArray().Last(r => string.CompareOrdinal(Text(r!, "from"), day) <= 0)!;
    public static void Validate(JsonObject data, string id)
    {
        var plan = data["plan"] ?? throw new ArgumentException("Missing habit plan.");
        if (!Guid.TryParse(id, out _) || Text(plan,"id") != id || Text(plan,"name").Length is < 1 or > 40 || Text(plan,"unit").Length is < 1 or > 8) throw new ArgumentException("Invalid habit.");
        var start = DateOnly.ParseExact(Text(plan,"start"), "yyyy-MM-dd"); var rules = plan["rules"]!.AsArray();
        var mode = Text(plan,"mode"); var input = Text(plan,"input");
        if (mode is not ("CHECK" or "AT_LEAST" or "AT_MOST") || input is not ("MANUAL" or "COUNT" or "TIMER" or "DAILY") || rules.Count is < 1 or > 21) throw new ArgumentException("Invalid habit rules.");
        DateOnly? previous = null;
        foreach (var rule in rules)
        {
            var from = DateOnly.ParseExact(Text(rule!,"from"),"yyyy-MM-dd"); var target = rule!["target"]!.GetValue<int>(); var days = rule["days"]!.AsArray();
            if (previous is null && from != start || previous is not null && from <= previous || from > start.AddDays(20) || target is < 0 or > 100000 || mode != "AT_MOST" && target == 0 || mode == "CHECK" && target != 1 || days.Count is < 1 or > 7 || days.Any(d => d!.GetValue<int>() is < 1 or > 7)) throw new ArgumentException("Invalid rule.");
            if (rule["reminder"] is not null && rule["reminder"]!.GetValue<int>() is < 0 or > 1439) throw new ArgumentException("Invalid reminder.");
            previous = from;
        }
        if (input == "TIMER" && (Text(plan,"unit") != "分钟" || mode != "AT_LEAST") || input == "COUNT" && mode == "CHECK") throw new ArgumentException("Invalid input mode.");
        if (plan["archivedOn"] is not null) DateOnly.ParseExact(Text(plan,"archivedOn"),"yyyy-MM-dd");
        var entries = data["entries"]!.AsObject(); if (entries.Count > 21) throw new ArgumentException("Too many entries.");
        foreach (var (day, entry) in entries)
        {
            var date = DateOnly.ParseExact(day,"yyyy-MM-dd");
            if (date < start || date > start.AddDays(20) || Text(entry!,"habitId") != id || Text(entry!,"date") != day || entry!["value"]!.GetValue<int>() is < 0 or > 100000 || Text(entry,"note").Length > 300 || entry["remainderSeconds"]!.GetValue<int>() is < 0 or > 59 || entry["recordedAt"]!.GetValue<long>() <= 0) throw new ArgumentException("Invalid entry.");
            if (mode == "CHECK" && entry["value"]!.GetValue<int>() > 1) throw new ArgumentException("Invalid check.");
            var sessions = entry["sessions"]!.AsArray();
            if (sessions.Count > 200 || input != "TIMER" && (sessions.Count != 0 || entry["remainderSeconds"]!.GetValue<int>() != 0)) throw new ArgumentException("Invalid sessions.");
            foreach (var session in sessions) if (session!["start"]!.GetValue<long>() <= 0 || session["end"]!.GetValue<long>() < session["start"]!.GetValue<long>()) throw new ArgumentException("Invalid time range.");
        }
        if (data["timer"] is { } timer)
        {
            if (input != "TIMER" || !CanRecord(plan, Text(timer,"day")) || timer["at"]!.GetValue<long>() <= 0 || !Guid.TryParse(Text(timer,"id"), out _) || !Guid.TryParse(Text(timer,"device"), out _)) throw new ArgumentException("Invalid timer.");
        }
    }
}
