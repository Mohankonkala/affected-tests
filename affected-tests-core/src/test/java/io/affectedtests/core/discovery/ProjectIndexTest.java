package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The regression this suite locks in is the v1.9.14 bug where
 * {@code outOfScopeTestDirs = ["api-test/**"]} filtered the diff-side
 * view in {@link io.affectedtests.core.mapping.PathToClassMapper} but
 * silently did nothing on the on-disk view in {@link ProjectIndex}.
 * Net effect for the user: an MR that only touched
 * {@code api-test/...} was correctly classified as out-of-scope, yet
 * tests discovered under {@code api-test/src/test/java} still got
 * dispatched because this class never dropped them. The fix is
 * covered in production code by delegating to {@link
 * io.affectedtests.core.mapping.OutOfScopeMatchers}; this suite
 * verifies the dispatch map and file lists agree with that delegation.
 */
class ProjectIndexTest {

    @TempDir
    Path projectDir;

    @Test
    void globOutOfScopeTestDirDropsTestFqn() throws Exception {
        // Two test classes: one under api-test/** (out of scope) and
        // one under src/test/java (normal). The dispatch map must
        // contain exactly the normal one.
        writeJava(projectDir.resolve("api-test/src/test/java/com/example/ApiFoo.java"),
                "package com.example; public class ApiFoo {}");
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();

        ProjectIndex index = ProjectIndex.build(projectDir, config);

        assertTrue(index.testFqns().contains("com.example.FooTest"),
                "Normal test FQN must survive the filter");
        assertFalse(index.testFqns().contains("com.example.ApiFoo"),
                "Glob out-of-scope test dir must drop the api-test FQN on the on-disk side, "
                        + "not just the diff side — this is the bug the v1.9.14 --explain Hint "
                        + "was introduced to warn about.");
    }

    @Test
    void literalAndGlobFormsAgreeOnTheSameOutcome() throws Exception {
        // Given an identical on-disk layout, writing the config as a
        // literal prefix ("api-test") and as a glob ("api-test/**")
        // must drop the same FQN. If these ever diverge again the
        // user's only visible signal is a mysterious full test run
        // after a pure api-test MR, so the lock-in matters.
        writeJava(projectDir.resolve("api-test/src/test/java/com/example/ApiFoo.java"),
                "package com.example; public class ApiFoo {}");
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        ProjectIndex literalIndex = ProjectIndex.build(projectDir,
                AffectedTestsConfig.builder()
                        .mode(Mode.CI)
                        .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                        .outOfScopeTestDirs(List.of("api-test"))
                        .build());
        ProjectIndex globIndex = ProjectIndex.build(projectDir,
                AffectedTestsConfig.builder()
                        .mode(Mode.CI)
                        .testDirs(List.of("src/test/java", "api-test/src/test/java"))
                        .outOfScopeTestDirs(List.of("api-test/**"))
                        .build());

        assertEquals(literalIndex.testFqns(), globIndex.testFqns(),
                "Literal and glob forms of the same logical rule must produce the same index");
    }

    @Test
    void parseFailureCountStartsAtZero() throws Exception {
        // Baseline for the B6-#9 regression: a brand-new index with
        // no compilationUnit() calls must report zero parse failures,
        // otherwise the downstream DISCOVERY_INCOMPLETE routing would
        // fire on every run and blow up {@link io.affectedtests.core.AffectedTestsEngine}
        // discovery with spurious escalations.
        writeJava(projectDir.resolve("src/main/java/com/example/Foo.java"),
                "package com.example; public class Foo {}");
        writeJava(projectDir.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example; public class FooTest {}");

        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.CI).build();
        ProjectIndex index = ProjectIndex.build(projectDir, config);

        assertEquals(0, index.parseFailureCount(),
                "A fresh index that never touched compilationUnit() must report zero failures");
    }

