package io.affectedtests.gradle.e2e.steps;

import io.affectedtests.gradle.e2e.World;
import io.cucumber.java.After;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cucumber step definitions shared across every feature file.
 *
 * <p>Each {@code @Given/@When/@Then} maps one line of a feature file to
 * a Java method. The methods delegate all real work to the
 * {@link io.affectedtests.gradle.e2e.TestProject} on the shared
 * {@link World} — step defs intentionally stay thin so the feature
 * files themselves remain the spec of record.
 *
 * <p>Grouping note: everything lives in one file on purpose.
 * Splitting step defs across many classes is the usual BDD anti-pattern
 * — it makes it much harder to track which step phrasings already
 * exist, encouraging near-duplicate definitions. A single ~300-line
 * file with contextually-named sections stays indexable by any IDE
 * outline and stays the honest single source of truth for the DSL the
 * feature files are written in.
 */
public class CommonSteps {

    private final World world;

    // Cucumber instantiates step classes per scenario and passes the
    // shared World by constructor-injection (picocontainer is bundled
    // with cucumber-java).
    public CommonSteps(World world) {
        this.world = world;
    }

    @After
    public void tearDown() throws Exception {
        world.cleanup();
    }

    // ------------------------------------------------------------------
    // Given — project setup and diff construction
    // ------------------------------------------------------------------

    @Given("a freshly initialised project with a committed baseline")
    public void aFreshlyInitialisedProjectWithACommittedBaseline() throws Exception {
        // Force lazy initialisation so the baseline commit exists before
        // any subsequent diff. No-op on repeated access.
        world.project();
    }

    @Given("the affected-tests DSL contains:")
    public void theAffectedTestsDslContains(String groovySnippet) throws Exception {
        world.project().extendAffectedTestsBlock(groovySnippet);
    }

    @Given("the build script also contains:")
    public void theBuildScriptAlsoContains(String groovySnippet) throws Exception {
        world.project().extendBuildScript(groovySnippet);
    }

    @Given("the project is multi-module with sub-projects {string} and {string}")
    public void theProjectIsMultiModuleWith(String modA, String modB) throws Exception {
        world.project().convertToMultiModule(modA, modB);
    }

    @Given("the mode is {word}")
    public void theModeIs(String mode) throws Exception {
        // Lightweight sugar for the matrix scenario outlines — a full
        // DSL doc-string for every row would bury the single token that
        // actually varies (the mode). Keeping this inline leaves the
        // Examples table self-explanatory.
        world.project().extendAffectedTestsBlock("    mode = '" + mode + "'");
    }

