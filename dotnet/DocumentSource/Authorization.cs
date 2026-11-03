using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace DocumentSource;

internal static class Authorization
{
    public static void RequireModes(Config c)
    {
        if (c.Fidelity == "reduced")
        {
            var mapping = c.Project.GetProperty("mapping");
            Require(c, "reduced-fidelity", [Json.Hash(Encoding.UTF8.GetBytes(Json.Stable(mapping)))]);
        }
        if (c.Consistency == "best-effort")
            Require(c, "best-effort-source", [c.Scope, c.ManifestHash]);
    }
    private static void Require(Config c, string operation, string[] resources)
    {
        Array.Sort(resources, StringComparer.Ordinal);
        foreach (var approval in c.Project.GetProperty("approvals").EnumerateArray())
        {
            if (Json.Text(approval, "operation") != operation || Json.Text(approval, "runId") != c.RunId) continue;
            var actual = approval.GetProperty("resources").EnumerateArray().Select(v => v.GetString()!).ToArray();
            var keyRef = Json.Text(approval, "keyRef");
            var expiry = Json.Text(approval, "expiresAt");
            if (approval.GetProperty("schemaVersion").GetInt32() != 1
                || !actual.SequenceEqual(resources) || Json.Text(approval, "inputHash") != c.InputHash
                || !DateTimeOffset.TryParse(expiry, out var expires) || expires <= DateTimeOffset.UtcNow
                || !Regex.IsMatch(keyRef, "^env:GRAPH_[A-Z0-9_]{1,100}$"))
                throw new Blocked("SCOPED_APPROVAL_REQUIRED", "source-authorization");
            var secret = Environment.GetEnvironmentVariable(keyRef[4..]);
            if (secret == null || Encoding.UTF8.GetByteCount(secret) < 32)
                throw new Blocked("SCOPED_APPROVAL_REQUIRED", "source-authorization");
            var payload = Json.Bytes(new object[] { 1, c.RunId, operation, resources, c.InputHash, expiry, keyRef });
            byte[] signature;
            try { signature = Convert.FromHexString(Json.Text(approval, "signature")); }
            catch (FormatException) { throw new Blocked("SCOPED_APPROVAL_REQUIRED", "source-authorization"); }
            if (!CryptographicOperations.FixedTimeEquals(
                HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), payload), signature))
                throw new Blocked("SCOPED_APPROVAL_REQUIRED", "source-authorization");
            return;
        }
        throw new Blocked("SCOPED_APPROVAL_REQUIRED", "source-authorization");
    }
}
