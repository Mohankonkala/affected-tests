package io.affectedtests.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-discovers Gradle source sets across the root project and every
 * subproject that has a {@code java-base}-derived plugin applied, then
 * surfaces the resulting suffix lists for {@code sourceDirs}, {@code testDirs},
 * and {@code testTaskNames}.
 *
 * <p>The classification heuristic is "does a {@link Test}-typed task
 * point at this source set's {@code output.classesDirs}?". This matches
 * Gradle's own {@code JvmTestSuitePlugin} convention and survives:
 *
 * <ul>
 *   <li>Custom-named test source sets (e.g. {@code integrationTest},
 *       {@code e2eTest}) — every {@code Test} task gets paired back to
 *       the source set whose {@code output.classesDirs} matches its
 *       {@code testClassesDirs}.</li>
 *   <li>Fixture source sets (Gradle's {@code java-test-fixtures}) and
 *       custom internal-only source sets — these don't have a paired
 *       {@code Test} task so they fall into the production bucket,
 *       which is the correct destination for helpers consumed by
 *       tests.</li>
 *   <li>Custom layouts (e.g. {@code src/cool/java} for the {@code main}
 *       source set) — the suffix is computed by relativising every
 *       {@code srcDir} against its owning project, so any Java-plugin
 *       layout the user has wired up gets picked up verbatim.</li>
 * </ul>
 *
 * <p>The output is intentionally <em>only</em> a list of relative-path
 * suffixes (e.g. {@code "src/integrationTest/java"}) — that's the same
 * shape {@link io.affectedtests.core.discovery.SourceFileScanner} expects.
 * The scanner already walks the project tree at any depth so the
 * suffixes work uniformly across single-module and multi-module
 * adopters; auto-discovery just builds the union of distinct suffixes
 * across every subproject.
 *
 * <p>This class is package-private and is constructed via the
 * {@link #from(Project)} factory so unit tests can drive it with
 * {@link org.gradle.testfixtures.ProjectBuilder} fixtures and assert
 * on the lists without spinning up a full Gradle test runtime.
 */
final class SourceSetAutoDiscovery {

    private final List<String> sourceDirs;
    private final List<String> testDirs;
    private final List<String> testTaskNames;

    private SourceSetAutoDiscovery(List<String> sourceDirs,
                                   List<String> testDirs,
                                   List<String> testTaskNames) {
        this.sourceDirs = List.copyOf(sourceDirs);
        this.testDirs = List.copyOf(testDirs);
        this.testTaskNames = List.copyOf(testTaskNames);
    }

    /**
     * Walks the supplied project's {@code allprojects} and collects
     * unique source/test directory suffixes plus unique
     * {@code Test}-task names. Idempotent — call this exactly once,
     * inside {@code project.afterEvaluate}, so every subproject's
     * {@code java-base}-derived plugin has already declared its
     * source sets and Test tasks. Calling earlier (in {@code apply()})
     * would race with subprojects' own {@code apply java} blocks and
     * silently drop their source sets from the discovered list.
     */
    static SourceSetAutoDiscovery from(Project rootProject) {
        Set<String> sourceDirSet = new LinkedHashSet<>();
        Set<String> testDirSet = new LinkedHashSet<>();
        Set<String> testTaskNameSet = new LinkedHashSet<>();

        for (Project p : rootProject.getAllprojects()) {
            JavaPluginExtension jpe = p.getExtensions()
                    .findByType(JavaPluginExtension.class);
            if (jpe == null) {
                // Project doesn't apply java-base. Nothing to discover —
                // leave its layout untouched. The plugin will fall
                // through to whatever the parent's testDirs say.
                continue;
            }
            SourceSetContainer sourceSets = jpe.getSourceSets();

            // Map each source set to the Test task that consumes its
            // output classes. We pair via a file-set intersection on
            // `output.classesDirs` so a custom-layout source set still
            // matches even if its name doesn't follow Gradle's
            // convention. Returns the first Test task that matches —
            // a source set wired to multiple Test tasks (rare; usually
            // a JvmTestSuite that's been split) maps to the first one
            // by registration order, which is the same posture the
            // dispatch path takes when an FQN matches multiple tasks.
            Map<String, String> sourceSetNameToTaskName =
                    pairSourceSetsWithTestTasks(p, sourceSets);

            for (SourceSet ss : sourceSets) {
                String taskName = sourceSetNameToTaskName.get(ss.getName());
                boolean isTestSourceSet = taskName != null;

                for (File srcDir : ss.getJava().getSrcDirs()) {
                    String suffix = projectRelativeSuffix(p, srcDir);
                    if (suffix == null) {
                        // The srcDir lives outside the project tree
                        // (rare — usually a generated-source folder
                        // wired in by a plugin). Skip; the user can
                        // opt it in explicitly via sourceDirs/testDirs.
                        continue;
                    }
                    if (isTestSourceSet) {
                        testDirSet.add(suffix);
                    } else {
                        sourceDirSet.add(suffix);
                    }
                }

                if (isTestSourceSet) {
                    testTaskNameSet.add(taskName);
                }
            }
        }

        return new SourceSetAutoDiscovery(
                new ArrayList<>(sourceDirSet),
                new ArrayList<>(testDirSet),
                new ArrayList<>(testTaskNameSet));
    }

    /**
     * Pairs each source set with the {@link Test}-typed task that
     * consumes its {@code output.classesDirs}. The pairing is
     * intentional: matching by name alone would miss adopters who
     * rename a Test task without also renaming its source set, and
     * matching the other direction (every Test task's name back to
     * a same-named source set) would miss test suites whose Test
     * task is named {@code "myCustomCheck"} but whose source set is
     * still {@code "test"}.
     *
     * <p>Resolves {@code FileCollection.getFiles()} eagerly. That's
     * a config-time call, but {@code afterEvaluate} is the soonest
     * we can resolve it without racing the consumer's own source-set
     * declarations, so the cost is unavoidable.
     */
    private static Map<String, String> pairSourceSetsWithTestTasks(
            Project p, SourceSetContainer sourceSets) {
        Map<String, String> result = new LinkedHashMap<>();
        // Pre-resolve each source set's classes-dirs once so we don't
        // re-resolve them per Test task — for a project with N source
        // sets and M Test tasks the naive form is O(N*M) FileCollection
        // resolutions; pre-cache makes it O(N+M) which matters on a
        // monorepo with ~50 modules at config time.
        Map<String, Set<File>> ssNameToClassDirs = new HashMap<>();
        for (SourceSet ss : sourceSets) {
            FileCollection ssClassesDirs = ss.getOutput().getClassesDirs();
            ssNameToClassDirs.put(ss.getName(), ssClassesDirs.getFiles());
        }

        p.getTasks().withType(Test.class).forEach(t -> {
            FileCollection tcd = t.getTestClassesDirs();
            Set<File> tcdFiles = tcd.getFiles();
            if (tcdFiles.isEmpty()) {
                // A Test task with no testClassesDirs configured can't
                // be paired back to any source set deterministically.
                // Fall back to the name-based heuristic so the common
                // case (a homegrown Test task named after its source
                // set) still works.
                if (sourceSets.findByName(t.getName()) != null) {
                    result.putIfAbsent(t.getName(), t.getName());
                }
                return;
            }
            for (Map.Entry<String, Set<File>> entry : ssNameToClassDirs.entrySet()) {
                if (intersects(tcdFiles, entry.getValue())) {
                    // No early exit: a single Test task can be wired
                    // to multiple source sets (the realistic shape is
                    // a JvmTestSuite split across two physical srcDirs)
                    // and we need every paired source set to surface
                    // in testDirs so discovery scans them. The
                    // {@code testTaskNames} list is deduplicated
                    // downstream via the {@code LinkedHashSet}, so
                    // mapping multiple source sets to the same task
                    // name doesn't pollute that list with duplicates.
                    result.putIfAbsent(entry.getKey(), t.getName());
                }
            }
        });
        return result;
    }

    private static boolean intersects(Set<File> a, Set<File> b) {
        // Iterate the smaller set — micro-optimisation that matters
        // when one side is a single-element file collection (the
        // common shape) and the other has many entries.
        Set<File> smaller = a.size() <= b.size() ? a : b;
        Set<File> larger = smaller == a ? b : a;
        for (File f : smaller) {
            if (larger.contains(f)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the suffix path relative to the owning project's
     * {@code projectDir}, normalised to forward slashes so the
     * resulting string is portable across Windows + Unix and
     * round-trips through {@link io.affectedtests.core.discovery.SourceFileScanner}'s
     * suffix-matching logic. Returns {@code null} when the source dir
     * lives outside the project tree (a non-relativisable shape that
     * the scanner can't match anyway).
     */
    private static String projectRelativeSuffix(Project p, File srcDir) {
        Path projectDir = p.getProjectDir().toPath().toAbsolutePath().normalize();
        Path src = srcDir.toPath().toAbsolutePath().normalize();
        if (!src.startsWith(projectDir)) {
            return null;
        }
        Path relative = projectDir.relativize(src);
        if (relative.toString().isEmpty()) {
            return null;
        }
        return relative.toString().replace(File.separatorChar, '/');
    }

    /**
     * Source directory suffixes (relative to project root) discovered
     * across every {@code java-base} subproject's <em>main-like</em>
     * source sets. Empty when no Java plugin is applied anywhere.
     */
    List<String> sourceDirs() {
        return Collections.unmodifiableList(sourceDirs);
    }

    /**
     * Test directory suffixes discovered across every {@code java-base}
     * subproject's source sets that are paired with a {@link Test}-typed
     * task. Empty when no Test task is registered anywhere.
     */
    List<String> testDirs() {
        return Collections.unmodifiableList(testDirs);
    }

    /**
     * Names of every {@link Test}-typed task paired with a source set.
     * Feeds straight into {@code testTaskNames} (issue #48) so the
     * dispatch path can route discovered FQNs to the right task on
     * monorepos with multiple test source sets.
     */
    List<String> testTaskNames() {
        return Collections.unmodifiableList(testTaskNames);
    }

    boolean hasMainDirs() { return !sourceDirs.isEmpty(); }
    boolean hasTestDirs() { return !testDirs.isEmpty(); }
    boolean hasTestTaskNames() { return !testTaskNames.isEmpty(); }
}
