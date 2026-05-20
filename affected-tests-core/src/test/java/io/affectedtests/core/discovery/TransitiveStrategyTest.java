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

class TransitiveStrategyTest {

    @TempDir
    Path tempDir;

    private TransitiveStrategy strategy;

    @BeforeEach
    void setUp() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .transitiveDepth(2)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);
        UsageStrategy usage = new UsageStrategy(config);
        strategy = new TransitiveStrategy(config, naming, usage);
    }

    @Test
    void discoversTestViaTransitiveDependency() throws IOException {
        // BarService depends on FooService (via field)
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("FooService.java"),
                "package com.example;\npublic class FooService {}");
        Files.writeString(prodDir.resolve("BarService.java"), """
                package com.example;
                
                public class BarService {
                    private FooService fooService;
                }
                """);

        // Test for BarService (transitive dependency of FooService)
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BarServiceTest.java"),
                "package com.example;\npublic class BarServiceTest {}");

        // Changed: FooService
        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooService"), tempDir);

        assertTrue(result.contains("com.example.BarServiceTest"),
                "Should discover test for BarService (depends on changed FooService)");
    }

    @Test
    void skipsWhenDepthIsZero() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .transitiveDepth(0)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);
        UsageStrategy usage = new UsageStrategy(config);
        TransitiveStrategy zeroDepth = new TransitiveStrategy(config, naming, usage);

        Set<String> result = zeroDepth.discoverTests(
                Set.of("com.example.Foo"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoDependents() throws IOException {
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("Isolated.java"),
                "package com.example;\npublic class Isolated {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Isolated"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void respectsMaxDepthLimit() throws IOException {
        // A -> B -> C (depth 2 chain)
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("A.java"),
                "package com.example;\npublic class A {}");
        Files.writeString(prodDir.resolve("B.java"), """
                package com.example;
                
                public class B {
                    private A a;
                }
                """);
        Files.writeString(prodDir.resolve("C.java"), """
                package com.example;
                
                public class C {
                    private B b;
                }
                """);

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("CTest.java"),
                "package com.example;\npublic class CTest {}");

        // Depth-1 strategy should NOT find CTest (C is 2 hops from A)
        AffectedTestsConfig depth1Config = AffectedTestsConfig.builder()
                .transitiveDepth(1)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(depth1Config);
        UsageStrategy usage = new UsageStrategy(depth1Config);
        TransitiveStrategy depth1 = new TransitiveStrategy(depth1Config, naming, usage);

        Set<String> depth1Result = depth1.discoverTests(Set.of("com.example.A"), tempDir);
        assertFalse(depth1Result.contains("com.example.CTest"),
                "Depth 1 should not reach CTest (2 hops away)");

        // Depth-2 strategy SHOULD find CTest
        Set<String> depth2Result = strategy.discoverTests(Set.of("com.example.A"), tempDir);
        assertTrue(depth2Result.contains("com.example.CTest"),
                "Depth 2 should reach CTest");
    }

    @Test
    void preservesGenericArgumentsInReverseDependencyEdges() throws IOException {
        // Regression: the old normalize-then-match pipeline stripped
        // `List<FooService>` down to `List`, looked up `List` against
        // the known FQN set (stdlib import, not a project class), and
        // threw the edge away. Any consumer that wrapped the changed
        // type in a container, Optional, Flux, Map<K,V>, or any other
        // generic lost its reverse-dependency edge — and with it, all
        // of its tests. Collection-of-service is the single most
        // common shape in Spring service layers, so this silently
        // dropped a very large fraction of downstream coverage.
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("FooService.java"),
                "package com.example;\npublic class FooService {}");
        Files.writeString(prodDir.resolve("BarService.java"), """
                package com.example;

                import java.util.List;

                public class BarService {
                    private List<FooService> fooServices;
                }
                """);

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BarServiceTest.java"),
                "package com.example;\npublic class BarServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooService"), tempDir);

        assertTrue(result.contains("com.example.BarServiceTest"),
                "Must treat `List<FooService>` as a reverse edge to FooService — "
                        + "stripping generics silently drops every `List<Foo>` "
                        + "consumer's tests");
    }

    @Test
    void discoversEdgesFromMethodBodyReferences() throws IOException {
        // Regression: the old scan looked at field types and method
        // signatures only. A helper class referenced solely inside a
        // method body — `new PricingCalculator()`, `(PricingCalculator)
        // svc`, `svc instanceof PricingCalculator` — had no reverse
        // edge, so its tests were silently dropped whenever the
        // helper changed. This fixture models the simplest such
        // shape: OrderService instantiates a changed PricingCalculator
        // inside a method, and OrderServiceTest is the only test that
        // exercises that path. Pre-fix, OrderServiceTest was invisible
        // to the transitive walk.
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        Files.writeString(prodDir.resolve("PricingCalculator.java"),
                "package com.example;\npublic class PricingCalculator {}");
        Files.writeString(prodDir.resolve("OrderService.java"), """
                package com.example;

                public class OrderService {
                    public void charge() {
                        PricingCalculator calc = new PricingCalculator();
                    }
                }
                """);

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("OrderServiceTest.java"),
                "package com.example;\npublic class OrderServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.PricingCalculator"), tempDir);

        assertTrue(result.contains("com.example.OrderServiceTest"),
                "Must discover the consumer's test when the changed class is "
                        + "only referenced inside a method body — otherwise the "
                        + "most common helper-refactor MR silently drops coverage");
    }

    @Test
    void discoversTestsAtEveryDepthOfChainAfterUnionRefactor() throws IOException {
        // Regression for #44: post-refactor, naming + usage run once on the
        // union of every BFS depth's consumers rather than once per depth.
        // Behaviour-preservation must hold across the entire chain, not just
        // the leaf — a multi-depth chain where each depth has a distinct
        // consumer test must still surface every test. Pre-#44 each depth
        // ran naming/usage on its own slice and the results were unioned by
        // `discoveredTests.addAll`, so the post-refactor union semantics
        // need to produce the same outcome.
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        // Chain: A <- B <- C (B depends on A; C depends on B)
        Files.writeString(prodDir.resolve("A.java"),
                "package com.example;\npublic class A {}");
        Files.writeString(prodDir.resolve("B.java"), """
                package com.example;

                public class B {
                    private A a;
                }
                """);
        Files.writeString(prodDir.resolve("C.java"), """
                package com.example;

                public class C {
                    private B b;
                }
                """);

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        // BTest is at depth 1 from A (B is the depth-1 consumer of A).
        // CTest is at depth 2 from A (C is the depth-2 consumer of A).
        Files.writeString(testDir.resolve("BTest.java"),
                "package com.example;\npublic class BTest {}");
        Files.writeString(testDir.resolve("CTest.java"),
                "package com.example;\npublic class CTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.A"), tempDir);

        assertTrue(result.contains("com.example.BTest"),
                "Depth-1 consumer's test must still surface — running naming "
                        + "/ usage on the union must include depth 1's slice");
        assertTrue(result.contains("com.example.CTest"),
                "Depth-2 consumer's test must still surface — running naming "
                        + "/ usage on the union must include depth 2's slice");
    }

    @Test
    void lazyWalkParsesOnlyFilesReachableFromTheFrontier() throws IOException {
        // Regression for #43: the pre-#43 implementation parsed every
        // source file in the project to build the reverse dependency
        // map upfront, even when the diff only touched a leaf class
        // with a handful of consumers. On a 10k-class monorepo with a
        // 1-line diff that's 10k AST parses for no useful information.
        //
        // The lazy frontier-first walk parses only files that the
        // text-based simple-name index flags as plausibly referencing
        // the current frontier. This test synthesises a 50-class
        // harness where only 3 files (1 direct consumer + 2
        // transitive consumers) actually reach the changed FQN, then
        // confirms the parse count stays bounded by reachability
        // rather than corpus size.
        //
        // The bound chosen here (< half the corpus) is deliberately
        // loose so the test pins the "lazy" contract without
        // committing to a specific tokenizer regex; making the bound
        // tight enough to fail on minor regex tweaks would invert the
        // signal-to-noise of this regression. The acceptance criterion
        // in the issue suggests "<50 ASTs out of 1000" — same
        // proportion (5%) as 3-of-50 here. Keeping the harness small
        // keeps the test fast.
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);

        // Changed target: Foo. 1 direct consumer (UsesFoo) + 1
        // transitive consumer (UsesUsesFoo). Both reach the changed
        // FQN. Everything else is unrelated noise.
        Files.writeString(prodDir.resolve("Foo.java"),
                "package com.example;\npublic class Foo {}");
        Files.writeString(prodDir.resolve("UsesFoo.java"), """
                package com.example;

                public class UsesFoo {
                    private Foo foo;
                }
                """);
        Files.writeString(prodDir.resolve("UsesUsesFoo.java"), """
                package com.example;

                public class UsesUsesFoo {
                    private UsesFoo usesFoo;
                }
                """);

        // 47 unrelated files that do NOT reference Foo or UsesFoo. The
        // pre-#43 implementation would have parsed every one; the
        // post-#43 implementation must not.
        for (int i = 0; i < 47; i++) {
            Files.writeString(prodDir.resolve("Unrelated" + i + ".java"),
                    "package com.example;\npublic class Unrelated" + i
                            + " { private String id; }");
        }

        // Use the ProjectIndex code path so we can count parses.
        // Direct projectDir-based discovery works too but goes through
        // a one-shot parser without the cache count surface.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .transitiveDepth(2)
                .build();
        NamingConventionStrategy naming = new NamingConventionStrategy(config);
        UsageStrategy usage = new UsageStrategy(config);
        TransitiveStrategy lazy = new TransitiveStrategy(config, naming, usage);

        ProjectIndex index = ProjectIndex.build(tempDir, config);
        assertEquals(50, index.sourceFiles().size(),
                "Sanity: harness must contain exactly 50 source files");
        assertEquals(0, index.parsedFileCount(),
                "Sanity: index must not parse anything during construction");

        Set<String> result = lazy.discoverTests(Set.of("com.example.Foo"), index);

        assertTrue(result.isEmpty(),
                "No tests in the harness — the assertion is on parse count, "
                        + "not on the discovered set");
        // Hard ceiling: 50% of corpus. With 3 reachable files plus a
        // small amount of false-positive over-match from the text
        // index (e.g. files mentioning "String" — not relevant here
        // because we're querying "Foo", but the cap absorbs that
        // class of noise) the realistic count should be well below
        // 25. Setting the bar at 25 keeps the test stable across
        // regex refinements while still failing loudly if we ever
        // regress to "parse everything".
        int parsed = index.parsedFileCount();
        assertTrue(parsed < 25,
                "Lazy walk must parse fewer than half the corpus; "
                        + "actually parsed " + parsed + "/50. "
                        + "If this regressed, TransitiveStrategy is parsing every "
                        + "source file again — the very thing #43 fixed.");
    }

    @Test
    void discoversConsumerTestsForDeletedProductionClass() throws IOException {
        // The fixture mirrors a `git rm FooService.java` MR: FooService is in
        // the changed set (surfaced by GitChangeDetector via the old path)
        // but the file itself is no longer on disk. BarService still
        // references FooService; BarServiceTest exercises BarService and is
        // therefore the test most likely to fail on the broken consumer.
        // Pre-fix, the reverse-dependency map filtered out FooService because
        // the on-disk scan could not derive an FQN for the deleted file, and
        // the transitive walk returned empty — silently dropping the test on
        // the MR. The fix unions `changedProductionClasses` into
        // `allKnownFqns` inside buildReverseDependencyMap so edges to
        // deleted FQNs survive.
        Path prodDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(prodDir);
        // FooService deliberately NOT written — simulating the deletion.
        Files.writeString(prodDir.resolve("BarService.java"), """
                package com.example;

                public class BarService {
                    private FooService fooService;
                }
                """);

        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BarServiceTest.java"),
                "package com.example;\npublic class BarServiceTest {}");

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.FooService"), tempDir);

        assertTrue(result.contains("com.example.BarServiceTest"),
                "Must discover tests for consumers of a deleted production class — "
                        + "otherwise a pure `git rm` MR silently drops coverage on "
                        + "the very tests that would surface the breakage");
    }
}
