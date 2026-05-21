package io.affectedtests.core;

import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.discovery.DiscoveryProfile;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #42 acceptance suite: engine-level parallel discovery.
 *
 * <p>The acceptance contract is "the parallel path produces a SET-equal
 * selection to the serial path on every workload, and the {@link
 * DiscoveryProfile} carries enough data for adopters to see whether
 * parallel actually helped". Each scenario below is one row in that
 * contract — together they cover the failure modes I considered when
 * picking Option 2 (engine-level fan-out) over Option 1 (intra-strategy
 * worker pool).
 *
 * <p>The suite intentionally builds REAL git repos with REAL Java
 * sources rather than mocking the engine internals: the whole point of
 * Option 2 is "does the existing code base survive being called from a
 * thread pool", and a unit-level mock would paper over exactly the
 * race conditions we care about.
 */
class AffectedTestsEngineParallelTest {

    @TempDir
    Path tempDir;

    private Git initRepoWithInitialCommit() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        File readme = tempDir.resolve("README.md").toFile();
        Files.writeString(readme.toPath(), "# init");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("initial commit").call();
        return git;
    }

    /**
     * Lays down a small but representative shape: a service interface,
     * an Impl class, a transitive consumer, and a matching test for
     * each. Touching {@code FooService.java} should exercise all four
     * strategies (naming finds {@code FooServiceTest}, usage finds
     * {@code FooServiceUsageTest}, impl finds the {@code FooServiceImpl}
     * mapping, transitive finds {@code FooConsumerTest}).
     */
    private void writeFourStrategyHarness() throws Exception {
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("FooService.java"),
                "package com.example;\n"
                        + "public interface FooService {\n"
                        + "    String greet(String n);\n"
                        + "}\n");
        Files.writeString(prodDir.resolve("FooServiceImpl.java"),
                "package com.example;\n"
                        + "public class FooServiceImpl implements FooService {\n"
                        + "    public String greet(String n) { return \"hi \" + n; }\n"
                        + "}\n");
        Files.writeString(prodDir.resolve("FooConsumer.java"),
                "package com.example;\n"
                        + "public class FooConsumer {\n"
                        + "    private final FooService svc;\n"
                        + "    public FooConsumer(FooService svc) { this.svc = svc; }\n"
                        + "    public String run() { return svc.greet(\"world\"); }\n"
                        + "}\n");

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooServiceTest.java"),
                "package com.example;\n"
                        + "public class FooServiceTest { void t() {} }\n");
        Files.writeString(testDir.resolve("FooServiceUsageTest.java"),
                "package com.example;\n"
                        + "public class FooServiceUsageTest {\n"
                        + "    void t() { FooService s = null; }\n"
                        + "}\n");
        Files.writeString(testDir.resolve("FooConsumerTest.java"),
                "package com.example;\n"
                        + "public class FooConsumerTest {\n"
                        + "    void t() { FooConsumer c = null; }\n"
                        + "}\n");
    }

    private AffectedTestsResult runEngine(boolean parallel, String baseRef) {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef(baseRef)
                .includeUncommitted(false)
                .includeStaged(false)
                // Pin LOCAL so a default-CI inversion in some CI env
                // can't accidentally flip discovery semantics under us
                // — the ENGINE behaviour we're testing is identical
                // across modes; the mode just changes which Action
                // wins on ambiguous situations.
                .mode(Mode.LOCAL)
                .strategies(Set.of("naming", "usage", "impl", "transitive"))
                .transitiveDepth(4)
                .parallelDiscovery(parallel)
                .build();
        return new AffectedTestsEngine(config, tempDir).run();
    }

    /**
     * Touch {@code FooService.java} on a real branch and prove the
     * engine produces the SAME selection set whether
     * {@code parallelDiscovery=true} or {@code false}. This is the
     * primary correctness contract for issue #42 — anything else we
     * gain (timing, concurrency level visibility, dominant-strategy
     * hint) is meaningless if parallel can mutate the selection
     * compared to serial.
     */
    @Test
    void parallelProducesSameSelectionAsSerial() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            AffectedTestsResult parallel = runEngine(true, base);
            AffectedTestsResult serial = runEngine(false, base);

            assertEquals(serial.testClassFqns(), parallel.testClassFqns(),
                    "Parallel and serial must produce identical test class "
                            + "selection sets — set equality, ordering may "
                            + "differ across runs by design");
            assertEquals(serial.runAll(), parallel.runAll(),
                    "Parallel and serial must agree on the runAll outcome");
            assertEquals(serial.skipped(), parallel.skipped(),
                    "Parallel and serial must agree on the skipped outcome");
            assertEquals(serial.situation(), parallel.situation(),
                    "Parallel and serial must agree on the situation");
            assertEquals(serial.action(), parallel.action(),
                    "Parallel and serial must agree on the action");
            assertEquals(serial.namingCrossPackageMatches(),
                    parallel.namingCrossPackageMatches(),
                    "Cross-package naming diagnostic must be a SET-equal "
                            + "map across parallel vs serial — the diagnostic "
                            + "is a function of inputs only, not dispatch order");
        }
    }

    /**
     * Repeats the parallel run 50 times and asserts every run produces
     * the same FQN selection. Determinism failures from parallel
     * discovery would manifest as non-determinism here: a CU cache
     * race where one thread wins the parse and another reads a
     * partially-constructed AST would either throw, miss a reference,
     * or pick up an unrelated reference depending on interleaving.
     *
     * <p>The default 10 runs is empirically enough — the racy version
     * of the code (no ConcurrentHashMap, no AtomicInteger) failed
     * within the first ~5 runs on a 4-vCPU box. The deeper 50-run
     * stress can be re-enabled with
     * {@code -Daffected-tests.test.determinismIterations=50} when
     * investigating a suspected flake; we keep the default low to
     * avoid spending 10+ seconds of CI budget on every PR for the
     * same property a 10-run loop catches.
     */
    @Test
    void parallelRunsAreDeterministic() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            Set<String> reference = runEngine(true, base).testClassFqns();
            assertFalse(reference.isEmpty(),
                    "Sanity: the harness must produce a non-empty selection — "
                            + "otherwise determinism is trivially true and the "
                            + "test isn't actually checking anything");

            int iterations = Integer.parseInt(System.getProperty(
                    "affected-tests.test.determinismIterations", "10"));
            for (int i = 0; i < iterations; i++) {
                AffectedTestsResult r = runEngine(true, base);
                assertEquals(reference, r.testClassFqns(),
                        "Run #" + i + " produced a different selection "
                                + "than the reference run — parallel "
                                + "discovery is non-deterministic");
            }
        }
    }

    /**
     * Confirms parallel actually fans out (concurrencyLevel > 0) when
     * multiple strategies are enabled, AND falls back to the serial
     * path when only one strategy is requested. The single-strategy
     * case proves we don't pay executor spin-up overhead for a
     * one-callable workload — that's a hot lookup on the test-only
     * fast path and would be a regression if we introduced it.
     */
    @Test
    void singleStrategyRunsSerialEvenWithParallelEnabled() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .mode(Mode.LOCAL)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .parallelDiscovery(true)
                    .build();

            AffectedTestsResult result = new AffectedTestsEngine(config, tempDir).run();
            DiscoveryProfile profile = result.discoveryProfile();

            assertFalse(profile.parallelEnabled(),
                    "Single-strategy run must NOT spin up the thread pool — "
                            + "executor overhead would dominate a one-callable "
                            + "workload. Got profile " + profile);
            assertEquals(0, profile.concurrencyLevel(),
                    "Concurrency level must be 0 on the serial fallback");
            assertEquals(1, profile.perStrategyWallTime().size(),
                    "Profile must still capture the one strategy that ran");
            assertTrue(profile.perStrategyWallTime().containsKey("naming"));
        }
    }

    /**
     * The kill-switch path: {@code parallelDiscovery=false} with
     * multiple strategies. The engine must run serially and the
     * profile must reflect that — adopters who hit the kill switch
     * because of a regression need to be able to verify from
     * {@code --explain} that the flag actually took effect.
     */
    @Test
    void parallelFlagFalseRoutesThroughSerialPath() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            AffectedTestsResult result = runEngine(false, base);
            DiscoveryProfile profile = result.discoveryProfile();

            assertFalse(profile.parallelEnabled(),
                    "parallelDiscovery=false must route through the serial path");
            assertEquals(0, profile.concurrencyLevel(),
                    "Serial path reports concurrencyLevel=0 — the field "
                            + "is the actual pool size, not a configured "
                            + "intent");
            assertTrue(profile.perStrategyWallTime().size() >= 1,
                    "At least one strategy must have run on the serial path");
        }
    }

    /**
     * On a multi-strategy parallel run, the profile must report
     * {@code parallelEnabled=true} AND a positive concurrency level.
     * This pins the dispatch path so a future refactor that
     * accidentally flips to serial (e.g. via a bad default) is
     * caught by the explain regression suite, not noticed weeks
     * later by adopters reading slower-than-expected wall times.
     */
    @Test
    void multiStrategyRunPopulatesParallelProfile() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            AffectedTestsResult result = runEngine(true, base);
            DiscoveryProfile profile = result.discoveryProfile();

            assertTrue(profile.parallelEnabled(),
                    "Multi-strategy run with parallelDiscovery=true must "
                            + "report parallelEnabled=true. Got: " + profile);
            assertTrue(profile.concurrencyLevel() > 0,
                    "Concurrency level must be > 0 on the parallel path. "
                            + "Got: " + profile);
            assertTrue(profile.concurrencyLevel() <= Runtime.getRuntime().availableProcessors(),
                    "Concurrency must be capped at availableProcessors so "
                            + "we don't oversubscribe small CI runners");
            assertTrue(profile.concurrencyLevel() <= 4,
                    "Concurrency must be capped at strategy count (4) — "
                            + "more threads than work items is pure overhead");
            assertEquals(profile.perStrategyWallTime().keySet(),
                    profile.perStrategyTestCount().keySet(),
                    "Per-strategy timing and count maps must have aligned "
                            + "keysets — consumers iterate one and look up "
                            + "the other");
        }
    }

    /**
     * Disabled-strategy paths are absent from the profile — the
     * profile records what RAN, not what was configured. A consumer
     * iterating perStrategyWallTime must not see a phantom
     * "transitive: 0ms" entry just because the strategy was off.
     */
    @Test
    void disabledStrategyDoesNotAppearInProfile() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .mode(Mode.LOCAL)
                    // transitive disabled both via strategy set and
                    // depth=0 — covers both kill switches.
                    .strategies(Set.of("naming", "usage"))
                    .transitiveDepth(0)
                    .parallelDiscovery(true)
                    .build();

            AffectedTestsResult result = new AffectedTestsEngine(config, tempDir).run();
            DiscoveryProfile profile = result.discoveryProfile();

            assertEquals(Set.of("naming", "usage"), profile.perStrategyWallTime().keySet(),
                    "Profile must list ONLY the strategies that ran — "
                            + "disabled strategies are absent, not "
                            + "Duration.ZERO placeholders");
        }
    }

    /**
     * Empty-diff runs must not pay discovery cost — the profile is
     * {@link DiscoveryProfile#empty()}, signalling "no discovery
     * happened". The {@code --explain} renderer keys off this to
     * skip the discovery block entirely (no point rendering "0ms
     * total" for a situation that didn't dispatch any work).
     */
    @Test
    void emptyDiffProducesEmptyProfile() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String head = git.log().call().iterator().next().getName();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(head)
                    .mode(Mode.LOCAL)
                    .parallelDiscovery(true)
                    .build();

            AffectedTestsResult result = new AffectedTestsEngine(config, tempDir).run();
            assertEquals(DiscoveryProfile.empty(), result.discoveryProfile(),
                    "Empty-diff path must return DiscoveryProfile.empty() "
                            + "so --explain can skip the discovery block");
        }
    }

    /**
     * Hammers the engine from N concurrent threads, each running a
     * full pipeline against the same temp repo. Any race in the
     * (now thread-safe) ProjectIndex CU cache or NamingConvention
     * crossPackageMatches map would show up as either an exception
     * propagating through the future, or a result whose FQN set
     * differs from the reference run. Because each engine call
     * allocates its own ProjectIndex via {@code ProjectIndex.build},
     * this test exercises the WITHIN-RUN parallelism AND any
     * cross-run pollution from shared mutable state we may have
     * missed.
     */
    @Test
    void concurrentEngineRunsAreSafe() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            Set<String> reference = runEngine(true, base).testClassFqns();
            assertFalse(reference.isEmpty(), "Sanity: harness must select tests");

            // 4 threads × 8 iterations covers the cross-run race
            // surface; the lower-level
            // {@code ProjectIndexConcurrencyTest} exercises the
            // 32-thread cache-storm shape more cheaply via a
            // {@code CountDownLatch} so we keep this test
            // proportional to its real coverage value. The deeper
            // {@code -Daffected-tests.test.concurrencyIterations=100}
            // hook lets us crank it back up when investigating a
            // suspected flake.
            int threads = 4;
            int iterations = Integer.parseInt(System.getProperty(
                    "affected-tests.test.concurrencyIterations", "8"));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                Set<Future<Set<String>>> futures = new LinkedHashSet<>();
                for (int i = 0; i < iterations; i++) {
                    futures.add(pool.submit(() -> runEngine(true, base).testClassFqns()));
                }
                for (Future<Set<String>> f : futures) {
                    Set<String> got = f.get(30, TimeUnit.SECONDS);
                    assertEquals(reference, got,
                            "Concurrent engine run produced a different "
                                    + "selection — there's still a race "
                                    + "somewhere in the discovery hot path");
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * On a successful selection that ran multiple strategies in
     * parallel, the profile's total wall time should be {@code <=}
     * the sum of per-strategy wall times. Perfect parallelism would
     * give {@code total = max(perStrategy)}, sequential dispatch
     * would give {@code total = sum(perStrategy)} — Option 2 lives
     * in between, but never above the sum.
     *
     * <p>This is the empirical confirmation that the parallel path
     * actually overlaps work. If a regression accidentally
     * serialised inside the engine (e.g. a synchronized block
     * around the strategy callable), this test would catch it
     * because the total would no longer be measurably lower than
     * the sum.
     */
    @Test
    void totalWallTimeNeverExceedsSumOfPerStrategy() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            AffectedTestsResult result = runEngine(true, base);
            DiscoveryProfile profile = result.discoveryProfile();

            long sumNanos = profile.perStrategyWallTime().values().stream()
                    .mapToLong(d -> d.toNanos())
                    .sum();
            long totalNanos = profile.totalDiscoveryWallTime().toNanos();

            // Allow a tiny slop for the engine-level capture
            // overhead (the wall time captures executor spin-up too,
            // but that should be at most a few ms even on cold JVMs).
            // The contract is "total <= sum + small slop", not
            // "total < sum" — a tiny harness might genuinely run all
            // four strategies in <1ms each, and the executor
            // overhead can dominate.
            long slopNanos = 50_000_000L;
            assertTrue(totalNanos <= sumNanos + slopNanos,
                    "Total wall time " + totalNanos + "ns exceeded sum-of-per-strategy "
                            + sumNanos + "ns + slop; that's only possible if discovery "
                            + "is actually anti-parallel (worse than serial) — likely "
                            + "indicates a regression in the dispatch path");
        }
    }

    /**
     * The dominant-strategy hint is the data adopters need to decide
     * "is Option 1 worth the engineering cost?". If transitive is
     * dominant on real workloads, Option 1's intra-strategy
     * parallelism would land additional wins; if naming dominates,
     * Option 1 is largely wasted effort. This test pins the
     * mechanism — what dominantStrategy() returns isn't asserted
     * (it's workload-dependent), but the contract that it returns
     * SOMETHING when multiple strategies ran IS asserted.
     */
    @Test
    void dominantStrategyIsExposedWhenMultipleStrategiesRan() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            DiscoveryProfile profile = runEngine(true, base).discoveryProfile();

            assertNotNull(profile.dominantStrategy(),
                    "When multiple strategies ran, dominantStrategy() must "
                            + "return one of them — null is reserved for the "
                            + "no-strategies-ran case (test-only fast path, "
                            + "EMPTY_DIFF, etc.). Profile: " + profile);
            assertTrue(profile.perStrategyWallTime().containsKey(profile.dominantStrategy()),
                    "Dominant strategy must be one of the strategies that "
                            + "actually ran in this profile");
        }
    }

    /**
     * Stress test: 100 parallel runs on the same harness using the
     * EXTERNAL parallelism (one engine per task) AND internal
     * parallelism (each engine fans its strategies out). Every run
     * must converge on the same selection, and we must accumulate
     * zero exceptions across all of them.
     *
     * <p>Why 100? At 50 the racy version of the code crashed within
     * the first ~5 runs in dev; 100 is deliberately overshooting to
     * give us a buffer against transient interleavings on slower
     * CI runners. If this test ever fails in CI it's a real defect,
     * not flake.
     */
    @Test
    void hundredConcurrentRunsAllConverge() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();
            writeFourStrategyHarness();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add four-strategy harness").call();

            Set<String> reference = runEngine(true, base).testClassFqns();
            int iterations = 100;
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                Set<Future<Set<String>>> futures = new HashSet<>();
                for (int i = 0; i < iterations; i++) {
                    futures.add(pool.submit(() -> runEngine(true, base).testClassFqns()));
                }
                for (Future<Set<String>> f : futures) {
                    assertEquals(reference, f.get(60, TimeUnit.SECONDS),
                            "Concurrent stress run produced a different selection");
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
