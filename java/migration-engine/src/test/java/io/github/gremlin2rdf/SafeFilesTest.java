package io.github.gremlin2rdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SafeFilesTest {
    @TempDir Path root;
    @Test void boundedWindowsRenameRetriesDoNotRewritePendingOrReplayOtherOperations() throws Exception {
        var calls=new AtomicInteger();var waits=new ArrayList<Long>();
        SafeFiles.replaceAtomically(true,()->{
            if(calls.incrementAndGet()<3) throw new AccessDeniedException("private-path");
        },waits::add);
        assertEquals(3,calls.get());assertEquals(List.of(25L,50L),waits);
        calls.set(0);waits.clear();
        var denied=assertThrows(SafeFiles.ArtifactException.class,()->SafeFiles.replaceAtomically(true,()->{
            calls.incrementAndGet();throw new AccessDeniedException("private-path","private-other","private-provider-reason");
        },waits::add));
        assertEquals(5,calls.get());assertEquals(List.of(25L,50L,100L,200L),waits);
        var report=Diagnostics.failure(denied,"source-enumeration-checkpoint-write");
        assertEquals("ARTIFACT_ATOMIC_REPLACE_ACCESS",report.get("code"));
        assertEquals("atomic-replace",report.get("artifactAction"));assertEquals(5,report.get("artifactAttempts"));
        assertEquals(report,Diagnostics.failure(Diagnostics.acknowledged(Json.MAPPER.valueToTree(report),"execution"),"execution"));
        assertFalse(Json.text(report).contains("private-"));
    }
    @Test void noNonAtomicFallbackOrRetryForOtherErrorsOrNonWindowsDenial() {
        for(IOException error:List.of(new AtomicMoveNotSupportedException("private-from","private-to","private-reason"),
            new NoSuchFileException("private-path"),new FileSystemException("private-path"),
            new AccessDeniedException("private-path"))) {
            var calls=new AtomicInteger();
            var failure=assertThrows(SafeFiles.ArtifactException.class,()->SafeFiles.replaceAtomically(
                !(error instanceof AccessDeniedException),()->{calls.incrementAndGet();throw error;},
                millis->{fail("No retry for non-sharing-shaped failure");}));
            assertEquals(1,calls.get());
            assertFalse(Json.text(Diagnostics.failure(failure,"source-request-journal-write")).contains("private"));
        }
    }
    @Test void interruptionPreservesThreadSignalWithoutRepeatingRename() {
        var calls=new AtomicInteger();
        try {
            assertThrows(SafeFiles.ArtifactException.class,()->SafeFiles.replaceAtomically(true,()->{
                calls.incrementAndGet();throw new AccessDeniedException("private-path");
            },millis->{throw new InterruptedException("private");}));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,calls.get());
        } finally {Thread.interrupted();}
    }
    @Test void existingPendingStateIsNeverTruncatedDeletedOrAdopted() throws Exception {
        var output=root.resolve("checkpoint.json");var pending=root.resolve("checkpoint.json.pending");
        Files.writeString(output,"old-acknowledged");Files.writeString(pending,"preserved-pending");
        var error=assertThrows(SafeFiles.ArtifactException.class,()->SafeFiles.atomic(output,Map.of("new",1)));
        var report=Diagnostics.failure(error,"source-enumeration-checkpoint-write");
        assertEquals("ARTIFACT_PENDING_EXISTS",report.get("code"));
        assertEquals("create-pending",report.get("artifactAction"));
        assertEquals("old-acknowledged",Files.readString(output));assertEquals("preserved-pending",Files.readString(pending));
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void realWindowsDeleteSharingDenialPreservesOldAndFsyncedPendingThenSucceedsAfterHandleRelease() throws Exception {
        var output=root.resolve("checkpoint.json");Files.writeString(output,"old-acknowledged");
        var lock=FileChannel.open(output,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
        try {
            var error=assertThrows(SafeFiles.ArtifactException.class,()->SafeFiles.atomic(output,Map.of("sequence",2)));
            assertInstanceOf(AccessDeniedException.class,error.getCause());
            assertEquals(SafeFiles.ArtifactAction.atomicReplace,error.action);assertEquals(5,error.attempts);
            assertEquals("old-acknowledged",Files.readString(output));
            var pending=root.resolve("checkpoint.json.pending");String saved=Files.readString(pending);
            assertEquals(2,Json.MAPPER.readTree(saved).path("sequence").asInt());
            var calls=new AtomicInteger();
            SafeFiles.replaceAtomically(true,()->{
                calls.incrementAndGet();
                Files.move(pending,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            },millis->{
                try {lock.close();}
                catch(IOException failure) {throw new java.io.UncheckedIOException(failure);}
            });
            assertEquals(2,calls.get());assertEquals(saved,Files.readString(output));assertFalse(Files.exists(pending));
        } finally {lock.close();}
    }
    @Test void resolvedDirectoryLinksCannotEscapeOwnedArtifacts() throws Exception {
        Path owned = Files.createDirectory(root.resolve("owned")), outside = Files.createDirectory(root.resolve("outside"));
        Path link = owned.resolve("external");
        Files.writeString(outside.resolve("evidence.json"), "{}");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd.exe", "/d", "/c",
                "mklink /J \"" + link + "\" \"" + outside + "\"").redirectErrorStream(true).start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } else Files.createSymbolicLink(link, outside);
        assertThrows(SecurityException.class, () -> SafeFiles.resolve(owned, "external/evidence.json"));
        assertThrows(SecurityException.class, () -> SafeFiles.resolve(owned, "external/new.json"));
    }
}
