package io.affectedtests.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Gradle plugin that registers the {@code affectedTest} task and the
 * {@code affectedTests} DSL extension.
 *
 * <p>Plugin ID: {@code io.github.vedanthvdev.affectedtests}
 */
public class AffectedTestsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        AffectedTestsExtension extension = project.getExtensions()
                .create("affectedTests", AffectedTestsExtension.class);

        extension.getBaseRef().convention(
                project.getProviders().gradleProperty("affectedTestsBaseRef")
                        .orElse("origin/master")
        );
        // Mirror the baseRef pattern: let callers flip mode from the
        // command line via `-PaffectedTestsMode=local|ci|strict|auto`
        // without editing build.gradle. Useful for adoption experiments
        // ("what would STRICT mode pick on today's HEAD?") and for CI
        // jobs that want to A/B two modes from the same pipeline.
        // DSL-declared `mode = '...'` still wins because Gradle's
        // Property resolution applies explicit sets before conventions;
        // when both the DSL and the -P are absent, no value is
        // present and the core config falls through to AUTO — same as
        // before this convention existed.
        //
        // Empty/whitespace-only values are coerced to absent: a CI
        // template that always emits `-PaffectedTestsMode=$MODE` with
        // an unset $MODE would otherwise land "" on the convention and
        // fail parseMode with "Unknown affectedTests.mode ''". Trimming
        // + filtering here keeps the happy path working ("no value ==
        // no override") and the error wording targeted at actual typos.
        extension.getMode().convention(
                project.getProviders().gradleProperty("affectedTestsMode")
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
        );
        // COMMITTED-ONLY by default: the plugin's whole job is "what
        // tests does this MR touch?", and the MR is the committed diff
        // — not the dev's WIP. Matching this default to the CI reality
        // means a local `./gradlew affectedTest` run picks the same
        // tests CI will run on the same HEAD, and two runs on the same
        // commit are deterministic. Adopters who iterate on WIP tests
        // flip these back to `true` in their build.gradle; the plugin
        // never silently expands the diff boundary.
        extension.getIncludeUncommitted().convention(false);
        extension.getIncludeStaged().convention(false);
        extension.getStrategies().convention(List.of("naming", "usage", "impl", "transitive"));
        extension.getTransitiveDepth().convention(4);
        extension.getTestSuffixes().convention(List.of("Test", "IT", "ITTest", "IntegrationTest"));
        extension.getSourceDirs().convention(List.of("src/main/java"));
        extension.getTestDirs().convention(List.of("src/test/java"));
        extension.getTestTaskNames().convention(List.of("test"));
        // No convention for ignorePaths: an empty Gradle provider is how
        // we signal "let the core config builder pick the default list".
        // Setting a convention here would stop callers who want to
        // explicitly wipe the default list with an empty list, and
        // would also pin the list shape to this file rather than to
        // the core {@code AffectedTestsConfig.Builder.DEFAULT_IGNORE_PATHS}.
        extension.getIncludeImplementationTests().convention(true);
        extension.getImplementationNaming().convention(List.of("Impl", "Default"));

        Project rootProject = project.getRootProject();
        Directory rootDir = rootProject.getLayout().getProjectDirectory();

        project.getTasks().register("affectedTest", AffectedTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs only the tests affected by changes in the current branch.");

            task.getBaseRef().set(extension.getBaseRef());
            task.getIncludeUncommitted().set(extension.getIncludeUncommitted());
            task.getIncludeStaged().set(extension.getIncludeStaged());
            task.getStrategies().set(extension.getStrategies());
            task.getTransitiveDepth().set(extension.getTransitiveDepth());
            task.getTestSuffixes().set(extension.getTestSuffixes());
            task.getSourceDirs().set(extension.getSourceDirs());
            task.getTestDirs().set(extension.getTestDirs());
            task.getTestTaskNames().set(extension.getTestTaskNames());
            task.getIgnorePaths().set(extension.getIgnorePaths());
            task.getOutOfScopeTestDirs().set(extension.getOutOfScopeTestDirs());
            task.getOutOfScopeSourceDirs().set(extension.getOutOfScopeSourceDirs());
            task.getIncludeImplementationTests().set(extension.getIncludeImplementationTests());
            task.getImplementationNaming().set(extension.getImplementationNaming());
            task.getMode().set(extension.getMode());
            task.getOnEmptyDiff().set(extension.getOnEmptyDiff());
            task.getOnAllFilesIgnored().set(extension.getOnAllFilesIgnored());
            task.getOnAllFilesOutOfScope().set(extension.getOnAllFilesOutOfScope());
            task.getOnUnmappedFile().set(extension.getOnUnmappedFile());
            task.getOnDiscoveryEmpty().set(extension.getOnDiscoveryEmpty());
            task.getOnDiscoveryIncomplete().set(extension.getOnDiscoveryIncomplete());
            task.getGradlewTimeoutSeconds().set(extension.getGradlewTimeoutSeconds());

            task.getRootDir().set(rootDir);
            task.getSubprojectPaths().set(project.provider(() -> collectSubprojectPaths(rootProject)));

            // Ensure test classes are compiled before the nested gradle
            // invocation runs. Without this, a fresh CI checkout would
            // have nothing to test and Gradle would fail with "No tests
            // found for given includes".
            //
            // Wrapped in a {@link Callable} so the dependency is
            // materialised at task-graph calculation time, which is
            // after Gradle has parsed {@code --explain} into
            // {@link AffectedTestTask#getExplain()}. When the operator
            // asked for the decision trace we skip every
            // {@code testClasses} dependency — {@code --explain}
            // short-circuits before dispatch and never consumes a
            // class file, so forcing a compile turns a 3-second
            // diagnostic into a multi-minute full compile. The
            // dispatch path still picks them up as before.
            //
            // CC-safe capture: we resolve the {@code TaskProvider<?>}
            // for each subproject's {@code testClasses} task eagerly
            // (providers are configuration-cache-serialisable) and
            // close the Callable over that Provider plus the task's
            // own {@code Property<Boolean>}. We deliberately do NOT
            // capture {@code Project p} or {@code task} — either would
            // make the lambda unserialisable when an adopter enables
            // {@code org.gradle.configuration-cache=true}.
            var explainFlag = task.getExplain();
            rootProject.allprojects(p -> p.getPluginManager().withPlugin("java", unused -> {
                TaskProvider<?> testClasses = p.getTasks().named("testClasses");
                Callable<List<TaskProvider<?>>> testClassesWhenDispatching = () -> {
                    if (Boolean.TRUE.equals(explainFlag.getOrElse(false))) {
                        return List.of();
                    }
                    return List.of(testClasses);
                };
                task.dependsOn(testClassesWhenDispatching);
            }));
        });

        // Validate scalar-range constraints at configuration completion so
        // operators get feedback during IDE sync / a dry `./gradlew help`
        // run instead of having to execute the task to see a negative
        // timeout get rejected. The task-side builder keeps its own
        // range check as belt-and-braces for programmatic callers that
        // bypass the extension.
        project.afterEvaluate(p -> {
            Long timeout = extension.getGradlewTimeoutSeconds().getOrNull();
            if (timeout != null && timeout < 0L) {
                throw new GradleException(
                        "affectedTests.gradlewTimeoutSeconds must be >= 0 "
                                + "(0 disables the timeout); got " + timeout);
            }
        });
    }

    /**
     * Returns a map of relative-path-from-root-project-dir (empty string for the
     * root project itself) to the Gradle path of each project. The task uses
     * this map to route {@code --tests} filters to the correct module task.
     */
    private static Map<String, String> collectSubprojectPaths(Project rootProject) {
        File rootDir = rootProject.getProjectDir();
        Map<String, String> result = new LinkedHashMap<>();
        rootProject.getAllprojects().forEach(p -> {
            String relative = relativiseNormalised(rootDir, p.getProjectDir());
            result.putIfAbsent(relative, p.getPath());
        });
        return result;
    }

    private static String relativiseNormalised(File rootDir, File projectDir) {
        String relative = rootDir.toPath().relativize(projectDir.toPath()).toString();
        if (relative.isEmpty()) {
            return "";
        }
        return relative.replace(File.separatorChar, '/');
    }
}
