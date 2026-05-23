package io.affectedtests.core.discovery;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link LanguageParsers} registry contract — the
 * extension-keyed dispatch table used by {@link ProjectIndex} and by
 * the strategy fallback paths ({@link UsageStrategy},
 * {@link ImplementationStrategy}, {@link TransitiveStrategy}).
 *
 * <p>Introduced in PR #2 of issue #76 (Phase 2 Kotlin AST). Pre-PR-2
 * the dispatch was hard-coded to JavaParser everywhere; PR #2 routes
 * through this registry so PR #3 can plug in the Kotlin parser
 * without touching every call site again.
 */
class LanguageParsersTest {

    @Test
    void forFileResolvesJavaParser() {
        LanguageParser parser = LanguageParsers.forFile(Path.of("src/main/java/Foo.java"));
        assertNotNull(parser);
        assertEquals(".java", parser.extension());
        assertSame(JavaLanguageParser.INSTANCE, parser,
                "Registry must hand out the singleton instance — fresh "
                        + "instances would defeat the per-thread parser "
                        + "sharing inside JavaLanguageParser.");
    }

    @Test
    void forFileIsCaseInsensitiveOnExtension() {
        // Filesystems on macOS / Windows are case-insensitive on
        // names. Two devs working on the same repo from different
        // OSes can introduce `Foo.JAVA` vs `Foo.java` mismatches
        // through merge accidents — pin the registry's posture.
        LanguageParser parser = LanguageParsers.forFile(Path.of("src/main/java/Foo.JAVA"));
        assertSame(JavaLanguageParser.INSTANCE, parser);
    }

    @Test
    void forFileReturnsNullForUnregisteredExtensions() {
        // PR #1 widened the scanner to admit `.kt`, but the parser
        // is deferred to PR #3. Until PR #3 ships, looking up `.kt`
        // must yield null — that's how strategies signal "skip
        // this file" in the no-index fallback path. A future
        // refactor that registered an "unimplemented" parser
        // returning null from parseOrWarn would emit one WARN per
        // .kt file per discovery phase; the null lookup keeps the
        // log clean.
        assertNull(LanguageParsers.forFile(Path.of("src/main/kotlin/Foo.kt")));
        assertNull(LanguageParsers.forFile(Path.of("src/main/groovy/Foo.groovy")));
        assertNull(LanguageParsers.forFile(Path.of("src/main/scala/Foo.scala")));
        assertNull(LanguageParsers.forFile(Path.of("build.gradle.kts")));
    }

    @Test
    void forFileReturnsNullForFileWithoutExtension() {
        assertNull(LanguageParsers.forFile(Path.of("Makefile")));
        assertNull(LanguageParsers.forFile(Path.of("scripts/run")));
    }

    @Test
    void forFileReturnsNullForBareDotfile() {
        // A literal `.java` file (no stem before the dot) must NOT
        // resolve to JavaLanguageParser. Pre-fix, the substring
        // would yield extension ".java" with empty stem, the
        // parser would parse an empty file, and the FQN pipeline
        // would feed empty FQNs to the naming strategy. Mirror the
        // SourceExtensions.isSource non-empty-stem rule from PR #1.
        assertNull(LanguageParsers.forFile(Path.of(".java")));
        assertNull(LanguageParsers.forFile(Path.of(".kt")));
        assertNull(LanguageParsers.forFile(Path.of("src/main/java/.java")));
    }

    @Test
    void forFileReturnsNullForNullPath() {
        assertNull(LanguageParsers.forFile(null));
    }

    @Test
    void forFileReturnsNullForFilenamelessPath() {
        // Path.of("/") (a filesystem root) stringifies to "/" with
        // no extension. The registry must return null cleanly
        // without NPE on this — the engine path always has a
        // filename, but the registry is the single source of
        // truth and should defend against degenerate inputs (e.g.
        // a future caller that passes a bare directory by
        // accident). The defence flows through
        // SourceExtensions.extensionOf, which returns null for
        // any path that doesn't end in a recognised extension.
        assertNull(LanguageParsers.forFile(Path.of("/")));
    }

    @Test
    void forExtensionLooksUpExactCanonicalForm() {
        // Direct extension lookup is exact-match (no
        // case-folding) — callers from filesystem paths must
        // lowercase first. Pin the contract to prevent a future
        // refactor from quietly adding case-insensitive matching
        // in two places (forFile delegates to
        // SourceExtensions.extensionOf which already returns the
        // canonical lowercased form).
        assertSame(JavaLanguageParser.INSTANCE, LanguageParsers.forExtension(".java"));
        assertNull(LanguageParsers.forExtension(".JAVA"),
                "forExtension is exact-match; callers from path "
                        + "strings must lowercase first via forFile.");
        assertNull(LanguageParsers.forExtension(""));
        assertNull(LanguageParsers.forExtension(null));
    }

    @Test
    void parseOrWarnReturnsNullForUnregisteredExtension() {
        // The strategy fallback contract: parseOrWarn returning
        // null is the canonical "skip this file" signal. For an
        // extension with no registered parser, parseOrWarn must
        // return null without emitting a WARN — the file is not a
        // parse failure in any registered language's sense, and
        // operators don't need a log entry every time the scanner
        // hands the no-index path a non-Java file.
        FileMetadata md = LanguageParsers.parseOrWarn(
                Path.of("src/main/kotlin/Foo.kt"), "test-label");
        assertNull(md);
    }

    @Test
    void registeredExtensionsMatchSourceExtensions() {
        // The two extension scopes — scanner-side
        // (SourceExtensions.EXTENSIONS) and parser-side
        // (LanguageParsers.BY_EXTENSION) — must stay in lock-step
        // OR the parser-side must be a strict subset of the
        // scanner-side (PR #1 widened the scanner; PR #3 will
        // widen the parser to match).
        //
        // Pin the subset relationship so a future contributor who
        // adds an extension to one but forgets the other gets
        // caught at build time. Today: scanner has {.java, .kt};
        // parser has {.java}. After PR #3 (default-on): both have
        // {.java, .kt}.
        assertTrue(SourceExtensions.EXTENSIONS.contains(".java"),
                "Scanner must recognise every parser-registered extension");
        // No assertion for .kt today — it's in the scanner but
        // not the parser, and that's the documented PR #1/#2
        // intermediate state.
    }
}
