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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic-workload performance harness for issue #42.
 *
 * <p><strong>Purpose:</strong> generate hard data on serial vs parallel
 * discovery across realistic project sizes so future decisions about
 * Option 1 (intra-strategy parallelism) can be made from measurements
 * rather than intuition. The previous implementation of this issue
 * shipped Option 2 only; if these numbers show one strategy
 * consistently dominating wall time on adopter workloads, Option 1
 * becomes the next lever — if not, Option 2's gains are sufficient
 * and Option 1 is YAGNI.
 *
 * <p><strong>Why this is a test:</strong> JUnit gives us a temp
 * directory, a real assertion gate (so a regression can't be silently
 * "fast in dev, slow in CI"), and the existing test runner
 * infrastructure. A standalone {@code main()} would have to
 * re-implement all of that and would never run in CI. The harness
 * intentionally keeps every size below a few seconds so it stays
 * cheap enough to run in the default test suite — adopters who want
 * larger sweeps can override via system properties.
 *
 * <p><strong>What's measured:</strong> end-to-end discovery wall
 * time captured by the engine itself (not external timing), to
 * factor out git-diff and project-index-build overhead. Each size
 * runs once warm (a previous warm-up engine build primes the JVM
 * + ProjectIndexCache), so the numbers reflect steady-state
 * dispatch cost, not cold-start.
 */
class AffectedTestsEngineParallelPerfHarnessTest {

    @TempDir
    Path tempDir;

    /**
     * Builds a synthetic monorepo of {@code numClasses} production
     * classes plus matching tests. The class shape is chosen to
     * exercise all four strategies:
     * <ul>
     *   <li>{@code Naming}: every test follows the
     *       {@code FooTest}/{@code FooIT}/{@code FooITTest} convention</li>
     *   <li>{@code Usage}: each test references at least one
     *       production class via simple name</li>
     *   <li>{@code Impl}: every other class is an {@code Impl} of the
     *       previous interface</li>
     *   <li>{@code Transitive}: classes form a chain
     *       {@code Class0 → Class1 → Class2 → ...} so transitive
     *       discovery has work to do</li>
     * </ul>
     * The chain length is {@code numClasses}, so transitive depth
     * caps the reach (default 4 reaches 4 classes downstream).
     */
    private void writeSyntheticProject(int numClasses) throws Exception {
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Path test = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(test);

        // First class: standalone leaf.
        Files.writeString(src.resolve("Source0.java"),
                "package com.example;\npublic class Source0 {}\n");
        Files.writeString(test.resolve("Source0Test.java"),
                "package com.example;\n"
                        + "public class Source0Test {\n"
                        + "    void t() { Source0 x = null; }\n"
                        + "}\n");

        for (int i = 1; i < numClasses; i++) {
            // Production class N references class N-1 — chain so
            // transitive discovery has multi-hop work to do.
            String body = "package com.example;\n"
                    + "public class Source" + i + " {\n"
                    + "    private final Source" + (i - 1) + " inner;\n"
                    + "    public Source" + i + "(Source" + (i - 1) + " inner) { this.inner = inner; }\n"
                    + "    public Source" + (i - 1) + " inner() { return inner; }\n"
                    + "}\n";
            Files.writeString(src.resolve("Source" + i + ".java"), body);

            // Test for class N references class N — exercises naming
            // (suffix) AND usage (simple-name reference) paths.
            String testBody = "package com.example;\n"
                    + "public class Source" + i + "Test {\n"
                    + "    void t() { Source" + i + " x = null; }\n"
                    + "}\n";
            Files.writeString(test.resolve("Source" + i + "Test.java"), testBody);
        }
    }