    @Given("a canned diff that produces the {word} situation")
    public void aCannedDiffThatProducesTheSituation(String situation) throws Exception {
        // One set-up routine per situation so the matrix outlines don't
        // have to re-author the per-situation diff shape in every row.
        // Each branch produces the minimal diff that reliably trips the
        // target situation on the current engine — the goal is to keep
        // scenarios honest about the situation they're asserting on, not
        // to stress-test every possible triggering path (pilot-scenarios
        // already covers those variations explicitly).
        switch (situation) {
            case "EMPTY_DIFF" -> {
                world.project().captureBaseline();
                // Empty diff — no additional commits after baseline.
            }
            case "ALL_FILES_IGNORED" -> {
                world.project().extendAffectedTestsBlock("    ignorePaths = ['docs/**']");
                world.project().writeFile("docs/architecture.md", "# Arch\n");
                world.project().captureBaseline();
                world.project().writeFile("docs/architecture.md", "# Arch\nupdated\n");
                world.project().commit("diff: update docs");
            }
            case "ALL_FILES_OUT_OF_SCOPE" -> {
                world.project().extendAffectedTestsBlock("    outOfScopeTestDirs = ['api-test/**']");
                world.project().writeFile("api-test/com/example/SmokeApiTest.java",
                        "package com.example;\npublic class SmokeApiTest {}\n");
                world.project().captureBaseline();
                world.project().writeFile("api-test/com/example/SmokeApiTest.java",
                        "package com.example;\npublic class SmokeApiTest { /* tweak */ }\n");
                world.project().commit("diff: update api-test");
            }
            case "UNMAPPED_FILE" -> {
                world.project().writeFile("src/main/resources/application.yml", "server:\n  port: 8080\n");
                world.project().captureBaseline();
                world.project().writeFile("src/main/resources/application.yml", "server:\n  port: 9090\n");
                world.project().commit("diff: update yaml");
            }
            case "DISCOVERY_EMPTY" -> {
                // Production class with no matching test → discovery
                // runs but finds nothing to route, and the diff is
                // clean Java (not UNMAPPED_FILE).
                world.project().writeFile("src/main/java/com/example/Lonely.java",
                        "package com.example;\npublic class Lonely {}\n");
                world.project().captureBaseline();
                world.project().writeFile("src/main/java/com/example/Lonely.java",
                        "package com.example;\npublic class Lonely { /* tweak */ }\n");
                world.project().commit("diff: update lonely");
            }
            case "DISCOVERY_SUCCESS" -> {
                world.project().writeFile("src/main/java/com/example/FooService.java",
                        "package com.example;\npublic class FooService {}\n");
                world.project().writeFile("src/test/java/com/example/FooServiceTest.java",
                        "package com.example;\npublic class FooServiceTest {}\n");
                world.project().captureBaseline();
                world.project().writeFile("src/main/java/com/example/FooService.java",
                        "package com.example;\npublic class FooService { /* tweak */ }\n");
                world.project().commit("diff: update foo");
            }
            case "DISCOVERY_INCOMPLETE" -> {
                // Malformed Java that JavaParser cannot parse, PAIRED
                // with a well-formed prod/test mapping so discovery
                // returns `DISCOVERY_INCOMPLETE + SELECTED` with a
                // non-empty FQN set. This is the exact shape Risk C
                // targets: the operator sees a selection succeed, but
                // the parser silently dropped an input file so the
                // selection is partial. Without the paired mapping
                // the engine routes SELECTED-with-empty-FQNs to
                // skipped=true and both the WARN and the explain
                // hint are correctly suppressed — which would make
                // the LOCAL-warn scenario silently untestable.
                //
                // Shape matters: the `broken(` below is a truncated
                // parameter list that JavaParser treats as a hard
                // syntax error (vs missing semicolons which it tends
                // to recover from).
                world.project().writeFile("src/main/java/com/example/FooService.java",
                        "package com.example;\npublic class FooService {}\n");
                world.project().writeFile("src/test/java/com/example/FooServiceTest.java",
                        "package com.example;\npublic class FooServiceTest {}\n");
                world.project().writeFile("src/main/java/com/example/Broken.java",
                        "package com.example;\npublic class Broken {\n  public void broken(\n}\n");
                world.project().captureBaseline();
                world.project().writeFile("src/main/java/com/example/FooService.java",
                        "package com.example;\npublic class FooService { /* tweak */ }\n");
                world.project().writeFile("src/main/java/com/example/Broken.java",
                        "package com.example;\npublic class Broken {\n  public void broken(\n  /* tweak */\n}\n");
                world.project().commit("diff: update foo + broken");
            }
            default -> throw new IllegalArgumentException(
                    "No canned setup for situation " + situation
                            + " — extend CommonSteps#aCannedDiffThatProducesTheSituation.");
        }
    }

    @Given("a production class {string} with its matching test {string}")
    public void aProductionClassWithItsMatchingTest(String prodFqn, String testFqn) throws Exception {
        writeJavaClass(prodFqn, "src/main/java", "public class " + simpleName(prodFqn) + " {}");
        writeJavaClass(testFqn, "src/test/java", "public class " + simpleName(testFqn) + " {}");
    }

