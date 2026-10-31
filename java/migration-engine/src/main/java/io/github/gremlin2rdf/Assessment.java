package io.github.gremlin2rdf;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Assessment {
    private Assessment() {}
    public record Finding(String file, String symbol, int startLine, int endLine, String fingerprint,
                          String confidence, String status, String diagnostic) {}
    public record Report(int schemaVersion, String status, List<Finding> findings, Map<String, String> applicationHashes,
                         boolean sensitiveArtifact, List<String> manualActions) {}
    public static Report assess(Path root) throws IOException {
        Path base = root.toRealPath(); List<Finding> findings = new ArrayList<>(); Map<String, String> hashes = new TreeMap<>();
        try (var files = Files.walk(base)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                Path relative = base.relativize(file);
                if (java.util.stream.StreamSupport.stream(relative.spliterator(), false)
                    .anyMatch(p -> Set.of(".git", "node_modules", "target", ".cosmosdb-graph-migration").contains(p.toString()))) continue;
                String extension = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(extension.endsWith(".java") || extension.endsWith(".py") || extension.endsWith(".cs")
                    || extension.endsWith(".js") || extension.endsWith(".ts") || extension.endsWith(".go")
                    || extension.equals("pom.xml") || extension.equals("package.json"))) continue;
                if (!file.toRealPath().startsWith(base) || Files.isSymbolicLink(file)) throw new IOException("Application symlink escapes are prohibited");
                String name = relative.toString().replace('\\', '/');
                if (Files.size(file) > 1048576) {
                    findings.add(new Finding(name, "file", 1, 1, "", "high", "blocked", "Source file exceeds assessment bound")); continue;
                }
                if (hashes.size() == 10000) throw new IOException("Assessment file bound exceeded");
                String source = Files.readString(file); hashes.put(name, SafeFiles.fileHash(file));
                if (extension.endsWith(".java")) {
                    final com.github.javaparser.ast.CompilationUnit unit;
                    try { unit = StaticJavaParser.parse(source); }
                    catch (com.github.javaparser.ParseProblemException e) {
                        findings.add(new Finding(name, "parser", 1, 1, Json.hash(source), "high", "blocked", "Java parsing requires manual remediation")); continue;
                    }
                    for (var call : unit.findAll(MethodCallExpr.class)) {
                        String symbol = call.getNameAsString();
                        if (!Set.of("submit", "V", "E", "addV", "addE", "property", "drop", "credentials", "serializer").contains(symbol)) continue;
                        var range = call.getRange().orElseThrow(); String status = "review-required", diagnostic = "Review identity, multiplicity, result shape and transaction assumptions";
                        if (symbol.equals("submit")) {
                            if (call.getArguments().isEmpty() || !(call.getArgument(0) instanceof StringLiteralExpr literal))
                                { status = "blocked"; diagnostic = "Dynamic query assembly is not evaluated or automatically rewritten"; }
                            else try {
                                new Translator("urn:assessment", Map.of()).translate(literal.asString()); status = "supported-read-subset";
                            } catch (Gremlin.Unsupported e) { status = "blocked"; diagnostic = e.code(); }
                        }
                        if (Set.of("addV", "addE", "property", "drop").contains(symbol))
                            { status = "blocked"; diagnostic = "Mutation needs declared cardinality/identity and an approved typed CRUD plan"; }
                        findings.add(new Finding(name, symbol, range.begin.line, range.end.line, Json.hash(call.toString()), "high", status, diagnostic));
                    }
                } else {
                    String[] lines = source.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) if (lines[i].toLowerCase(Locale.ROOT).contains("gremlin") || lines[i].contains("g.V(") || lines[i].contains("g.E("))
                        findings.add(new Finding(name, "dependency-or-query", i + 1, i + 1, Json.hash(lines[i]), "low", "review-required",
                            extension.equals("pom.xml") || extension.equals("package.json") ? "Review driver dependency and serializer version"
                                : "Automatic rewriting is limited to documented Java fixtures; migrate this access layer manually"));
                }
            }
        }
        return new Report(1, "succeeded", List.copyOf(findings), Map.copyOf(hashes), true,
            List.of("Inventory does not execute application queries", "Confirm ordering, partition-qualified identities and retry assumptions",
                "Review every unsupported mutation and dynamic query before cutover"));
    }
}
