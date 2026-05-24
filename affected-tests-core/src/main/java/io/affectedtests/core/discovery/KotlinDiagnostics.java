package io.affectedtests.core.discovery;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-engine carrier for the four Kotlin AST signals plumbed into
 * {@code --explain}'s pinned-verbatim output (issue
 * <a href="https://github.com/vedanthvdev/affected-tests/issues/76">#76</a>
 * Phase 2 PR #4):
 *
 * <ol>
 *   <li>{@code Kotlin source AST-mapped to FQN {fqn}.} — fires once per
 *       distinct FQN the Kotlin parser successfully extracted in this
 *       run, capped at {@code AffectedTestTask.EXPLAIN_SAMPLE_LIMIT} so
 *       a wide-diff log stays bounded. The total count is preserved
 *       separately so the renderer can append "(+N more)" when the
 *       sample is truncated.</li>
 *   <li>{@code Kotlin file failed to parse with embeddable {version};
 *       counted into DISCOVERY_INCOMPLETE.} — fires when at least one
 *       {@code .kt} file returned {@code null} from
 *       {@link KotlinLanguageParser#parseOrWarn(Path, String)}. The
 *       per-engine count is enough; the WARN line at the failure site
 *       has already named the specific file and the underlying
 *       error.</li>
 *   <li>{@code Kotlin file {path} declares package {parsed} but
 *       path-derives to {path-derived}; AST-driven strategies use the
 *       declared package, naming strategy uses the path-derived
 *       FQN.} — fires once per distinct mismatched file (capped). The
 *       AST and naming strategies disagree on the FQN for these files
 *       (declared {@code package} vs. directory layout), and {@code
 *       --explain} surfaces it so adopters can spot under-selection
 *       early.</li>
 *   <li>{@code Kotlin embeddable failed to load: {cause}. Treating
 *       .kt files as unparseable for this run.} — fires once if the
 *       embeddable's {@code KotlinCoreEnvironment} bootstrap threw,
 *       carrying the underlying message so the operator does not have
 *       to grep WARN lines to learn what broke.</li>
 * </ol>
 *
 * <p>Sample collections are bounded at {@link #SAMPLE_LIMIT} entries
 * by insertion. The total counts ({@link #astMappedCount()},
 * {@link #pathPackageMismatchCount()}, {@link #parseFailureCount()})
 * keep climbing past the cap so the trace can render "first N of M"
 * honestly. Memory cost is bounded by construction: at most
 * {@code SAMPLE_LIMIT} FQN strings + {@code SAMPLE_LIMIT}
 * {@link MismatchSample} records per engine run.
 *
 * <p>Concurrency: the parser-side increments come from arbitrary
 * discovery-thread-pool workers (parallel discovery is on by default),
 * so every counter and every sample collection is thread-safe by
 * construction. {@link AtomicInteger} for counters,
 * {@link Collections#synchronizedSet} +
 * {@link Collections#synchronizedMap} for samples — the locks fire
 * only on the bounded-add path and are uncontended on hot lookup.
 *
 * <p>Visibility: public so the engine and the Gradle task can read
 * the diagnostics off the {@link io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult}
 * for {@code --explain} rendering. The mutating accessors
 * ({@code recordAstMapped}, {@code recordParseFailure}, etc.) are
 * package-private so only the discovery layer can write into the
 * record; outside callers consume the read-only view.
 */
public final class KotlinDiagnostics {

    /**
     * Pinned version of {@code kotlin-compiler-embeddable} bundled in
     * the shadow JAR. Kept as a constant rather than read from the
     * dependency at runtime because {@code --explain} string stability
     * is part of the issue #76 contract — the embeddable version
     * appears verbatim in the parse-failure pinned string. Bumping
     * the embeddable in {@code affected-tests-gradle/build.gradle} is
     * paired with bumping this constant; the ShadowParseGate
     * functional test pins the version in the shaded JAR so a drift
     * surfaces at TestKit-build latency, not at adopter-CI latency.
     */
    public static final String EMBEDDABLE_VERSION = "2.1.20";

    /**
     * Cap on the number of distinct samples preserved per category.
     * Mirrors {@code AffectedTestTask.EXPLAIN_SAMPLE_LIMIT} so the
     * --explain trace renders at most {@code SAMPLE_LIMIT} lines per
     * pinned string before falling back to the "+N more" tail.
     */
    public static final int SAMPLE_LIMIT = 10;

    /**
     * Sentinel "no diagnostics" instance returned by
     * {@link io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult#kotlinDiagnostics()}
     * when the engine ran without Kotlin AST participation (the
     * {@code kotlinEnabled} flag is off, the Java-only fast paths,
     * etc.). Renderers must check {@link #isEmpty()} and skip the
     * Kotlin --explain block when true so existing Java-only
     * snapshots stay byte-stable.
     *
     * <p>Defensively immutable: the four {@code recordXxx} mutators
     * short-circuit when {@code this == EMPTY}, so a misuse (a
     * future no-arg parser ctor that injects EMPTY into a live
     * {@link KotlinLanguageParser}, or a unit-test fixture that
     * shares the singleton across cases) cannot pollute the JVM-wide
     * state. Without this guard the
     * {@link io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult}
     * back-compat constructors that default to {@code EMPTY} would
     * silently inherit any leaked counters from earlier runs.
     */
    public static final KotlinDiagnostics EMPTY = new KotlinDiagnostics();

    private final AtomicInteger astMappedCount = new AtomicInteger();
    private final AtomicInteger parseFailureCount = new AtomicInteger();
    private final AtomicInteger pathPackageMismatchCount = new AtomicInteger();

    /**
     * First {@link #SAMPLE_LIMIT} distinct AST-mapped FQNs encountered
     * in this engine run. Insertion order is preserved so the
     * --explain trace iterates oldest-first (the order matches the
     * order strategies walked the source tree, which is a stable
     * lexicographic walk).
     */
    private final Set<String> astMappedFqnSamples =
            Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * First {@link #SAMPLE_LIMIT} distinct path/package mismatches
     * encountered. Keyed by {@link Path} (the file's absolute path)
     * so the same file cannot occupy two slots if discovery races
     * realise its metadata twice.
     */
    private final Map<Path, MismatchSample> mismatchSamples =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Cause string captured when the embeddable bootstrap threw.
     * {@code null} until the failure site writes it; subsequent
     * writes are no-ops (only the first cause matters — every
     * later parse short-circuits on the same broken state). Held
     * in an {@link AtomicReference} so the
     * compare-and-set guarantees the first-cause-wins contract.
     */
    private final AtomicReference<String> embeddableLoadFailureCause =
            new AtomicReference<>();

    public KotlinDiagnostics() {
    }

    /**
     * Joins {@code packageName} and {@code typeName} into the dotted
     * FQN the AST-mapped sample preserves. {@code packageName} is the
     * {@link FileMetadata#packageName()} field (empty when the file
     * declares no package), {@code typeName} is the
     * {@link FileMetadata#primaryTypeName()} field (the file's
     * primary class / object, or the synthetic {@code <basename>Kt}
     * for a class-less Kotlin file). Either or both can legitimately
     * be empty for an empty / package-less file; the helper produces
     * a dotted FQN when both are present and falls back gracefully
     * otherwise.
     *
     * <p>Shared by {@code KotlinLanguageParser.extractMetadata}
     * (cold-cache parse path) and {@code ProjectIndex.seedFileMetadata}
     * (warm-cache hydrate path) so both routes record the same FQN
     * shape into the AST-mapped sample set.
     */
    public static String joinFqn(String packageName, String typeName) {
        if (packageName == null || packageName.isEmpty()) {
            return typeName == null ? "" : typeName;
        }
        if (typeName == null || typeName.isEmpty()) {
            return packageName;
        }
        return packageName + "." + typeName;
    }

    /**
     * Records that the Kotlin parser successfully extracted metadata
     * for {@code fqn}. Called from
     * {@link KotlinLanguageParser#parseOrWarn(Path, String)} on the
     * happy path. Increments {@link #astMappedCount()} unconditionally
     * and adds {@code fqn} to the bounded sample set when
     * (a) {@code fqn} is a fresh entry and (b) the sample set is
     * still under {@link #SAMPLE_LIMIT}. Both gates are respected so
     * the sample preserves the first {@code SAMPLE_LIMIT} distinct
     * FQNs and ignores duplicates.
     */
    void recordAstMapped(String fqn) {
        if (this == EMPTY) {
            return;
        }
        if (fqn == null || fqn.isEmpty()) {
            return;
        }
        astMappedCount.incrementAndGet();
        synchronized (astMappedFqnSamples) {
            if (astMappedFqnSamples.size() < SAMPLE_LIMIT) {
                astMappedFqnSamples.add(fqn);
            }
        }
    }

    /**
     * Records that the Kotlin parser returned {@code null} for a file
     * — either a syntax error past what the embeddable can recover
     * from, an IO failure, or a JVM error from the parser machinery.
     * Always increments the counter; the WARN line at the failure
     * site already named the specific file, so this counter is
     * intentionally just a per-engine-run tally.
     */
    void recordParseFailure() {
        if (this == EMPTY) {
            return;
        }
        parseFailureCount.incrementAndGet();
    }

    /**
     * Records that {@code file}'s declared package
     * ({@code parsedPackage}) does not match the package the diff
     * mapper would derive from the file's path
     * ({@code pathDerivedPackage}). Increments
     * {@link #pathPackageMismatchCount()} unconditionally; samples
     * the first {@link #SAMPLE_LIMIT} distinct files so adopters
     * can see representative offenders without bloating the
     * --explain output.
     */
    void recordPathPackageMismatch(Path file, String parsedPackage, String pathDerivedPackage) {
        if (this == EMPTY) {
            return;
        }
        if (file == null) {
            return;
        }
        pathPackageMismatchCount.incrementAndGet();
        synchronized (mismatchSamples) {
            if (!mismatchSamples.containsKey(file)
                    && mismatchSamples.size() < SAMPLE_LIMIT) {
                mismatchSamples.put(file,
                        new MismatchSample(file, parsedPackage, pathDerivedPackage));
            }
        }
    }

    /**
     * Records that the embeddable's {@code KotlinCoreEnvironment}
     * bootstrap threw, carrying the underlying message for the
     * --explain trace. Only the first cause is preserved
     * (compare-and-set semantics); subsequent failure-site writes
     * are no-ops because every later parse short-circuits on the
     * same broken state.
     */
    void recordEmbeddableLoadFailure(String cause) {
        if (this == EMPTY) {
            return;
        }
        embeddableLoadFailureCause.compareAndSet(null,
                cause == null || cause.isEmpty() ? "unknown" : cause);
    }

    /** @return the total number of {@code .kt} files AST-mapped this run */
    public int astMappedCount() {
        return astMappedCount.get();
    }

    /** @return the total number of {@code .kt} files that failed to parse this run */
    public int parseFailureCount() {
        return parseFailureCount.get();
    }

    /**
     * @return the total number of {@code .kt} files whose declared
     *         package did not match the path-derived package this run
     */
    public int pathPackageMismatchCount() {
        return pathPackageMismatchCount.get();
    }

    /**
     * Snapshot of the bounded AST-mapped FQN sample set, ordered by
     * insertion (the order discovery encountered each FQN). Returned
     * as an unmodifiable view so the renderer cannot accidentally
     * mutate the live tracker. Empty when the parser produced zero
     * successful parses.
     */
    public Set<String> astMappedFqnSamples() {
        synchronized (astMappedFqnSamples) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(astMappedFqnSamples));
        }
    }

    /**
     * Snapshot of the bounded path/package mismatch samples, ordered
     * by insertion. Returned as an unmodifiable view.
     */
    public Collection<MismatchSample> mismatchSamples() {
        synchronized (mismatchSamples) {
            return Collections.unmodifiableCollection(
                    new LinkedHashMap<>(mismatchSamples).values());
        }
    }

    /**
     * @return the embeddable bootstrap failure cause for this run, or
     *         {@code null} if the bootstrap succeeded (or was never
     *         attempted because no {@code .kt} file reached the
     *         parser).
     */
    public String embeddableLoadFailureCause() {
        return embeddableLoadFailureCause.get();
    }

    /**
     * @return {@code true} when no Kotlin signal fired in this engine
     *         run — equivalent to "the renderer has nothing to print
     *         in the Kotlin AST block". Used to skip the entire block
     *         on Java-only / no-diff runs so existing snapshots stay
     *         byte-stable.
     */
    public boolean isEmpty() {
        return astMappedCount.get() == 0
                && parseFailureCount.get() == 0
                && pathPackageMismatchCount.get() == 0
                && embeddableLoadFailureCause.get() == null;
    }

    /**
     * Single mismatch entry for the {@code --explain} renderer. The
     * record carries the file path so the trace can sanitise it (the
     * filename came from an attacker-controllable diff), and both
     * package strings so the renderer can name them in the order the
     * pinned string requires.
     *
     * @param file               absolute path of the offending file
     * @param parsedPackage      package declared inside the {@code .kt} file
     *                           (empty string when the file declares no package)
     * @param pathDerivedPackage package the diff-side mapper derived
     *                           from the file's directory layout
     *                           (empty string when the file lives at
     *                           a source-root immediately, e.g.
     *                           {@code src/main/java/Foo.kt})
     */
    public record MismatchSample(Path file, String parsedPackage, String pathDerivedPackage) {
        public MismatchSample {
            Objects.requireNonNull(file, "file");
            parsedPackage = parsedPackage == null ? "" : parsedPackage;
            pathDerivedPackage = pathDerivedPackage == null ? "" : pathDerivedPackage;
        }
    }
}
