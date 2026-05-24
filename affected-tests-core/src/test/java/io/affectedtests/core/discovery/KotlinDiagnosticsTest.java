package io.affectedtests.core.discovery;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the per-engine Kotlin diagnostic carrier's contract surface
 * (issue #76 PR #4). The four pinned {@code --explain} strings the
 * Gradle task emits depend on this class for their counters and
 * sample sets, so a regression here surfaces directly as
 * {@code --explain} output drift.
 *
 * <p>Coverage matrix:
 *
 * <ul>
 *   <li>Sample-cap arithmetic ({@link KotlinDiagnostics#SAMPLE_LIMIT})
 *       on both AST-mapped FQN samples and path/package mismatch
 *       samples — counters keep climbing past the cap, sample sets
 *       stop adding past the cap.</li>
 *   <li>Dedup contract: the same FQN twice produces one sample but
 *       two counter increments; the same {@link Path} twice produces
 *       one sample but two counter increments.</li>
 *   <li>First-cause-wins on
 *       {@link KotlinDiagnostics#recordEmbeddableLoadFailure(String)}
 *       — concurrent failures preserve exactly one cause (the first
 *       to land in the {@link java.util.concurrent.atomic.AtomicReference})
 *       and never an interleaved string.</li>
 *   <li>Null / empty input is a no-op for every mutator (counter
 *       and sample stay locked together).</li>
 *   <li>{@link KotlinDiagnostics#EMPTY} is read-only — every
 *       mutator short-circuits when {@code this == EMPTY}, so a
 *       misuse (no-arg parser ctor with EMPTY injected, fixture
 *       sharing the singleton) cannot pollute the JVM-wide
 *       state.</li>
 *   <li>Concurrent writers + concurrent readers: 200-thread fan-out
 *       calling {@link KotlinDiagnostics#recordAstMapped(String)} +
 *       {@link KotlinDiagnostics#recordPathPackageMismatch(Path, String, String)}
 *       while a separate thread reads the bounded sample views
 *       must not throw {@link java.util.ConcurrentModificationException}
 *       and must produce a final count == fan-out width.</li>
 * </ul>
 */
class KotlinDiagnosticsTest {

    @Test
    void freshInstanceReportsNoSignals() {
        KotlinDiagnostics diag = new KotlinDiagnostics();

        assertTrue(diag.isEmpty(),
                "A freshly-constructed carrier must report isEmpty()=true so "
                        + "the renderer skips the entire Kotlin --explain block "
                        + "on Java-only / no-diff runs (every existing snapshot-based "
                        + "test fixture relies on this byte-stability).");
        assertEquals(0, diag.astMappedCount());
        assertEquals(0, diag.parseFailureCount());
        assertEquals(0, diag.pathPackageMismatchCount());
        assertNull(diag.embeddableLoadFailureCause());
        assertTrue(diag.astMappedFqnSamples().isEmpty());
        assertTrue(diag.mismatchSamples().isEmpty());
    }

    @Test
    void astMappedRecordsFqnAndIncrementsCounter() {
        KotlinDiagnostics diag = new KotlinDiagnostics();

        diag.recordAstMapped("com.example.Foo");

        assertEquals(1, diag.astMappedCount());
        assertEquals(1, diag.astMappedFqnSamples().size());
        assertTrue(diag.astMappedFqnSamples().contains("com.example.Foo"));
        assertFalse(diag.isEmpty());
    }

    @Test
    void astMappedDeduplicatesFqnButCountsEverySignal() {
        // A multi-strategy run can call extractMetadata for the same
        // file twice (e.g. Naming + Usage both reach for the same
        // file's FileMetadata across the parallel-discovery fan-out).
        // The dedup contract is "samples is a Set keyed by FQN" so
        // the trace doesn't spam the same line; the counter still
        // climbs so the "(+N more)" tail is honest.
        KotlinDiagnostics diag = new KotlinDiagnostics();

        diag.recordAstMapped("com.example.Foo");
        diag.recordAstMapped("com.example.Foo");
        diag.recordAstMapped("com.example.Foo");

        assertEquals(3, diag.astMappedCount(),
                "Counter must climb on every record* call — the renderer's "
                        + "'… and N more' arithmetic depends on the count being a "
                        + "literal-tally, not a distinct-tally.");
        assertEquals(1, diag.astMappedFqnSamples().size(),
                "Sample is a Set so the trace renders one line per distinct FQN.");
    }

    @Test
    void astMappedRespectsSampleLimit() {
        KotlinDiagnostics diag = new KotlinDiagnostics();

        for (int i = 0; i < KotlinDiagnostics.SAMPLE_LIMIT * 2; i++) {
            diag.recordAstMapped("com.example.Foo" + i);
        }

        assertEquals(KotlinDiagnostics.SAMPLE_LIMIT * 2, diag.astMappedCount());
        assertEquals(KotlinDiagnostics.SAMPLE_LIMIT, diag.astMappedFqnSamples().size(),
                "Sample set caps at SAMPLE_LIMIT; the renderer's '… and N more' "
                        + "summary fills in the rest.");
        // The first SAMPLE_LIMIT FQNs survive (insertion order is preserved
        // so the trace is stable across cold-cache runs).
        assertTrue(diag.astMappedFqnSamples().contains("com.example.Foo0"));
        assertTrue(diag.astMappedFqnSamples().contains(
                "com.example.Foo" + (KotlinDiagnostics.SAMPLE_LIMIT - 1)));
        assertFalse(diag.astMappedFqnSamples().contains(
                "com.example.Foo" + KotlinDiagnostics.SAMPLE_LIMIT));
    }

    @Test
    void astMappedNullOrEmptyFqnIsNoOp() {
        // Counter and sample stay in lockstep so the renderer's
        // 'rendered > 0 && count > rendered' guard never produces a
        // bare '… and N more' line.
        KotlinDiagnostics diag = new KotlinDiagnostics();

        diag.recordAstMapped(null);
        diag.recordAstMapped("");

        assertEquals(0, diag.astMappedCount(),
                "Null / empty input must not climb the counter — without this "
                        + "guard the renderer would emit a bare '… and 1 more' tail "
                        + "with no preceding sample line.");
        assertTrue(diag.astMappedFqnSamples().isEmpty());
        assertTrue(diag.isEmpty(),
                "Null / empty inputs must not flip isEmpty() — the renderer "
                        + "would otherwise emit an empty Kotlin block on every "
                        + "Java-only diff that happens to call the diagnostics "
                        + "carrier through a defensive code path.");
    }

    @Test
    void pathPackageMismatchRecordsSampleAndIncrementsCounter() {
        KotlinDiagnostics diag = new KotlinDiagnostics();
        Path file = Path.of("/tmp/repo/src/main/kotlin/com/example/foo/Bar.kt");

        diag.recordPathPackageMismatch(file, "com.example.different", "com.example.foo");

        assertEquals(1, diag.pathPackageMismatchCount());
        assertEquals(1, diag.mismatchSamples().size());
        KotlinDiagnostics.MismatchSample sample =
                diag.mismatchSamples().iterator().next();
        assertEquals(file, sample.file());
        assertEquals("com.example.different", sample.parsedPackage());
        assertEquals("com.example.foo", sample.pathDerivedPackage());
    }

    @Test
    void pathPackageMismatchDeduplicatesByPath() {
        KotlinDiagnostics diag = new KotlinDiagnostics();
        Path file = Path.of("/tmp/repo/src/main/kotlin/Foo.kt");

        diag.recordPathPackageMismatch(file, "com.a", "com.b");
        diag.recordPathPackageMismatch(file, "com.a", "com.b");
        diag.recordPathPackageMismatch(file, "com.a", "com.b");

        assertEquals(3, diag.pathPackageMismatchCount());
        assertEquals(1, diag.mismatchSamples().size(),
                "Sample is keyed by Path so the trace renders one line per "
                        + "distinct file even when discovery races realise the "
                        + "metadata twice.");
    }

    @Test
    void pathPackageMismatchRespectsSampleLimit() {
        KotlinDiagnostics diag = new KotlinDiagnostics();

        for (int i = 0; i < KotlinDiagnostics.SAMPLE_LIMIT * 2; i++) {
            diag.recordPathPackageMismatch(
                    Path.of("/tmp/Foo" + i + ".kt"), "com.a", "com.b");
        }

        assertEquals(KotlinDiagnostics.SAMPLE_LIMIT * 2, diag.pathPackageMismatchCount());
        assertEquals(KotlinDiagnostics.SAMPLE_LIMIT, diag.mismatchSamples().size());
    }

    @Test
    void pathPackageMismatchNullFileIsNoOp() {
        KotlinDiagnostics diag = new KotlinDiagnostics();

        diag.recordPathPackageMismatch(null, "com.a", "com.b");

        assertEquals(0, diag.pathPackageMismatchCount(),
                "Null file must not climb the counter — pairs with the "
                        + "renderer's 'rendered > 0' guard so a defensive null call "
                        + "cannot orphan a '… and N more' tail line.");
        assertTrue(diag.mismatchSamples().isEmpty());
    }

    @Test
    void parseFailureCounterClimbsButHasNoSample() {
        // Per the field Javadoc, parseFailureCount intentionally
        // doesn't carry a sample — the WARN line at the failure site
        // already names the specific file. The --explain trace
        // surfaces only the run-wide tally.
        KotlinDiagnostics diag = new KotlinDiagnostics();

        diag.recordParseFailure();
        diag.recordParseFailure();
        diag.recordParseFailure();

        assertEquals(3, diag.parseFailureCount());
        assertFalse(diag.isEmpty());
    }

    @Test
    void embeddableLoadFailureFirstCauseWins() {
        // Two threads can race the bootstrap on a multi-module
        // gradle --parallel build. Both call the catch block
        // recording their own throwable. The pinned --explain
        // string carries exactly one cause; preserving the first
        // mirrors what the WARN-line at the parse site already
        // promises (one log line, the one the first failing
        // thread emitted).
        KotlinDiagnostics diag = new KotlinDiagnostics();

        diag.recordEmbeddableLoadFailure("first failure");
        diag.recordEmbeddableLoadFailure("second failure");
        diag.recordEmbeddableLoadFailure("third failure");

        assertEquals("first failure", diag.embeddableLoadFailureCause());
    }

    @Test
    void embeddableLoadFailureMapsNullAndEmptyToUnknownSentinel() {
        // The cause string can legitimately be empty when an
        // exception's getMessage() is empty (some JVM impls of
        // LinkageError carry the class name in toString() but not
        // in getMessage()). The carrier maps both null and ""
        // to "unknown" so the rendered string is never literally
        // 'failed to load: .' (with a trailing dot and no cause
        // between).
        KotlinDiagnostics nullCase = new KotlinDiagnostics();
        nullCase.recordEmbeddableLoadFailure(null);
        assertEquals("unknown", nullCase.embeddableLoadFailureCause());

        KotlinDiagnostics emptyCase = new KotlinDiagnostics();
        emptyCase.recordEmbeddableLoadFailure("");
        assertEquals("unknown", emptyCase.embeddableLoadFailureCause());
    }

    @Test
    void emptySingletonRejectsAllMutations() {
        // Defensive immutability — every record* call on EMPTY is a
        // no-op so a misuse (e.g. the no-arg KotlinLanguageParser
        // ctor at one point injected EMPTY into the parser, which
        // would have polluted the JVM-wide singleton across every
        // unit-test run) cannot leak counters into adopters' actual
        // engine runs that happen to use the back-compat
        // AffectedTestsResult constructors that default to EMPTY.
        KotlinDiagnostics empty = KotlinDiagnostics.EMPTY;

        empty.recordAstMapped("com.attacker.Foo");
        empty.recordParseFailure();
        empty.recordPathPackageMismatch(
                Path.of("/tmp/Foo.kt"), "com.a", "com.b");
        empty.recordEmbeddableLoadFailure("attacker-controlled cause");

        assertTrue(empty.isEmpty(),
                "EMPTY.isEmpty() must remain true after any mutator call — "
                        + "without this guard a no-arg KotlinLanguageParser ctor "
                        + "(or a fixture that shares the singleton) would pollute "
                        + "the JVM-wide state and corrupt every back-compat "
                        + "AffectedTestsResult constructor's default.");
        assertEquals(0, empty.astMappedCount());
        assertEquals(0, empty.parseFailureCount());
        assertEquals(0, empty.pathPackageMismatchCount());
        assertNull(empty.embeddableLoadFailureCause());
        assertTrue(empty.astMappedFqnSamples().isEmpty());
        assertTrue(empty.mismatchSamples().isEmpty());
    }

    @Test
    void concurrentWritersAndReadersDoNotThrow() throws Exception {
        // Pin the concurrency claim from the class Javadoc: 'every
        // counter and every sample collection is thread-safe by
        // construction'. A future refactor that drops the
        // synchronizedSet / synchronizedMap wrappers (or removes the
        // defensive copy from astMappedFqnSamples / mismatchSamples)
        // would surface a ConcurrentModificationException here on
        // most JVMs.
        final int writerWidth = 200;
        final int readerWidth = 50;
        KotlinDiagnostics diag = new KotlinDiagnostics();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Throwable> firstFailure = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < writerWidth; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        diag.recordAstMapped("com.example.Foo" + idx);
                        diag.recordPathPackageMismatch(
                                Path.of("/tmp/F" + idx + ".kt"),
                                "com.a", "com.b");
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                        firstFailure.add(t);
                    }
                });
            }
            for (int i = 0; i < readerWidth; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int r = 0; r < 50; r++) {
                            // Iterate the snapshot views — these
                            // would CME on a refactor that returns
                            // the live collections instead of
                            // defensive copies.
                            for (String fqn : diag.astMappedFqnSamples()) {
                                fqn.length();
                            }
                            for (KotlinDiagnostics.MismatchSample s
                                    : diag.mismatchSamples()) {
                                s.file().toString();
                            }
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                        firstFailure.add(t);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS),
                    "Concurrency stress run must complete inside 30s");
        } finally {
            if (!pool.isShutdown()) {
                pool.shutdownNow();
            }
        }

        assertEquals(0, errors.get(),
                "No exception should escape any concurrent writer or reader. "
                        + "First failure: "
                        + (firstFailure.isEmpty() ? "(none)" : firstFailure.get(0)));
        assertEquals(writerWidth, diag.astMappedCount());
        assertEquals(writerWidth, diag.pathPackageMismatchCount());
        assertEquals(KotlinDiagnostics.SAMPLE_LIMIT, diag.astMappedFqnSamples().size());
        assertEquals(KotlinDiagnostics.SAMPLE_LIMIT, diag.mismatchSamples().size());
    }

    @Test
    void joinFqnHandlesAllPackageAndTypeShapes() {
        // The shared FQN helper is called from both the cold-cache
        // KotlinLanguageParser.extractMetadata and the warm-cache
        // ProjectIndex.seedFileMetadata. Both routes must produce
        // identical FQN strings so the AST-mapped sample is
        // byte-stable across cache hit / miss.
        assertEquals("com.example.Foo",
                KotlinDiagnostics.joinFqn("com.example", "Foo"));
        assertEquals("Foo",
                KotlinDiagnostics.joinFqn("", "Foo"),
                "Default-package Kotlin file must NOT render a leading dot — "
                        + "the trace would otherwise show 'AST-mapped to FQN .Foo' "
                        + "which is invalid as a class FQN.");
        assertEquals("Foo",
                KotlinDiagnostics.joinFqn(null, "Foo"));
        assertEquals("com.example",
                KotlinDiagnostics.joinFqn("com.example", null),
                "Null type name (an empty / package-only Kotlin file) must "
                        + "not produce a trailing dot.");
        assertEquals("com.example",
                KotlinDiagnostics.joinFqn("com.example", ""));
        assertEquals("",
                KotlinDiagnostics.joinFqn(null, null));
        assertEquals("",
                KotlinDiagnostics.joinFqn("", ""));
    }

    @Test
    void mismatchSampleNullFileRejectedAtConstruction() {
        // The record's compact constructor enforces non-null file
        // because the renderer renders the path verbatim and a null
        // would surface as 'Kotlin file null declares package ...'
        // — meaningless for diagnostics.
        assertThrows(NullPointerException.class,
                () -> new KotlinDiagnostics.MismatchSample(null, "a", "b"));
    }

    @Test
    void mismatchSampleNormalisesNullPackageStringsToEmpty() {
        // Either package can be empty (default-package files); null
        // is normalised to "" so the renderer's '+sample.parsedPackage()'
        // never emits the literal string 'null' into the trace.
        KotlinDiagnostics.MismatchSample sample = new KotlinDiagnostics.MismatchSample(
                Path.of("/tmp/Foo.kt"), null, null);
        assertEquals("", sample.parsedPackage());
        assertEquals("", sample.pathDerivedPackage());
    }
}
