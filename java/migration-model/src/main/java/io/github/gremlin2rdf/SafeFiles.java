package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

public final class SafeFiles {
    private SafeFiles() {}
    public static void relative(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || Path.of(value).isAbsolute() || value.contains("\\")
            || value.contains(":") || java.util.Arrays.asList(value.split("/")).contains(".."))
            throw new IllegalArgumentException("Artifact must be a safe relative path");
    }
    public static Path resolve(Path root, String value) throws IOException {
        relative(value);
        Path base = root.toRealPath(), result = base.resolve(value).normalize();
        if (!result.startsWith(base)) throw new SecurityException("Artifact path escapes run directory");
        for (Path p = result; p != null && !p.equals(base); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new SecurityException("Symlink artifact paths prohibited");
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !p.toRealPath().startsWith(base))
                throw new SecurityException("Artifact junction or resolved path escapes run directory");
        }
        return result;
    }
    public static void atomic(Path output, Object value) throws IOException {
        Path temp = output.resolveSibling(output.getFileName() + ".pending");
        byte[] bytes = Json.text(value).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Artifact filesystem must support atomic checkpoints; pending state preserved", e);
        }
    }
    public static String fileHash(Path path) throws IOException {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
