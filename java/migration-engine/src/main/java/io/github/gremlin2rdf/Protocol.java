package io.github.gremlin2rdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Protocol {
    private Protocol() {}
    public record Message(int protocolVersion, String type, String runId, String operationId, long sequence,
                          String timestamp, String phase, JsonNode payload) {}
    public interface Operation { Object execute(String operation, JsonNode payload, Engine engine) throws Exception; }
    public static void serve(InputStream input, PrintStream output, Operation operation) throws IOException {
        AtomicLong outgoing = new AtomicLong();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Engine[] active = {null};
        long incoming = -1;
        String[] activeRun = {null};
        String[] activeOperation = {null};
        Set<String> commands = Set.of("start", "pause", "cancel", "status");
        emit(output, outgoing, "hello", "", "", "idle", Map.of("workerVersion", "0.1.0", "maxFrameBytes", 65536));
        try {
            while (true) {
                byte[] frame;
                try { frame = frame(input); }
                catch (IOException e) {
                    emit(output, outgoing, "error", "", "", "protocol", Map.of("code", "FRAME_BOUND_OR_TRUNCATION"));
                    break;
                }
                if (frame == null) break;
                Message message;
                try { message = Json.MAPPER.readValue(frame, Message.class); }
                catch (IOException e) { emit(output, outgoing, "error", "", "", "protocol", Map.of("code", "INVALID_FRAME")); continue; }
                if (message.protocolVersion() != 1 || message.sequence() <= incoming || !commands.contains(message.type())
                    || message.runId() == null || !message.runId().matches("[A-Za-z0-9_-]{1,80}")
                    || message.operationId() == null || !message.operationId().matches("[A-Za-z0-9_-]{1,80}") || message.payload() == null) {
                    emit(output, outgoing, "error", "", "", "protocol", Map.of("code", "PROTOCOL_MISMATCH")); continue;
                }
                incoming = message.sequence();
                synchronized (active) {
                    if (!message.type().equals("start")) {
                        if (active[0] != null && !message.runId().equals(activeRun[0])) {
                            emit(output, outgoing, "error", message.runId(), message.operationId(), "control", Map.of("code", "RUN_MISMATCH")); continue;
                        }
                        if (active[0] != null && !message.operationId().equals(activeOperation[0])) {
                            emit(output, outgoing, "error", message.runId(), message.operationId(), "control", Map.of("code", "OPERATION_MISMATCH")); continue;
                        }
                        if (active[0] != null && message.type().equals("cancel")) active[0].cancel();
                        if (active[0] != null && message.type().equals("pause")) active[0].pause();
                        emit(output, outgoing, "status", message.runId(), message.operationId(), "control",
                            Map.of("active", active[0] != null, "acknowledged", message.type()));
                        continue;
                    }
                    if (active[0] != null) {
                        emit(output, outgoing, "error", message.runId(), message.operationId(), "control", Map.of("code", "OPERATION_ACTIVE")); continue;
                    }
                    Engine engine = new Engine(); active[0] = engine; activeRun[0] = message.runId(); activeOperation[0] = message.operationId();
                    engine.observe(checkpoint -> emit(output, outgoing, "checkpoint", message.runId(),
                        message.operationId(), "load", checkpoint));
                    executor.submit(() -> {
                        try {
                            String command = message.payload().path("operation").asText();
                            long started = System.nanoTime();
                            long[] records = {0}, bytes = {0};
                            engine.measure(metric -> {
                                records[0] += metric.records(); bytes[0] += metric.requestBodyBytes();
                                emit(output, outgoing, "progress", message.runId(), message.operationId(), command,
                                    Map.of("recordsConfirmed", records[0], "requestBodyBytes", bytes[0],
                                        "queueDepth", metric.queueDepth(), "retries", metric.retries(),
                                        "elapsedMillis", (System.nanoTime() - started) / 1000000));
                            });
                            Object result = operation.execute(command, message.payload(), engine);
                            if (engine.deadlineExceeded()) emit(output, outgoing, "warning", message.runId(),
                                message.operationId(), command, Map.of("code", "OPERATION_DEADLINE",
                                    "remediation", "Review preserved checkpoints before resuming with a separately reviewed execution budget."));
                            emit(output, outgoing, "result", message.runId(), message.operationId(), command, result);
                        } catch (Exception e) {
                            emit(output, outgoing, "error", message.runId(), message.operationId(), "execution", Main.diagnostic(e));
                        } finally { synchronized (active) { active[0] = null; activeRun[0] = null; activeOperation[0] = null; } }
                    });
                }
            }
        } finally {
            synchronized (active) { if (active[0] != null) active[0].cancel(); }
            executor.shutdown();
        }
    }
    private static byte[] frame(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') return buffer.toByteArray();
            if (buffer.size() == 65536) throw new IOException("Protocol frame exceeds bound");
            buffer.write(b);
        }
        if (buffer.size() != 0) throw new IOException("Truncated protocol frame");
        return null;
    }
    public static synchronized void emit(PrintStream output, AtomicLong sequence, String type, String runId,
                                         String operationId, String phase, Object payload) {
        Message message = new Message(1, type, runId, operationId, sequence.getAndIncrement(), Instant.now().toString(),
            phase, Json.MAPPER.valueToTree(payload));
        String frame = Json.text(message);
        if (frame.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalStateException("Progress payload exceeds protocol bound");
        output.println(frame); output.flush();
    }
}
