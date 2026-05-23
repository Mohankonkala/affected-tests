package io.affectedtests.core.discovery;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link KotlinLanguageParser} contract — the {@link
 * LanguageParser} implementation registered behind the
 * {@code .kt} extension when
 * {@link io.affectedtests.core.config.AffectedTestsConfig#kotlinEnabled()}
 * is on.
 *
 * <p>Introduced in PR #3 of issue #76 (Phase 2 Kotlin AST). The
 * coverage mirrors {@link JavaLanguageParserTest}'s shape so a
 * future contributor scanning the test suite for "what does a
 * language parser owe its callers?" sees the contract for both
 * languages stated in the same form: extension name, valid-source
 * → {@link FileMetadata} round-trip, primary-type name (including
 * the synthetic {@code <basename>Kt} for class-less files),
 * imports (regular + wildcard), nested type declarations
 * (companions + inner classes), and parse-failure / missing-file /
 * post-close fall-through to {@code null}.
 *
 * <p>Single bootstrap shared across the test class via
 * {@link TestInstance.Lifecycle#PER_CLASS} + {@link BeforeAll} /
 * {@link AfterAll}: the embeddable's
 * {@link org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment}
 * costs ~2-5s to construct and pins a static MockApplication;
 * bootstrap-once-per-class keeps the suite under a few seconds and
 * sidesteps the "fresh KotlinCoreEnvironment per test" question
 * (which is a real risk on the embeddable — the static
 * MockApplication slot doesn't always cleanly accept a
 * re-registration after disposal).
 *
 * <p>Lifecycle-specific tests (idempotent close, parse-after-close
 * fail-closed) use their own throwaway {@link KotlinLanguageParser}
 * instance — the shared one stays untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinLanguageParserTest {

    @TempDir
    Path tmp;

    private KotlinLanguageParser parser;

    @BeforeAll
    void bootstrapShared() {
        parser = new KotlinLanguageParser();
    }

    @AfterAll
    void disposeShared() {
        if (parser != null) parser.close();
    }

    @Test
    void extensionIsCanonicalLowercaseDotKt() {
        assertEquals(".kt", parser.extension());
    }

    @Test
    void parseOrWarnReturnsFileMetadataForClassFile() throws Exception {
        Path file = tmp.resolve("Greeter.kt");
        Files.writeString(file, """
                package com.example

                import kotlin.collections.List

                class Greeter {
                    fun greet(): List<String> = listOf("hi")
                }
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md, "Valid Kotlin must yield a FileMetadata, not null");
        assertEquals("com.example", md.packageName());
        assertEquals("Greeter", md.primaryTypeName(),
                "primaryTypeName tracks the first top-level class in the file, "
                        + "matching the Kotlin compiler's compiled-class shape.");
        assertEquals(List.of("kotlin.collections.List"),
                md.imports().stream().map(FileMetadata.Import::name).toList());
        assertFalse(md.imports().get(0).isStatic(),
                "Kotlin imports never surface as static — Kotlin has no "
                        + "static-import distinction; the bit is reserved for "
                        + "Java's import static syntax. Usage's tier-3 dotted "
                        + "match handles member-level Kotlin imports the same "
                        + "way it handles regular type imports.");
        assertFalse(md.imports().get(0).isAsterisk());
    }

    @Test
    void parseOrWarnSurfacesSyntheticKtClassForFileWithoutTopLevelClass() throws Exception {
        // PR #1's PathToClassMapper emits {@code com.example.UtilKt}
        // for a class-less {@code Util.kt}. Parser side must agree
        // on the same literal — disagreement is the kind of
        // silent-under-selection bug the diff-side / parser-side
        // shape mismatch class produces.
        Path file = tmp.resolve("Util.kt");
        Files.writeString(file, """
                package com.example

                fun helper(): Int = 42
                val constant = "hello"
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertEquals("com.example", md.packageName());
        assertEquals("UtilKt", md.primaryTypeName(),
                "Class-less Kotlin files surface as <basename>Kt — matches "
                        + "the Kotlin compiler's JVM facade synthetic.");
        assertTrue(md.typeDeclarations().isEmpty(),
                "Top-level functions / properties don't produce TypeDecls "
                        + "— only KtClassOrObjects do.");
    }

    @Test
    void parseOrWarnSurfacesSyntheticKtClassPreservingBasenameCase() throws Exception {
        // The Kotlin compiler appends {@code Kt} verbatim — it does
        // not capitalise the basename. {@code util.kt} (lowercase)
        // → {@code utilKt}. Idiomatic Kotlin caps the file stem so
        // this surfaces rarely in practice, but the diff side
        // ({@link io.affectedtests.core.mapping.PathToClassMapper})
        // also preserves verbatim and a mismatch here would silently
        // miss tests on lowercase-stem files.
        Path file = tmp.resolve("util.kt");
        Files.writeString(file, """
                package com.example

                fun helper(): Int = 42
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertEquals("utilKt", md.primaryTypeName());
    }

    @Test
    void parseOrWarnSurfacesEmptyPackageForFileWithoutPackageDecl() throws Exception {
        Path file = tmp.resolve("Loose.kt");
        Files.writeString(file, """
                fun helper(): Int = 42
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertEquals("", md.packageName(),
                "Files without a package declaration surface as the empty "
                        + "package — matches the Java-side contract from "
                        + "FileMetadata's constructor (null package becomes "
                        + "empty string).");
        assertEquals("LooseKt", md.primaryTypeName());
    }

    @Test
    void parseOrWarnExposesWildcardImports() throws Exception {
        Path file = tmp.resolve("Wide.kt");
        Files.writeString(file, """
                package com.example

                import com.other.*
                import com.specific.Thing

                class Wide
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertEquals(2, md.imports().size());
        FileMetadata.Import wildcard = md.imports().get(0);
        assertEquals("com.other", wildcard.name());
        assertTrue(wildcard.isAsterisk(),
                "Kotlin {@code import com.other.*} mirrors Java's wildcard "
                        + "import semantics; isAsterisk must be true so "
                        + "Usage's wildcard-package tier picks it up.");
        FileMetadata.Import specific = md.imports().get(1);
        assertEquals("com.specific.Thing", specific.name());
        assertFalse(specific.isAsterisk());
    }

    @Test
    void parseOrWarnSurfacesNestedAndCompanionTypeDeclarations() throws Exception {
        // ImplementationStrategy's fixpoint scan walks every
        // TypeDecl in the cached metadata to follow subtype edges.
        // Companion objects + nested classes therefore must show
        // up so a {@code class Outer { class Inner : Base }} edge
        // catches a test that touches {@code Base}.
        Path file = tmp.resolve("Outer.kt");
        Files.writeString(file, """
                package com.example

                open class Base
                interface Marker

                class Outer : Base(), Marker {
                    class Inner : Base()
                    companion object Named {
                        const val K = 1
                    }
                }
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertEquals("Base", md.primaryTypeName(),
                "First top-level class wins — Base is declared first.");
        List<String> declNames = md.typeDeclarations().stream()
                .map(FileMetadata.TypeDecl::simpleName)
                .toList();
        assertTrue(declNames.contains("Base"));
        assertTrue(declNames.contains("Marker"));
        assertTrue(declNames.contains("Outer"));
        assertTrue(declNames.contains("Inner"),
                "Nested classes must surface — Implementation walks "
                        + "TypeDecls to follow subtype edges across a file's "
                        + "type tree, not just the top-level types.");
        assertTrue(declNames.contains("Named"),
                "Named companion objects surface as their own TypeDecl "
                        + "— the simple name is what Implementation matches "
                        + "against. (Anonymous {@code companion object {}} "
                        + "is filtered out separately because the simple "
                        + "name {@code Companion} would over-match every "
                        + "Kotlin file in the codebase.)");

        FileMetadata.TypeDecl outer = findDecl(md, "Outer");
        assertEquals(List.of("Base", "Marker"), outer.supertypeSimpleNames(),
                "Supertype list mirrors source order, simple names only — "
                        + "Implementation matches by simple name and resolves "
                        + "to FQN via the same package + import lookup it "
                        + "uses for Java.");

        FileMetadata.TypeDecl inner = findDecl(md, "Inner");
        assertEquals(List.of("Base"), inner.supertypeSimpleNames());
    }

    @Test
    void parseOrWarnSkipsAnonymousCompanionObjects() throws Exception {
        // Anonymous companions ({@code companion object {}}) compile
        // to {@code Outer$Companion} — the simple name {@code
        // Companion} is generic enough that surfacing it as a
        // TypeDecl would generate spurious matches against every
        // Kotlin file's companion. The class skips them and lets
        // their declarations recurse normally (so a nested class
        // inside an anonymous companion still surfaces).
        Path file = tmp.resolve("WithAnonCompanion.kt");
        Files.writeString(file, """
                package com.example

                class Holder {
                    companion object {
                        class Nested
                    }
                }
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        List<String> declNames = md.typeDeclarations().stream()
                .map(FileMetadata.TypeDecl::simpleName)
                .toList();
        assertTrue(declNames.contains("Holder"));
        assertFalse(declNames.contains("Companion"),
                "Anonymous companion must NOT surface as TypeDecl — its "
                        + "JVM compiled name is the over-generic literal "
                        + "{@code Companion} which would over-match.");
        assertTrue(declNames.contains("Nested"),
                "Declarations inside an anonymous companion still recurse "
                        + "— the skip applies only to the companion's own "
                        + "TypeDecl entry, not to its body.");
    }

    @Test
    void parseOrWarnHarvestsTypeReferencesAcrossPositions() throws Exception {
        // Usage matches against typeRefSimpleNames + typeRefDottedNames.
        // The PSI walk must collect every KtTypeReference in the file
        // — return types, parameter types, supertype-list types,
        // generic args. Pin the union shape so a future refactor
        // that narrows the harvest accidentally drops one position
        // (e.g. only top-level functions, missing nested) goes red.
        Path file = tmp.resolve("References.kt");
        Files.writeString(file, """
                package com.example

                import com.foo.Foo
                import com.bar.Bar

                fun f(arg: Foo): Bar = TODO()

                class Holder {
                    val field: Foo? = null
                    fun nested(): Map<String, Bar> = emptyMap()
                }
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertTrue(md.typeRefSimpleNames().contains("Foo"));
        assertTrue(md.typeRefSimpleNames().contains("Bar"));
        assertTrue(md.typeRefSimpleNames().contains("String"));
        assertTrue(md.typeRefSimpleNames().contains("Map"),
                "Generic outer types must surface as simple names — Map's "
                        + "type args don't replace it in the harvest, they "
                        + "extend it.");
    }

    @Test
    void parseOrWarnHarvestsFullyQualifiedInlineReferences() throws Exception {
        // Usage's tier-3 fully-qualified inline match consumes
        // typeRefDottedNames. Kotlin's KtUserType qualifier chain
        // mirrors JavaParser's getNameWithScope() — pin the
        // dotted-name harvest so the tier-3 branch fires for
        // {@code val x: com.example.foo.Bar = ...}.
        Path file = tmp.resolve("Inline.kt");
        Files.writeString(file, """
                package com.example

                class Holder {
                    val x: com.example.foo.Bar? = null
                }
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertTrue(md.typeRefDottedNames().contains("com.example.foo.Bar"),
                "Inline FQN type reference must surface in the dotted-name "
                        + "harvest. typeRefSimpleNames also includes Bar (the "
                        + "leaf), but tier 3 needs the full dotted form.");
        assertTrue(md.typeRefSimpleNames().contains("Bar"),
                "Leaf simple name still surfaces alongside the dotted form "
                        + "— matches JavaParser's two-channel harvest where "
                        + "the same reference contributes to both sets.");
    }

    @Test
    void parseOrWarnFlagsFileLevelJvmNameAnnotation() throws Exception {
        // Phase 2 surfaces @file:JvmName via --explain but does
        // NOT honour the override (the synthetic name stays
        // <basename>Kt, not the jvmName argument). The hint makes
        // adopters aware of the documented limitation.
        Path file = tmp.resolve("Annotated.kt");
        Files.writeString(file, """
                @file:JvmName("CustomName")
                package com.example

                fun helper(): Int = 42
                """);

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNotNull(md);
        assertEquals("AnnotatedKt", md.primaryTypeName(),
                "@file:JvmName is documented as not honoured in Phase 2 — "
                        + "the synthetic class name keeps the <basename>Kt "
                        + "shape so a future PR that flips the behaviour "
                        + "can introduce a deliberate cache invalidation.");
        assertTrue(parser.hasFileLevelJvmNameAnnotation(file),
                "File must be flagged on the parser's side-channel so the "
                        + "engine's --explain output can surface the limitation "
                        + "to adopters.");
    }

    @Test
    void parseOrWarnReturnsNullOnMissingFile() {
        Path file = tmp.resolve("does-not-exist.kt");
        // Don't create the file — simulate a git-rm race or a
        // diff-side path that no longer exists on disk.

        FileMetadata md = parser.parseOrWarn(file, "test");

        assertNull(md, "Missing files must surface as null — same posture "
                + "as malformed source. Mirrors the Java parser's "
                + "I/O-race handling.");
    }

    @Test
    void parseOrWarnReturnsNullAfterCloseAndDoesNotBootstrap() throws Exception {
        // Defence-in-depth path on the parser: a strategy or test
        // that holds onto the registry past engine shutdown gets a
        // clear log hint instead of an obscure
        // "Disposable already disposed" stack trace from inside
        // the embeddable. The post-close parseOrWarn must return
        // null without bootstrapping a fresh environment — that
        // would defeat the disposal contract.
        KotlinLanguageParser throwaway = new KotlinLanguageParser();
        throwaway.close();
        Path file = tmp.resolve("AfterClose.kt");
        Files.writeString(file, "package com.example\nfun f() = 1\n");

        FileMetadata md = throwaway.parseOrWarn(file, "test");

        assertNull(md);
    }

    @Test
    void closeIsIdempotent() {
        // Pin the disposal contract from LanguageParser#close():
        // calling close() twice (or from two threads) is safe.
        KotlinLanguageParser throwaway = new KotlinLanguageParser();
        throwaway.close();
        throwaway.close();
        // No assertion needed — the test passes if neither call
        // throws. If a future refactor accidentally non-idempotent
        // the disposal, the second {@code Disposer.dispose} call
        // throws and this test goes red.
    }

    @Test
    void closeWithoutEverParsingIsSafe() {
        // The LanguageParsers.close() loop must walk every
        // registered parser even when none of them ever parsed —
        // a common shape for tests that build a registry, never
        // hit it, and let try-with-resources close it. The Kotlin
        // parser must short-circuit cleanly without bootstrapping
        // an environment just to dispose it.
        KotlinLanguageParser throwaway = new KotlinLanguageParser();
        throwaway.close();
        assertFalse(throwaway.hasFileLevelJvmNameAnnotation(Path.of("never-seen.kt")),
                "Side-channel state stays empty on a never-bootstrapped "
                        + "parser — calling the accessor must not NPE.");
    }

    @Test
    void syntheticFileFacadeNameAppendsKtVerbatim() {
        // Direct unit-level pin for the helper that the diff side
        // and the parser side both depend on.
        // {@code Util.kt} → {@code UtilKt}, {@code util.kt} →
        // {@code utilKt}, {@code MyKt.kt} → {@code MyKtKt} (yes,
        // really — the compiler doesn't dedupe the trailing Kt;
        // a file literally named {@code MyKt.kt} compiles to
        // {@code MyKtKt}).
        assertEquals("UtilKt", KotlinLanguageParser.syntheticFileFacadeName("Util.kt"));
        assertEquals("utilKt", KotlinLanguageParser.syntheticFileFacadeName("util.kt"));
        assertEquals("MyKtKt", KotlinLanguageParser.syntheticFileFacadeName("MyKt.kt"));
        assertEquals("NoExtensionKt",
                KotlinLanguageParser.syntheticFileFacadeName("NoExtension"),
                "Extensionless basename (defensive: a path that lost its "
                        + "extension upstream) still gets the Kt suffix — "
                        + "it's the Kotlin compiler's contract, not a "
                        + ".kt-specific shape.");
    }

    private static FileMetadata.TypeDecl findDecl(FileMetadata md, String name) {
        return md.typeDeclarations().stream()
                .filter(td -> td.simpleName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "TypeDecl '" + name + "' not found in " + md.typeDeclarations()));
    }
}
