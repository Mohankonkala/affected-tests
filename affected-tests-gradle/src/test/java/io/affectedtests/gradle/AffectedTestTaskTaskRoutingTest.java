package io.affectedtests.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the per-task-name routing introduced in #48. The helper is
 * a pure function so the unit tests exercise it directly without
 * spinning up a Gradle test runtime.
 */
class AffectedTestTaskTaskRoutingTest {

    @Test
    void testTaskPathDefaultIsBackwardsCompatibleWithSingleArgCallers() {
        // Existing tests + downstream callers depend on the
        // 1-arg form returning ":test" / ":module:test". The
        // 2-arg form must produce the same result when called
        // with "test", and the 1-arg form must keep delegating
        // to "test" so a test-only DSL caller doesn't have to
        // touch its config to upgrade.
        assertEquals(":test", AffectedTestTask.testTaskPath(""));
        assertEquals(":app:test", AffectedTestTask.testTaskPath(":app"));
        assertEquals(":test", AffectedTestTask.testTaskPath("", "test"));
        assertEquals(":app:test", AffectedTestTask.testTaskPath(":app", "test"));
    }

    @Test
    void testTaskPathProducesPerTaskKeyForCustomNames() {
        // Without this contract, dispatch can't distinguish
        // ":app:integrationTest" from ":app:test" — every FQN
        // would land in the wrong bucket and either fail with
        // "No tests found" (wrong source set) or silently
        // run the wrong task.
        assertEquals(":integrationTest", AffectedTestTask.testTaskPath("", "integrationTest"));
        assertEquals(":app:e2eTest", AffectedTestTask.testTaskPath(":app", "e2eTest"));
        assertEquals(":services:payment:integrationTest",
                AffectedTestTask.testTaskPath(":services:payment", "integrationTest"));
    }

    @Test
    void resolveTaskNameForFileMatchesGradleSourceSetConvention() {
        // The Gradle Java plugin convention is src/<sourceSet>/java.
        // The helper has to recognise that segment exactly so
        // FQNs from different source sets route to the right
        // task. This is the central correctness contract for
        // #48 — get this wrong and a multi-source-set adopter's
        // dispatch is wrong on every MR.
        List<String> taskNames = List.of("test", "integrationTest");
        assertEquals("test",
                AffectedTestTask.resolveTaskNameForFile(
                        Path.of("/repo/app/src/test/java/com/example/FooTest.java"),
                        taskNames),
                "src/test/java segment must route to 'test'");
        assertEquals("integrationTest",
                AffectedTestTask.resolveTaskNameForFile(
                        Path.of("/repo/app/src/integrationTest/java/com/example/BarIT.java"),
                        taskNames),
                "src/integrationTest/java segment must route to 'integrationTest' "
                        + "— the central #48 contract");
    }

    @Test
    void resolveTaskNameForFileFallsBackToFirstEntryWhenNoSegmentMatches() {
        // A file outside any configured source set (root-level,
        // non-Gradle layout, etc.) falls back to the first task
        // name. This matches the conservative "we never silently
        // dispatch nothing" posture the rest of the plugin takes
        // when an input shape is ambiguous. Listing 'test' first
        // by default keeps the fallback identical to the pre-#48
        // hard-coded ':test'.
        List<String> taskNames = List.of("test", "integrationTest");
        assertEquals("test",
                AffectedTestTask.resolveTaskNameForFile(
                        Path.of("/repo/some/odd/path/com/example/FooTest.java"),
                        taskNames));
    }

    @Test
    void resolveTaskNameForFileDoesNotMatchSubstringsInsideOtherTaskNames() {
        // Strict-segment matching: 'integrationTest' must NOT
        // false-match a path containing 'integrationTestPart'
        // (or vice versa) — otherwise an adopter who happens to
        // have both source sets would have FQNs route to the
        // wrong task and either run the wrong tests or fail
        // with "No tests found". The marker uses '/src/<name>/'
        // bracketed by both delimiters precisely to prevent
        // this.
        List<String> taskNames = List.of("test", "integrationTest");
        // 'integrationTestPart' is NOT in the configured list,
        // so a file under it falls back to the first entry. The
        // critical assertion is that it does NOT route to
        // 'integrationTest' just because the simple-name shows
        // up as a substring of the directory name.
        assertEquals("test",
                AffectedTestTask.resolveTaskNameForFile(
                        Path.of("/repo/app/src/integrationTestPart/java/com/example/Foo.java"),
                        taskNames));
    }

    @Test
    void resolveTaskNameForFileHandlesNullsAndEmptyListsDefensively() {
        // The helper is unit-test-callable with arbitrary inputs.
        // A null/empty list defaults to "test" (matches the
        // documented default); a null path falls back to the
        // first list entry rather than crashing. Without these
        // guards, a buggy custom strategy that returned a null
        // path would cascade into an NPE three call levels up.
        assertEquals("test",
                AffectedTestTask.resolveTaskNameForFile(
                        Path.of("/repo/app/src/test/java/com/example/FooTest.java"),
                        null));
        assertEquals("test",
                AffectedTestTask.resolveTaskNameForFile(
                        Path.of("/repo/app/src/test/java/com/example/FooTest.java"),
                        List.of()));
        assertEquals("integrationTest",
                AffectedTestTask.resolveTaskNameForFile(
                        null,
                        List.of("integrationTest", "test")),
                "Null path falls back to first list entry, "
                        + "preserving the order the user configured");
    }

    @Test
    void resolveTaskNameForFileRespectsConfiguredOrderForOverlappingPriorities() {
        // The first task name in the list whose source-set
        // segment matches wins. This lets adopters express a
        // priority for ambiguous shapes (a file copy-pasted
        // into both src/test/java and src/integrationTest/java
        // at different times resolves deterministically based
        // on configured order, not on filesystem listing order).
        Path file = Path.of("/repo/app/src/integrationTest/java/com/example/Foo.java");
        assertEquals("integrationTest",
                AffectedTestTask.resolveTaskNameForFile(
                        file, List.of("test", "integrationTest")));
        // Same file, but 'integrationTest' listed first: still
        // resolves to 'integrationTest' since that's where the
        // path actually lives. Order matters when the path
        // matches multiple task names — which it can't here
        // (a file lives under exactly one src/X/java segment) —
        // but the assertion pins the unambiguous case to keep
        // the contract honest.
        assertEquals("integrationTest",
                AffectedTestTask.resolveTaskNameForFile(
                        file, List.of("integrationTest", "test")));
    }
}
