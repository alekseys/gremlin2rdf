package io.github.gremlin2rdf;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Anchored, delete-on-close copy: later edits to the selected input cannot change approved remote writes. */
public final class ApprovedRecords implements AutoCloseable {
    private final FileChannel channel;
    private boolean reading;
    public ApprovedRecords(Path records, Path root, String expectedHash, long minimumFreeBytes) throws IOException {
        if (!Files.isRegularFile(records, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Approved canonical input must be a regular non-symlink file");
        long required;
        try { required = Math.addExact(Files.size(records), minimumFreeBytes); }
        catch (ArithmeticException e) { throw new IOException("Approved input snapshot exceeds disk accounting range", e); }
        if (Files.getFileStore(root).getUsableSpace() < required)
            throw new IOException("Insufficient disk for approved input snapshot and configured reserve");
        Path owned = Files.createTempFile(root, ".g2r-approved-", ".ndjson");
        FileChannel opened = null;
        try {
            opened = FileChannel.open(owned, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
            MessageDigest hash;
            try { hash = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            try (var input = Files.newInputStream(records)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    hash.update(bytes, 0, count);
                    var buffer = ByteBuffer.wrap(bytes, 0, count);
                    while (buffer.hasRemaining()) opened.write(buffer);
                }
            }
            opened.force(true);
            if (!HexFormat.of().formatHex(hash.digest()).equals(expectedHash))
                throw new IOException("Canonical input changed before approved snapshot was established");
            channel = opened;
        } catch (IOException | RuntimeException e) {
            if (opened != null) {
                try { opened.close(); } catch (IOException close) { e.addSuppressed(close); }
            }
            try { Files.deleteIfExists(owned); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    public synchronized RecordReader reader(int limit, long offset) throws IOException {
        if (reading) throw new IllegalStateException("Only one reader may use an approved snapshot at a time");
        channel.position(0);
        reading = true;
        return new RecordReader(new FilterInputStream(Channels.newInputStream(channel)) {
            @Override public void close() {
                synchronized (ApprovedRecords.this) { reading = false; }
            }
        }, limit, offset);
    }
    public void close() throws IOException { channel.close(); }
}