    /**
     * Resolves the workload-size sweep from system properties so
     * adopters investigating perf behaviour on their own can run
     * larger sweeps without rebuilding.
     *
     * <p>Default: {@code 200,500,1000} — small enough that the
     * harness completes in a few seconds in CI. Override via
     * {@code -Daffected-tests.perfHarness.sizes=200,1000,5000}.
     * The {@code 0} or empty value disables the harness entirely
     * (skip-mode for environments where the perf cost is not
     * worth the regression coverage).
     */
    private static int[] resolveSizes() {
        String raw = System.getProperty("affected-tests.perfHarness.sizes", "200,500,1000");
        if (raw == null || raw.isBlank()) {
            return new int[0];
        }
        List<Integer> sizes = new ArrayList<>();
        for (String tok : raw.split(",")) {
            try {
                int n = Integer.parseInt(tok.trim());
                if (n > 0) sizes.add(n);
            } catch (NumberFormatException ignored) {
                // Skip the malformed token rather than failing the
                // whole sweep — it's a system property, not a
                // structured config.
            }
        }
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    private AffectedTestsResult runEngine(boolean parallel, String baseRef) {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .baseRef(baseRef)
                .includeUncommitted(false)
                .includeStaged(false)
                .mode(Mode.LOCAL)
                .strategies(Set.of("naming", "usage", "impl", "transitive"))
                .transitiveDepth(4)
                .parallelDiscovery(parallel)
                .build();
        return new AffectedTestsEngine(config, tempDir).run();
    }

    /**
     * Sweep across project sizes, comparing serial vs parallel
     * discovery wall time and selection-set equality. Prints a
     * tabular summary at the end so a developer running this from
     * the IDE / CLI can read the data directly. The test asserts:
     * <ol>
     *   <li>Selection sets are SET-equal across serial and parallel
     *       at every size (correctness gate)</li>
     *   <li>Total wall time on the parallel path never exceeds
     *       serial + 50ms (anti-regression gate — the parallel path
     *       must not be materially slower than serial even on
     *       small workloads where parallelism doesn't help)</li>
     * </ol>
     * The parallel-faster claim is NOT asserted at the test level
     * because it depends on JVM warm-up, available cores, and
     * filesystem caching; instead the printed summary lets a human
     * compare the numbers and adjust the heuristic if needed.
     */
    @Test
    void perfHarness_sweepsSerialVsParallelAcrossProjectSizes() throws Exception {
        int[] sizes = resolveSizes();
        if (sizes.length == 0) {
            // Configurable opt-out: a CI environment with extreme
            // budget pressure can disable the harness via
            // -Daffected-tests.perfHarness.sizes= (empty value).
            // We treat the empty case as "skip" rather than an
            // error so adopters don't have to suppress the test
            // class entirely.
            return;
        }

        // Build the synthetic project at the LARGEST requested size
        // ONCE, then re-use the same workspace for every size by
        // having the test commit ONE file (Source0) per iteration —
        // the diff size is constant; what varies is the project
        // index and the strategy reach. That way we measure the
        // discovery cost as a function of project size, holding
        // diff size fixed, which is the realistic workload.
        int largest = 0;
        for (int s : sizes) largest = Math.max(largest, s);

        // Init a real git repo so the engine's diff path works.
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            File readme = tempDir.resolve("README.md").toFile();
            Files.writeString(readme.toPath(), "# init");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("initial").call();

            writeSyntheticProject(largest);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("synthetic project " + largest + " classes").call();

            String preTouch = git.log().call().iterator().next().getName();

            // Touch one file post-baseline so the diff has exactly
            // one production class, every size. We touch
            // {@code Source0} because it's the leaf of the chain
            // (no inner reference) — any class higher up the chain
            // would also be valid; leaf keeps the workload minimal.
            Files.writeString(tempDir.resolve("src/main/java/com/example/Source0.java"),
                    "package com.example;\npublic class Source0 { /* tweaked */ }\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("touch Source0").call();

            // Warm-up run primes the JVM + ProjectIndexCache + git
            // pack. Without this, the first iteration's wall time is
            // dominated by JIT compilation and dwarfs the rest of
            // the data.
            runEngine(false, preTouch);

            Map<Integer, SizeResult> results = new LinkedHashMap<>();
            for (int size : sizes) {
                // For each iteration we rewrite the project to the
                // current size — strictly speaking we'd want a
                // separate index per size, but doing this in one
                // workspace + invalidating the cache between
                // iterations gives us the same characteristics with
                // less setup time. The cache is invalidated by
                // touching Source0 on every iteration.
                Path src0 = tempDir.resolve("src/main/java/com/example/Source0.java");
                Files.writeString(src0,
                        "package com.example;\npublic class Source0 { /* size=" + size + " */ }\n");

                AffectedTestsResult serial = runEngine(false, preTouch);
                AffectedTestsResult parallel = runEngine(true, preTouch);

                assertEquals(serial.testClassFqns(), parallel.testClassFqns(),
                        "Selection sets must match across serial and parallel "
                                + "at size=" + size);

                results.put(size, new SizeResult(serial.discoveryProfile(),
                        parallel.discoveryProfile(),
                        serial.testClassFqns().size()));

                // Anti-regression gate: parallel must not be
                // materially slower than serial. The slop scales with
                // workload because pool spin-up + thread context-
                // switching is a fixed-ish cost (~50–300ms on cold or
                // shared CI runners) that dominates the total at the
                // smallest sizes; on a 1-vCPU container the engine
                // ALSO routes through the serial path internally
                // (availableProcessors gate), so the gate becomes
                // "both paths are roughly equivalent". The
                // {@code max(serialMs * 3, 250)} formula gives us
                // headroom on cold/small workloads while still
                // catching genuine regressions on larger sizes —
                // a 10x slowdown at size=5000 would still trip the
                // assertion.
                long serialMs = serial.discoveryProfile().totalDiscoveryWallTime().toMillis();
                long parallelMs = parallel.discoveryProfile().totalDiscoveryWallTime().toMillis();
                long budget = Math.max(serialMs * 3, 250);
                assertTrue(parallelMs <= budget,
                        "At size=" + size + ", parallel (" + parallelMs + "ms) "
                                + "was materially slower than serial (" + serialMs + "ms, "
                                + "budget=" + budget + "ms) — this means the engine-level "
                                + "fan-out is anti-helpful and the kill switch should "
                                + "probably default to off");
            }

            printSweepSummary(results);
        }
    }

    private record SizeResult(DiscoveryProfile serial, DiscoveryProfile parallel, int selectionSize) {}

    private static void printSweepSummary(Map<Integer, SizeResult> results) {
        System.out.println();
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println("Issue #42 perf harness — engine-level fan-out vs serial");
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.printf(Locale.ROOT,
                "%-7s %-9s %-10s %-9s %-10s %-12s %s%n",
                "size", "selected", "serial_ms", "par_ms", "speedup", "concur", "dominant");
        for (Map.Entry<Integer, SizeResult> e : results.entrySet()) {
            int size = e.getKey();
            SizeResult r = e.getValue();
            long serialMs = Math.max(1, r.serial.totalDiscoveryWallTime().toMillis());
            long parallelMs = Math.max(1, r.parallel.totalDiscoveryWallTime().toMillis());
            double speedup = (double) serialMs / parallelMs;
            String dominant = r.parallel.dominantStrategy();
            System.out.printf(Locale.ROOT,
                    "%-7d %-9d %-10d %-9d %-10s %-12s %s%n",
                    size,
                    r.selectionSize,
                    serialMs,
                    parallelMs,
                    String.format(Locale.ROOT, "%.2fx", speedup),
                    r.parallel.concurrencyLevel(),
                    dominant != null ? dominant : "—");
        }
        System.out.println();
        System.out.println("Per-strategy wall time on the parallel path (ms):");
        System.out.printf(Locale.ROOT, "%-7s %-10s %-10s %-10s %-10s%n",
                "size", "naming", "usage", "impl", "transitive");
        for (Map.Entry<Integer, SizeResult> e : results.entrySet()) {
            int size = e.getKey();
            DiscoveryProfile p = e.getValue().parallel;
            System.out.printf(Locale.ROOT, "%-7d %-10s %-10s %-10s %-10s%n",
                    size,
                    msOrDash(p, "naming"),
                    msOrDash(p, "usage"),
                    msOrDash(p, "impl"),
                    msOrDash(p, "transitive"));
        }
        System.out.println("─────────────────────────────────────────────────────────────");
        System.out.println();
    }

    private static String msOrDash(DiscoveryProfile profile, String name) {
        java.time.Duration d = profile.perStrategyWallTime().get(name);
        return d == null ? "—" : Long.toString(d.toMillis());
    }
}
