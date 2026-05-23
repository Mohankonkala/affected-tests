package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Strategy: Implementation / "Impl" discovery.
 * <p>
 * When a base class or interface changes, discovers implementations of that type
 * and then delegates to naming and usage strategies to find tests for those implementations.
 */
public final class ImplementationStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ImplementationStrategy.class);

    private final AffectedTestsConfig config;
    private final NamingConventionStrategy namingStrategy;
    private final UsageStrategy usageStrategy;

    public ImplementationStrategy(AffectedTestsConfig config,
                                  NamingConventionStrategy namingStrategy,
                                  UsageStrategy usageStrategy) {
        this.config = config;
        this.namingStrategy = namingStrategy;
        this.usageStrategy = usageStrategy;
    }

    @Override
    public String name() {
        return "impl";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        if (!config.includeImplementationTests()) {
            log.debug("[impl] Implementation test discovery disabled");
            return new LinkedHashSet<>();
        }

        Set<String> implClasses = findImplementations(changedProductionClasses,
                collectSourceFqns(projectDir),
                SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs()),
                null);
        return discoverTestsForImpls(implClasses, projectDir, null);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks
     * and AST parses).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        if (!config.includeImplementationTests()) {
            log.debug("[impl] Implementation test discovery disabled");
            return new LinkedHashSet<>();
        }

        Set<String> implClasses = findImplementations(changedProductionClasses,
                index.sourceFqns(), index.sourceFiles(), index);
        return discoverTestsForImpls(implClasses, null, index);
    }

    private Set<String> discoverTestsForImpls(Set<String> implClasses, Path projectDir, ProjectIndex index) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (!implClasses.isEmpty()) {
            log.info("[impl] Found {} implementation classes", implClasses.size());

            if (config.strategies().contains(AffectedTestsConfig.STRATEGY_NAMING)) {
                discoveredTests.addAll(index != null
                        ? namingStrategy.discoverTests(implClasses, index)
                        : namingStrategy.discoverTests(implClasses, projectDir));
            }
            if (config.strategies().contains(AffectedTestsConfig.STRATEGY_USAGE)) {
                discoveredTests.addAll(index != null
                        ? usageStrategy.discoverTests(implClasses, index)
                        : usageStrategy.discoverTests(implClasses, projectDir));
            }
        }

        log.info("[impl] Discovered {} tests via implementation strategy", discoveredTests.size());
        return discoveredTests;
    }

    /**
     * Finds classes that extend or implement any of the changed production classes.
     * Uses both JavaParser AST scanning and naming convention ({@code *Impl}).
     */
    private Set<String> findImplementations(Set<String> changedClasses,
                                            Set<String> allSourceFqns,
                                            List<Path> sourceFiles,
                                            ProjectIndex index) {
        Set<String> implementations = new LinkedHashSet<>();

        Map<String, Set<String>> simpleNameToFqns = new HashMap<>();
        for (String fqn : changedClasses) {
            simpleNameToFqns.computeIfAbsent(SourceFileScanner.simpleClassName(fqn), k -> new HashSet<>()).add(fqn);
        }

        // 1. Naming convention: look for both suffix (*Impl) and prefix
        // (Default*) derivatives of the changed interface name. The
        // config list ships with {"Impl", "Default"} and the Builder
        // javadoc promises both shapes; before this loop checked both
        // sides, "Default" was appended as a suffix (FooServiceDefault),
        // which matches nothing real — Spring/Guice code writes
        // DefaultFooService. The AST branch below rescues the clean
        // "implements FooService" case, but impls that declare the
        // super-type via generics only, or files JavaParser could not
        // parse, were silently missed.
        Set<String> changedSimpleNames = new HashSet<>();
        for (String fqn : changedClasses) {
            changedSimpleNames.add(SourceFileScanner.simpleClassName(fqn));
        }
        for (String token : config.implementationNaming()) {
            for (String changedSimple : changedSimpleNames) {
                String suffixShape = changedSimple + token;
                String prefixShape = token + changedSimple;
                for (String sourceFqn : allSourceFqns) {
                    String sourceSimple = SourceFileScanner.simpleClassName(sourceFqn);
                    if (sourceSimple.equals(suffixShape) || sourceSimple.equals(prefixShape)) {
                        implementations.add(sourceFqn);
                    }
                }
            }
        }

        // 2. AST scanning: find classes that extend/implement changed types,
        // iterated to a fixpoint so multi-level hierarchies are covered.
        //
        // Motivating case: `interface A` ← `abstract class B implements A`
        // ← `class C extends B`, with `CTest` the only real test
        // coverage of A's behaviour through the C implementation. When A
        // changes, a single pass finds only B (because nothing declares
        // `extends A` directly except B). Pre-fix, CTest was silently
        // dropped. The fixpoint loop treats each newly-found impl as a
        // fresh target for the next pass; the loop terminates when a
        // pass adds nothing new, or when depth hits the configured
        // transitiveDepth (reused as a sanity bound — in practice Java
        // hierarchies are 2-3 deep).
        // Deep-copy the inner sets so the fixpoint loop's subsequent
        // `.add(implFqn)` calls don't leak mutations into
        // simpleNameToFqns. The latter is unused after this point
        // today, but a shared mutable reference is a footgun waiting
        // for the next refactor.
        Map<String, Set<String>> targetsBySimpleName = new HashMap<>();
        simpleNameToFqns.forEach((k, v) -> targetsBySimpleName.put(k, new HashSet<>(v)));
        int depthCap = Math.max(1, config.transitiveDepth());
        for (int depth = 0; depth < depthCap; depth++) {
            Set<String> newImpls = new LinkedHashSet<>();
            for (Path sourceFile : sourceFiles) {
                FileMetadata md = metadataOrGet(sourceFile, index);
                if (md == null) continue;

                // Per-file metadata carries every TypeDeclaration in the
                // source — primary plus inner — each with its declared
                // supertypes already extracted as simple names by
                // FileMetadataExtractor. That covers the same shapes the
                // pre-stage-2 AST walk did (classes, interfaces, records,
                // enums, annotations) without re-visiting JavaParser
                // nodes.
                //
                // Record-valued consumers and enum implementers stay in
                // scope for the same reason: `record UsdMoney implements
                // Money { ... }` / `enum Currency implements HasCode { ... }`
                // both surface in `typeDeclarations()` with their
                // implements clause as a supertype simple name.
                String pkg = md.packageName();
                for (FileMetadata.TypeDecl decl : md.typeDeclarations()) {
                    String implFqn = pkg.isEmpty() ? decl.simpleName() : pkg + "." + decl.simpleName();
                    if (implementations.contains(implFqn)) {
                        // Already known — skip. `implementations.contains`
                        // is the loop's termination gate: once a class is
                        // in the set it stops seeding further passes.
                        continue;
                    }
                    for (String supertypeName : decl.supertypeSimpleNames()) {
                        if (targetsBySimpleName.containsKey(supertypeName)) {
                            newImpls.add(implFqn);
                            // {@code implFqn} and {@code supertypeName}
                            // both originate from the scanned source tree,
                            // which on a merge-gate run is attacker-
                            // influenced. DEBUG-level, but sanitised for
                            // forgery-resistance when ops drop the level.
                            log.debug("[impl] depth {}: {} extends/implements {}",
                                    depth + 1,
                                    LogSanitizer.sanitize(implFqn),
                                    LogSanitizer.sanitize(supertypeName));
                            break;
                        }
                    }
                }
            }

            if (newImpls.isEmpty()) {
                break;
            }
            implementations.addAll(newImpls);
            // Seed the next pass with the newly-found impls keyed by
            // simple name — subclasses of these impls will match on the
            // next iteration. Previously-targeted names stay in the map
            // so a single type can be discovered via two independent
            // paths without losing an edge.
            for (String implFqn : newImpls) {
                targetsBySimpleName
                        .computeIfAbsent(SourceFileScanner.simpleClassName(implFqn),
                                k -> new HashSet<>())
                        .add(implFqn);
            }
        }

        return implementations;
    }

    /**
     * Resolves the {@link FileMetadata} for {@code file}.
     * <p>Index-backed path (the engine-level run) returns the cached
     * record from {@link ProjectIndex#fileMetadata(Path)} —
     * Stage&nbsp;2 of issue&nbsp;#41 lets the warm path do zero
     * parsing.
     * <p>Standalone path (unit tests + the legacy
     * {@code projectDir}-based entry point) dispatches via
     * {@link LanguageParsers#parseOrWarn} which looks up the parser
     * for {@code file}'s extension and produces {@link FileMetadata}
     * directly. Pays the parse cost every time but keeps the
     * strategy independent from {@link ProjectIndex} for tests that
     * don't want the index plumbing.
     *
     * <p>Pre-PR-2 of issue #76 this method took a
     * {@code JavaParser fallbackParser} argument and hard-coded
     * Java-only parsing in the standalone branch — even after PR #1
     * widened the scanner to admit {@code .kt} files. Post-PR-2 the
     * dispatch is extension-driven, so a {@code .kt} test file
     * fed to the standalone path will reach the (still-absent
     * pre-PR-3) Kotlin parser and silently drop out via
     * {@code null}, matching the contract every other parser-side
     * call site already has.
     */
    private FileMetadata metadataOrGet(Path file, ProjectIndex index) {
        if (index != null) {
            return index.fileMetadata(file);
        }
        // Standalone fallback (no engine-supplied registry) routes
        // through {@link LanguageParsers#defaultJavaOnly()}; see
        // {@link UsageStrategy#metadataOrGet} for the per-engine-vs-
        // standalone rationale.
        return LanguageParsers.defaultJavaOnly().parseOrWarn(file, "impl");
    }

    /**
     * Collects FQNs for all source files under the project dir at any depth.
     */
    private Set<String> collectSourceFqns(Path projectDir) {
        Set<String> fqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            for (Path resolved : SourceFileScanner.findAllMatchingDirs(projectDir, sourceDir)) {
                fqns.addAll(SourceFileScanner.fqnsUnder(resolved));
            }
        }
        return fqns;
    }
}
