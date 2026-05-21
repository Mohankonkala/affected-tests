package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence behaviour of {@link ProjectIndexCache} (issue #41).
 *
 * <p>Each test follows the same shape: write a tree, build an index
 * to populate the cache, then mutate something (or nothing) and
 * build again. The second build's behaviour — cache hit vs. cache
 * miss — is the assertion. Equivalence between cache-hit results
 * and cache-miss results is what makes the cache safe to ship as
 * always-on; the four invalidation tests pin the safety contract
 * (config drift, file added, file removed, scan-root mtime drift)
 * so a future change cannot silently widen the validity window
 * and serve stale data.
 */
class ProjectIndexCacheTest {

    @TempDir
    Path projectDir;

    private static final AffectedTestsConfig BASE_CONFIG = AffectedTestsConfig.builder()
            .mode(Mode.CI)
            .build();

    @Test
    void freshBuildPersistsSnapshotAndSecondBuildLoadsIt() throws Exception {
        writeJava(projectDir.resolve("src/main/java/com/example/Foo.java"),
                "package com.example; public class Foo {}");
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);

        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot),
                "First build must persist the snapshot file — without it the cache is "
                        + "a no-op and issue #41's < 100ms warm path never materialises");

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);

        // Equivalence is the safety contract: a cache hit must
        // produce the same observable index as a cache miss would
        // have produced. Test FQNs and source FQNs are the two
        // collections every downstream strategy reads.
        assertEquals(first.testFqns(), second.testFqns(),
                "Cache-loaded index must expose the same testFqns as the original build");
        assertEquals(first.sourceFqns(), second.sourceFqns(),
                "Cache-loaded index must expose the same sourceFqns as the original build");
        assertEquals(first.testFiles().size(), second.testFiles().size());
        assertEquals(first.sourceFiles().size(), second.sourceFiles().size());
    }

    @Test
    void configChangeInvalidatesCache() throws Exception {
        // Two test classes; one normal, one under api-test/**. With
        // outOfScopeTestDirs=['api-test/**'] the dispatch map drops
        // ApiFoo. Building first with the OOS rule → 1 test FQN
        // (FooTest). Then rebuild with the OOS rule REMOVED. The
        // cache key (configHash) covers outOfScopeTestDirs, so the
        // second build must take the miss path and surface ApiFoo.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");
        writeJava(projectDir.resolve("api-test/src/test/java/com/example/ApiFoo.java"),
                "package com.example; public class ApiFoo {}");

        AffectedTestsConfig narrow = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();

        ProjectIndex original = ProjectIndex.build(projectDir, narrow);
        assertFalse(original.testFqns().contains("com.example.ApiFoo"),
                "Sanity: out-of-scope FQN must be filtered on first build");

        AffectedTestsConfig wider = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                // outOfScopeTestDirs intentionally cleared
                .build();

        ProjectIndex broadened = ProjectIndex.build(projectDir, wider);
        assertTrue(broadened.testFqns().contains("com.example.ApiFoo"),
                "Removing outOfScopeTestDirs must invalidate the cached snapshot — "
                        + "otherwise an adopter who broadens scope sees the cache "
                        + "serving the narrower view forever and has to nuke build/ by hand");
    }

    @Test
    void newFileInExistingScanRootInvalidatesCache() throws Exception {
        // Adding a file changes the scan root's mtime AND its child
        // count, so the verifyDirs gate must reject the cache. This
        // is the dominant real-world invalidation shape — adopters
        // adding tests, NOT changing config — so it has to work
        // first try.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(Set.of("com.example.FooTest"), first.testFqns());

        // Sleep to outrun the 1s-resolution mtime on some
        // filesystems, otherwise a same-second add can slip through
        // the mtime gate and the test depends on the child-count
        // belt-and-braces alone.
        Thread.sleep(1100);
        writeJava(projectDir.resolve("src/test/java/com/example/BarTest.java"),
                "package com.example; public class BarTest {}");

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertTrue(second.testFqns().contains("com.example.BarTest"),
                "A newly-added test under an existing scan root must be picked up — the "
                        + "cache cannot be the reason a freshly-written test fails to run");
        assertEquals(2, second.testFqns().size());
    }

    @Test
    void deletedFileInExistingScanRootInvalidatesCache() throws Exception {
        Path foo = projectDir.resolve("src/test/java/com/example/FooTest.java");
        Path bar = projectDir.resolve("src/test/java/com/example/BarTest.java");
        writeJava(foo, "package com.example; public class FooTest {}");
        writeJava(bar, "package com.example; public class BarTest {}");

        ProjectIndex first = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(2, first.testFqns().size(), "Sanity: both tests indexed");

        Thread.sleep(1100);
        Files.delete(bar);

        ProjectIndex second = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(Set.of("com.example.FooTest"), second.testFqns(),
                "Removing a test must drop it from the next index build — a stale cache "
                        + "would dispatch a non-existent FQN and the consumer sees a "
                        + "Gradle 'no tests found for given includes' failure");
    }

    @Test
    void cacheClearWipesSnapshot() throws Exception {
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex.build(projectDir, BASE_CONFIG);
        Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot), "Sanity: build wrote the snapshot");

        ProjectIndexCache.clear(projectDir);
        assertFalse(Files.exists(snapshot),
                "clear() is the documented escape hatch — if it left the snapshot in "
                        + "place, support advice 'clear the cache' would be hollow");
    }

    @Test
    void corruptSnapshotFallsBackToFullBuild() throws Exception {
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        // Pre-seed a malformed snapshot. The cache layer must treat
        // any read failure as "miss" rather than propagating an IO
        // exception out of ProjectIndex.build — adopters whose
        // build/ got corrupted by a previous Gradle daemon crash
        // should NOT see affectedTest start failing.
        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "this\tis\tnot\ta\tvalid\tsnapshot\nrandom garbage on the second line\n");

        ProjectIndex index = assertDoesNotThrow(() -> ProjectIndex.build(projectDir, BASE_CONFIG),
                "Corrupt snapshot must not surface as an exception — the cache is a perf "
                        + "tier, not a correctness requirement");
        assertEquals(Set.of("com.example.FooTest"), index.testFqns(),
                "After falling back to a full build, the index content must be exactly "
                        + "what a fresh-tree build would have produced");

        // Also: the corrupt snapshot must have been REPLACED by a
        // valid one, otherwise every subsequent run pays the
        // fallback cost forever.
        Path snapshot = cacheDir.resolve("snapshot.tsv");
        assertTrue(Files.isRegularFile(snapshot));
        String contents = Files.readString(snapshot);
        assertTrue(contents.contains("# affected-tests project-index snapshot"),
                "Fallback build must overwrite the corrupt snapshot so the next run "
                        + "takes the fast path again — otherwise the cache fails open "
                        + "permanently after a single corruption event");
    }

    @Test
    void schemaVersionMismatchInvalidatesCache() throws Exception {
        // Pre-seed a snapshot that claims schema v0 (i.e. older
        // than the current). The cache must reject it and rebuild
        // — without this, a forward-incompatible bump (whenever we
        // ship one) would silently serve old-schema data through
        // new-schema readers.
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        Path cacheDir = projectDir.resolve("build/affected-tests/index/v1");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("snapshot.tsv"),
                "v\t0\ncfg\tdoesnotmatter\n");

        ProjectIndex index = ProjectIndex.build(projectDir, BASE_CONFIG);
        assertEquals(Set.of("com.example.FooTest"), index.testFqns());
    }

    @Test
    void disableViaSystemPropertyBypassesCache() throws Exception {
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        String prevValue = System.getProperty("affected-tests.indexCache.enabled");
        System.setProperty("affected-tests.indexCache.enabled", "false");
        try {
            ProjectIndex.build(projectDir, BASE_CONFIG);
            Path snapshot = projectDir.resolve("build/affected-tests/index/v1/snapshot.tsv");
            assertFalse(Files.exists(snapshot),
                    "System property must short-circuit BOTH read and write — otherwise "
                            + "an adopter disables the cache to debug a problem and a "
                            + "stale snapshot still influences the next enabled run");
        } finally {
            // Restore for sibling tests in the same class — the
            // property is a JVM-global, and surviving "false" would
            // silently turn every subsequent test in this run into
            // a cache-miss test.
            if (prevValue == null) {
                System.clearProperty("affected-tests.indexCache.enabled");
            } else {
                System.setProperty("affected-tests.indexCache.enabled", prevValue);
            }
        }
    }

    private static void writeJava(Path target, String body) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
        // Pin a stable mtime so multiple writes within the same
        // second don't rely on filesystem-resolution luck. Tests
        // that genuinely care about mtime drift call
        // setLastModifiedTime themselves with a different value.
        Files.setLastModifiedTime(target, FileTime.fromMillis(1_000_000_000_000L));
    }
}
