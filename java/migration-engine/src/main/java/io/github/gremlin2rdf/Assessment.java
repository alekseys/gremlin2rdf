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
            var iterator = files.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                var file = iterator.next();
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
                    for (var initialization : unit.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
                        if (!initialization.getType().asString().matches(".*(?:Cluster|Client|GraphTraversalSource|RemoteConnection).*")) continue;
                        var range = initialization.getRange().orElseThrow();
                        findings.add(new Finding(name, enclosingSymbol(initialization), range.begin.line, range.end.line,
                            Json.hash(initialization.toString()), "medium", "review-required",
                            "Review connection lifecycle, endpoint/credential references, serializer and partition identity configuration"));
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
                        if (symbol.equals("submit")) {
                            findings.add(new Finding(name, enclosingSymbol(call), range.begin.line, range.end.line,
                                Json.hash(call.toString()), "high", "review-required",
                                call.getArguments().size() > 1
                                    ? "Bindings are present: review names, typed values and partition-qualified identity; expressions are not evaluated"
                                    : "No explicit bindings argument: review literal identities and parameterization"));
                        }
                    }
                    Set<String> resultMethods = Set.of("all", "one", "get", "join", "getString", "getInt", "getLong",
                        "getDouble", "getObject", "valueMap", "elementMap", "toList", "toSet", "order", "by", "limit", "range", "dedup");
                    for (var method : unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
                        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
                        if (calls.stream().noneMatch(call -> Set.of("submit", "V", "E", "addV", "addE").contains(call.getNameAsString()))) continue;
                        String assumptions = calls.stream().map(MethodCallExpr::getNameAsString).filter(resultMethods::contains)
                            .distinct().sorted().collect(java.util.stream.Collectors.joining(", "));
                        var range = method.getRange().orElseThrow();
                        findings.add(new Finding(name, method.getNameAsString(), range.begin.line, range.end.line,
                            Json.hash(method.toString()), "medium", "review-required",
                            "Result/order/multiplicity indicators: " + (assumptions.isEmpty() ? "not established" : assumptions)
                                + "; inspect blocking/asynchronous behavior, typed decoding, absent properties and duplicate results"));
                        if (!method.findAll(com.github.javaparser.ast.stmt.CatchClause.class).isEmpty()
                            || !method.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).isEmpty()
                            || !method.findAll(com.github.javaparser.ast.stmt.ForStmt.class).isEmpty())
                            findings.add(new Finding(name, method.getNameAsString(), range.begin.line, range.end.line,
                                Json.hash(method.toString()), "medium", "review-required",
                                "Exception or loop handling surrounds graph access: review retry classification, deadlines and mutation idempotency"));
                    }
                    for (var field : unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
                        if (!field.getType().asString().matches(".*(?:Cluster|Client|GraphTraversalSource|RemoteConnection).*")) continue;
                        var range = field.getRange().orElseThrow();
                        findings.add(new Finding(name, enclosingSymbol(field), range.begin.line, range.end.line,
                            Json.hash(field.toString()), "medium", "review-required",
                            "Graph client/traversal declaration: review initialization, ownership and close/disposal behavior"));
                    }
                } else {
                    String[] lines = source.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) if (lines[i].toLowerCase(Locale.ROOT).contains("gremlin") || lines[i].contains("g.V(") || lines[i].contains("g.E("))
                        findings.add(new Finding(name, "dependency-or-query", i + 1, i + 1, Json.hash(lines[i]), "low", "review-required",
                            extension.equals("pom.xml") || extension.equals("package.json") ? "Review driver dependency and serializer version"
                                : "Automatic rewriting is limited to documented Java fixtures; migrate this access layer manually"));
                }
                if (findings.size() > 10000) throw new IOException("Assessment finding bound exceeded");
            }
        }
        return new Report(1, "succeeded", List.copyOf(findings), Map.copyOf(hashes), true,
            List.of("Inventory does not execute application queries", "Confirm ordering, partition-qualified identities and retry assumptions",
                "Review every unsupported mutation and dynamic query before cutover"));
    }
    private static String enclosingSymbol(com.github.javaparser.ast.Node node) {
        return node.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class)
            .map(com.github.javaparser.ast.body.MethodDeclaration::getNameAsString)
            .orElseGet(() -> node.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .map(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration::getNameAsString).orElse("initialization"));
    }
}
