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

class UsageStrategyTest {

    @TempDir
    Path tempDir;

    private UsageStrategy strategy;

    @BeforeEach
    void setUp() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        strategy = new UsageStrategy(config);
    }

    @Test
    void findsTestThatImportsChangedClass() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BazValidatorTest.java"), """
                package com.example;

                import com.example.service.FooModel;

                public class BazValidatorTest {
                    public void testValidate(FooModel details) {}
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooModel"), tempDir);

        assertTrue(result.contains("com.example.BazValidatorTest"),
                "Should match test that imports the changed class");
    }

    @Test
    void findsTestThatUsesChangedClassAsField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BarControllerIT.java"), """
                package com.example;

                import com.example.service.FooService;

                public class BarControllerIT {
                    private FooService fooService;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooService"), tempDir);

        assertTrue(result.contains("com.example.BarControllerIT"));
    }

    @Test
    void findsTestWithAutowiredField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("ServiceIT.java"), """
                package com.example;

                import com.example.service.FooService;
                import org.springframework.beans.factory.annotation.Autowired;

                public class ServiceIT {
                    @Autowired
                    private FooService fooService;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooService"), tempDir);

        assertTrue(result.contains("com.example.ServiceIT"));
    }

    @Test
    void findsTestWithMockField() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooBarTest.java"), """
                package com.example;

                import com.example.service.FooBar;
                import org.mockito.Mock;

                public class FooBarTest {
                    @Mock
                    private FooBar fooBar;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.contains("com.example.FooBarTest"));
    }

    @Test
    void findsTestThatUsesChangedClassAsMethodParameter() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("BazMapperTest.java"), """
                package com.example;

                import com.example.service.BarModel;

                public class BazMapperTest {
                    public void testMap(BarModel item) {
                        // uses BarModel as method param only
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.BazMapperTest"),
                "Should match test that imports the changed class even without a field");
    }

    @Test
    void findsTestThatUsesChangedClassInConstructorCall() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FactoryTest.java"), """
                package com.example;

                import com.example.service.BarModel;

                public class FactoryTest {
                    public void testCreate() {
                        BarModel bm = new BarModel();
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.FactoryTest"),
                "Should match test that creates instances of the changed class");
    }

    @Test
    void findsTestWithWildcardImportAndTypeReference() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("WildcardTest.java"), """
                package com.example;

                import com.example.service.*;

                public class WildcardTest {
                    private BarModel item;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.WildcardTest"),
                "Should match test with wildcard import covering the changed class's package");
    }

    @Test
    void findsTestInSamePackageWithoutImport() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("InternalTest.java"), """
                package com.example.service;

                public class InternalTest {
                    private BarModel item;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.service.InternalTest"),
                "Should match test in same package (no import needed)");
    }

    @Test
    void doesNotMatchUnrelatedTypes() throws IOException {
        Path testDir = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("OtherTest.java"), """
                package com.example;

                import com.other.UnrelatedService;

                public class OtherTest {
                    private UnrelatedService service;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.FooBar"), tempDir);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForEmptyInput() {
        Set<String> result = strategy.discoverTests(Set.of(), tempDir);
        assertTrue(result.isEmpty());
    }

    @Test
    void findsCrossModuleTestViaImport() throws IOException {
        // Simulate: FooModel in api module, test in application module
        Path appTestDir = tempDir.resolve("application/src/test/java/com/example/tests");
        Files.createDirectories(appTestDir);
        Files.writeString(appTestDir.resolve("BazValidatorTest.java"), """
                package com.example.tests;

                import com.example.api.FooModel;

                public class BazValidatorTest {
                    public void testValidate(FooModel fm) {}
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.api.FooModel"), tempDir);

        assertTrue(result.contains("com.example.tests.BazValidatorTest"),
                "Should find test in sub-module that imports the changed class");
    }

    @Test
    void findsTestInDeeplyNestedModule() throws IOException {
        // Depth 2: services/core/src/test/java/...
        Path deepTestDir = tempDir.resolve("services/core/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("BazGatewayIT.java"), """
                package com.example;

                import com.example.service.BazGateway;

                public class BazGatewayIT {
                    private BazGateway gateway;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BazGateway"), tempDir);

        assertTrue(result.contains("com.example.BazGatewayIT"),
                "Should find test nested 2 levels deep: services/core/src/test/java");
    }

    @Test
    void findsTestsAcrossMultipleDepths() throws IOException {
        // Depth 1
        Path apiTestDir = tempDir.resolve("api/src/test/java/com/example");
        Files.createDirectories(apiTestDir);
        Files.writeString(apiTestDir.resolve("ShallowTest.java"), """
                package com.example;

                import com.example.service.QuxService;

                public class ShallowTest {
                    private QuxService svc;
                }
                """);

        // Depth 3
        Path deepTestDir = tempDir.resolve("platform/services/core/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("DeepTest.java"), """
                package com.example;

                import com.example.service.QuxService;

                public class DeepTest {
                    private QuxService svc;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.QuxService"), tempDir);

        assertTrue(result.contains("com.example.ShallowTest"),
                "Should find test at depth 1");
        assertTrue(result.contains("com.example.DeepTest"),
                "Should find test at depth 3");
        assertEquals(2, result.size());
    }

    @Test
    void findsTestThatImportsInnerClassOfChangedOuter() throws IOException {
        // Regression: PathToClassMapper is file-based and only surfaces the
        // outer FQN when `Outer.java` (containing nested `Outer.Inner`) is
        // changed. A test that only references the inner class writes
        // `import com.example.Outer.Inner;` — pre-fix the direct-import
        // tier matched `importedFqns.contains("com.example.Outer")` which
        // is false (the imported name is `com.example.Outer.Inner`). The
        // test was silently dropped.
        Path testDir = tempDir.resolve("src/test/java/com/example/inner");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("InnerUserTest.java"), """
                package com.example.inner;

                import com.example.Outer.Inner;

                public class InnerUserTest {
                    public void t() { Inner i = new Inner(); }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Outer"), tempDir);

        assertTrue(result.contains("com.example.inner.InnerUserTest"),
                "Test importing Outer.Inner must be selected when Outer changes — "
                        + "inner-class changes surface via the outer's file");
    }

    @Test
    void findsTestThatUsesStaticImportFromChangedClass() throws IOException {
        // Regression: a test that only consumes a changed class through
        // `import static com.example.Constants.MAX_VALUE;` never matched
        // any tier — the imported name is `com.example.Constants.MAX_VALUE`,
        // not the class FQN, and the class itself is often never written
        // as a type in the test body. Consequence: changes to a pure
        // constants-holder class silently dropped the tests that consume
        // its constants.
        Path testDir = tempDir.resolve("src/test/java/com/example/cfg");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("ConstantsUserTest.java"), """
                package com.example.cfg;

                import static com.example.Constants.MAX_VALUE;

                public class ConstantsUserTest {
                    public void t() { int x = MAX_VALUE; }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Constants"), tempDir);

        assertTrue(result.contains("com.example.cfg.ConstantsUserTest"),
                "Static-import-only consumer must be selected when the "
                        + "backing class changes");
    }

    @Test
    void findsTestThatUsesChangedClassByFullyQualifiedInlineReference() throws IOException {
        // Regression: a test that never imports the changed class but
        // refers to it inline by its full dotted name
        //   com.example.service.Thing t = new com.example.service.Thing();
        // slid through every tier pre-fix (no matching import, no
        // wildcard, different package, and the simple-name AST scan
        // would only fire if the test lived in the same package). The
        // new Tier 3 walks ClassOrInterfaceType nodes and reads
        // getNameWithScope() so the inline fully-qualified shape is
        // caught deterministically, without depending on raw source
        // text or on comment stripping.
        Path testDir = tempDir.resolve("src/test/java/com/example/other");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FullyQualifiedInlineTest.java"), """
                package com.example.other;

                public class FullyQualifiedInlineTest {
                    public void t() {
                        com.example.service.Thing x =
                                new com.example.service.Thing();
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.Thing"), tempDir);

        assertTrue(result.contains("com.example.other.FullyQualifiedInlineTest"),
                "Tests that inline-qualify the changed class must still be "
                        + "selected — otherwise any test in a sibling package "
                        + "that avoids imports (common in Cucumber-style steps) "
                        + "silently drops coverage");
    }

    @Test
    void findsTestThatInnerClassQualifiesThroughChangedOuter() throws IOException {
        // Follow-up to findsTestThatImportsInnerClassOfChangedOuter:
        // same semantic (Outer.java change surfaces only the outer
        // FQN, but the test actually uses Outer.Inner) but expressed
        // inline rather than via an import. The Tier 3 startsWith
        // check must treat `com.example.Outer.Inner` as a dependency
        // of `com.example.Outer` so a test that never imports Outer
        // still gets selected.
        Path testDir = tempDir.resolve("src/test/java/com/example/other");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("InlineInnerTest.java"), """
                package com.example.other;

                public class InlineInnerTest {
                    public void t() {
                        com.example.Outer.Inner x = new com.example.Outer.Inner();
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Outer"), tempDir);

        assertTrue(result.contains("com.example.other.InlineInnerTest"),
                "Inline reference to an inner class of the changed outer must "
                        + "still pull the test in — the inner is part of the "
                        + "outer's file and shares its change signature");
    }

    @Test
    void findsTestThatWildcardsClassMembersOfChangedClass() throws IOException {
        // Regression: `import com.example.Outer.*;` imports every
        // member of Outer — nested types, public static fields,
        // nested classes. PathToClassMapper reports changes at the
        // outer-FQN granularity, so when Outer.java changes the
        // strategy sees `changedFqns = { "com.example.Outer" }`. The
        // old wildcard tier bucketed "com.example.Outer" as a package
        // wildcard and then checked whether the AST referenced the
        // simple name "Outer" — which test code using the wildcard
        // almost never writes (the whole point of the wildcard is to
        // skip qualification). Result: the consumer's tests were
        // silently dropped on every change to Outer.java. The new
        // class-member wildcard tier returns true as soon as the
        // wildcard target equals a changed FQN.
        Path testDir = tempDir.resolve("src/test/java/com/example/consumers");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("WildcardMemberTest.java"), """
                package com.example.consumers;

                import com.example.Outer.*;

                public class WildcardMemberTest {
                    public void t() {
                        Inner i = new Inner();
                    }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Outer"), tempDir);

        assertTrue(result.contains("com.example.consumers.WildcardMemberTest"),
                "`import pkg.Outer.*` must be treated as a dependency on "
                        + "pkg.Outer — every member the wildcard brings in lives "
                        + "inside Outer.java and shares its change signature");
    }

    @Test
    void findsTestThatReferencesChangedClassOnlyInsideNestedGeneric() throws IOException {
        // Regression for the AST-walk consolidation in #39. The post-#39
        // matcher does a single `findAll(ClassOrInterfaceType)` walk and
        // checks `type.getNameAsString()` against changed-class simple
        // names, instead of regex-scanning the type strings of fields /
        // params / methods / object creations. The cheapest scenario where
        // those two shapes diverge is a same-package reference to the
        // changed class only inside a nested generic position
        // (`Map<String, List<Foo>>`). JavaParser's recursive
        // findAll surfaces every nested ClassOrInterfaceType node, so
        // the inner `Foo` is picked up by name even though no field /
        // param / method / object-creation type *equals* "Foo" — its
        // type string is `Map<String, List<Foo>>`. Pre-#39 this happened
        // to work via the regex fallback in `typeMatches`; locking it
        // in keeps that behaviour from silently regressing if the AST
        // shape changes in a future JavaParser bump.
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("NestedGenericTest.java"), """
                package com.example.service;

                import java.util.List;
                import java.util.Map;

                public class NestedGenericTest {
                    private Map<String, List<BarModel>> byKey;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.contains("com.example.service.NestedGenericTest"),
                "Same-package reference to a changed class inside a nested "
                        + "generic position must still match — the consolidated "
                        + "AST walk must descend into generic type arguments");
    }

    @Test
    void doesNotMatchWhenSimpleNameOnlyAppearsAsUnrelatedIdentifier() throws IOException {
        // The pre-#39 regex-on-type-string approach used a word-boundary
        // pattern (`(?<![\w])Name(?![\w])`) so `Id` wouldn't match
        // `GridLayout`. The post-#39 walk uses
        // `ClassOrInterfaceType.getNameAsString()` which returns the
        // tokenised simple name directly from the AST — so the same
        // false-positive is structurally impossible. Lock the behaviour
        // in: a test that mentions `BarModel` only inside a longer
        // class name (`BarModelException`) must not match a change to
        // `BarModel`.
        Path testDir = tempDir.resolve("src/test/java/com/example/service");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("PrefixTest.java"), """
                package com.example.service;

                public class PrefixTest {
                    private BarModelException ex;
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.service.BarModel"), tempDir);

        assertTrue(result.isEmpty(),
                "A class named BarModelException must not match a change to "
                        + "BarModel — the simple-name compare uses the parsed "
                        + "AST identifier, not a substring scan");
    }

    @Test
    void findsTestThatUsesStaticWildcardImportFromChangedClass() throws IOException {
        // Same as above but with the wildcard static form:
        // `import static com.example.Constants.*;`. Pre-fix this was
        // bucketed into wildcardPackages with the value "com.example.Constants"
        // — wrong, because that's a class name and would only match tests
        // in a (non-existent) package named "com.example.Constants".
        Path testDir = tempDir.resolve("src/test/java/com/example/cfg");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("ConstantsWildcardUserTest.java"), """
                package com.example.cfg;

                import static com.example.Constants.*;

                public class ConstantsWildcardUserTest {
                    public void t() { int x = MAX_VALUE; }
                }
                """);

        Set<String> result = strategy.discoverTests(
                Set.of("com.example.Constants"), tempDir);

        assertTrue(result.contains("com.example.cfg.ConstantsWildcardUserTest"),
                "Static wildcard import must be treated as a reference to the "
                        + "class, not as a package-level wildcard");
    }
}
