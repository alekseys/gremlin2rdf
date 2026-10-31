package io.github.gremlin2rdf;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.rdf.model.Model;

public final class RdfExport {
    private RdfExport() {}
    public static Map<String, Object> run(Project project, Path source, Path output) throws IOException {
        SourceCompatibility.authorizeMode(project);
        String sourceHash = SafeFiles.fileHash(source);
        Files.createDirectory(output);
        SafeFiles.atomic(output.resolve("ownership.json"), Map.of("schemaVersion", 1, "runId", project.runId(),
            "inputHash", project.inputHash(), "sourceHash", sourceHash, "kind", "local-rdf-export", "sensitiveArtifact", true));
        Path canonical = output.resolve("canonical.nt"), derived = output.resolve("derived.nt");
        long records = 0, canonicalFrames = 0, derivedFrames = 0;
        var mapper = new RdfMapper(project.mapping());
        try (var reader = new RecordReader(source, project.limits().maxRecordBytes(), 0);
             var canonicalWriter = Files.newBufferedWriter(canonical, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             var derivedWriter = Files.newBufferedWriter(derived, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            GraphRecord record;
            while ((record = reader.next()) != null) {
                SourceCompatibility.authorizeMode(project);
                if (Files.getFileStore(output).getUsableSpace() < project.limits().minimumFreeDiskBytes())
                    throw new IOException("Insufficient disk for local RDF export; owned partial output preserved");
                try (var mapped = mapper.map(record)) {
                    canonicalFrames = Math.addExact(canonicalFrames, write(mapped.canonical(), canonicalWriter));
                    derivedFrames = Math.addExact(derivedFrames, write(mapped.derived(), derivedWriter));
                }
                records++;
            }
        }
        for (var file : List.of(canonical, derived))
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
        if (records == 0 || !sourceHash.equals(SafeFiles.fileHash(source))) throw new IOException("Empty or changing canonical source cannot complete RDF export");
        var report = new TreeMap<String, Object>(Map.<String, Object>of("schemaVersion", 1, "status", "succeeded", "scope", "local-canonical-input-not-live-observation",
            "runId", project.runId(), "inputHash", project.inputHash(), "sourceHash", sourceHash, "mappingHash", project.mapping().hash(),
            "records", records, "canonical", Map.of("path", "canonical.nt", "sha256", SafeFiles.fileHash(canonical), "tripleFrames", canonicalFrames),
            "derived", Map.of("path", "derived.nt", "sha256", SafeFiles.fileHash(derived), "tripleFrames", derivedFrames)));
        report.put("globalStructuralAndShaclValidation", "not-executed-by-export; validate the complete dataset with endpoint context");
        SafeFiles.atomic(output.resolve("manifest.json"), report);
        return report;
    }
    private static long write(Model model, BufferedWriter writer) throws IOException {
        var lines = new TreeSet<String>();
        var iterator = model.getGraph().find();
        try {
            while (iterator.hasNext()) {
                var triple = iterator.next();
                lines.add(NodeFmtLib.strNT(triple.getSubject()) + " " + NodeFmtLib.strNT(triple.getPredicate()) + " "
                    + NodeFmtLib.strNT(triple.getObject()) + " .\n");
            }
        } finally { iterator.close(); }
        for (var line : lines) writer.write(line);
        return lines.size();
    }
}
