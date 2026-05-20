package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Strategy: Reverse Transitive / "Used-by".
 * <p>
 * Builds a reverse dependency map of all production classes: for each class,
 * which other classes depend on it (have it as a field type). When a class
 * changes, walks this "used-by" graph N levels deep to find consumers, then
 * discovers tests for those consumers via the naming and usage strategies.
 * <p>
 * Depth is configurable via {@code transitiveDepth} (default 4, max 5).
 */
public final class TransitiveStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(TransitiveStrategy.class);

    private final AffectedTestsConfig config;
    private final NamingConventionStrategy namingStrategy;
    private final UsageStrategy usageStrategy;

    public TransitiveStrategy(AffectedTestsConfig config,
                              NamingConventionStrategy namingStrategy,
                              UsageStrategy usageStrategy) {
        this.config = config;
        this.namingStrategy = namingStrategy;
        this.usageStrategy = usageStrategy;
    }

    @Override
    public String name() {
        return "transitive";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        List<Path> sourceFiles = SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs());
        return walkTransitives(changedProductionClasses, sourceFiles, projectDir, null);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks
     * and AST parses).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        return walkTransitives(changedProductionClasses, index.sourceFiles(), null, index);
    }

    private Set<String> walkTransitives(Set<String> changedProductionClasses,
                                        List<Path> sourceFiles,
                                        Path projectDir, ProjectIndex index) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (config.transitiveDepth() <= 0) {
            log.debug("[transitive] Transitive depth is 0, skipping");
            return discoveredTests;
        }

        Set<String> currentLevel = new LinkedHashSet<>(changedProductionClasses);
        Set<String> allVisited = new LinkedHashSet<>(changedProductionClasses);

        // The changed set is threaded into map construction so reverse edges
        // pointing at a class that was just `git rm`'d still get recorded.
        // Without this, a pure-delete MR (file on disk is gone, consumers
        // still reference the FQN from their still-present sources) produces
        // an empty `dependencyMap.get(deletedFqn)`, and the downstream tests
        // that are now broken against the deleted symbol are silently skipped.
        // GitChangeDetector already surfaces the old path for DELETEs to seed
        // `changedProductionClasses`; this patch closes the other half of the
        // contract on the index side.
        Map<String, Set<String>> dependencyMap =
                buildReverseDependencyMap(sourceFiles, index, changedProductionClasses);

        // Collect the union of every depth's consumers BEFORE running naming
        // / usage. Pre-this-change, naming + usage ran once per depth on each
        // depth's `nextLevel` set, so a default `transitiveDepth = 4` walk
        // re-scanned every test file four times. Both strategies are pure
        // functions of their input class set — naming.discover(A) ∪
        // naming.discover(B) ≡ naming.discover(A ∪ B), and the same holds
        // for usage — so deferring the strategy invocation to the end of
        // the BFS and running it once on the union is behaviour-preserving
        // and removes (depth − 1) full passes through the test corpus.
        Set<String> transitiveConsumers = new LinkedHashSet<>();

        for (int depth = 1; depth <= config.transitiveDepth(); depth++) {
            Set<String> nextLevel = new LinkedHashSet<>();

            for (String classFqn : currentLevel) {
                Set<String> deps = dependencyMap.getOrDefault(classFqn, Set.of());
                for (String dep : deps) {
                    if (!allVisited.contains(dep)) {
                        nextLevel.add(dep);
                        allVisited.add(dep);
                    }
                }
            }

            if (nextLevel.isEmpty()) {
                log.debug("[transitive] No more downstream types at depth {}", depth);
                break;
            }

            log.debug("[transitive] Depth {}: found {} downstream types", depth, nextLevel.size());

            transitiveConsumers.addAll(nextLevel);
            currentLevel = nextLevel;
        }

        // One pass through all test files for naming, one for usage, against
        // the union of every depth's consumers.
        if (!transitiveConsumers.isEmpty()) {
            if (index != null) {
                discoveredTests.addAll(namingStrategy.discoverTests(transitiveConsumers, index));
                discoveredTests.addAll(usageStrategy.discoverTests(transitiveConsumers, index));
            } else {
                discoveredTests.addAll(namingStrategy.discoverTests(transitiveConsumers, projectDir));
                discoveredTests.addAll(usageStrategy.discoverTests(transitiveConsumers, projectDir));
            }
        }

        log.info("[transitive] Discovered {} tests across {} transitive consumer(s) (max depth={})",
                discoveredTests.size(), transitiveConsumers.size(), config.transitiveDepth());
        return discoveredTests;
    }

    /**
     * Builds a <em>reverse</em> dependency map: for each production class FQN,
     * lists the FQNs of other production classes that depend on it.
     */
    private Map<String, Set<String>> buildReverseDependencyMap(List<Path> sourceFiles,
                                                               ProjectIndex index,
                                                               Set<String> extraKnownFqns) {
        Map<String, Set<String>> reverseMap = new HashMap<>();
        JavaParser fallbackParser = (index == null) ? JavaParsers.newParser() : null;

        // First pass: collect all known FQNs so we can resolve simple names.
        // `extraKnownFqns` is unioned in so reverse edges to deleted classes
        // (which no longer have a source file on disk) still resolve — see
        // the caller's comment for the full rationale.
        Set<String> allKnownFqns = new HashSet<>();
        for (Path file : sourceFiles) {
            String fqn = pathToFqn(file);
            if (fqn != null) {
                allKnownFqns.add(fqn);
            }
        }
        if (extraKnownFqns != null) {
            allKnownFqns.addAll(extraKnownFqns);
        }

        // Second pass: for each source file, find field types and build reverse edges
        for (Path file : sourceFiles) {
            CompilationUnit cu = parseOrGet(file, index, fallbackParser);
            if (cu == null) continue;

            String classFqn = pathToFqn(file);
            if (classFqn == null) continue;

            Map<String, String> importMap = new HashMap<>();
            Set<String> wildcardPackages = new HashSet<>();
            for (ImportDeclaration imp : cu.getImports()) {
                if (imp.isAsterisk()) {
                    wildcardPackages.add(imp.getNameAsString());
                } else {
                    String impFqn = imp.getNameAsString();
                    importMap.put(SourceFileScanner.simpleClassName(impFqn), impFqn);
                }
            }

            String currentPackage = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Walk every ClassOrInterfaceType node in the compilation unit.
            // This is deliberately broader than the old "fields + method
            // signatures" scan it replaces:
            //
            //  * Generics: `List<Foo>` parses as ClassOrInterfaceType(List)
            //    with a type-argument ClassOrInterfaceType(Foo). The old
            //    code normalised the outer type name and threw the
            //    argument away — any consumer that wrapped the changed
            //    class in a container lost its reverse edge.
            //  * Method bodies: local declarations (`PricingCalculator c
            //    = new PricingCalculator()`), ObjectCreationExpr
            //    (`new Foo()`), cast expressions, and `instanceof` checks
            //    all show up as ClassOrInterfaceType nodes inside the
            //    body subtree. The old code only looked at field types
            //    and method signatures, so a helper class instantiated
            //    inside a method body had no reverse edge and its
            //    consumer's tests were silently dropped on changes.
            //  * extends/implements: also surface as ClassOrInterfaceType,
            //    giving us a correct supertype-aware reverse edge for
            //    free.
            Set<String> referencedTypes = new LinkedHashSet<>();
            for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
                String simpleName = t.getNameAsString();
                if (!simpleName.isEmpty()) {
                    referencedTypes.add(simpleName);
                }
            }

            for (String typeName : referencedTypes) {
                String resolvedFqn = importMap.get(typeName);
                if (resolvedFqn == null) {
                    String candidate = currentPackage.isEmpty()
                            ? typeName : currentPackage + "." + typeName;
                    if (allKnownFqns.contains(candidate)) {
                        resolvedFqn = candidate;
                    }
                }
                if (resolvedFqn == null) {
                    for (String pkg : wildcardPackages) {
                        String candidate = pkg + "." + typeName;
                        if (allKnownFqns.contains(candidate)) {
                            resolvedFqn = candidate;
                            break;
                        }
                    }
                }

                if (resolvedFqn != null && allKnownFqns.contains(resolvedFqn)
                        && !resolvedFqn.equals(classFqn)) {
                    reverseMap.computeIfAbsent(resolvedFqn, k -> new LinkedHashSet<>())
                            .add(classFqn);
                }
            }
        }

        log.debug("[transitive] Built reverse dependency map with {} entries", reverseMap.size());
        return reverseMap;
    }

    private CompilationUnit parseOrGet(Path file, ProjectIndex index, JavaParser fallbackParser) {
        if (index != null) {
            return index.compilationUnit(file);
        }
        return JavaParsers.parseOrWarn(fallbackParser, file, "transitive");
    }

    private String pathToFqn(Path file) {
        return SourceFileScanner.pathToFqn(file, config.sourceDirs());
    }
}
