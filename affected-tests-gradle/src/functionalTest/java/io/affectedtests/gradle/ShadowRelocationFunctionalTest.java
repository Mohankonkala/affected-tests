package io.affectedtests.gradle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the post-shading namespace shape of the published plugin JAR — the
 * ZIP-walk verification gate from {@code docs/PHASE-2-KOTLIN-AST.md} §5.
 *
 * <p>Introduced in PR #3 of issue #76 (Phase 2 Kotlin AST). Pre-PR-3 the
 * shaded namespace ({@code io.affectedtests.shadow.jgit /
 * io.affectedtests.shadow.javaparser / io.affectedtests.shadow.slf4j}) was
 * trusted-by-construction with no explicit verification; PR #3's Kotlin
 * compiler embeddable + bundled IntelliJ Platform / ASM / fastutil / jline
 * payload forced a real audit of the shadow JAR contents because:
 *
 * <ul>
 *   <li><strong>Missing a relocate rule</strong> on a sub-namespace of the
 *       embeddable (e.g. {@code org.jetbrains.org.objectweb.asm} —
 *       JetBrains' pre-relocated ASM, {@code _COROUTINE} — coroutine stack
 *       markers) leaks adopter-classpath conflict surface that's invisible
 *       at compile time. Pre-PR-3 we discovered four such leaks from
 *       JetBrains pre-relocations alone via this test's first run; without
 *       the test, adopters would have surfaced them later as production
 *       classpath collisions.</li>
 *   <li><strong>Misordered relocate rules</strong> (Shadow 9.x evaluates
 *       relocators in DSL-insertion order with first-match-wins semantics,
 *       per {@code RelocationContext.kt} at the pinned 9.4.1 tag) silently
 *       double-relocate already-relocated nested namespaces. Putting
 *       {@code org.jetbrains.kotlin} before its more-specific
 *       {@code org.jetbrains.kotlin.com.intellij} sibling produces
 *       {@code io.affectedtests.shadow.kotlin.com.intellij} (correct shape)
 *       only by coincidence — change the order and the namespace warps to
 *       {@code io.affectedtests.shadow.kotlin.io.affectedtests.shadow.kotlin.com.intellij}
 *       (pathological). This test asserts the shape, not the rules.</li>
 *   <li><strong>{@code ServiceFileTransformer} content-rewrite gaps</strong>
 *       — {@link Shadow}'s {@code mergeServiceFiles} renames service files
 *       AND rewrites their bodies to track relocations, but only for
 *       services whose name matches a relocated package. A service file
 *       under a non-relocated namespace whose body references a relocated
 *       FQN would surface as a {@link java.util.ServiceConfigurationError}
 *       on first parse. This test grep-checks every entry under
 *       {@code META-INF/services/} for {@code org.jetbrains.kotlin.*}
 *       references the rewriter missed.</li>
 * </ul>
 *
 * <p>The companion runtime gate (the end-to-end parse-gate functional test
 * that bootstraps {@code KotlinCoreEnvironment} from the shaded JAR in an
 * isolated classloader, parses a representative Kotlin fixture, and asserts
 * the bootstrap doesn't throw) catches the case where everything is
 * correctly relocated but the JAR-size pruning ({@code exclude} rules in
 * {@code build.gradle}) cut a class the bootstrap actually needs. ZIP-walk
 * is purely structural; runtime is purely behavioural; both gates are
 * required for the rollout flag's documented "all .kt files parse, no
 * silent drops" contract to hold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowRelocationFunctionalTest {

    /**
     * 60 MB hard ceiling on the shadow JAR. Plan §5 documents the realistic
     * post-shading target as 40-50 MB (the original 28-35 MB target was
     * wishful — minimize cannot prune the embeddable's reflection / service-
     * loader / extension-XML reachability graph). Anything above 60 MB
     * trips the empirical Plugin Portal upload-failure cluster around
     * ~90 MB; the 60 MB ceiling is the conservative shoulder under that
     * cluster.
     */
    private static final long HARD_SIZE_CEILING_BYTES = 60L * 1024 * 1024;

    /**
     * Top-level package roots that MUST NOT appear in the shadow JAR after
     * relocation. Each entry is rooted in a class namespace that an adopter
     * might also ship (Kotlin compiler against a Kotlin-using project, ASM
     * against a Lombok-using project, kotlinx-coroutines against any
     * adopter that uses it — the collision class for every entry below is
     * the same adopter-classpath-conflict failure mode).
     */
    private static final List<String> FORBIDDEN_TOPLEVEL_ROOTS = List.of(
            "kotlin/",
            "kotlinx/",
            "org/jetbrains/kotlin/",
            "org/jetbrains/concurrency/",
            "org/jetbrains/org/",
            "org/jetbrains/annotations/",
            "org/intellij/lang/",
            "com/intellij/",
            "gnu/trove/",
            "_COROUTINE/"
    );

    /**
     * Representative classes pinned at their post-relocation paths. Each
     * one is reached by the parse pipeline ({@code KtFile} = the parser's
     * primary output type; {@code KotlinCoreEnvironment} = the bootstrap
     * entry point) — verifying their post-shading address proves the
     * relocator did the right thing for the classes the runtime gate
     * actually loads.
     */
    private static final List<String> RELOCATED_PRESENT = List.of(
            "io/affectedtests/shadow/kotlin/psi/KtFile.class",
            "io/affectedtests/shadow/kotlin/cli/jvm/compiler/KotlinCoreEnvironment.class",
            "io/affectedtests/shadow/jgit/api/Git.class",
            "io/affectedtests/shadow/javaparser/JavaParser.class",
            "io/affectedtests/shadow/slf4j/Logger.class"
    );

    private Path shadowJar;

    @BeforeAll
    void resolveShadowJar() {
        String path = System.getProperty("affectedTests.shadowJar.path");
        assertNotNull(path,
                "affectedTests.shadowJar.path system property must be set by the "
                        + "Gradle build (see :affected-tests-gradle:functionalTest "
                        + "configuration in build.gradle). Running this test class "
                        + "without that wiring leaves the test pointing at no JAR "
                        + "and would always pass vacuously.");
        shadowJar = Path.of(path);
        assertTrue(Files.exists(shadowJar),
                "Shadow JAR does not exist at " + shadowJar + ". Did the "
                        + "shadowJar task fail or get up-to-date-skipped?");
    }

    @Test
    void shadowJarIsUnderHardSizeCeiling() throws IOException {
        long size = Files.size(shadowJar);
        assertTrue(size <= HARD_SIZE_CEILING_BYTES,
                String.format(
                        "Shadow JAR is %.1f MB which exceeds the %.0f MB hard "
                                + "ceiling. The minimize { } block + path-based "
                                + "exclude rules in build.gradle are the levers; "
                                + "see docs/PHASE-2-KOTLIN-AST.md §5 'JAR Size "
                                + "Budget' for the rationale and remediation steps.",
                        size / (1024.0 * 1024.0),
                        HARD_SIZE_CEILING_BYTES / (1024.0 * 1024.0)));
    }

    @Test
    void shadowJarHasNoLeakedTopLevelKotlinNamespace() throws IOException {
        List<String> leaks = collectEntries(entry -> {
            String name = entry.getName();
            for (String root : FORBIDDEN_TOPLEVEL_ROOTS) {
                if (name.startsWith(root)) return true;
            }
            return false;
        });
        assertTrue(leaks.isEmpty(),
                "Shadow JAR leaks top-level entries that should have been "
                        + "relocated. Each leak is an adopter-classpath-conflict "
                        + "vector. Update the relocate rules in build.gradle to "
                        + "cover every leaked root, then re-run this test. "
                        + "Leaks (first 20):\n  - "
                        + String.join("\n  - ", leaks.subList(0, Math.min(leaks.size(), 20))));
    }

    @Test
    void relocatedClassesPresentAtExpectedPostShadingAddress() throws IOException {
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = new ZipFile(shadowJar.toFile())) {
            for (String pinned : RELOCATED_PRESENT) {
                if (zip.getEntry(pinned) == null) {
                    missing.add(pinned);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "Pinned post-shading representatives are missing from the "
                        + "shadow JAR. Either the relocate rule for that "
                        + "namespace changed (verify the build.gradle "
                        + "relocate target matches the test's expected path) "
                        + "or a new path-exclude rule pruned a class the "
                        + "runtime gate actually loads (narrow the exclude). "
                        + "Missing:\n  - "
                        + String.join("\n  - ", missing));
    }

    @Test
    void serviceFilesAreRewrittenToShadedNamespace() throws IOException {
        List<String> bodyLeaks = new ArrayList<>();
        List<String> nameLeaks = new ArrayList<>();
        try (ZipFile zip = new ZipFile(shadowJar.toFile())) {
            var entries = Collections.list(zip.entries());
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!name.startsWith("META-INF/services/")) continue;
                if (entry.isDirectory()) continue;
                if (name.contains("org.jetbrains.kotlin")
                        || name.contains("kotlinx.coroutines")) {
                    nameLeaks.add(name);
                }
                String body;
                try (var in = zip.getInputStream(entry)) {
                    body = new String(in.readAllBytes());
                }
                for (String line : body.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    // Match every relocate source prefix from the
                    // build.gradle shadowJar block — the original
                    // three-prefix list ({@code org.jetbrains.kotlin},
                    // {@code kotlinx.coroutines}, {@code kotlin.})
                    // missed five other relocated namespaces, any of
                    // which can land verbatim in a service-file body
                    // and surface as
                    // {@link java.util.ServiceConfigurationError}
                    // at adopter runtime. Mirror the relocator
                    // input set 1:1 so adding a relocate rule
                    // forces a same-PR update here.
                    boolean leaked =
                            trimmed.startsWith("org.jetbrains.kotlin")
                                    || trimmed.startsWith("kotlinx.coroutines")
                                    || trimmed.startsWith("kotlinx.")
                                    || trimmed.startsWith("kotlin.")
                                    || trimmed.startsWith("_COROUTINE.")
                                    || trimmed.startsWith("gnu.trove.")
                                    || trimmed.startsWith("org.jetbrains.org.")
                                    || trimmed.startsWith("org.jetbrains.concurrency.")
                                    || trimmed.startsWith("org.jetbrains.annotations.")
                                    || trimmed.startsWith("org.intellij.lang.")
                                    || trimmed.startsWith("com.intellij.");
                    if (leaked) {
                        bodyLeaks.add(name + "  →  " + trimmed);
                    }
                }
            }
        }
        assertTrue(nameLeaks.isEmpty(),
                "Service file basenames still under unrelocated namespace. "
                        + "ServiceFileTransformer (Shadow's mergeServiceFiles "
                        + "pipeline) should have renamed every entry. Leaks:\n  - "
                        + String.join("\n  - ", nameLeaks));
        assertTrue(bodyLeaks.isEmpty(),
                "Service file bodies still reference unrelocated FQNs. "
                        + "ServiceFileTransformer should have rewritten every "
                        + "line through the active relocation map; an entry "
                        + "here is a ServiceConfigurationError waiting to "
                        + "surface at runtime. Leaks (first 20):\n  - "
                        + String.join("\n  - ",
                                bodyLeaks.subList(0, Math.min(bodyLeaks.size(), 20))));
    }

    private List<String> collectEntries(java.util.function.Predicate<ZipEntry> filter)
            throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(shadowJar.toFile())) {
            var entries = Collections.list(zip.entries());
            for (ZipEntry entry : entries) {
                if (filter.test(entry)) out.add(entry.getName());
            }
        }
        return out;
    }
}
