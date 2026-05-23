package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NamingConventionStrategyTest {

    @TempDir
    Path tempDir;

    private NamingConventionStrategy strategy;

    @BeforeEach
    void setUp() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        strategy = new NamingConventionStrategy(config);
    }

    @Test
    void findsTestByNamingConvention() throws IOException {
        // Create test file
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooBarTest.java"),
                "package com.example.service;\npublic class FooBarTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.contains("com.example.service.FooBarTest"));
    }

    @Test
    void findsMultipleTestSuffixes() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooTest.java"),
                "package com.example;\npublic class FooTest {}");
        Files.writeString(testDir.resolve("FooIT.java"),
                "package com.example;\npublic class FooIT {}");
        Files.writeString(testDir.resolve("FooIntegrationTest.java"),
                "package com.example;\npublic class FooIntegrationTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Foo"), tempDir);

        assertEquals(3, result.size());
        assertTrue(result.contains("com.example.FooTest"));
        assertTrue(result.contains("com.example.FooIT"));
        assertTrue(result.contains("com.example.FooIntegrationTest"));
    }

    @Test
    void returnsEmptyWhenNoTestsMatch() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("UnrelatedTest.java"),
                "package com.example;\npublic class UnrelatedTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooBar"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        Set<String> result = strategy.discoverTests(Set.of(), tempDir);
        assertTrue(result.isEmpty());
    }

    @Test
    void findsTestsInSubModules() throws IOException {
        Path moduleTestDir = tempDir.resolve("application/src/test/java/com/example");
        Files.createDirectories(moduleTestDir);
        Files.writeString(moduleTestDir.resolve("BarServiceTest.java"),
                "package com.example;\npublic class BarServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.BarService"), tempDir);

        assertTrue(result.contains("com.example.BarServiceTest"));
    }

    @Test
    void findsTestsInDeeplyNestedModules() throws IOException {
        // Depth 2: services/core/src/test/java/...
        Path deepTestDir = tempDir.resolve("services/core/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("BazServiceTest.java"),
                "package com.example;\npublic class BazServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.BazService"), tempDir);

        assertTrue(result.contains("com.example.BazServiceTest"),
                "Should find test nested 2 levels deep: services/core/src/test/java");
    }

    @Test
    void crossPackageSimpleNameCollisionStillSelectsButRecordsHint() throws IOException {
        // Regression for #40. Two production classes with the same
        // simple name `Foo` live in different packages —
        // `a.b.Foo` (the one in the diff) and `c.d.Foo`. The test
        // for `c.d.Foo` lives at `e.f.FooTest`, deliberately in a
        // third package (mirrors the parallel-test-tree shape we
        // can't strict-match against without breaking adopters).
        //
        // The naming strategy's policy is "select on simple-name
        // match, packages don't gate," so `e.f.FooTest` IS pulled
        // into the selection — that's documented over-selection.
        // What we care about here is the diagnostic: the strategy
        // must record this match in {@link #crossPackageMatches}
        // so the engine can surface it via --explain. Otherwise
        // an operator never sees "you ran 200 unrelated tests
        // because of a simple-name collision" and the cost of
        // the policy stays invisible.
        Path testDir = tempDir.resolve("src/test/java/e/f");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooTest.java"),
                "package e.f;\npublic class FooTest {}");

        Set<String> result = strategy.discoverTests(Set.of("a.b.Foo"), tempDir);

        assertTrue(result.contains("e.f.FooTest"),
                "Cross-package match must still be selected — "
                        + "over-select is the documented trade-off, "
                        + "stricter matching breaks parallel-test-tree adopters.");

        assertEquals(Set.of("e.f.FooTest"),
                strategy.crossPackageMatches().get("a.b.Foo"),
                "Cross-package match must be recorded so --explain "
                        + "can surface the over-selection without "
                        + "flipping the selection policy.");
    }

    @Test
    void samePackageMatchIsNotRecordedAsCrossPackage() throws IOException {
        // Companion to crossPackageSimpleNameCollisionStillSelectsButRecordsHint:
        // the silent-success path stays silent. A test in the same
        // package as the SUT — the most common shape — must NOT
        // trigger the diagnostic, otherwise --explain would fire
        // the cross-package hint on every healthy run and operators
        // would learn to ignore it.
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooBarTest.java"),
                "package com.example.service;\npublic class FooBarTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.contains("com.example.service.FooBarTest"));
        assertTrue(strategy.crossPackageMatches().isEmpty(),
                "Same-package match must not pollute the cross-package "
                        + "diagnostic — otherwise the hint fires on "
                        + "every healthy run and signal value drops.");
    }

    @Test
    void findsTestsAcrossMultipleNestedModules() throws IOException {
        // Root level
        Path rootTestDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(rootTestDir);
        Files.writeString(rootTestDir.resolve("FooServiceTest.java"),
                "package com.example;\npublic class FooServiceTest {}");

        // Depth 1
        Path apiTestDir = tempDir.resolve("api/src/test/java/com/example");
        Files.createDirectories(apiTestDir);
        Files.writeString(apiTestDir.resolve("FooServiceIT.java"),
                "package com.example;\npublic class FooServiceIT {}");

        // Depth 2
        Path deepTestDir = tempDir.resolve("services/foo/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("FooServiceIntegrationTest.java"),
                "package com.example;\npublic class FooServiceIntegrationTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooService"), tempDir);

        assertEquals(3, result.size(), "Should find tests at root, depth 1, and depth 2");
        assertTrue(result.contains("com.example.FooServiceTest"));
        assertTrue(result.contains("com.example.FooServiceIT"));
        assertTrue(result.contains("com.example.FooServiceIntegrationTest"));
    }

    // ── Phase 2 PR #1 of issue #76 — Kotlin tests participate in
    //    NamingConventionStrategy via the widened
    //    SourceFileScanner.scanTestFqns universe. Strategy itself
    //    is unchanged; the parity comes for free from the scanner.

    @Test
    void findsKotlinTestByNamingConvention() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooBarTest.kt"),
                "package com.example.service\nclass FooBarTest");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.contains("com.example.service.FooBarTest"),
                "Kotlin neighbour test must be selected when its class FQN matches "
                        + "the naming convention against a changed production class — "
                        + "the strategy reads from scanTestFqns which PR #1 widened "
                        + "to include .kt. Got: " + result);
    }

    @Test
    void findsKotlinTestForKotlinTopLevelFunctionFile() throws IOException {
        // Top-level functions in Util.kt compile to a class named
        // UtilKt. PathToClassMapper emits both `Util` and `UtilKt`
        // as changed production FQNs in the diff side; the naming
        // strategy then probes `UtilKtTest.kt` against the test-FQN
        // universe and finds it.
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("UtilKtTest.kt"),
                "package com.example\nclass UtilKtTest");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.UtilKt"), tempDir);

        assertTrue(result.contains("com.example.UtilKtTest"),
                "UtilKt → UtilKtTest is the top-level-fn convention this PR "
                        + "ships for Kotlin (issue #76). Got: " + result);
    }

    @Test
    void phantomSyntheticDoesNotSelectAnyTest() throws IOException {
        // The whole "FormatKt.kt phantom over-selection is bounded
        // and harmless" argument from docs/PHASE-2-KOTLIN-AST.md §6
        // rests on the claim that
        // `NamingConventionStrategy.discoverTests({…, FormatKtKt})`
        // selects nothing extra because no test class is ever
        // literally named `FormatKtKt<suffix>`. Pin that claim:
        // simulate the diff-side mapper output (both `FormatKt`
        // and the phantom `FormatKtKt`), give the strategy a
        // realistic test-FQN universe with `FormatKtTest.kt`
        // (legitimate match for `FormatKt` → `FormatKtTest`), and
        // assert the phantom does NOT bring `FormatKtKtTest` into
        // selection. If a future reader writes a test fixture
        // containing a literal `FormatKtKtTest`, the over-selection
        // claim would change from "harmless" to "noisy" — this
        // test is the early warning.
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FormatKtTest.kt"),
                "package com.example\nclass FormatKtTest");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FormatKt", "com.example.FormatKtKt"),
                tempDir);

        assertEquals(Set.of("com.example.FormatKtTest"), result,
                "Phantom synthetic FormatKtKt must probe FormatKtKtTest etc., "
                        + "find no match in the test-FQN universe, and silently "
                        + "drop out — leaving only the legitimate FormatKt → "
                        + "FormatKtTest selection. Got: " + result);
    }

    @Test
    void mixedJavaAndKotlinTestsBothSelectableForSameProductionClass() throws IOException {
        // Java tier of a project that's mid-migration to Kotlin —
        // both the legacy Java test and the new Kotlin test target
        // the same production class. Both must select.
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooTest.java"),
                "package com.example; public class FooTest {}");
        Files.writeString(testDir.resolve("FooIT.kt"),
                "package com.example\nclass FooIT");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Foo"), tempDir);

        assertTrue(result.contains("com.example.FooTest"));
        assertTrue(result.contains("com.example.FooIT"));
        assertEquals(2, result.size());
    }
}
