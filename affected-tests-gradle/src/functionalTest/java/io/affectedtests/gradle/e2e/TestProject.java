package io.affectedtests.gradle.e2e;

import org.eclipse.jgit.api.Git;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A scratch consumer project for one Cucumber scenario.
 *
 * <p>Combines a JGit-managed working tree, a minimal Gradle build that
 * applies {@code io.github.vedanthvdev.affectedtests} via TestKit's
 * {@code withPluginClasspath()}, and helpers for committing an initial
 * baseline then layering diffs on top. Instances are one-shot per
 * scenario — Cucumber's {@code @Before} creates one and {@code @After}
 * cleans it up, so scenarios never see state from prior runs.
 *
 * <p>The shape mirrors how a real consumer adopts the plugin: a tiny
 * root project with the plugin applied and a {@code baseRef}
 * configured. Everything else (file layout, diff contents, out-of-scope
 * dirs) comes from the feature-file steps.
 */
public final class TestProject {

    private final Path projectDir;
    private final Git git;
    private String baselineCommit;
    private final List<String> gradleArguments = new ArrayList<>();
    private String affectedTestsBlock = "";
    private String extraBuildScript = "";
    private BuildResult lastBuildResult;
    private String lastBuildOutput = "";
    private boolean lastBuildFailed;

    private TestProject(Path projectDir, Git git) {
        this.projectDir = projectDir;
        this.git = git;
    }

    /**
     * Initialises a fresh temp project with a single {@code project init}
     * commit that contains only the settings file, README, and the
     * plugin-applied build script. The baseline commit — i.e. the ref
     * the engine will diff against — is captured later via
     * {@link #captureBaseline()}, *after* Given steps have added the
     * pre-existing production / test classes the scenario starts from.
     *
     * <p>This two-phase setup mirrors how a real MR looks in git: the
     * pre-existing code base is the baseline, the MR's commits are the
     * diff, and the engine resolves test selection from that delta.
     * Capturing the baseline before Given-step files exist would make
     * them appear as part of the diff, which is the opposite of what
     * every scenario wants to assert.
     */
    public static TestProject createEmptyBaseline(Path projectDir) throws Exception {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'affected-tests-e2e'\n");
        Files.writeString(projectDir.resolve("README.md"), "# initial\n");
        // TestKit leaves `.gradle/` and `build/` trees in the working
        // directory after each invocation. Without this .gitignore,
        // any scenario that runs affectedTest twice — or enables
        // includeUncommitted/includeStaged — would see those
        // TestKit-produced directories surface as unmapped non-Java
        // files in the diff, flipping real DISCOVERY_SUCCESS runs into
        // spurious UNMAPPED_FILE → FULL_SUITE outcomes.
        Files.writeString(projectDir.resolve(".gitignore"),
                ".gradle/\nbuild/\n");

        Git git = Git.init().setDirectory(projectDir.toFile()).call();
        TestProject p = new TestProject(projectDir, git);
        p.writeBuildScript();

        git.add().addFilepattern(".").call();
        git.commit().setMessage("project init").call();
        return p;
    }

    /**
     * Commits any uncommitted work the Given steps have written and
     * captures the resulting SHA as {@link #baselineCommit()}. The
     * baseRef is passed to each Gradle invocation via
     * {@code -PaffectedTestsBaseRef=<sha>} (see {@link #runAffectedTests()})
     * instead of being baked into {@code build.gradle} — that avoids a
     * chicken-and-egg where rewriting the build script after capturing
     * the SHA leaves an uncommitted edit that the next
     * {@link #commit(String)} would silently drag into the diff,
     * classifying scenarios as {@code UNMAPPED_FILE} instead of the
     * pure production change they set up.
     */
    public String captureBaseline() throws Exception {
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        if (!git.status().call().isClean()) {
            git.commit().setMessage("baseline").call();
        }
        this.baselineCommit = git.log().call().iterator().next().getName();
        return baselineCommit;
    }

    /**
     * Appends a snippet of Groovy DSL inside the {@code affectedTests {}}
     * block before the next Gradle invocation. Scenario steps use this
     * to set {@code mode}, {@code onXxx} overrides, {@code ignorePaths},
     * {@code outOfScopeTestDirs}, etc., without re-authoring the whole
     * build script for every scenario.
     */
    public void extendAffectedTestsBlock(String groovySnippet) throws IOException {
        this.affectedTestsBlock = (this.affectedTestsBlock.isBlank() ? "" : this.affectedTestsBlock + "\n") + groovySnippet;
        writeBuildScript();
    }

