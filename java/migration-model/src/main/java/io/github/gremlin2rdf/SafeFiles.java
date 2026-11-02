package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

public final class SafeFiles {
    private SafeFiles() {}
    public enum ArtifactAction { createPending, writePending, flushPending, atomicReplace }
    public static final class ArtifactException extends IOException {
        public final ArtifactAction action;
        public final int attempts;
        ArtifactException(ArtifactAction action,int attempts,IOException cause) {
            super("Local artifact operation failed; state preserved",cause);
            this.action=action;this.attempts=attempts;
        }
    }
    @FunctionalInterface interface Mover { void move() throws IOException; }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
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
        ArtifactAction action=ArtifactAction.createPending;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                action=ArtifactAction.writePending;
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                action=ArtifactAction.flushPending;
                channel.force(true);
            }
        } catch(IOException failure) {
            throw new ArtifactException(action,1,failure);
        }
        replaceAtomically(temp,output);
    }
    /** Retry only a closed, already-fsynced file's rename; never replay a source request or rewrite pending state. */
    public static void replaceAtomically(Path pending,Path output) throws IOException {
        replaceAtomically(System.getProperty("os.name","").startsWith("Windows"),
            ()->Files.move(pending,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING),
            Thread::sleep);
    }
    static void replaceAtomically(boolean windows,Mover mover,Sleeper sleeper) throws IOException {
        for(int attempt=1;;attempt++) {
            try {mover.move();return;}
            catch(IOException failure) {
                if(!windows || !(failure instanceof AccessDeniedException) || attempt>=5)
                    throw new ArtifactException(ArtifactAction.atomicReplace,attempt,failure);
                try {sleeper.sleep(25L << (attempt-1));}
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    var stopped=new java.io.InterruptedIOException("Atomic artifact replacement interrupted; state preserved");
                    stopped.initCause(interrupted);
                    throw new ArtifactException(ArtifactAction.atomicReplace,attempt,stopped);
                }
            }
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
