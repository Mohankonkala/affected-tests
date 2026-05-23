package io.affectedtests.core.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SourceExtensionsTest {

    @Test
    void extensionsContainsJavaAndKotlin() {
        assertTrue(SourceExtensions.EXTENSIONS.contains(".java"));
        assertTrue(SourceExtensions.EXTENSIONS.contains(".kt"));
        assertEquals(2, SourceExtensions.EXTENSIONS.size(),
                "PR #1 of issue #76 ships .java + .kt. PR #3 (full Kotlin AST) "
                        + "does not change this set; future Groovy / Scala work "
                        + "extends it under separate issues.");
    }

    @Test
    void isSourceMatchesKnownExtensionsCaseInsensitively() {
        assertTrue(SourceExtensions.isSource("Foo.java"));
        assertTrue(SourceExtensions.isSource("Foo.kt"));
        assertTrue(SourceExtensions.isSource("Foo.JAVA"),
                "Filesystem case-insensitivity on macOS / Windows means "
                        + "FOO.JAVA must classify the same as FOO.java");
        assertTrue(SourceExtensions.isSource("Foo.KT"));
        assertTrue(SourceExtensions.isSource("/abs/path/Foo.kt"));
    }

    @Test
    void isSourceRejectsNonSourceFiles() {
        assertFalse(SourceExtensions.isSource("build.gradle"));
        assertFalse(SourceExtensions.isSource("application.yml"));
        assertFalse(SourceExtensions.isSource("README.md"));
        assertFalse(SourceExtensions.isSource("Foo.kts"),
                ".kts is a Kotlin script — NOT mapped by PR #1; the "
                        + "polyglot hint surfaces it separately.");
        assertFalse(SourceExtensions.isSource("Foo.groovy"));
        assertFalse(SourceExtensions.isSource("Foo.scala"));
        assertFalse(SourceExtensions.isSource(""));
        assertFalse(SourceExtensions.isSource(null));
    }

    @Test
    void isSourceRejectsBareDotfileWithNoStem() {
        // A file literally named `.kt` (or `.java`) has no stem
        // before the extension. Accepting it would yield FQN ""
        // from the suffix-strip pipeline, and the synthetic Kotlin
        // emission would produce FQN "Kt" — both of which feed the
        // naming-strategy probe, dragging in any unrelated class
        // literally named `Test` / `IT` / `ITTest` /
        // `IntegrationTest`. Reject up-front so the rest of the
        // pipeline never sees the empty-stem shape.
        assertFalse(SourceExtensions.isSource(".kt"));
        assertFalse(SourceExtensions.isSource(".java"));
        assertFalse(SourceExtensions.isSource("src/main/java/.kt"));
        assertFalse(SourceExtensions.isSource("/abs/.java"));
        assertFalse(SourceExtensions.isSource("dir\\.kt"),
                "Windows separator parity: \\.kt under any directory "
                        + "is still a stem-less dotfile.");
        // Sanity: a one-char stem still passes.
        assertTrue(SourceExtensions.isSource("a.kt"));
        assertTrue(SourceExtensions.isSource("X.java"));
    }

    @Test
    void extensionOfRejectsBareDotfileWithNoStem() {
        assertNull(SourceExtensions.extensionOf(".kt"));
        assertNull(SourceExtensions.extensionOf(".java"));
        assertNull(SourceExtensions.extensionOf("src/.kt"));
    }

    @Test
    void extensionOfReturnsCanonicalLowercaseForm() {
        assertEquals(".java", SourceExtensions.extensionOf("Foo.java"));
        assertEquals(".kt", SourceExtensions.extensionOf("Foo.kt"));
        assertEquals(".java", SourceExtensions.extensionOf("Foo.JAVA"),
                "Returned form must be the canonical lowercase value so "
                        + "downstream lookups can compare against literal \".kt\" / \".java\"");
        assertNull(SourceExtensions.extensionOf("Foo.kts"));
        assertNull(SourceExtensions.extensionOf(null));
    }

    @Test
    void stripKnownExtensionRemovesJavaSuffix() {
        assertEquals("com.example.Foo",
                SourceExtensions.stripKnownExtension("com.example.Foo.java"));
    }

    @Test
    void stripKnownExtensionRemovesKotlinSuffix() {
        assertEquals("com.example.Foo",
                SourceExtensions.stripKnownExtension("com.example.Foo.kt"));
    }

    @Test
    void stripKnownExtensionLeavesNonSourceUnchanged() {
        assertEquals("Foo.kts", SourceExtensions.stripKnownExtension("Foo.kts"),
                ".kts must NOT lose its `.kts` suffix — it isn't in the "
                        + "supported-extensions set, so leaving it intact prevents "
                        + "the file from being silently mistaken for a class FQN");
        assertEquals("Foo", SourceExtensions.stripKnownExtension("Foo"));
        assertEquals("", SourceExtensions.stripKnownExtension(""));
        assertNull(SourceExtensions.stripKnownExtension(null));
    }

    @Test
    void stripKnownExtensionPreservesDottedPackageSegments() {
        // Defensive: pre-PR-1 the .java strip used a literal `length() - 5`
        // which only worked because nothing else was 5 chars.
        // The centralised helper must still work when a path contains
        // multiple dots (e.g. version-tagged file names like
        // `Foo.v2.kt` or a package path collapsed to a dotted FQN).
        assertEquals("com.example.Foo.v2",
                SourceExtensions.stripKnownExtension("com.example.Foo.v2.kt"));
    }
}
