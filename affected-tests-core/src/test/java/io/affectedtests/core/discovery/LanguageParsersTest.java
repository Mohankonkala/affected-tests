package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
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
 * without touching every call site again. PR #3 converts the registry
 * from a static-map utility to a per-engine instance because
 * {@code KotlinLanguageParser} owns lifecycle state that must be
 * disposed; tests therefore exercise the
 * {@link LanguageParsers#defaultJavaOnly()} singleton (matching the
 * standalone strategy fallback path) plus the
 * {@link LanguageParsers#forConfig(AffectedTestsConfig)} factory used
 * by the engine.
 */
class LanguageParsersTest {

    private final LanguageParsers javaOnly = LanguageParsers.defaultJavaOnly();

    @Test
    void forFileResolvesJavaParser() {
        LanguageParser parser = javaOnly.forFile(Path.of("src/main/java/Foo.java"));
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
        LanguageParser parser = javaOnly.forFile(Path.of("src/main/java/Foo.JAVA"));
        assertSame(JavaLanguageParser.INSTANCE, parser);
    }

    @Test
    void forFileReturnsNullForUnregisteredExtensionsOnDefaultJavaOnly() {
        // The default Java-only registry has no parser registered
        // for .kt / .groovy / .scala / .gradle.kts. PR #3's Kotlin
        // gate is OFF in this registry by construction (it's the
        // singleton used by the strategy fallback path that has no
        // engine config to consult). Looking up these extensions
        // must yield null — that's how strategies signal "skip
        // this file" in the no-index fallback path.
        assertNull(javaOnly.forFile(Path.of("src/main/kotlin/Foo.kt")));
        assertNull(javaOnly.forFile(Path.of("src/main/groovy/Foo.groovy")));
        assertNull(javaOnly.forFile(Path.of("src/main/scala/Foo.scala")));
        assertNull(javaOnly.forFile(Path.of("build.gradle.kts")));
    }

    @Test
    void forFileReturnsNullForFileWithoutExtension() {
        assertNull(javaOnly.forFile(Path.of("Makefile")));
        assertNull(javaOnly.forFile(Path.of("scripts/run")));
    }

    @Test
    void forFileReturnsNullForBareDotfile() {
        // A literal `.java` file (no stem before the dot) must NOT
        // resolve to JavaLanguageParser. Pre-fix, the substring
        // would yield extension ".java" with empty stem, the
        // parser would parse an empty file, and the FQN pipeline
        // would feed empty FQNs to the naming strategy. Mirror the
        // SourceExtensions.isSource non-empty-stem rule from PR #1.
        assertNull(javaOnly.forFile(Path.of(".java")));
        assertNull(javaOnly.forFile(Path.of(".kt")));
        assertNull(javaOnly.forFile(Path.of("src/main/java/.java")));
    }

    @Test
    void forFileReturnsNullForNullPath() {
        assertNull(javaOnly.forFile(null));
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
        assertNull(javaOnly.forFile(Path.of("/")));
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
        assertSame(JavaLanguageParser.INSTANCE, javaOnly.forExtension(".java"));
        assertNull(javaOnly.forExtension(".JAVA"),
                "forExtension is exact-match; callers from path "
                        + "strings must lowercase first via forFile.");
        assertNull(javaOnly.forExtension(""));
        assertNull(javaOnly.forExtension(null));
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
        FileMetadata md = javaOnly.parseOrWarn(
                Path.of("src/main/kotlin/Foo.kt"), "test-label");
        assertNull(md);
    }

    @Test
    void registeredExtensionsMatchSourceExtensions() {
        // The two extension scopes — scanner-side
        // (SourceExtensions.EXTENSIONS) and parser-side
        // (the per-engine LanguageParsers instance) — must stay
        // in lock-step OR the parser-side must be a strict subset
        // of the scanner-side. The default-Java-only registry
        // pins this for the rollout-window default
        // (kotlin.enabled=false): scanner has {.java, .kt};
        // parser has {.java}. After PR #4 flips the default, both
        // sides have {.java, .kt}.
        assertTrue(SourceExtensions.EXTENSIONS.contains(".java"),
                "Scanner must recognise every parser-registered extension");
        // No assertion for .kt against the default Java-only
        // registry — it's in the scanner but not the parser, and
        // that's the documented PR #1 / PR #2 intermediate state.
    }

    @Test
    void forConfigJavaOnlyDoesNotRegisterKotlin() {
        // PR #3 introduces the per-engine factory. With kotlinEnabled
        // false (the rollout default), forConfig must produce a
        // registry shape indistinguishable from defaultJavaOnly()
        // for every observable lookup — the only difference is the
        // lifecycle ownership flag, which is verified separately by
        // forConfigOwnsLifecycleAndCloseIsIdempotent below.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .kotlinEnabled(false)
                .build();
        try (LanguageParsers parsers = LanguageParsers.forConfig(config)) {
            assertSame(JavaLanguageParser.INSTANCE,
                    parsers.forFile(Path.of("Foo.java")));
            assertNull(parsers.forFile(Path.of("Foo.kt")),
                    "kotlinEnabled=false must NOT register a Kotlin parser; "
                            + "the rollout flag default is the gate that keeps "
                            + "every Kotlin file routed through Phase 1's "
                            + "path-derived FQN pipeline until PR #4 flips "
                            + "the default.");
        }
    }

    @Test
    void forConfigKotlinEnabledRegistersKotlinParser() {
        // PR #3's contract: when the gate is on, .kt resolves to a
        // KotlinLanguageParser. Cleaning up via try-with-resources
        // is required because the Kotlin parser owns a Disposable
        // lifecycle; calling close() on a never-bootstrapped
        // parser is safe (the lazy-bootstrap guard means close()
        // has nothing to dispose unless parseOrWarn ran first).
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .kotlinEnabled(true)
                .build();
        try (LanguageParsers parsers = LanguageParsers.forConfig(config)) {
            LanguageParser parser = parsers.forFile(Path.of("Foo.kt"));
            assertNotNull(parser, "kotlinEnabled=true must register a parser for .kt");
            assertEquals(".kt", parser.extension());
            assertTrue(parser instanceof KotlinLanguageParser,
                    "Registered parser for .kt must be the KotlinLanguageParser; "
                            + "got " + parser.getClass().getName());
            // Java still resolves to the singleton — adding Kotlin
            // does not perturb the Java path.
            assertSame(JavaLanguageParser.INSTANCE,
                    parsers.forFile(Path.of("Foo.java")));
        }
    }

    @Test
    void defaultJavaOnlyCloseIsNoOp() {
        // Closing the singleton must be benign so a misconfigured
        // test that calls defaultJavaOnly().close() does not brick
        // every subsequent fallback-path caller. The lifecycle-
        // owned flag is false on the singleton; close() short-
        // circuits before walking parsers.
        LanguageParsers shared = LanguageParsers.defaultJavaOnly();
        shared.close();
        shared.close();
        // Lookups still work after redundant closes — proves the
        // close() short-circuit is wired correctly.
        assertSame(JavaLanguageParser.INSTANCE, shared.forFile(Path.of("Foo.java")));
    }
}