    @Given("a production class {string} with no matching test on disk")
    public void aProductionClassWithNoMatchingTestOnDisk(String prodFqn) throws Exception {
        writeJavaClass(prodFqn, "src/main/java", "public class " + simpleName(prodFqn) + " {}");
    }

    @Given("a file at {string} with content:")
    public void aFileAtWithContent(String relativePath, String content) throws Exception {
        world.project().writeFile(relativePath, content);
    }

    @Given("the baseline commit is captured")
    public void theBaselineCommitIsCaptured() throws Exception {
        world.project().captureBaseline();
    }

    // ------------------------------------------------------------------
    // And — diff layering (additive over Given)
    // ------------------------------------------------------------------

    @And("the diff modifies {string}")
    public void theDiffModifies(String relativePath) throws Exception {
        // Read the current file and append a no-op tweak so git
        // records a content change. If the file doesn't exist we
        // create it — matching the intuition of "modifies" in the
        // feature file when the scenario's Given didn't pre-create it.
        io.affectedtests.gradle.e2e.TestProject p = world.project();
        java.nio.file.Path path = p.projectDir().resolve(relativePath);
        String existing = java.nio.file.Files.exists(path)
                ? java.nio.file.Files.readString(path)
                : "";
        p.writeFile(relativePath, existing + "\n// e2e modification\n");
        p.commit("diff: modify " + relativePath);
    }

    @And("the diff adds a new file at {string} with content:")
    public void theDiffAddsANewFileAtWithContent(String relativePath, String content) throws Exception {
        world.project().writeFile(relativePath, content);
        world.project().commit("diff: add " + relativePath);
    }

    @And("the diff deletes the file {string}")
    public void theDiffDeletesTheFile(String relativePath) throws Exception {
        world.project().deleteFile(relativePath);
        world.project().commit("diff: delete " + relativePath);
    }

    @And("the diff renames {string} to {string}")
    public void theDiffRenames(String from, String to) throws Exception {
        world.project().renameFile(from, to);
    }

    @And("the working tree has an uncommitted modification to {string}")
    public void theWorkingTreeHasAnUncommittedModificationTo(String path) throws Exception {
        io.affectedtests.gradle.e2e.TestProject p = world.project();
        java.nio.file.Path file = p.projectDir().resolve(path);
        String existing = java.nio.file.Files.exists(file)
                ? java.nio.file.Files.readString(file)
                : "";
        p.writeUncommitted(path, existing + "\n// uncommitted edit\n");
    }

    @And("the working tree has a staged modification to {string}")
    public void theWorkingTreeHasAStagedModificationTo(String path) throws Exception {
        io.affectedtests.gradle.e2e.TestProject p = world.project();
        java.nio.file.Path file = p.projectDir().resolve(path);
        String existing = java.nio.file.Files.exists(file)
                ? java.nio.file.Files.readString(file)
                : "";
        p.writeStaged(path, existing + "\n// staged edit\n");
    }

    @And("the diff contains no committed changes on top of baseline")
    public void theDiffContainsNoCommittedChangesOnTopOfBaseline() {
        // Intentional no-op. The project at this point has only the
        // baseline commit, so `baseRef = <baseline>` vs HEAD produces
        // an empty diff — exactly the S01 EMPTY_DIFF pre-condition.
    }

    // ------------------------------------------------------------------
    // When — execution
    // ------------------------------------------------------------------

    @When("the affected-tests task runs")
    public void theAffectedTestsTaskRuns() throws Exception {
        world.project().runAffectedTests();
    }

    @When("the affected-tests task runs with {string}")
    public void theAffectedTestsTaskRunsWith(String extraArg) throws Exception {
        world.project().addGradleArgument(extraArg);
        world.project().runAffectedTests();
    }