    @Test
    void parseFailureCountIncrementsOnUnparseableFile() throws Exception {
        // Regression for B6-#9: a scanned Java file that JavaParser
        // cannot parse must bump parseFailureCount so the engine can
        // route through Situation.DISCOVERY_INCOMPLETE instead of
        // silently under-reporting discovery. Before the fix the
        // cache hid every null return behind containsKey, so the
        // engine had no way to tell a clean empty selection apart
        // from "parse failed, we saw nothing".
        Path prod = projectDir.resolve("src/main/java/com/example/Foo.java");
        writeJava(prod, "package com.example; public class Foo {}");
        // Malformed: missing closing brace, no class declaration.
        Path broken = projectDir.resolve("src/main/java/com/example/Broken.java");
        writeJava(broken, "package com.example; public class Broken {");

        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.CI).build();
        ProjectIndex index = ProjectIndex.build(projectDir, config);

        // Warm the cache for both files — the working one should
        // return a non-null CU, the broken one should return null.
        assertNotNull(index.compilationUnit(prod),
                "Valid source must still parse — otherwise the counter is counting false positives");
        assertNull(index.compilationUnit(broken),
                "Malformed source must still return null (the current parseOrWarn contract)");

        assertEquals(1, index.parseFailureCount(),
                "Broken file must be counted exactly once — de-dup means a repeat call does not re-count");

        // Hit the cache again to make sure we did not double-count:
        // the whole point of counting at the cache boundary is that
        // N strategies can ask for the same file and we still report
        // a single distinct failure.
        index.compilationUnit(broken);
        index.compilationUnit(broken);
        assertEquals(1, index.parseFailureCount(),
                "Repeated compilationUnit() calls on the same file must not inflate the count");
    }

    @Test
    void compilationUnitReturnsNullForKotlinWithoutBumpingParseFailureCount() throws Exception {
        // PR #1 of issue #76 contract: Kotlin files are
        // genuinely unparsed-by-design until PR #3 lands the
        // KotlinLanguageParser. They must NOT count against
        // parseFailureCount — that counter drives
        // DISCOVERY_INCOMPLETE escalation, and treating every
        // .kt file as a parse failure would surface
        // DISCOVERY_INCOMPLETE on every Kotlin diff (the exact
        // failure mode PR #1's short-circuit in
        // ProjectIndex.compilationUnit was added to prevent).
        Path kotlin = projectDir.resolve("src/main/java/com/example/Util.kt");
        Files.createDirectories(kotlin.getParent());
        Files.writeString(kotlin,
                "package com.example\nfun greet(name: String) = \"hi $name\"");

        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.CI).build();
        ProjectIndex index = ProjectIndex.build(projectDir, config);

        assertNull(index.compilationUnit(kotlin),
                "Kotlin file must yield null (no JavaParser CU) until PR #3 "
                        + "of issue #76 plugs in the Kotlin language parser");
        assertEquals(0, index.parseFailureCount(),
                "A .kt file must NOT increment parseFailureCount — treating "
                        + "it as a parse failure would surface DISCOVERY_INCOMPLETE "
                        + "on every Kotlin diff. Got: " + index.parseFailureCount());

        // Idempotency: repeated lookups on the same .kt file
        // must continue to yield null and never start counting
        // (regression-proofing against a future change that
        // dropped the short-circuit but kept the cache).
        index.compilationUnit(kotlin);
        index.compilationUnit(kotlin);
        assertEquals(0, index.parseFailureCount());

        // fileMetadata follows the same contract: null for .kt,
        // and strategies skip on null. UsageStrategy /
        // ImplementationStrategy / TransitiveStrategy index-driven
        // paths therefore return zero matches for Kotlin until
        // PR #3 — exactly what docs/PHASE-2-KOTLIN-AST.md §9 PR #1
        // promises.
        assertNull(index.fileMetadata(kotlin),
                "fileMetadata(.kt) must follow compilationUnit's null contract");
    }

    private static void writeJava(Path target, String body) throws Exception {
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
    }
}
