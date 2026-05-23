package io.affectedtests.core.discovery;

import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link JavaLanguageParser} contract — the {@link
 * LanguageParser} implementation backing the {@code .java} extension
 * registration in {@link LanguageParsers}.
 *
 * <p>Introduced in PR #2 of issue #76 (Phase 2 Kotlin AST).
 * Pre-PR-2 the equivalent code lived in {@code JavaParsers} (a
 * static utility) and the {@link ThreadLocal} parser sat on
 * {@link ProjectIndex}. PR #2 collapses both into this class; the
 * tests below pin the language-level posture, the
 * {@link LanguageParser#parseOrWarn} → {@link FileMetadata}
 * contract, and the silent-drop-on-failure posture.
 */
class JavaLanguageParserTest {

    @TempDir
    Path tmp;

    @Test
    void extensionIsCanonicalLowercaseDotJava() {
        assertEquals(".java", JavaLanguageParser.INSTANCE.extension());
    }

    @Test
    void languageLevelIsHighestStableNonPreview() {
        // The motivating bug for the language-level pin: if anyone
        // ever drops this to JAVA_11 (the JavaParser default),
        // every record / sealed-type / pattern-match file silently
        // fails to parse and drops out of discovery. Pin the
        // constant so a regression here goes red, not silently.
        assertEquals(LanguageLevel.JAVA_25, JavaLanguageParser.LANGUAGE_LEVEL);
    }

    @Test
    void newParserReturnsParserAtCanonicalLanguageLevel() {
        // Tests that build a CompilationUnit by hand (e.g.
        // FileMetadataExtractorTest) need a parser at the engine's
        // language level — otherwise their fixtures parse at
        // JAVA_11 and silently fail on records / sealed types.
        var parser = JavaLanguageParser.newParser();
        assertNotNull(parser);
        assertEquals(JavaLanguageParser.LANGUAGE_LEVEL,
                parser.getParserConfiguration().getLanguageLevel(),
                "newParser() must apply the canonical language level — "
                        + "if this drifts, every test that builds a CU by "
                        + "hand silently regresses to JAVA_11.");
    }

    @Test
    void parseOrWarnReturnsFileMetadataForValidJava() throws Exception {
        Path file = tmp.resolve("Foo.java");
        Files.writeString(file, "package com.example;\n"
                + "import java.util.List;\n"
                + "public class Foo {\n"
                + "    List<String> bar() { return List.of(); }\n"
                + "}\n");

        FileMetadata md = JavaLanguageParser.INSTANCE.parseOrWarn(file, "test");

        assertNotNull(md);
        assertEquals("com.example", md.packageName());
        assertEquals("Foo", md.primaryTypeName());
        assertEquals(1, md.imports().size());
        assertEquals("java.util.List", md.imports().get(0).name());
    }

    @Test
    void parseOrWarnReturnsFileMetadataForRecord() {
        // Direct regression for the JAVA_11 → JAVA_25 motivating
        // bug. A record at JAVA_11 produces isSuccessful == false
        // and parseOrWarn returns null — silently dropping the
        // file from discovery. At JAVA_25 the same file parses
        // cleanly and yields metadata. Pin so a future "let's just
        // use the parser default" change goes red.
        Path file = tmp.resolve("Money.java");
        try {
            Files.writeString(file, "package com.example;\n"
                    + "public record Money(long cents) {}\n");
        } catch (Exception e) {
            fail("Could not write fixture: " + e);
        }

        FileMetadata md = JavaLanguageParser.INSTANCE.parseOrWarn(file, "test");

        assertNotNull(md, "Records must parse at the canonical language level");
        assertEquals("Money", md.primaryTypeName());
    }

    @Test
    void parseOrWarnReturnsNullOnMalformedJava() throws Exception {
        Path file = tmp.resolve("Broken.java");
        Files.writeString(file, "package com.example;\n"
                + "this is not valid java {{{\n");

        FileMetadata md = JavaLanguageParser.INSTANCE.parseOrWarn(file, "test");

        assertNull(md, "Malformed source must surface as null FileMetadata "
                + "so callers can drop the file from discovery cleanly.");
    }

    @Test
    void parseOrWarnReturnsNullOnMissingFile() {
        Path file = tmp.resolve("does-not-exist.java");
        // Don't create the file — simulate a git-rm race or a
        // diff-side path that no longer exists on disk.

        FileMetadata md = JavaLanguageParser.INSTANCE.parseOrWarn(file, "test");

        assertNull(md, "Missing files must surface as null — same posture "
                + "as malformed source. The exception is logged at DEBUG, "
                + "not WARN, so noisy CI environments don't pollute the "
                + "log on harmless I/O races.");
    }

    @Test
    void compilationUnitReturnsRawCuForValidJava() throws Exception {
        // The Java-specific surface that ProjectIndex.compilationUnit
        // routes through. Tests that need the raw AST (e.g.
        // FileMetadataExtractorTest historically) should still be
        // able to get one.
        Path file = tmp.resolve("Foo.java");
        Files.writeString(file, "package com.example;\npublic class Foo {}\n");

        CompilationUnit cu = JavaLanguageParser.INSTANCE.compilationUnit(file, "test");

        assertNotNull(cu);
        assertTrue(cu.getPrimaryTypeName().isPresent());
        assertEquals("Foo", cu.getPrimaryTypeName().get());
    }

    @Test
    void compilationUnitReturnsNullOnMalformedJava() throws Exception {
        Path file = tmp.resolve("Broken.java");
        Files.writeString(file, "this is not java");

        CompilationUnit cu = JavaLanguageParser.INSTANCE.compilationUnit(file, "test");

        assertNull(cu);
    }
}