    /**
     * Appends a snippet of Groovy DSL <em>outside</em> the
     * {@code affectedTests {}} block — typically for source-set
     * declarations, custom Test task wiring, or anything else that
     * has to land at the top level of the build script. Used by
     * the source-set auto-discovery scenarios (issue #49) which
     * need to register an {@code integrationTest} source set + Test
     * task before the plugin's {@code projectsEvaluated} hook fires.
     */
    public void extendBuildScript(String groovySnippet) throws IOException {
        this.extraBuildScript = (this.extraBuildScript.isBlank() ? "" : this.extraBuildScript + "\n") + groovySnippet;
        writeBuildScript();
    }

    /**
     * Extends this single-module test project into a multi-module layout
     * shaped like security-service's: a root project applying the plugin
     * plus N sub-projects each with their own {@code java} plugin. Used
     * by scenarios that need to assert on cross-module routing (a prod
     * change in {@code :module-a} should select tests in
     * {@code :module-a:test} and not {@code :module-b:test}).
     *
     * <p>This must be called before {@link #captureBaseline()} so the
     * {@code settings.gradle} with {@code include} directives is part
     * of the baseline, not the diff — otherwise every multi-module
     * scenario would classify as {@code UNMAPPED_FILE} due to the
     * root-level settings edit.
     */
    public void convertToMultiModule(String... moduleNames) throws IOException {
        StringBuilder settings = new StringBuilder();
        settings.append("rootProject.name = 'affected-tests-e2e'\n");
        for (String mod : moduleNames) {
            settings.append("include '").append(mod).append("'\n");
            Path moduleDir = projectDir.resolve(mod);
            Files.createDirectories(moduleDir);
            // Minimal per-module build: `java` plugin so :module:test
            // exists as a dispatch target. The root project's
            // affectedTests block does all the heavy lifting.
            Files.writeString(moduleDir.resolve("build.gradle"),
                    "plugins {\n    id 'java'\n}\n");
        }
        Files.writeString(projectDir.resolve("settings.gradle"), settings.toString());
    }

