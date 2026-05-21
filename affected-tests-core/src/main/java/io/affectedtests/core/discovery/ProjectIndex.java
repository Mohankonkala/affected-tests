package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.mapping.OutOfScopeMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Pre-scanned index of source and test files for a project directory.
 * Built once per engine run to avoid redundant file-tree walks and AST parses
 * across strategies.
 *
 * <p>{@link CompilationUnit}s are parsed lazily on first access and cached.
 * <strong>Thread-safety contract (issue #42)</strong>: the lookup map and
 * parse-failure counter are safe under concurrent {@link #compilationUnit}
 * calls from multiple discovery strategies, and the parser itself is held
 * in a {@link ThreadLocal} so each thread parses with its own
 * {@link JavaParser} instance (JavaParser's mutable parser-config and
 * symbol-resolver state make a single shared parser unsafe to invoke
 * concurrently). Callers still MUST NOT use the returned CU from
 * multiple threads concurrently — that's a JavaParser AST constraint
 * that no amount of cache wrapping can paper over.
 */
public final class ProjectIndex {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndex.class);

    private final List<Path> sourceFiles;
    private final List<Path> testFiles;
    private final Map<String, Path> testFqnToPath;
    private final Set<String> sourceFqns;

    /**
     * Scan-root paths captured at {@link #build(Path, AffectedTestsConfig) build}
     * time so {@link ProjectIndexCache} can re-fingerprint exactly what
     * was walked when persisting at end of run (Stage&nbsp;2 of
     * issue&nbsp;#41). On a Stage&nbsp;1 cache hit
     * {@link ProjectIndexCache} reconstructs this from the cached
     * dir-snapshot rows so the persist path stays uniform across
     * hit / miss.
     */
    private final ProjectIndexCache.ScannedDirs scannedDirs;

    /**
     * Lazy AST cache. Holds {@code Optional.empty()} for paths that
     * parseOrWarn returned null for, since {@link ConcurrentHashMap}
     * disallows null values. Switching to {@link ConcurrentHashMap}
     * (issue #42) lets multiple discovery strategies request CUs
     * concurrently without racing on {@code containsKey}/{@code put}.
     * Compute happens through {@link Map#computeIfAbsent} so a single
     * file is parsed at most once even when N threads request it
     * simultaneously.
     */
    private final ConcurrentHashMap<Path, Optional<CompilationUnit>> cuCache =
            new ConcurrentHashMap<>();

    /**
     * Strategy-facing per-file derived data (issue&nbsp;#41 stage&nbsp;2).
     * Holds {@code Optional.empty()} for paths that failed to parse so
     * the same retry-and-fail behaviour as {@link #cuCache} is preserved
     * (and so {@link ConcurrentHashMap}'s null-value prohibition doesn't
     * collapse a failure into a recompute loop).
     *
     * <p>On a cache hit the snapshot pre-populates this map directly from
     * disk via {@link #seedFileMetadata(Path, FileMetadata, long, long)}
     * so strategies never touch the parser. On a miss (or for a file
     * whose fingerprint changed since the snapshot) the entry is
     * populated lazily through {@link #fileMetadata(Path)} via
     * {@link FileMetadataExtractor}.
     *
     * <p>Each entry carries the {@code (mtime, size)} fingerprint that
     * was captured immediately after the parse / at seed time (see
     * {@link CachedMetadata}) so the end-of-run persist can write it
     * back without re-statting every file. Capturing at extract time
     * also collapses the TOCTOU window: if a user edits the file
     * mid-run, the persisted fingerprint corresponds (within
     * microseconds) to the content we actually parsed, instead of to
     * the post-edit state which would silently match a stale cache
     * row on the next run.
     */
    private final ConcurrentHashMap<Path, Optional<CachedMetadata>> metadataCache =
            new ConcurrentHashMap<>();

    /**
     * Cached {@link FileMetadata} bundled with the {@code (mtime, size)}
     * fingerprint of the source file as observed at the moment the
     * record was produced. Lazy-extract path captures the fingerprint
     * via {@link Files#readAttributes} immediately after the
     * {@link CompilationUnit} parse succeeds; cache-hit-seed path
     * inherits the fingerprint from the on-disk row that already
     * verified against the live file. End-of-run persist writes the
     * carried fingerprint directly — no second {@link Files#readAttributes}
     * pass — which both halves the per-file syscall count and binds
     * each persisted row to the file version we actually consumed.
     *
     * <p>{@link #UNKNOWN_FINGERPRINT} marks an entry whose fingerprint
     * could not be captured (the {@link Files#readAttributes} call
     * after a successful parse threw). Such entries serve strategy
     * lookups normally but are excluded from the persist snapshot —
     * a row with no verifiable fingerprint would either be invalidated
     * on every subsequent load (best case) or silently survive past
     * a real change (worst case), and either outcome is worse than
     * paying one re-extract on the next run.
     */
    record CachedMetadata(FileMetadata metadata, long mtime, long size) {
        static final long UNKNOWN_FINGERPRINT = -1L;

        boolean hasFingerprint() {
            return mtime != UNKNOWN_FINGERPRINT && size != UNKNOWN_FINGERPRINT;
        }
    }

    /**
     * Per-thread parser. JavaParser instances are not safe to share
     * across threads (their {@code ParserConfiguration} is mutated
     * during parse and the symbol-resolver state is per-instance).
     * {@code ThreadLocal.withInitial} keeps the construction cost off
     * the critical path — each thread builds its parser exactly
     * once, on its first compilationUnit() call.
     *
     * <p>Held as a {@code static} field on purpose. An instance-level
     * ThreadLocal would entry a fresh slot in every running thread's
     * {@code ThreadLocalMap} for every engine run; on the serial path
     * the calling thread is a Gradle-daemon worker that lives across
     * builds, and the {@code JavaParser} values would leak ~30–80 KB
     * each until the worker dies (`ThreadLocalMap` only expunges
     * stale entries opportunistically). Promoting to {@code static}
     * gives us one parser per thread, shared across every engine run
     * the JVM serves — bounded growth, zero adopter cost. Pool
     * threads on the parallel path are daemons that die at
     * {@code shutdown()}, so they shed their parser entries
     * naturally.
     */
    private static final ThreadLocal<JavaParser> PARSER =
            ThreadLocal.withInitial(JavaParsers::newParser);

    /**
     * Count of distinct files that {@link JavaParsers#parseOrWarn}
     * returned null for. The engine consults this after discovery to
     * decide whether to route through
     * {@link io.affectedtests.core.config.Situation#DISCOVERY_INCOMPLETE}:
     * a parse failure silently drops the affected file from Usage /
     * Implementation / Transitive strategies, and before v1.9.22 the
     * only signal was a WARN at parse time — the engine itself
     * couldn't tell a clean empty selection apart from "we couldn't
     * read half the tests". Counting at the index boundary (not at
     * each strategy) de-duplicates across strategies — the shared
     * cache means one file parses once per run regardless of how
     * many strategies consult it.
     *
     * <p>Backed by {@link AtomicInteger} (issue #42) so the increment
     * is safe under parallel discovery; the de-dup contract relies
     * on {@link ConcurrentHashMap#computeIfAbsent} running its
     * mapping function exactly once per absent key, so a parse
     * failure is observed (and counted) by exactly one thread even
     * when N threads race to request the same unparseable file.
     */
    private final AtomicInteger parseFailureCount = new AtomicInteger();

    private ProjectIndex(List<Path> sourceFiles, List<Path> testFiles,
                         Map<String, Path> testFqnToPath, Set<String> sourceFqns,
                         ProjectIndexCache.ScannedDirs scannedDirs) {
        this.sourceFiles = sourceFiles;
        this.testFiles = testFiles;
        this.testFqnToPath = testFqnToPath;
        this.sourceFqns = sourceFqns;
        this.scannedDirs = scannedDirs;
    }

    public static ProjectIndex build(Path projectDir, AffectedTestsConfig config) {
        // Issue #41: try the persistent cache first. The validity contract
        // (schemaVersion + configHash + per-scan-root mtime + child count)
        // lives entirely inside ProjectIndexCache; any drift falls
        // through to a full rebuild. A cache miss is exactly the
        // pre-cache behaviour, so the worst case is unchanged.
        if (Boolean.parseBoolean(System.getProperty("affected-tests.indexCache.enabled", "true"))) {
            Optional<ProjectIndex> cached = ProjectIndexCache.tryLoad(projectDir, config);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        log.info("Building project index for {}", projectDir);

        // Share the exact matcher compilation PathToClassMapper uses so
        // diff-side bucketing and indexed-file-side filtering agree on
        // every entry — glob form, literal form, or mixed. Before this
        // shared source of truth the two sides silently disagreed on
        // glob entries: the mapper compiled "api-test/**" into a
        // PathMatcher while this class treated it as a literal prefix
        // and matched nothing, so mixed diffs still dispatched tests
        // under "api-test/".
        List<Predicate<String>> oosSourceMatchers = OutOfScopeMatchers.compile(
                config.outOfScopeSourceDirs(), "outOfScopeSourceDirs");
        List<Predicate<String>> oosTestMatchers = OutOfScopeMatchers.compile(
                config.outOfScopeTestDirs(), "outOfScopeTestDirs");
        boolean hasOutOfScope = !oosSourceMatchers.isEmpty() || !oosTestMatchers.isEmpty();

        // Capture the resolved scan roots so the cache can fingerprint
        // exactly what we walked — the mtime + child-count gate on each
        // root is what makes the next-run fast path safe. Resolved roots
        // are project-relative paths after glob/suffix expansion (e.g.
        // "src/main/java", "services/orders/src/test/java"), filtered to
        // the in-scope ones — out-of-scope dirs would still drift their
        // mtime independently and force unnecessary cache misses if we
        // recorded them.
        List<Path> sourceRoots = new ArrayList<>();
        List<Path> testRoots = new ArrayList<>();

        List<Path> sourceFiles = filterOutOfScope(
                SourceFileScanner.collectSourceFiles(projectDir, config.sourceDirs(), sourceRoots),
                projectDir, oosSourceMatchers, oosTestMatchers, hasOutOfScope);
        List<Path> testFiles = filterOutOfScope(
                SourceFileScanner.collectTestFiles(projectDir, config.testDirs(), testRoots),
                projectDir, oosSourceMatchers, oosTestMatchers, hasOutOfScope);

        if (hasOutOfScope) {
            sourceRoots.removeIf(p -> isUnderAny(p, projectDir, oosSourceMatchers, oosTestMatchers));
            testRoots.removeIf(p -> isUnderAny(p, projectDir, oosSourceMatchers, oosTestMatchers));
        }

        LinkedHashMap<String, Path> testFqnToPath = SourceFileScanner.scanTestFqnsWithFiles(
                projectDir, config.testDirs());
        if (hasOutOfScope) {
            // Drop out-of-scope test FQNs from the dispatch map. Without
            // this, discovery strategies could still return FQNs living
            // under {@code api-test/src/test/java} and the task would then
            // try to run them — the entire point of the out-of-scope knob
            // is that those tests never reach the affected-test dispatch.
            testFqnToPath.entrySet().removeIf(entry -> isUnderAny(
                    entry.getValue(), projectDir, oosSourceMatchers, oosTestMatchers));
        }

        Set<String> sourceFqns = new LinkedHashSet<>();
        for (String sourceDir : config.sourceDirs()) {
            for (Path resolved : SourceFileScanner.findAllMatchingDirs(projectDir, sourceDir)) {
                if (hasOutOfScope && isUnderAny(resolved, projectDir, oosSourceMatchers, oosTestMatchers)) continue;
                sourceFqns.addAll(SourceFileScanner.fqnsUnder(resolved));
            }
        }

        log.info("Project index: {} source files, {} test files, {} source FQNs, {} test FQNs"
                        + " (out-of-scope source dirs: {}, out-of-scope test dirs: {})",
                sourceFiles.size(), testFiles.size(), sourceFqns.size(), testFqnToPath.size(),
                config.outOfScopeSourceDirs().size(), config.outOfScopeTestDirs().size());

        ProjectIndexCache.ScannedDirs dirs =
                new ProjectIndexCache.ScannedDirs(sourceRoots, testRoots);
        ProjectIndex index = new ProjectIndex(
                Collections.unmodifiableList(sourceFiles),
                Collections.unmodifiableList(testFiles),
                Collections.unmodifiableMap(testFqnToPath),
                Collections.unmodifiableSet(sourceFqns),
                dirs
        );

        if (Boolean.parseBoolean(System.getProperty("affected-tests.indexCache.enabled", "true"))) {
            ProjectIndexCache.persist(projectDir, config, index, dirs);
        }
        return index;
    }

    /**
     * Reconstitutes a {@link ProjectIndex} from cached aggregates without
     * doing any source-tree walking. Stage 1 of issue #41 surfaces the
     * cache hit through this factory; everything else (lazy CU cache,
     * parse-failure counter) initialises empty exactly as a freshly-built
     * index does — the cache stores derived data, not parser state, so
     * the first {@code compilationUnit(file)} call on a cached index
     * still parses on demand.
     */
    static ProjectIndex fromCache(List<Path> sourceFiles,
                                  List<Path> testFiles,
                                  LinkedHashMap<String, Path> testFqnToPath,
                                  Set<String> sourceFqns,
                                  ProjectIndexCache.ScannedDirs scannedDirs) {
        return new ProjectIndex(
                Collections.unmodifiableList(sourceFiles),
                Collections.unmodifiableList(testFiles),
                Collections.unmodifiableMap(testFqnToPath),
                Collections.unmodifiableSet(sourceFqns),
                scannedDirs
        );
    }

    /**
     * Returns the scan-root paths captured when this index was built.
     * Used by {@link ProjectIndexCache} for end-of-run persistence so
     * Stage&nbsp;2 metadata can be written without re-resolving the
     * {@code sourceDirs} / {@code testDirs} glob shapes a second time.
     * Package-private — no external consumer.
     */
    ProjectIndexCache.ScannedDirs scannedDirs() {
        return scannedDirs;
    }

    private static List<Path> filterOutOfScope(List<Path> files, Path projectDir,
                                               List<Predicate<String>> oosSourceMatchers,
                                               List<Predicate<String>> oosTestMatchers,
                                               boolean hasOutOfScope) {
        if (!hasOutOfScope) {
            return files;
        }
        List<Path> filtered = new ArrayList<>(files.size());
        for (Path file : files) {
            if (!isUnderAny(file, projectDir, oosSourceMatchers, oosTestMatchers)) {
                filtered.add(file);
            }
        }
        return filtered;
    }

    /**
     * Normalised "does this absolute path sit under any of the compiled
     * out-of-scope matchers?" check. Evaluates exactly the matchers
     * {@link io.affectedtests.core.mapping.PathToClassMapper} uses on
     * the diff side, so a file and an indexed file pointing to the
     * same location route the same way whether the entry was written
     * as {@code api-test}, {@code api-test/**}, or {@code **&#47;api-test/**}.
     */
    static boolean isUnderAny(Path file, Path projectDir,
                              List<Predicate<String>> oosSourceMatchers,
                              List<Predicate<String>> oosTestMatchers) {
        if (oosSourceMatchers.isEmpty() && oosTestMatchers.isEmpty()) return false;
        String rel;
        try {
            rel = projectDir.toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return false;
        }
        String normalized = rel.replace(java.io.File.separatorChar, '/');
        return OutOfScopeMatchers.matchesAny(normalized, oosSourceMatchers)
                || OutOfScopeMatchers.matchesAny(normalized, oosTestMatchers);
    }

    public List<Path> sourceFiles() { return sourceFiles; }
    public List<Path> testFiles() { return testFiles; }
    public Set<String> testFqns() { return testFqnToPath.keySet(); }
    public Map<String, Path> testFqnToPath() { return testFqnToPath; }
    public Set<String> sourceFqns() { return sourceFqns; }

    /**
     * Parses {@code file} with JavaParser, caching the result. Returns
     * {@code null} if the file cannot be parsed (malformed source, I/O error).
     *
     * <p>Results are shared across strategies so the same test file is parsed
     * at most once per engine run, even when N strategies request it
     * concurrently from different threads. The compute-once guarantee
     * comes from {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent};
     * the same primitive ensures the parse-failure count is
     * incremented at most once per failing file no matter how many
     * threads race to ask for it.
     */
    public CompilationUnit compilationUnit(Path file) {
        Optional<CompilationUnit> entry = cuCache.computeIfAbsent(file, key -> {
            CompilationUnit cu = JavaParsers.parseOrWarn(PARSER.get(), key, "index");
            if (cu == null) {
                parseFailureCount.incrementAndGet();
            }
            return Optional.ofNullable(cu);
        });
        return entry.orElse(null);
    }

    /**
     * Returns the strategy-relevant per-file derived data — package,
     * imports, type-reference simple/dotted names, type declarations
     * with their supertype simple names — for {@code file}. Returns
     * {@code null} when the file failed to parse and no cached metadata
     * is available, mirroring the {@link #compilationUnit(Path)} null
     * contract so strategies can keep their existing {@code if (md == null) continue;}
     * skip pattern.
     *
     * <p>Issue&nbsp;#41 stage&nbsp;2 hot path. On a Stage&nbsp;2 cache
     * hit the snapshot has already seeded {@link #metadataCache}
     * directly from disk for every fingerprint-matching file, so
     * strategy reads cost a single map lookup. On a miss (or for a
     * file whose {@code (mtime, size)} fingerprint drifted) the lazy
     * load extracts metadata from the freshly-parsed
     * {@link CompilationUnit} via {@link FileMetadataExtractor}, and
     * the resulting record is shareable across the discovery thread
     * pool because {@link FileMetadata} is immutable.
     *
     * <p>The compilation unit obtained in the miss path is itself
     * cached by {@link #compilationUnit(Path)}, so a strategy that
     * still wants the raw AST for some future feature can pay only
     * the parse cost once — not once per consumer.
     */
    public FileMetadata fileMetadata(Path file) {
        Optional<CachedMetadata> entry = metadataCache.computeIfAbsent(file, key -> {
            CompilationUnit cu = compilationUnit(key);
            if (cu == null) {
                return Optional.empty();
            }
            FileMetadata md = FileMetadataExtractor.extract(cu);
            // Capture the source file's (mtime, size) fingerprint
            // immediately after the parse so the persisted row reflects
            // the file version we actually consumed. The TOCTOU window
            // shrinks to a few microseconds — small enough that an
            // editor-save-during-discovery race is now bounded by the
            // OS scheduler rather than by "until end of engine run".
            // An IO failure here keeps the metadata usable for this
            // run but marks the entry unfit for persist (see
            // {@link #metadataCacheSnapshot()}); the alternative —
            // writing a row whose fingerprint we can't justify — is
            // strictly worse for cache correctness.
            long mtime;
            long size;
            try {
                BasicFileAttributes attrs = Files.readAttributes(key, BasicFileAttributes.class);
                mtime = attrs.lastModifiedTime().toMillis();
                size = attrs.size();
            } catch (IOException ioe) {
                mtime = CachedMetadata.UNKNOWN_FINGERPRINT;
                size = CachedMetadata.UNKNOWN_FINGERPRINT;
            }
            return Optional.of(new CachedMetadata(md, mtime, size));
        });
        return entry.map(CachedMetadata::metadata).orElse(null);
    }

    /**
     * Pre-populates the metadata cache for {@code file} from the
     * persistent snapshot — the cache hit path. Call this exactly once
     * per fingerprint-matching cached file before strategies fan out;
     * any later {@link #fileMetadata(Path)} call short-circuits to the
     * pre-seeded entry without touching JavaParser.
     *
     * <p>Carries the {@code (mtime, size)} fingerprint forward from
     * the on-disk row so end-of-run persist can re-emit the same row
     * without a fresh {@link Files#readAttributes} call. The seeded
     * fingerprint already passed verification against the live file
     * in {@link ProjectIndexCache} — re-stat would only confirm what
     * we just confirmed.
     *
     * <p>Package-private because only {@link ProjectIndexCache} should
     * be wiring cached records back into a {@code ProjectIndex}.
     */
    void seedFileMetadata(Path file, FileMetadata metadata, long mtime, long size) {
        metadataCache.put(file, Optional.of(new CachedMetadata(metadata, mtime, size)));
    }

    /**
     * Number of distinct files whose {@link FileMetadata} has been
     * realised during this engine run, summed across snapshot-seeded
     * entries and lazily-extracted entries. Diagnostic accessor used by
     * Stage&nbsp;2 cache tests to assert "warm-run discovery touched
     * the parser zero times" on an unchanged tree.
     */
    int fileMetadataCacheSize() {
        return metadataCache.size();
    }

    /**
     * Returns an unmodifiable view of every successfully-realised
     * {@link FileMetadata} entry currently in the per-file cache that
     * also carries a verifiable {@code (mtime, size)} fingerprint,
     * keyed by the absolute file path. Filtered to:
     * <ul>
     *   <li>drop unsuccessful parses (entries holding
     *       {@link Optional#empty()}) — persisting them is incorrect
     *       since the next run might succeed once the source error is
     *       fixed;</li>
     *   <li>drop entries whose fingerprint capture failed
     *       ({@link CachedMetadata#hasFingerprint()} is {@code false})
     *       — a row without a verifiable fingerprint can't be safely
     *       reused on the next run.</li>
     * </ul>
     *
     * <p>Used by {@link ProjectIndexCache} stage&nbsp;2 to persist the
     * per-file derived data at end of run. Package-private because no
     * other consumer should be poking at the cache shape.
     */
    Map<Path, CachedMetadata> metadataCacheSnapshot() {
        LinkedHashMap<Path, CachedMetadata> out = new LinkedHashMap<>();
        for (Map.Entry<Path, Optional<CachedMetadata>> e : metadataCache.entrySet()) {
            e.getValue().ifPresent(cm -> {
                if (cm.hasFingerprint()) {
                    out.put(e.getKey(), cm);
                }
            });
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Number of distinct scanned files for which {@link #compilationUnit(Path)}
     * could not produce an AST during this engine run. Non-zero means the
     * Usage / Implementation / Transitive strategies may have under-
     * reported their tier: the engine consumes this to route through
     * {@link io.affectedtests.core.config.Situation#DISCOVERY_INCOMPLETE}
     * so the configured action (SELECTED / FULL_SUITE / SKIPPED) decides
     * the outcome instead of the pre-v1.9.22 silent-drop behaviour.
     *
     * @return the de-duplicated count of files that failed to parse
     */
    public int parseFailureCount() {
        return parseFailureCount.get();
    }

    /**
     * Number of distinct files for which {@link #compilationUnit(Path)}
     * has been called during this engine run (regardless of whether
     * the parse succeeded or failed). Backed by the lazy CU cache:
     * a file added on first request, never re-added on subsequent
     * cache hits.
     *
     * <p>Consumed by {@code TransitiveStrategyTest} to pin the
     * frontier-first lazy-walk contract from issue #43: on a 1k-file
     * harness where only a handful of files reach the changed FQN,
     * the parsed-file count must stay well below the source-file
     * total. Without this accessor the test would have to scrape
     * log output, which is brittle across log-config changes.
     *
     * @return the count of distinct files that have been requested
     *         from {@link #compilationUnit(Path)} so far
     */
    public int parsedFileCount() {
        return cuCache.size();
    }
}
