package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy: Reverse Transitive / "Used-by".
 * <p>
 * Builds a reverse dependency map of all production classes: for each class,
 * which other classes depend on it (have it as a field type). When a class
 * changes, walks this "used-by" graph N levels deep to find consumers, then
 * discovers tests for those consumers via the naming and usage strategies.
 * <p>
 * Depth is configurable via {@code transitiveDepth} (default 4, max 5).
 *
 * <h3>Performance posture (issue #43)</h3>
 *
 * <p>The pre-#43 implementation built the reverse dependency map by parsing
 * the AST of <em>every</em> source file in the project, regardless of
 * whether the file could possibly contribute to the BFS frontier. On a
 * 10k-class monorepo with a small diff that only touches a few leaf
 * classes, that was 10k AST parses for no useful information beyond
 * "no edge to the changed leaf."
 *
 * <p>The current implementation is frontier-first and lazy:
 *
 * <ol>
 *   <li>A cheap text-only {@link SimpleNameTextIndex} is built once at
 *       the start of the walk: it tokenises each source file's content
 *       for uppercase-leading identifiers (Java's type-name shape) and
 *       keys them by simple name → set of files mentioning that
 *       name. No AST is constructed; no JavaParser is invoked. The
 *       index over-matches deliberately (constants, type variables,
 *       string-literal contents) — false positives only trigger
 *       parses, never unsafe edges.</li>
 *   <li>The BFS expands one frontier FQN at a time. For each frontier
 *       FQN, the text index produces a candidate-file set bounded by
 *       "files that mention this simple name." Only those files get
 *       parsed (via the shared {@link ProjectIndex} CU cache so the
 *       same file parses at most once per engine run). Each parsed
 *       file is then resolved precisely (import map + wildcard +
 *       same-package) to confirm whether the simple-name reference
 *       resolves to the frontier FQN; only confirmed consumers feed
 *       the next frontier.</li>
 * </ol>
 *
 * <p>For a sparse graph (the typical case), this turns
 * O(allSourceFiles × averageReferencedTypes) into roughly
 * O(transitivelyReachable × averageReferencedTypes). The full-graph
 * worst case is unchanged (every file reaches the changed set
 * transitively, every file gets parsed), but that's also the scenario
 * where the user genuinely wants the full reverse graph anyway.
 *
 * <p>Correctness invariants preserved against the pre-#43 fixture set:
 *
 * <ul>
 *   <li><b>Generics</b> (e.g. {@code List<FooService>}) — text scan
 *       picks up {@code FooService} as a separate uppercase token,
 *       so the consumer's file becomes a candidate.</li>
 *   <li><b>Method-body references</b> (e.g.
 *       {@code new PricingCalculator()}) — same. The text scan does
 *       not look at AST structure, only at identifier-shape tokens
 *       in the file content.</li>
 *   <li><b>Deleted production classes</b> — {@code extraKnownFqns}
 *       (sourced from {@code changedProductionClasses}) is unioned
 *       into the resolution step's known-FQN set so reverse edges
 *       to a {@code git rm}-deleted class still resolve.</li>
 * </ul>
 */
public final class TransitiveStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(TransitiveStrategy.class);

    /**
     * Pattern for "looks like a Java type name": ASCII letters/_/$
     * starting with uppercase, optionally followed by digits or
     * inner-class delimiter. Deliberately broader than strict type-
     * name resolution — over-matching here only adds false-positive
     * candidate files that the parse step then rejects, which is
     * cheaper than risking an under-match (a missed edge would silently
     * drop a consumer's tests, the exact regression #43 must not
     * introduce). Tokens like {@code MAX_VALUE} or string-literal
     * contents that start with uppercase are deliberately picked up;
     * the parse step ignores them because they don't appear as
     * {@code ClassOrInterfaceType} nodes.
     */
    private static final Pattern UPPERCASE_IDENT =
            Pattern.compile("\\b[A-Z][A-Za-z0-9_$]*\\b");

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
        if (changedProductionClasses.isEmpty()) {
            // Nothing to walk from. Defensive: callers don't currently
            // hit this branch (the engine short-circuits on test-only
            // diffs before invoking transitive), but it would be wrong
            // to build the text index for an empty starting set.
            return discoveredTests;
        }

        JavaParser fallbackParser = (index == null) ? JavaParsers.newParser() : null;

        // Cheap path-derived FQN map. Used for resolution only; built
        // once and reused across BFS depths. {@code extraKnownFqns}
        // (== changedProductionClasses) is unioned in so reverse edges
        // to a `git rm`-deleted class still resolve — see the
        // {@code discoversConsumerTestsForDeletedProductionClass}
        // regression in TransitiveStrategyTest for the contract.
        Map<String, Path> fqnToFile = new HashMap<>();
        Set<String> allKnownFqns = new HashSet<>();
        for (Path file : sourceFiles) {
            String fqn = pathToFqn(file);
            if (fqn != null) {
                fqnToFile.put(fqn, file);
                allKnownFqns.add(fqn);
            }
        }
        allKnownFqns.addAll(changedProductionClasses);

        // Cheap text-only reverse index — built once, reused across
        // every BFS depth. Reading each source file once and tokenising
        // for uppercase identifiers is dramatically cheaper than
        // parsing every file's AST upfront, which is the pre-#43 cost
        // we're trying to avoid.
        SimpleNameTextIndex textIndex = SimpleNameTextIndex.build(sourceFiles);

        Set<String> currentLevel = new LinkedHashSet<>(changedProductionClasses);
        Set<String> allVisited = new LinkedHashSet<>(changedProductionClasses);

        // Collect the union of every depth's consumers BEFORE running naming
        // / usage. Pre-#44, naming + usage ran once per depth on each depth's
        // `nextLevel` set, so a default `transitiveDepth = 4` walk re-scanned
        // every test file four times. Both strategies are pure functions of
        // their input class set — naming.discover(A) ∪ naming.discover(B) ≡
        // naming.discover(A ∪ B), and the same holds for usage — so deferring
        // the strategy invocation to the end of the BFS and running it once
        // on the union is behaviour-preserving and removes (depth − 1) full
        // passes through the test corpus.
        Set<String> transitiveConsumers = new LinkedHashSet<>();

        for (int depth = 1; depth <= config.transitiveDepth(); depth++) {
            Set<String> nextLevel = expandFrontier(
                    currentLevel, textIndex, fqnToFile, allKnownFqns,
                    allVisited, index, fallbackParser);

            if (nextLevel.isEmpty()) {
                log.debug("[transitive] No more downstream types at depth {}", depth);
                break;
            }

            log.debug("[transitive] Depth {}: found {} downstream types", depth, nextLevel.size());

            allVisited.addAll(nextLevel);
            transitiveConsumers.addAll(nextLevel);
            currentLevel = nextLevel;
        }

        // One pass through all test files for naming, one for usage, against
        // the union of every depth's consumers (the #44 deferred-strategy
        // contract).
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
     * Expands a single BFS frontier: for each FQN in {@code currentLevel},
     * looks up plausibly-referencing files via the text index, parses
     * each, and confirms the reference resolves to the frontier FQN.
     * Returns the set of consumer FQNs not already in {@code allVisited}.
     *
     * <p>Files reaching the parse step are deduplicated across the
     * whole frontier (a file mentioning two different frontier FQNs
     * parses once). For the index-backed code path the {@code
     * ProjectIndex.compilationUnit} cache deduplicates further across
     * BFS depths — the same file parses at most once per engine run.
     */
    private Set<String> expandFrontier(Set<String> currentLevel,
                                       SimpleNameTextIndex textIndex,
                                       Map<String, Path> fqnToFile,
                                       Set<String> allKnownFqns,
                                       Set<String> allVisited,
                                       ProjectIndex index,
                                       JavaParser fallbackParser) {
        Set<String> nextLevel = new LinkedHashSet<>();

        // Group the frontier by simple name so we collect candidate
        // files once per simple name even when multiple FQNs share it
        // (cross-package collision, same shape as the #40 over-select).
        Map<String, Set<String>> simpleNameToFrontierFqns = new LinkedHashMap<>();
        for (String fqn : currentLevel) {
            simpleNameToFrontierFqns
                    .computeIfAbsent(SourceFileScanner.simpleClassName(fqn), k -> new LinkedHashSet<>())
                    .add(fqn);
        }

        // Visit each candidate file at most once per frontier expansion.
        // Multiple frontier FQNs can produce the same candidate (e.g.
        // a file imports both A and B and both are in the frontier),
        // and we still only need to parse once.
        Set<Path> visitedCandidates = new HashSet<>();

        for (var entry : simpleNameToFrontierFqns.entrySet()) {
            String simpleName = entry.getKey();
            Set<String> targetFqnsForName = entry.getValue();

            for (Path candidate : textIndex.filesMentioning(simpleName)) {
                if (!visitedCandidates.add(candidate)) {
                    continue;
                }

                String candidateFqn = pathToFqn(candidate);
                if (candidateFqn == null) continue;
                if (allVisited.contains(candidateFqn)) continue;
                // A file declares its own simple name; we don't want
                // a self-edge (FooService.java mentions `FooService`
                // because it declares it). The pre-#43 reverse-map
                // construction also skipped self-edges via the
                // explicit `!resolvedFqn.equals(classFqn)` check.
                if (targetFqnsForName.contains(candidateFqn)) continue;

                FileMetadata md = metadataOrGet(candidate, index, fallbackParser);
                if (md == null) continue;

                Set<String> resolvedTargets = resolveReferencesToFrontier(
                        md, simpleName, targetFqnsForName,
                        candidateFqn, allKnownFqns);
                if (!resolvedTargets.isEmpty()) {
                    nextLevel.add(candidateFqn);
                }
            }
        }
        return nextLevel;
    }

    /**
     * Inspects a candidate file's cached {@link FileMetadata} and
     * returns the subset of {@code targetFqnsForName} that
     * {@code candidate} actually references via {@code simpleName}.
     * The metadata's type-ref simple-name set replaces the old
     * {@code findAll(ClassOrInterfaceType.class)} walk: the
     * resolution itself does not depend on which AST node mentions
     * the simple name (every reference resolves to the same FQN
     * given the same import map / wildcard list / current package),
     * so a single membership check is equivalent to walking every
     * type-ref node.
     *
     * <p>Static imports are deliberately excluded from the import
     * map — they're member-scoped, not type-scoped, and cannot
     * produce reverse-dependency edges. Usage normalises them
     * differently because direct-import matching there cares about
     * the owning class FQN; Transitive does not have that need.
     *
     * <p>Returning the resolved set rather than a boolean lets the
     * caller (and future diagnostics) tell which exact frontier FQN
     * the candidate consumed. The frontier expansion only needs the
     * non-empty / empty distinction today, but the richer return
     * shape costs nothing and avoids pre-emptively narrowing the
     * helper's contract.
     */
    private Set<String> resolveReferencesToFrontier(FileMetadata md,
                                                    String simpleName,
                                                    Set<String> targetFqnsForName,
                                                    String candidateFqn,
                                                    Set<String> allKnownFqns) {
        Set<String> hits = new LinkedHashSet<>();

        if (!md.typeRefSimpleNames().contains(simpleName)) {
            return hits;
        }

        Map<String, String> importMap = new HashMap<>();
        Set<String> wildcardPackages = new HashSet<>();
        for (FileMetadata.Import imp : md.imports()) {
            if (imp.isStatic()) {
                continue;
            }
            String name = imp.name();
            if (imp.isAsterisk()) {
                wildcardPackages.add(name);
            } else {
                importMap.put(SourceFileScanner.simpleClassName(name), name);
            }
        }
        String currentPackage = md.packageName();

        String resolvedFqn = resolveSimpleName(
                simpleName, importMap, wildcardPackages,
                currentPackage, allKnownFqns);
        if (resolvedFqn == null) return hits;
        if (resolvedFqn.equals(candidateFqn)) return hits;
        if (targetFqnsForName.contains(resolvedFqn)) {
            hits.add(resolvedFqn);
        }
        return hits;
    }

    /**
     * Same resolution ladder the pre-#43 reverse-map used: explicit
     * imports → same-package candidate → wildcard packages. Returns
     * {@code null} when the simple name does not resolve to any known
     * FQN (the candidate must then be a stdlib type, a third-party
     * type, or a typo — none of which produce reverse edges in the
     * project graph).
     */
    private String resolveSimpleName(String simpleName,
                                     Map<String, String> importMap,
                                     Set<String> wildcardPackages,
                                     String currentPackage,
                                     Set<String> allKnownFqns) {
        String explicit = importMap.get(simpleName);
        if (explicit != null) return explicit;

        if (!currentPackage.isEmpty()) {
            String candidate = currentPackage + "." + simpleName;
            if (allKnownFqns.contains(candidate)) return candidate;
        } else {
            // Default-package case — the simple name itself is the FQN.
            if (allKnownFqns.contains(simpleName)) return simpleName;
        }

        for (String pkg : wildcardPackages) {
            String candidate = pkg + "." + simpleName;
            if (allKnownFqns.contains(candidate)) return candidate;
        }
        return null;
    }

    private FileMetadata metadataOrGet(Path file, ProjectIndex index, JavaParser fallbackParser) {
        if (index != null) {
            return index.fileMetadata(file);
        }
        CompilationUnit cu = JavaParsers.parseOrWarn(fallbackParser, file, "transitive");
        return cu == null ? null : FileMetadataExtractor.extract(cu);
    }

    private String pathToFqn(Path file) {
        return SourceFileScanner.pathToFqn(file, config.sourceDirs());
    }

    /**
     * Cheap text-derived "which files mention each simple type name"
     * map. Built once per {@code walkTransitives} call from a single
     * pass of file content (no AST construction, no JavaParser
     * invocation). Tokenises each file's body for uppercase-leading
     * identifiers — Java's type-name shape — and indexes them.
     *
     * <p>Over-matches by design: constants ({@code MAX_VALUE}), type
     * variables ({@code T}), string-literal contents starting with
     * uppercase, and other non-type uppercase tokens all land in the
     * index. False positives only cause an extra {@code parseOrGet}
     * which is bounded by the {@link ProjectIndex} CU cache, so a
     * file parses at most once per engine run regardless of how many
     * false-positive simple names match it. False <em>negatives</em>
     * would silently drop reverse edges (a regression of the kind
     * the {@code preservesGenericArgumentsInReverseDependencyEdges}
     * and {@code discoversEdgesFromMethodBodyReferences} tests pin),
     * so the regex is deliberately permissive on the over-match
     * side.
     *
     * <p>Files that fail to read (I/O error, missing file due to
     * concurrent {@code git rm}) are logged at debug and skipped —
     * the same posture {@code JavaParsers.parseOrWarn} takes on
     * malformed source. A skipped file simply doesn't contribute
     * candidate edges; it does not crash the walk.
     */
    static final class SimpleNameTextIndex {
        private final Map<String, Set<Path>> simpleNameToFiles;

        private SimpleNameTextIndex(Map<String, Set<Path>> simpleNameToFiles) {
            this.simpleNameToFiles = simpleNameToFiles;
        }

        static SimpleNameTextIndex build(List<Path> sourceFiles) {
            Map<String, Set<Path>> map = new HashMap<>();
            int filesScanned = 0;
            int filesSkipped = 0;
            for (Path file : sourceFiles) {
                String content;
                try {
                    content = Files.readString(file);
                } catch (IOException e) {
                    log.debug("[transitive] Skipping unreadable source file {}: {}",
                            file, e.getMessage());
                    filesSkipped++;
                    continue;
                }
                Matcher m = UPPERCASE_IDENT.matcher(content);
                while (m.find()) {
                    map.computeIfAbsent(m.group(), k -> new LinkedHashSet<>()).add(file);
                }
                filesScanned++;
            }
            log.debug("[transitive] Built simple-name text index from {} file(s) "
                            + "({} skipped); {} distinct simple names indexed",
                    filesScanned, filesSkipped, map.size());
            return new SimpleNameTextIndex(map);
        }

        Set<Path> filesMentioning(String simpleName) {
            return simpleNameToFiles.getOrDefault(simpleName, Set.of());
        }
    }
}