    /**
     * Creates (or overwrites) a file under the project root and schedules
     * it to be staged on the next {@link #commit(String)} call. Parent
     * directories are created as needed — tests shouldn't have to author
     * boilerplate directory setup inline.
     */
    public void writeFile(String relativePath, String content) throws IOException {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Deletes a file from the working tree. The deletion will be picked
     * up by the next {@code git add .} inside {@link #commit(String)}.
     */
    public void deleteFile(String relativePath) throws IOException {
        Files.deleteIfExists(projectDir.resolve(relativePath));
    }

    /**
     * Stages every change under the working tree (additions, modifications,
     * deletions) and commits with the given message. Returns the resulting
     * commit SHA so scenarios can chain diffs against arbitrary checkpoints.
     */
    public String commit(String message) throws Exception {
        git.add().addFilepattern(".").call();
        // addFilepattern(".") doesn't stage deletions until setUpdate(true)
        // is called, because the legacy libgit2-style pathspec semantics
        // treat deletion as a rename candidate. Without this pass, a
        // "delete the production class" scenario would commit the
        // additions from this round but keep the deleted files in the
        // index, which completely hides the S07 scenario from the
        // engine's diff path.
        git.add().setUpdate(true).addFilepattern(".").call();
        return git.commit().setMessage(message).call().getName();
    }

    /** Returns the SHA of the baseline commit created by {@link #createEmptyBaseline(Path)}. */
    public String baselineCommit() { return baselineCommit; }

    /**
     * Renames a file on disk and stages the rename via git — JGit's
     * {@code git mv} equivalent. Used by rename-detection scenarios in
     * {@code 05-edge-cases.feature}: the diff must be recognised as a
     * rename (not an add + delete) so the engine can route the new
     * filename to the same test class.
     */
    public void renameFile(String fromPath, String toPath) throws Exception {
        Path from = projectDir.resolve(fromPath);
        Path to = projectDir.resolve(toPath);
        Files.createDirectories(to.getParent());
        Files.move(from, to);
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        git.commit().setMessage("rename " + fromPath + " -> " + toPath).call();
    }

    /**
     * Writes content to a file without committing. Scenarios that
     * exercise {@code includeUncommitted = true} use this to leave
     * working-tree edits in place, then run affectedTest — the engine
     * should see those changes as part of the diff.
     */
    public void writeUncommitted(String relativePath, String content) throws IOException {
        writeFile(relativePath, content);
    }

    /**
     * Stages a new write to the index but does not commit. Scenarios
     * that exercise {@code includeStaged = true} — the intermediate
     * diff-visibility tier between {@code includeUncommitted=true} and
     * pure committed-only — land here.
     */
    public void writeStaged(String relativePath, String content) throws Exception {
        writeFile(relativePath, content);
        git.add().addFilepattern(relativePath).call();
    }

    /** Directory the build was initialised in. Useful for crafting diff assertions. */
    public Path projectDir() { return projectDir; }

    /**
     * Adds a Gradle CLI argument for the next invocation (e.g.
     * {@code --explain}, {@code -PaffectedTests.baseRef=HEAD~1}). Arguments
     * are reset after every {@link #runAffectedTests()} call to keep
     * scenarios from leaking flags into unrelated assertions.
     */
    public void addGradleArgument(String argument) {
        this.gradleArguments.add(argument);
    }

    /**
     * Invokes {@code affectedTest} via TestKit with the arguments queued
     * by {@link #addGradleArgument(String)}. The task is run in isolation
     * from compile tasks ({@code -x compileJava -x compileTestJava -x
     * processResources -x processTestResources}) because the scenario
     * project never produces real class files — mirroring the pilot's
     * --explain-only mode and keeping per-scenario wall time
     * to a few seconds instead of a full Java build.
     *
     * <p>Both the success and failure paths are supported: the caller
     * decides via {@link #lastBuildFailed()} whether a non-zero exit was
     * the expected outcome (e.g. DSL migration errors) or a genuine
     * regression.
     */
    public void runAffectedTests() {
        runAffectedTests(true);
    }

    /**
     * Runs {@code affectedTest} without the {@code -x compileJava -x
     * compileTestJava ...} safety net so the real task-graph
     * dependency shape is exercised. Used by the v2.2 e2e scenario
     * that pins the "--explain must not force a compile" fix: with
     * the skip-flags present we'd short-circuit compile via CLI
     * regardless of the plugin's dependency wiring, which would mask
     * a regression in the fix. Every other scenario should stick
     * with {@link #runAffectedTests()} so per-scenario wall time
     * stays in the seconds-range.
     */
    public void runAffectedTestsWithLiveDependencies() {
        runAffectedTests(false);
    }

    private void runAffectedTests(boolean skipCompileTasks) {
        List<String> args = new ArrayList<>();
        args.add("affectedTest");
        args.add("--stacktrace");
        if (skipCompileTasks) {
            args.add("-x"); args.add("compileJava");
            args.add("-x"); args.add("compileTestJava");
            args.add("-x"); args.add("processResources");
            args.add("-x"); args.add("processTestResources");
        }
        if (baselineCommit != null) {
            // Pass baseRef as a Gradle property instead of baking it
            // into build.gradle. See captureBaseline() for the full
            // reasoning on why the baked-in approach silently poisons
            // the diff with the build.gradle rewrite itself.
            args.add("-PaffectedTestsBaseRef=" + baselineCommit);
        }
        args.addAll(gradleArguments);

        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(args)
                .withPluginClasspath()
                .forwardOutput();
        try {
            lastBuildResult = runner.build();
            lastBuildFailed = false;
        } catch (RuntimeException buildFailure) {
            lastBuildResult = runner.buildAndFail();
            lastBuildFailed = true;
        }
        lastBuildOutput = lastBuildResult.getOutput();
        gradleArguments.clear();
    }

    /**
     * Runs a non-{@code affectedTest} task (typically {@code help}) used
     * by scenarios that only care about configuration-time failures — the
     * legacy-DSL-knob migration tests in 04-dsl-migration-errors.feature
     * hit this path. Running {@code help} is the lightest way to force
     * {@code project.afterEvaluate} to fire without accidentally doing
     * real build work.
     */
    public void runHelpExpectingFailure() {
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("help", "--stacktrace")
                .withPluginClasspath()
                .forwardOutput();
        lastBuildResult = runner.buildAndFail();
        lastBuildOutput = lastBuildResult.getOutput();
        lastBuildFailed = true;
    }

    public String lastOutput() { return lastBuildOutput; }
    public boolean lastBuildFailed() { return lastBuildFailed; }
    public BuildResult lastBuildResult() { return lastBuildResult; }

    private void writeBuildScript() throws IOException {
        // The `java` plugin is applied so a real `test` task exists for
        // the plugin to dispatch into. Compilation is skipped via the
        // `-x compileJava -x compileTestJava ...` arguments on every
        // invocation, so scenarios don't pay the cost of resolving a
        // toolchain or producing class files — we only need the task
        // graph to be shaped the way a real consumer's is.
        //
        // baseRef intentionally omitted from the DSL block — it's
        // passed per-invocation via -PaffectedTestsBaseRef. See
        // captureBaseline() for the rationale.
        String body = """
                plugins {
                    id 'java'
                    id 'io.github.vedanthvdev.affectedtests'
                }

                affectedTests {
                """
                + affectedTestsBlock
                + "\n}\n"
                + (extraBuildScript.isBlank() ? "" : "\n" + extraBuildScript + "\n");
        Files.writeString(projectDir.resolve("build.gradle"), body);
    }
}