    @Given("the Gradle command-line argument {string}")
    public void theGradleCommandLineArgument(String arg) {
        // Lets a scenario stack multiple CLI flags (e.g.
        // `-PaffectedTestsMode=strict` and `--explain`) without
        // inventing a per-combination `runs with "... ..."` step.
        // Cleared automatically after the next runAffectedTests()
        // call, so unrelated scenarios don't leak.
        world.project().addGradleArgument(arg);
    }

    @When("the affected-tests task runs with live task dependencies")
    public void theAffectedTestsTaskRunsWithLiveTaskDependencies() throws Exception {
        // Exercises the real dependency graph — no `-x compileJava`
        // CLI escape hatch — so the v2.2 "--explain does not force
        // compile" fix is provable via the resulting task list. If
        // the fix regresses, `:compileJava` will reappear in the
        // executed-tasks list and the paired assertion fails.
        world.project().runAffectedTestsWithLiveDependencies();
    }

    @When("any Gradle task is configured")
    public void anyGradleTaskIsConfigured() throws Exception {
        // Scenarios that assert on configuration-time failures run
        // `help` — the cheapest task that still forces afterEvaluate
        // to fire. TestProject.runHelpExpectingFailure handles the
        // non-zero exit path.
        world.project().runHelpExpectingFailure();
    }

    // ------------------------------------------------------------------
    // Then — assertions on the TestKit build output
    // ------------------------------------------------------------------

    @Then("the task succeeds")
    public void theTaskSucceeds() {
        assertFalse(world.project().lastBuildFailed(),
                "Expected a green build, got:\n" + world.project().lastOutput());
    }

    @Then("the task fails at configuration time")
    public void theTaskFailsAtConfigurationTime() {
        assertTrue(world.project().lastBuildFailed(),
                "Expected a configuration-time failure, build was green:\n" + world.project().lastOutput());
        assertTrue(world.project().lastOutput().contains("A problem occurred configuring")
                        || world.project().lastOutput().contains("A problem occurred evaluating"),
                "Failure must come from configuration / evaluation phase, got:\n" + world.project().lastOutput());
    }

    @Then("the task fails")
    public void theTaskFails() {
        // Generic failure assertion for scenarios where the failure
        // phase is uninteresting — e.g. an unknown `-P` value that
        // escapes the extension convention and is only rejected at
        // task-action time via {@code parseMode}. Asserting on the
        // lifecycle phase would tie the test to an implementation
        // detail (exactly where the enum lookup fires), so this
        // step only pins "the build did not turn green".
        assertTrue(world.project().lastBuildFailed(),
                "Expected a failing build, got green:\n" + world.project().lastOutput());
    }

    @Then("the situation is {word}")
    public void theSituationIs(String situation) {
        // The --explain trace prints the ACTUAL situation on the
        // "Situation:       DISCOVERY_SUCCESS" header line, but the
        // downstream matrix block lists *every* situation by name as a
        // reference table — so a bare contains(situation) would pass
        // trivially regardless of the actual classification. Anchor on
        // the exact header substring to assert on the resolved call,
        // not on the reference matrix.
        String expected = "Situation:       " + situation;
        assertTrue(world.project().lastOutput().contains(expected),
                "Expected header '" + expected + "' in --explain output, got:\n"
                        + world.project().lastOutput());
    }

    @Then("the action is {word}")
    public void theActionIs(String action) {
        // Same matrix-block-poisoning hazard as theSituationIs — the
        // reference matrix always contains every Action name, so match
        // the "Action:          <ACTION> (source: ...)" header line
        // which is the only line that names the ACTUAL resolved action.
        String expected = "Action:          " + action + " (";
        assertTrue(world.project().lastOutput().contains(expected),
                "Expected header '" + expected + "...)' in --explain output, got:\n"
                        + world.project().lastOutput());
    }

