package io.github.gremlin2rdf;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Rewrite {
    private Rewrite() {}
    public record Finding(String symbol, int startLine, int endLine, String fingerprint, String status, String remediation) {}
    public record Plan(int schemaVersion, String sourcePath, String sourceHash, String graph, String output,
                       List<Finding> findings, List<String> blockers, boolean sensitiveArtifact) {}
    public static Plan plan(Path root, String sourcePath, String graph) throws IOException {
        Path source = SafeFiles.resolve(root, sourcePath);
        if (!source.getFileName().toString().endsWith(".java") || Files.size(source) > 1048576)
            throw new IOException("Bounded Java source input required");
        String text = Files.readString(source);
        CompilationUnit unit = StaticJavaParser.parse(text);
        LexicalPreservingPrinter.setup(unit);
        List<Finding> findings = new ArrayList<>(); List<String> blockers = new ArrayList<>();
        if (unit.getImports().stream().noneMatch(i -> i.getNameAsString()
            .equals("org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource")))
            blockers.add("Exact TinkerPop traversal-source import is required; ambiguous type resolution is not rewritten");
        for (var variable : unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class))
            if (Set.of("password", "accountkey", "masterkey", "token", "connectionstring").contains(variable.getNameAsString().toLowerCase(Locale.ROOT))
                && variable.getInitializer().orElse(null) instanceof StringLiteralExpr)
                blockers.add("Move embedded credentials to approved secret references before rewriting");
        if (unit.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals("credentials")))
            blockers.add("Credential initialization needs explicit secret-reference review");
        Set<String> migratedVariables = new HashSet<>();
        Set<String> sourceVariables = new HashSet<>();
        unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(variable -> {
            if (variable.getType().asString().equals("GraphTraversalSource")) sourceVariables.add(variable.getNameAsString());
        });
        unit.findAll(com.github.javaparser.ast.body.Parameter.class).forEach(parameter -> {
            if (parameter.getType().asString().equals("GraphTraversalSource")) sourceVariables.add(parameter.getNameAsString());
        });
        for (var declaration : unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class))
            if (sourceVariables.contains(declaration.getNameAsString()) && !declaration.getType().asString().equals("GraphTraversalSource"))
                blockers.add("Shadowed traversal-source names require full symbol resolution");
            else if (declaration.getType().asString().equals("GraphTraversalSource") && declaration.getInitializer().isPresent())
                blockers.add("Traversal-source initialization requires a separately reviewed access-layer construction change");
        for (var parameter : unit.findAll(com.github.javaparser.ast.body.Parameter.class))
            if (sourceVariables.contains(parameter.getNameAsString()) && !parameter.getType().asString().equals("GraphTraversalSource"))
                blockers.add("Shadowed traversal-source parameters require full symbol resolution");
        if (unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream()
            .anyMatch(c -> c.getNameAsString().equals("GraphTraversalSource")))
            blockers.add("A locally declared traversal-source type is not supported");
        List<MethodCallExpr> expressions = new ArrayList<>(unit.findAll(MethodCallExpr.class));
        for (var call : expressions) {
            if (call.getNameAsString().equals("submit")) {
                var range = call.getRange().orElseThrow();
                findings.add(new Finding("submit", range.begin.line, range.end.line, Json.hash(call.toString()),
                    "blocked", "Driver-result and asynchronous submit rewrites require explicit result-shape handling."));
                blockers.add("Unsupported driver submission at line " + range.begin.line); continue;
            }
            if (!call.getNameAsString().equals("next") || !call.getArguments().isEmpty()
                || !(call.getScope().orElse(null) instanceof MethodCallExpr count)
                || !count.getNameAsString().equals("count") || !count.getArguments().isEmpty()
                || !(count.getScope().orElse(null) instanceof MethodCallExpr selection)
                || !List.of("V", "E").contains(selection.getNameAsString()) || !selection.getArguments().isEmpty()
                || !(selection.getScope().orElse(null) instanceof NameExpr variable)) continue;
            boolean declared = sourceVariables.contains(variable.getNameAsString());
            if (!declared) { blockers.add("Traversal source type is not established"); continue; }
            var translated = new Translator(graph, Map.of()).translate("g." + selection.getNameAsString() + "().count()");
            var replacement = new MethodCallExpr(new NameExpr(variable.getNameAsString()), "count")
                .addArgument(new StringLiteralExpr().setString(translated.sparql()));
            var range = call.getRange().orElseThrow();
            findings.add(new Finding(variable.getNameAsString(), range.begin.line, range.end.line,
                translated.fingerprint(), "supported", "Replace exact terminal traversal count with typed Jena SELECT count."));
            call.replace(replacement); migratedVariables.add(variable.getNameAsString());
        }
        if (migratedVariables.isEmpty()) blockers.add("No supported terminal V/E count fixture was found");
        for (var call : unit.findAll(MethodCallExpr.class)) {
            if (call.getScope().orElse(null) instanceof FieldAccessExpr field && sourceVariables.contains(field.getNameAsString()))
                blockers.add("Qualified traversal-source access requires explicit symbol resolution");
            if (call.getScope().orElse(null) instanceof NameExpr scope && sourceVariables.contains(scope.getNameAsString())
                && !(call.getNameAsString().equals("count") && call.getArguments().size() == 1
                    && call.getArguments().get(0) instanceof StringLiteralExpr))
                blockers.add("Unrewritten traversal-source usage requires manual migration");
        }
        if (!migratedVariables.containsAll(sourceVariables)) blockers.add("Unrelated traversal-source declarations are not rewritten");
        for (var reference : unit.findAll(NameExpr.class)) {
            if (!sourceVariables.contains(reference.getNameAsString())) continue;
            var parent = reference.getParentNode().orElseThrow();
            boolean countReceiver = parent instanceof MethodCallExpr call && call.getNameAsString().equals("count")
                && call.getScope().orElse(null) == reference && call.getArguments().size() == 1;
            boolean constructorAssignment = parent instanceof AssignExpr assignment && assignment.getValue() == reference
                && assignment.getTarget() instanceof FieldAccessExpr field && field.getScope() instanceof ThisExpr
                && field.getNameAsString().equals(reference.getNameAsString());
            if (!countReceiver && !constructorAssignment) blockers.add("Traversal-source escape requires manual call-site migration");
        }
        if (!blockers.isEmpty()) return new Plan(1, sourcePath, SafeFiles.fileHash(source), graph, "",
            List.copyOf(findings), List.copyOf(blockers), true);
        for (var variable : unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class))
            if (variable.getType().asString().equals("GraphTraversalSource")) variable.setType(new ClassOrInterfaceType(null, "GraphAccess"));
        for (var parameter : unit.findAll(com.github.javaparser.ast.body.Parameter.class))
            if (parameter.getType().asString().equals("GraphTraversalSource")) parameter.setType(new ClassOrInterfaceType(null, "GraphAccess"));
        unit.getImports().removeIf(i -> i.getNameAsString().equals("org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource"));
        unit.addImport("io.github.gremlin2rdf.GraphAccess");
        return new Plan(1, sourcePath, SafeFiles.fileHash(source), graph, LexicalPreservingPrinter.print(unit),
            List.copyOf(findings), List.copyOf(blockers), true);
    }
    public static void apply(Project project, Path root, Plan plan, String output) throws IOException {
        if (plan.schemaVersion() != 1 || !plan.blockers().isEmpty() || plan.findings().isEmpty())
            throw new IllegalArgumentException("Unsafe or unsupported rewrite plan cannot be applied");
        Path source = SafeFiles.resolve(root, plan.sourcePath()), target = SafeFiles.resolve(root, output);
        if (!plan.graph().equals(project.graph("canonical"))) throw new IllegalArgumentException("Rewrite graph does not match approved project");
        if (!SafeFiles.fileHash(source).equals(plan.sourceHash())) throw new IOException("Application source changed after review");
        if (source.equals(target) || Files.exists(target)) throw new IOException("Rewrite requires a new separate output location");
        Approval.require(project, "rewrite", List.of(plan.sourcePath(), plan.sourceHash(), Json.hash(plan), output));
        Files.createDirectories(target.getParent());
        Files.writeString(target, plan.output(), StandardOpenOption.CREATE_NEW);
    }
}
