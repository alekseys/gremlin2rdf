using System.Security.Cryptography;
using System.Text;

namespace DocumentSource;

internal static class SafeFiles
{
    public static string Resolve(string root, string relative)
    {
        if (string.IsNullOrWhiteSpace(relative) || Path.IsPathRooted(relative)
            || relative.Contains(':') || relative.Contains('\\')
            || relative.Split('/').Any(s => s is "" or "." or ".." || s.EndsWith(' ') || s.EndsWith('.')
                || s.IndexOfAny(['<', '>', '"', '|', '?', '*']) >= 0 || s.Any(char.IsControl)
                || System.Text.RegularExpressions.Regex.IsMatch(s,
                    "^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\.|$)",
                    System.Text.RegularExpressions.RegexOptions.IgnoreCase)))
            throw new Blocked("UNSAFE_ARTIFACT_PATH", "artifact-validation");
        var path = Path.GetFullPath(Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar)));
        var prefix = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!path.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            throw new Blocked("UNSAFE_ARTIFACT_PATH", "artifact-validation");
        for (var current = path; current != null; current = Path.GetDirectoryName(current))
        {
            if ((File.Exists(current) || Directory.Exists(current))
                && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new Blocked("LINKED_ARTIFACT_PATH", "artifact-validation");
        }
        return path;
    }
    public static string HashFile(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexStringLower(SHA256.HashData(stream));
    }
    public static FileStream New(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        return new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, FileOptions.WriteThrough);
    }
    public static void WriteNew(string path, byte[] bytes)
    {
        using var stream = New(path);
        stream.Write(bytes);
        stream.Flush(true);
    }
    public static void Atomic(string path, byte[] bytes)
    {
        var pending = path + ".pending";
        WriteNew(pending, bytes);
        for (var retry = 0; ; retry++)
        {
            try { File.Move(pending, path, true); return; }
            catch (IOException) when (retry < 4) { Thread.Sleep(25 * (retry + 1)); }
        }
    }
    public static IEnumerable<byte[]> Lines(string path, int maxBytes)
    {
        using var stream = File.OpenRead(path);
        using var line = new MemoryStream();
        var buffer = new byte[65536];
        int count;
        while ((count = stream.Read(buffer)) > 0)
        {
            for (var i = 0; i < count; i++)
            {
                if (buffer[i] == 10)
                {
                    if (line.Length == 0) throw new Blocked("EMPTY_NDJSON_LINE");
                    yield return line.ToArray();
                    line.SetLength(0);
                }
                else
                {
                    if (line.Length >= maxBytes) throw new Blocked("RECORD_SIZE_LIMIT", "artifact-validation");
                    line.WriteByte(buffer[i]);
                }
            }
        }
        if (line.Length != 0) throw new Blocked("INCOMPLETE_NDJSON", "artifact-validation");
    }
    public static void AppendLine(FileStream stream, byte[] bytes)
    {
        stream.Write(bytes);
        stream.WriteByte(10);
    }
    public static void Space(Config c)
    {
        if (new DriveInfo(Path.GetPathRoot(c.Root)!).AvailableFreeSpace < c.MinimumFreeDiskBytes + c.BatchBytes * 3L)
            throw new Blocked("INSUFFICIENT_DISK_SPACE", "disk-budget");
    }
    public static async Task<byte[]> BoundedRead(Stream stream, int maximum, CancellationToken token)
    {
        using var output = new MemoryStream();
        var buffer = new byte[16384];
        int count;
        while ((count = await stream.ReadAsync(buffer, token)) != 0)
        {
            if (output.Length + count > maximum) throw new Blocked("PAGE_SIZE_LIMIT", "source-page");
            output.Write(buffer, 0, count);
        }
        return output.ToArray();
    }
}
