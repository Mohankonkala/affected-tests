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
}
