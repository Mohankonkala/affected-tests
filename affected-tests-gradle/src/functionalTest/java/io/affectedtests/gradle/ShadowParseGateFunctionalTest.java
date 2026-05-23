package io.affectedtests.gradle;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime gate for the post-shading Kotlin parser path. Loads the published
 * shadow JAR in an isolated child-first classloader (parent = JDK bootstrap)
 * and reflectively invokes
 * {@code io.affectedtests.core.discovery.KotlinLanguageParser#parseOrWarn} to
 * confirm that the JAR-size-cutting {@code exclude 'org/jetbrains/kotlin/...'}
 * patterns in {@code build.gradle} did not prune a class the embeddable
 * actually needs at bootstrap or PSI-walk time.
 *
 * <p>Companion to {@link ShadowRelocationFunctionalTest} (the ZIP-walk
 * structural gate). Both gates are required: ZIP-walk catches missing /
 * misordered relocate rules; the parse gate catches over-aggressive path
 * excludes and the
 * {@code IllegalArgumentException: Missing extension point: ...} class of
 * failure that surfaces only when {@code KotlinCoreEnvironment.createForProduction}
 * boots its IntelliJ-Platform extension-point registry against an
 * over-pruned JAR.
 *
 * <p>Why a child-first classloader? The functional-test JVM already has the
 * pre-shading {@code kotlin-compiler-embeddable} on its testRuntime
 * classpath (added transitively by the Cucumber + JGit deps the gradle
 * module declares). A parent-first loader against the shadow JAR would
 * resolve {@code KotlinLanguageParser} from the test classpath (the
 * pre-shading copy whose bytecode references {@code org.jetbrains.kotlin.*})
 * and miss the rewritten copy entirely — defeating the gate. Passing
 * {@code null} as the parent causes {@link URLClassLoader} to fall back to
 * the implicit bootstrap classloader, which only sees the JDK; everything
 * application-level resolves from the shadow JAR.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowParseGateFunctionalTest {

    @TempDir
    Path tmp;

    private URLClassLoader shadedLoader;

    @BeforeAll
    void openShadedClassloader() throws Exception {
        String path = System.getProperty("affectedTests.shadowJar.path");
        assertNotNull(path, "affectedTests.shadowJar.path system property must "
                + "be set by build.gradle (see :functionalTest task wiring).");
        URL[] urls = { Path.of(path).toUri().toURL() };
        shadedLoader = new URLClassLoader(urls, null);
    }

    @AfterAll
    void closeShadedClassloader() throws Exception {
        if (shadedLoader != null) shadedLoader.close();
    }

    @Test
    void shadedParserBootstrapsAndParsesKotlinFile() throws Exception {
        // Fixture exercises the four shapes that historically trip up
        // shaded-Kotlin-compiler builds: top-level fun (synthesised JVM
        // facade class via <basename>Kt), a real class declaration with
        // supertype, a wildcard import (BuiltInsLoader resource lookup
        // path), and a kotlin.collections reference (verifies the kotlin
        // stdlib relocation didn't break stdlib-FQN resolution as
        // string-literals during PSI parse).
        Path file = tmp.resolve("ParseGate.kt");
        Files.writeString(file, """
                package com.example

                import com.other.*
                import kotlin.collections.List

                open class Base
                class ParseGate : Base() {
                    fun greet(): List<String> = listOf("hi")
                    companion object {
                        const val K = 1
                    }
                }
                """);

        Class<?> parserClass = Class.forName(
                "io.affectedtests.core.discovery.KotlinLanguageParser",
                true, shadedLoader);
        Constructor<?> ctor = parserClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object parser;
        try {
            parser = ctor.newInstance();
        } catch (Throwable t) {
            // Constructor failure is a class-graph-incomplete signal.
            // The `Disposable` parent + `KotlinCoreEnvironment` lazy
            // bootstrap means construction itself is cheap; if this
            // throws it means even the parser's class file references
            // a missing dep — narrow a path exclude in build.gradle.
            fail("KotlinLanguageParser constructor failed against the shaded "
                    + "JAR — likely a path-exclude in build.gradle pruned a "
                    + "class our parser source references. Cause: " + t, t);
            return;
        }

        // close() is idempotent; defer a try-finally around the parse so
        // the embeddable's MockApplication + extension-point registry get
        // disposed even if the parse throws. {@code setAccessible(true)}
        // on every reflectively-looked-up method is required because
        // {@code KotlinLanguageParser} is package-private — the methods
        // themselves are public, but reflection's accessibility check
        // sees the package-private declaring class first and blocks the
        // invoke unless we override.
        Method closeMethod = parserClass.getMethod("close");
        closeMethod.setAccessible(true);
        Method parseOrWarn = parserClass.getDeclaredMethod(
                "parseOrWarn", Path.class, String.class);
        parseOrWarn.setAccessible(true);

        Object metadata;
        try {
            metadata = parseOrWarn.invoke(parser, file, "shadow-parse-gate");
        } catch (Throwable t) {
            fail("KotlinLanguageParser.parseOrWarn threw against the shaded "
                    + "JAR — typical cause is the embeddable's bootstrap "
                    + "needing an extension point or service descriptor that "
                    + "a path-exclude removed. Symptoms surface here as "
                    + "IllegalArgumentException 'Missing extension point' / "
                    + "ServiceConfigurationError / NoClassDefFoundError. "
                    + "Cause: " + t, t);
            return;
        } finally {
            closeMethod.invoke(parser);
        }

        assertNotNull(metadata,
                "Shaded parser returned null FileMetadata for a well-formed "
                        + "Kotlin fixture — bootstrap silently failed (the "
                        + "single-WARN posture short-circuits the parse and "
                        + "returns null; check the build log for the WARN "
                        + "line that names the bootstrap failure cause).");

        // Reach into FileMetadata reflectively — the record's accessor
        // methods (packageName, primaryTypeName, imports, ...) are stable
        // public surface; their existence is asserted by the unshaded
        // {@link io.affectedtests.core.discovery.KotlinLanguageParserTest}
        // and re-asserted here against the shaded copy.
        Class<?> fileMetadataClass = metadata.getClass();
        Object packageName = fileMetadataClass.getMethod("packageName").invoke(metadata);
        Object primaryTypeName = fileMetadataClass.getMethod("primaryTypeName").invoke(metadata);
        java.util.List<?> imports =
                (java.util.List<?>) fileMetadataClass.getMethod("imports").invoke(metadata);
        java.util.Set<?> typeRefSimple = (java.util.Set<?>) fileMetadataClass
                .getMethod("typeRefSimpleNames").invoke(metadata);
        java.util.List<?> typeDecls = (java.util.List<?>) fileMetadataClass
                .getMethod("typeDeclarations").invoke(metadata);

        assertEquals("com.example", packageName);
        assertEquals("Base", primaryTypeName,
                "First top-level class wins — Base is declared first.");
        assertEquals(2, imports.size(),
                "Both imports must surface — wildcard + named — even after "
                        + "BuiltInsLoader resource paths got rewritten by the "
                        + "kotlin-stdlib relocate.");
        assertTrue(typeRefSimple.contains("List"),
                "kotlin.collections.List reference must resolve — confirms "
                        + "the stdlib relocate didn't break PSI's string-form "
                        + "FQN handling.");
        assertTrue(((java.util.List<String>) typeDecls.stream()
                        .map(td -> {
                            try {
                                return td.getClass().getMethod("simpleName")
                                        .invoke(td).toString();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toList())
                        .containsAll(java.util.List.of("Base", "ParseGate")),
                "Top-level Base + ParseGate must both surface in typeDeclarations "
                        + "— Implementation strategy walks them to follow subtype "
                        + "edges.");
    }
}
