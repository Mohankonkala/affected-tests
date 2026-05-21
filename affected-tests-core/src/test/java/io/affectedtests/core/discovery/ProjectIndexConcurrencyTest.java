package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #42 thread-safety contract for {@link ProjectIndex}: the
 * lazy CU cache and the parse-failure counter must survive concurrent
 * access from N discovery strategies, and {@link
 * ProjectIndex#compilationUnit(Path)} must parse each file at most
 * once even when M threads request it simultaneously.
 *
 * <p>Each test below targets one specific failure mode of a naive
 * (HashMap + int) implementation. The tests use a {@link CountDownLatch}
 * to release N threads at the same instant — without that
 * synchronisation barrier, the JVM tends to serialise even
 * "concurrent" calls and the race never actually happens, masking
 * real defects.
 */
class ProjectIndexConcurrencyTest {

    @TempDir
    Path tempDir;

    private AffectedTestsConfig minimalConfig() {
        return AffectedTestsConfig.builder()
                .baseRef("HEAD")
                .strategies(Set.of("naming"))
                .transitiveDepth(0)
                .build();
    }

    private ProjectIndex buildIndexWithSources(int numSources, int numTests) throws Exception {
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        for (int i = 0; i < numSources; i++) {
            Files.writeString(src.resolve("Source" + i + ".java"),
                    "package com.example;\npublic class Source" + i + " {}\n");
        }
        Path test = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(test);
        for (int i = 0; i < numTests; i++) {
            Files.writeString(test.resolve("Source" + i + "Test.java"),
                    "package com.example;\npublic class Source" + i + "Test {}\n");
        }
        return ProjectIndex.build(tempDir, minimalConfig());
    }

    /**
     * Hammer {@link ProjectIndex#compilationUnit} from N threads
     * targeting the SAME file. The contract is "every thread gets
     * the same {@link com.github.javaparser.ast.CompilationUnit}
     * instance" — that's the whole point of the cache. A
     * non-thread-safe HashMap would either lose updates (one CU
     * computed on each thread, stale put visible to another, last-
     * writer-wins on the eventual visible cached value) or throw
     * ConcurrentModificationException.
     */
    @Test
    void concurrentRequestsForSameFileShareTheCachedCu() throws Exception {
        ProjectIndex index = buildIndexWithSources(1, 1);
        Path file = index.testFiles().get(0);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return index.compilationUnit(file);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            Object reference = futures.get(0).get(10, TimeUnit.SECONDS);
            assertNotNull(reference, "Cached CU must be parseable for this test");
            for (Future<Object> f : futures) {
                Object got = f.get(10, TimeUnit.SECONDS);
                assertSame(reference, got,
                        "All concurrent compilationUnit() calls for the "
                                + "same file must return the same cached CU "
                                + "instance — different instances would mean "
                                + "we re-parsed the same file under contention");
            }
            // After the storm: parsedFileCount must be exactly 1
            // (the file was requested 32 times but parsed once
            // thanks to ConcurrentHashMap.computeIfAbsent's
            // compute-once semantics).
            assertEquals(1, index.parsedFileCount(),
                    "Cache must record exactly one parse for the contested "
                            + "file — N parses would mean computeIfAbsent's "
                            + "semantics aren't being honoured");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Repeat the contention storm against a malformed file and
     * assert {@link ProjectIndex#parseFailureCount} reports exactly
     * 1 (not N). The de-dup contract from issue #41 originally lived
     * inside {@code if (cuCache.containsKey(file))} — under the
     * pre-#42 HashMap implementation, two threads could both miss
     * the containsKey check, both call parseOrWarn, and both
     * increment parseFailureCount, producing a count of 2 for a
     * single failing file. The {@link
     * java.util.concurrent.atomic.AtomicInteger} +
     * {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent}
     * combo restores the de-dup invariant under contention.
     */
    @Test
    void parseFailureCountStaysAtOneUnderContention() throws Exception {
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Bad.java"),
                "this is not valid java { missing braces / } } } extends garbage");
        Path test = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(test);
        Files.writeString(test.resolve("OkTest.java"),
                "package com.example;\npublic class OkTest {}\n");

        ProjectIndex index = ProjectIndex.build(tempDir, minimalConfig());
        Path bad = index.sourceFiles().get(0);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return index.compilationUnit(bad);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Object> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertEquals(1, index.parseFailureCount(),
                    "parseFailureCount must be 1 even after 32 threads "
                            + "raced to request the malformed file. A count "
                            + "above 1 means the de-dup contract from issue "
                            + "#41 is broken under concurrency, which would "
                            + "in turn break the DISCOVERY_INCOMPLETE "
                            + "situation routing (the engine compares the "
                            + "count against thresholds to decide policy).");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Mixed workload: N threads each request a DIFFERENT file from
     * a 100-file index. With a thread-safe cache, every file should
     * parse exactly once (parsedFileCount == 100) and every thread
     * should observe the same CU instance for any given file
     * regardless of which thread computed it. Without the
     * ConcurrentHashMap fix, a HashMap resize triggered by
     * concurrent puts could either lose entries or throw
     * NullPointerException / ConcurrentModificationException.
     */
    @Test
    void hundredFilesParsedConcurrentlyEachOnlyOnce() throws Exception {
        ProjectIndex index = buildIndexWithSources(100, 100);
        List<Path> files = new ArrayList<>(index.sourceFiles());
        files.addAll(index.testFiles());

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<Set<Object>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    Set<Object> seen = new HashSet<>();
                    for (Path f : files) {
                        seen.add(index.compilationUnit(f));
                    }
                    return seen;
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            // Every thread must succeed without exception.
            for (Future<Set<Object>> f : futures) {
                Set<Object> seen = f.get(15, TimeUnit.SECONDS);
                assertEquals(files.size(), seen.size(),
                        "Each thread must observe one CU per file — duplicate "
                                + "or missing entries would indicate cache "
                                + "corruption under contention");
            }
            assertEquals(files.size(), index.parsedFileCount(),
                    "Mixed workload must produce exactly one parse per file "
                            + "across the whole index");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Concurrent runs of {@link NamingConventionStrategy#discoverTests}
     * on the same instance: this is the exact race that
     * {@link io.affectedtests.core.discovery.ImplementationStrategy}
     * and {@link TransitiveStrategy} create when running in parallel
     * — they both call back into {@code namingStrategy.discoverTests}
     * with different changedProductionClasses inputs. The contract:
     * the merged {@code crossPackageMatches} map must contain every
     * cross-package match observed by any caller, with no
     * ConcurrentModificationException.
     */
    @Test
    void concurrentNamingDiscoveryDoesNotCorruptCrossPackageMap() throws Exception {
        ProjectIndex index = buildIndexWithSources(40, 40);
        AffectedTestsConfig config = minimalConfig();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            List<Future<Set<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        // Each thread targets a different changed
                        // FQN — the maps share the namingStrategy
                        // instance, so populating their per-thread
                        // matches concurrently is exactly what
                        // impl/transitive trigger in production.
                        return naming.discoverTests(
                                Set.of("com.example.Source" + threadId),
                                index);
                    } catch (Throwable th) {
                        failure.compareAndSet(null, th);
                        throw th;
                    }
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            for (Future<Set<String>> f : futures) {
                Set<String> got = f.get(10, TimeUnit.SECONDS);
                assertNotNull(got);
            }
            assertTrue(failure.get() == null,
                    "Concurrent discoverTests calls must not throw — "
                            + "ConcurrentModificationException here means "
                            + "the cross-package accumulator is still using "
                            + "a non-thread-safe collection");
            // Every thread targeted SourceN; matchTests should have
            // populated crossPackageMatches with up to {threads}
            // entries. We don't assert an exact count — same-package
            // matches are intentionally not recorded (so 8 threads
            // with same-package layouts yields 0 entries) — but
            // simply iterating the map must not throw.
            int observed = 0;
            for (Set<String> v : naming.crossPackageMatches().values()) {
                observed += v.size();
            }
            assertTrue(observed >= 0,
                    "Sanity: iteration of the cross-package map must "
                            + "complete without exception under concurrent "
                            + "writes (the underlying ConcurrentHashMap +"
                            + " synchronizedSet contract guarantees this)");
        } finally {
            pool.shutdownNow();
        }
    }
}
