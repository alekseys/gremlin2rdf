package io.github.gremlin2rdf;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

public final class RunLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    public RunLock(Path root) throws IOException {
        channel = FileChannel.open(root.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException e) { channel.close(); throw new IOException("Run already owned by another worker", e); }
        if (lock == null) { channel.close(); throw new IOException("Run already owned by another worker"); }
    }
    public void close() throws IOException { lock.release(); channel.close(); }
}
