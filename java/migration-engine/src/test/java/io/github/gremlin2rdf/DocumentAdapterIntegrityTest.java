package io.github.gremlin2rdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentAdapterIntegrityTest {
    @TempDir Path root;

    @Test void verifiesAllSdkRuntimeFilesBeforeExecutionAndRejectsChangedDllsAndTraversal() throws Exception {
        var runtime=Files.createDirectory(root.resolve("win-x64"));
        var executable=runtime.resolve("DocumentSource.exe");
        var library=runtime.resolve("Microsoft.Azure.Cosmos.Client.dll");
        Files.writeString(executable,"fixture-not-executable");
        Files.writeString(library,"fixture-sdk-library");
        var manifest=root.resolve("worker-manifest.json");
        Files.writeString(manifest,Json.text(Map.of(
            "schemaVersion",1,"rid","win-x64","executable","DocumentSource.exe","sdkVersion","3.58.0",
            "files",Map.of("DocumentSource.exe",SafeFiles.fileHash(executable),
                "Microsoft.Azure.Cosmos.Client.dll",SafeFiles.fileHash(library)))));
        Main.verifyDocumentAdapter(executable);
        Files.writeString(library,"changed-library");
        assertThrows(Diagnostics.Failure.class,()->Main.verifyDocumentAdapter(executable));
        Files.writeString(manifest,Json.text(Map.of(
            "schemaVersion",1,"rid","win-x64","executable","DocumentSource.exe","sdkVersion","3.58.0",
            "files",Map.of("DocumentSource.exe",SafeFiles.fileHash(executable),"../outside.dll","0".repeat(64)))));
        assertThrows(Diagnostics.Failure.class,()->Main.verifyDocumentAdapter(executable));
        assertEquals("fixture-not-executable",Files.readString(executable));
    }
}
