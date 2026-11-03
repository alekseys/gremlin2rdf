package io.github.gremlin2rdf;

import java.io.*;
import java.nio.file.*;

public final class RecordReader implements AutoCloseable {
    private final InputStream input;
    private final int limit;
    private long offset;
    public RecordReader(Path path, int limit, long offset) throws IOException {
        this(Files.newInputStream(path), limit, offset);
    }
    public RecordReader(InputStream stream, int limit, long offset) throws IOException {
        input = new BufferedInputStream(stream, 65536); this.limit = limit;
        try { input.skipNBytes(offset); this.offset = offset; }
        catch (IOException | RuntimeException e) {
            try { input.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }
    public long offset() { return offset; }
    public GraphRecord next() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            offset++;
            if (b == '\n') break;
            if (frame.size() == limit) throw new IOException("Canonical record exceeds configured frame limit");
            frame.write(b);
        }
        if (b == -1 && frame.size() == 0) return null;
        if (b == -1) throw new IOException("Incomplete canonical record; extraction did not finish");
        try { return Json.MAPPER.readValue(frame.toByteArray(), GraphRecord.class); }
        catch (IOException | IllegalArgumentException e) { throw new IOException("Malformed canonical record; strict extraction rejected", e); }
    }
    public void close() throws IOException { input.close(); }
}