    @Then("the action source is {word}")
    public void theActionSourceIs(String source) {
        // The `Action:` header is the only line that names the *actual*
        // resolved action source for this run (matrix rows include every
        // situation's hypothetical source, which would make a bare
        // `contains("[mode default]")` trivially true on every run).
        // Anchor on the full `(source: ...)` substring so we're asserting
        // the resolved call, not the reference matrix.
        String expected = switch (source.toUpperCase()) {
            case "MODE_DEFAULT" -> "(source: mode default)";
            case "EXPLICIT" -> "(source: explicit onXxx setting)";
            default -> throw new IllegalArgumentException(
                    "Unknown action source in feature file: " + source
                            + " (use MODE_DEFAULT or EXPLICIT)");
        };
        assertTrue(world.project().lastOutput().contains(expected),
                "Expected action source " + expected + " in --explain output, got:\n"
                        + world.project().lastOutput());
    }

    @Then("the output contains {string}")
    public void theOutputContains(String needle) {
        assertTrue(world.project().lastOutput().contains(needle),
                "Expected output to contain '" + needle + "', got:\n"
                        + world.project().lastOutput());
    }

    @Then("the output does not contain {string}")
    public void theOutputDoesNotContain(String needle) {
        assertFalse(world.project().lastOutput().contains(needle),
                "Expected output NOT to contain '" + needle + "', got:\n"
                        + world.project().lastOutput());
    }

    @Then("the selected tests include {string}")
    public void theSelectedTestsInclude(String testFqn) {
        // FQN assertions only work on non-`--explain` runs where the
        // dispatch preview is actually emitted. On `--explain` runs the
        // task exits after the decision trace and no dispatch happens,
        // so scenarios that specifically want to verify FQN-level
        // routing must omit the --explain flag.
        assertTrue(world.project().lastOutput().contains(testFqn),
                "Expected selected tests to include " + testFqn + ", got:\n"
                        + world.project().lastOutput());
    }

    @Then("the executed task list does not include {string}")
    public void theExecutedTaskListDoesNotInclude(String taskPath) {
        // TestKit's BuildResult.task() returns null when a task was
        // never scheduled (filtered out, pruned, or its declaring
        // subproject doesn't exist). That's exactly the "never
        // scheduled" shape we want — a task that was scheduled and
        // SKIPPED would still appear in the list with a SKIPPED
        // outcome, which is a different (legitimate) behaviour we
        // must not confuse with pruning. Fail loudly if the task
        // was any non-null state so regressions — including the
        // "explain quietly UP-TO-DATEs compile instead of pruning
        // it" shape — surface as a named assertion.
        org.gradle.testkit.runner.BuildTask task = world.project().lastBuildResult().task(taskPath);
        assertTrue(task == null,
                "Expected task " + taskPath + " to be absent from the executed task list, "
                        + "but it ran with outcome " + (task == null ? "<null>" : task.getOutcome())
                        + ". Output was:\n" + world.project().lastOutput());
    }

    @Then("the outcome is {string}")
    public void theOutcomeIs(String outcome) {
        // The `Outcome:` line in the --explain trace is the canonical
        // single-line summary of the resolved action, combining the
        // action name and any action-specific detail (e.g. "SELECTED —
        // 1 test class(es) will run" or "FULL_SUITE —
        // onUnmappedFile=FULL_SUITE — non-Java or unmapped file in
        // diff"). Matching on the full substring pins both what
        // happened and why, which together are a stronger spec than
        // action-alone.
        String expected = "Outcome:         " + outcome;
        assertTrue(world.project().lastOutput().contains(expected),
                "Expected outcome line '" + expected + "' in --explain output, got:\n"
                        + world.project().lastOutput());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void writeJavaClass(String fqn, String sourceRoot, String body) throws Exception {
        int lastDot = fqn.lastIndexOf('.');
        String pkg = fqn.substring(0, lastDot);
        String simple = fqn.substring(lastDot + 1);
        String relativePath = sourceRoot + "/" + pkg.replace('.', '/') + "/" + simple + ".java";
        String source = "package " + pkg + ";\n" + body + "\n";
        world.project().writeFile(relativePath, source);
    }

    private String simpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }
}
