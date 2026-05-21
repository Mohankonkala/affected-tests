package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine;
import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.Buckets;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.ActionSource;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.config.Situation;
import io.affectedtests.core.util.LogSanitizer;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Gradle task that detects affected tests and executes them.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Detects git changes against the configured base ref</li>
 *   <li>Maps changed files to production/test classes</li>
 *   <li>Discovers which test classes are affected</li>
 *   <li>Groups FQNs by owning subproject and runs {@code :<module>:test --tests <fqn>}
 *       once per module so that {@code --tests} filters don't cross module boundaries</li>
 * </ol>
 *
 * <p>Configuration-cache safe: every input is a Gradle {@code Property} /
 * {@code ListProperty} / {@code MapProperty} / {@code DirectoryProperty},
 * the dispatch path runs through an injected {@link ExecOperations}
 * service, and no {@code Project} or live build-time object is captured
 * by the task action. Reading the live git state and walking the source
 * tree happen entirely inside {@link #runAffectedTests()} — that's
 * task-action work, which CC stores once and replays on subsequent
 * runs without re-resolving the task graph.
 *
 * <p>The task is deliberately marked as never up-to-date
 * ({@code outputs.upToDateWhen(t -> false)}) so a real git change always
 * triggers a re-run; that's an up-to-date concern, independent of CC.
 */
public abstract class AffectedTestTask extends DefaultTask {

    public AffectedTestTask() {
        // No declared outputs and the result depends on live git state,
        // so up-to-date checks must always miss. CC compatibility is a
        // separate concern (the task IS CC-safe — see the class Javadoc)
        // so we no longer self-mark with notCompatibleWithConfigurationCache.
        getOutputs().upToDateWhen(t -> false);
    }

    /**
     * Git base ref to diff against.
     * Default: {@code "origin/master"}. Override via {@code -PaffectedTestsBaseRef=...}.
     *
     * @return the base ref property
     */
    @Input
    public abstract Property<String> getBaseRef();

    /**
     * Whether to include uncommitted (unstaged) changes in the diff.
     * Default: {@code false} — committed-only, so a local run picks
     * the same tests CI would pick on the same HEAD, and two runs on
     * the same commit are deterministic. Flip to {@code true} in
     * {@code build.gradle} if you iterate on tests locally and want
     * WIP to seed the diff.
     *
     * @return the include uncommitted property
     */
    @Input
    public abstract Property<Boolean> getIncludeUncommitted();

    /**
     * Whether to include staged (added to index) changes in the diff.
     * Default: {@code false} — see {@link #getIncludeUncommitted()} for
     * the rationale; both knobs move together on the CI-first defaults.
     *
     * @return the include staged property
     */
    @Input
    public abstract Property<Boolean> getIncludeStaged();

    /**
     * Discovery strategies to use for finding affected tests.
     * Valid values: {@code "naming"}, {@code "usage"}, {@code "impl"}, {@code "transitive"}.
     * Default: all four.
     *
     * @return the strategies list property
     */
    @Input
    public abstract ListProperty<String> getStrategies();

    /**
     * How many levels of transitive dependencies to follow when the
     * {@code transitive} strategy is enabled.
     * Range: 0 (disabled) to 5. Default: {@code 4} — matches the depth
     * most Java controller &rarr; service &rarr; repository chains
     * actually reach in Modulr-shaped codebases while leaving one level
     * of margin. The pre-v2 default was {@code 2}, which under-reached
     * on any MR that crossed more than one seam; consumers reading this
     * Javadoc were being told they still needed to set {@code 4}
     * explicitly — they do not.
     *
     * @return the transitive depth property
     */
    @Input
    public abstract Property<Integer> getTransitiveDepth();

    /**
     * Suffixes used by the naming strategy to find test classes.
     * Default: {@code ["Test", "IT", "ITTest", "IntegrationTest"]}.
     *
     * @return the test suffixes list property
     */
    @Input
    public abstract ListProperty<String> getTestSuffixes();

    /**
     * Production source directories relative to each module root.
     * Default: {@code ["src/main/java"]}.
     *
     * @return the source dirs list property
     */
    @Input
    public abstract ListProperty<String> getSourceDirs();

    /**
     * Test source directories relative to each module root.
     * Default: {@code ["src/test/java"]}.
     *
     * @return the test dirs list property
     */
    @Input
    public abstract ListProperty<String> getTestDirs();

    /**
     * Glob patterns for files that must never influence test selection.
     * Optional — when unset, the core config's default list applies.
     *
     * @return the ignore paths list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getIgnorePaths();

    /**
     * Test source directories (e.g. {@code "api-test/src/test/java"})
     * whose contents the plugin must not dispatch via the
     * {@code affectedTest} task.
     *
     * @return the out-of-scope test dirs list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getOutOfScopeTestDirs();

    /**
     * Production source directories the plugin must treat as
     * out-of-scope.
     *
     * @return the out-of-scope source dirs list property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract ListProperty<String> getOutOfScopeSourceDirs();

    /**
     * Whether to include tests for implementations of changed interfaces/base classes.
     * Default: {@code true}.
     *
     * @return the include implementation tests property
     */
    @Input
    public abstract Property<Boolean> getIncludeImplementationTests();

    /**
     * Suffixes/prefixes for finding implementation classes.
     * Default: {@code ["Impl", "Default"]}.
     *
     * @return the implementation naming list property
     */
    @Input
    public abstract ListProperty<String> getImplementationNaming();

    /**
     * Execution profile name — one of {@code "auto"}, {@code "local"},
     * {@code "ci"}, {@code "strict"}. Unset resolves to {@code "auto"}
     * in the core config, which detects CI from common env vars.
     *
     * @return the mode property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getMode();

    /** @return the on-empty-diff situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnEmptyDiff();

    /** @return the on-all-files-ignored situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnAllFilesIgnored();

    /** @return the on-all-files-out-of-scope situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnAllFilesOutOfScope();

    /** @return the on-unmapped-file situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnUnmappedFile();

    /** @return the on-discovery-empty situation action property */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnDiscoveryEmpty();

    /**
     * Action to take when discovery ran but one or more scanned Java files
     * failed to parse (see {@link Situation#DISCOVERY_INCOMPLETE}). One of
     * {@code "selected"}, {@code "full_suite"}, {@code "skipped"}. Unset
     * falls through to the {@link Mode} default (CI and STRICT escalate
     * to {@code full_suite}; LOCAL keeps the partial selection).
     *
     * @return the on-discovery-incomplete property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getOnDiscoveryIncomplete();

    /**
     * Wall-clock timeout (in seconds) for the nested {@code ./gradlew}
     * invocation that runs the affected / full test suite. {@code 0}
     * disables the timeout (pre-v1.9.22 default: wait indefinitely).
     * Positive values deadline the child: the task destroys the
     * process tree after the interval and fails the build.
     *
     * <p>Kept {@link org.gradle.api.tasks.Optional @Optional} so that
     * zero-config callers skip the timeout entirely — opting in is
     * explicit. Must be {@code >= 0}; the core config builder rejects
     * negative values at build-config time.
     *
     * @return the gradlew timeout property in seconds
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<Long> getGradlewTimeoutSeconds();

    /**
     * When {@code true}, the task prints the full decision trace
     * (buckets, situation, action, action source) and exits without
     * running any tests. Used by operators to answer "why did this MR
     * land on that outcome?" without having to enable debug logs.
     *
     * <p>Marked {@link Internal @Internal} rather than {@link Input @Input}
     * because flipping the explain flag must not invalidate a cached
     * execution — it changes only the lifecycle logging, never the set
     * of tests Gradle would actually run.
     *
     * @return the explain flag property
     */
    @Internal
    @Option(option = "explain",
            description = "Print the decision trace (buckets, situation, action, source) "
                    + "and exit without running tests.")
    public abstract Property<Boolean> getExplain();

    /**
     * Map of subproject directory (relative to the root project, empty string
     * for the root project itself) to the Gradle path of that subproject
     * (e.g. {@code ":services:payment"}). Populated automatically by the plugin
     * and used to group affected test FQNs by their owning module.
     *
     * @return the subproject dirs map property
     */
    @Internal
    public abstract MapProperty<String, String> getSubprojectPaths();

    /**
     * The root project directory (resolved at configuration time).
     *
     * @return the root dir property
     */
    @Internal
    public abstract DirectoryProperty getRootDir();

    /**
     * Injected by Gradle for executing the test subprocess.
     *
     * @return the exec operations service
     */
    @Inject
    protected abstract ExecOperations getExecOperations();

    /**
     * Detects affected tests and executes them via a Gradle subprocess.
     */
    @TaskAction
    public void runAffectedTests() {
        Path projectDir = getRootDir().get().getAsFile().toPath();

        AffectedTestsConfig config = buildConfig();

        AffectedTestsEngine engine = new AffectedTestsEngine(config, projectDir);
        AffectedTestsResult result = engine.run();

        // Loud warning before the explain/dispatch fork: LOCAL-mode's
        // default on DISCOVERY_INCOMPLETE is SELECTED, which silently
        // accepts a partial selection when the discovery parser hit an
        // unparseable Java file. On a workstation that's a conscious
        // trade-off (dev iterates on WIP tests, wants fast feedback),
        // but unless the operator knows about it, a green "SELECTED N
        // tests" summary reads as "we ran the affected tests" — not
        // as "we ran what the parser could see, which may not be all
        // of them". We emit WARN so it's visible in both the IDE and
        // CI logs without extra flags, and we do it on the --explain
        // path too so the diagnostic mode surfaces the same risk the
        // dispatch mode does.
        warnIfLocalDiscoveryIncompleteSelected(config, result);

        boolean explain = getExplain().getOrElse(false);
        if (explain) {
            // {@code --explain} is a diagnostic mode: we print the trace and
            // return without touching the executor so operators can re-run
            // as many times as they need without waiting for the suite.
            // Every line goes through {@code lifecycle()} so the trace is
            // visible by default (no {@code --info} gymnastics).
            //
            // Compute the module grouping here (not inside
            // renderExplainTrace) because the grouping needs
            // instance-level state — {@link #getSubprojectPaths()} and
            // the {@code projectDir} Path — which the pure-function
            // renderer deliberately doesn't see. Empty map when
            // there's no selection to split by module, so the renderer
            // can simply skip the "Modules:" block rather than print
            // an empty one.
            Map<String, List<String>> moduleGroupsForExplain = Map.of();
            if (result.action() == Action.SELECTED && !result.testClassFqns().isEmpty()) {
                // groupFqnsByModule returns keys shaped like the
                // owning project path (``, `:api`, `:application`).
                // The dispatch path then appends `:test` to produce
                // the real `:module:test` task path before invoking
                // the nested gradle. Mirror that transformation here
                // so the --explain preview names the EXACT tasks
                // that would run in a non-explain dispatch — anything
                // less defeats the point of the diagnostic.
                Map<String, List<String>> rawGroups = groupFqnsByModule(
                        projectDir, result.testClassFqns(), result.testFqnToPath());
                Map<String, List<String>> taskKeyed = new LinkedHashMap<>(rawGroups.size());
                for (Map.Entry<String, List<String>> e : rawGroups.entrySet()) {
                    taskKeyed.put(testTaskPath(e.getKey()), e.getValue());
                }
                moduleGroupsForExplain = taskKeyed;
            }
            for (String line : renderExplainTrace(config, result, moduleGroupsForExplain)) {
                getLogger().lifecycle(line);
            }
            return;
        }

        // The summary line is the single place the task names the
        // outcome (SELECTED / FULL_SUITE / SKIPPED), the situation that
        // produced it, and the reason phrase. Downstream lines must not
        // repeat any of those fields or CI logs drift into contradictory
        // duplicate phrasing. We hand the logger a format string + args
        // pair instead of a pre-formatted string so any future phrase
        // containing `{` or `}` characters (Liquibase file names,
        // JSONpath expressions) renders literally rather than silently
        // disappearing into the placeholder parser.
        LogLine summary = renderSummary(result);
        getLogger().lifecycle(summary.format(), summary.args());

        // Skipped results return here without touching the executor.
        // Intentionally no follow-up line — the summary already carried
        // the {@code SKIPPED (SITUATION)} prefix and the reason phrase,
        // so a separate "Skipping test execution (…)" log would only
        // duplicate what the operator already read.
        if (result.skipped()) {
            return;
        }

        if (result.testClassFqns().isEmpty() && !result.runAll()) {
            // Defensive fallthrough: the engine should have already
            // routed this into {@code SKIPPED} via
            // {@link AffectedTestsEngine.Situation#DISCOVERY_EMPTY},
            // but we keep the guard so a future regression surfaces as
            // a log line instead of a silent no-op.
            return;
        }

        executeTests(projectDir,
                result.testClassFqns(),
                result.testFqnToPath(),
                result.runAll(),
                config.gradlewTimeoutSeconds());
    }

    /**
     * Assembles the immutable core config from the task's Gradle
     * properties. All situation/mode knobs are optional at this layer;
     * the core builder handles precedence via the v2 two-tier ladder
     * (explicit {@code onXxx} > mode default).
     */
    private AffectedTestsConfig buildConfig() {
        AffectedTestsConfig.Builder builder = AffectedTestsConfig.builder()
                .baseRef(getBaseRef().get())
                .includeUncommitted(getIncludeUncommitted().get())
                .includeStaged(getIncludeStaged().get())
                .strategies(new LinkedHashSet<>(getStrategies().get()))
                .transitiveDepth(getTransitiveDepth().get())
                .testSuffixes(getTestSuffixes().get())
                .sourceDirs(getSourceDirs().get())
                .testDirs(getTestDirs().get())
                .includeImplementationTests(getIncludeImplementationTests().get())
                .implementationNaming(getImplementationNaming().get());

        if (getIgnorePaths().isPresent() && !getIgnorePaths().get().isEmpty()) {
            builder.ignorePaths(getIgnorePaths().get());
        }
        if (getOutOfScopeTestDirs().isPresent() && !getOutOfScopeTestDirs().get().isEmpty()) {
            builder.outOfScopeTestDirs(getOutOfScopeTestDirs().get());
        }
        if (getOutOfScopeSourceDirs().isPresent() && !getOutOfScopeSourceDirs().get().isEmpty()) {
            builder.outOfScopeSourceDirs(getOutOfScopeSourceDirs().get());
        }
        if (getMode().isPresent()) {
            builder.mode(parseMode(getMode().get()));
        }
        if (getOnEmptyDiff().isPresent()) {
            builder.onEmptyDiff(parseAction(getOnEmptyDiff().get(), "onEmptyDiff"));
        }
        if (getOnAllFilesIgnored().isPresent()) {
            builder.onAllFilesIgnored(parseAction(getOnAllFilesIgnored().get(), "onAllFilesIgnored"));
        }
        if (getOnAllFilesOutOfScope().isPresent()) {
            builder.onAllFilesOutOfScope(parseAction(getOnAllFilesOutOfScope().get(), "onAllFilesOutOfScope"));
        }
        if (getOnUnmappedFile().isPresent()) {
            builder.onUnmappedFile(parseAction(getOnUnmappedFile().get(), "onUnmappedFile"));
        }
        if (getOnDiscoveryEmpty().isPresent()) {
            builder.onDiscoveryEmpty(parseAction(getOnDiscoveryEmpty().get(), "onDiscoveryEmpty"));
        }
        if (getOnDiscoveryIncomplete().isPresent()) {
            builder.onDiscoveryIncomplete(parseAction(getOnDiscoveryIncomplete().get(), "onDiscoveryIncomplete"));
        }
        if (getGradlewTimeoutSeconds().isPresent()) {
            builder.gradlewTimeoutSeconds(getGradlewTimeoutSeconds().get());
        }
        return builder.build();
    }

    // Package-private so AffectedTestTaskLocaleTest can lock in the
    // Locale.ROOT contract — the Turkish-locale failure this guards
    // against is not reachable from any Gradle-level test shape, so
    // direct unit coverage is the only line of defence.
    static Mode parseMode(String raw) {
        try {
            // Locale.ROOT is mandatory here: Turkish-locale JVMs turn
            // "ci".toUpperCase() into "Cİ" (U+0130) and the enum lookup
            // then fails with a misleading "Unknown mode 'ci'" error
            // even though the spelling is literally in the valid list.
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Mention the AUTO fallback explicitly: the most common
            // cause of a bad value here is a CI template that forgot
            // to scrub an unset variable, and the operator needs to
            // know they can just leave the -P off (or pass an empty
            // value — see the plugin's convention wiring) to get the
            // environment-aware default.
            //
            // We list the actual trigger set (mirror
            // AffectedTestsConfig.Builder.detectMode) rather than
            // just "CI=true" because a Jenkins or Azure Pipelines
            // operator reading a "CI=true is exported" hint on
            // their own runner would wrongly conclude AUTO falls
            // back to LOCAL — JENKINS_HOME / TF_BUILD flip it to CI
            // without CI=true ever being set.
            throw new GradleException("Unknown affectedTests.mode '" + raw
                    + "'. Expected one of: auto, local, ci, strict "
                    + "(omit the value or leave -PaffectedTestsMode unset to keep "
                    + "the AUTO default, which picks CI on recognised runners — "
                    + "CI=true, GITHUB_ACTIONS, GITLAB_CI, JENKINS_HOME, CIRCLECI, "
                    + "TRAVIS, BUILDKITE, TF_BUILD).", e);
        }
    }

    static Action parseAction(String raw, String property) {
        try {
            return Action.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GradleException("Unknown " + property + " action '" + raw
                    + "'. Expected one of: selected, full_suite, skipped.", e);
        }
    }

    private void executeTests(Path projectDir,
                              Set<String> testFqns,
                              Map<String, Path> fqnToPath,
                              boolean runAll,
                              long gradlewTimeoutSeconds) {
        String gradleCommand = resolveGradleCommand(projectDir);

        List<String> args = new ArrayList<>();
        args.add(gradleCommand);

        // The previous `runAll || testFqns.isEmpty()` disjunct was stale:
        // the guard in runAffectedTests above already returned when the set
        // was empty and runAll was false, so by the time we reach this branch
        // testFqns.isEmpty() can only be true under runAll.
        if (runAll) {
            // Reason already printed by renderSummary in the caller; keeping
            // it out of this line prevents the "log prints the same phrase
            // twice" grep collision on the "Running ALL tests" marker.
            getLogger().lifecycle("Running ALL tests.");
            args.add("test");
        } else {
            // Group FQNs by owning subproject and emit ":moduleA:test --tests x
            // :moduleB:test --tests y" so Gradle's --tests filters don't spill
            // across modules (where they'd fail any subproject that doesn't
            // happen to contain the FQN).
            Map<String, List<String>> grouped = groupFqnsByModule(projectDir, testFqns, fqnToPath);

            // Validate every discovered FQN up front so the header,
            // per-module preview, and argv-append stay arithmetically
            // consistent. Splitting validation from dispatch also
            // means a module whose entire discovered set is malformed
            // (vanishingly unlikely in practice, but possible from a
            // buggy custom strategy) is dropped cleanly instead of
            // silently falling through to "run the whole module's
            // test suite" once the bare `taskPath` is appended to the
            // argv with no `--tests` filter.
            Map<String, List<String>> validatedGroups = new LinkedHashMap<>(grouped.size());
            int totalValid = 0;
            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                String taskPath = testTaskPath(entry.getKey());
                List<String> valid = new ArrayList<>(entry.getValue().size());
                for (String fqn : entry.getValue()) {
                    if (!isValidFqn(fqn)) {
                        // Defence in depth against a compromised source
                        // tree sneaking shell-like tokens into a --tests
                        // argument. The FQN cannot correspond to a real
                        // JVM test class, so dropping it is lossless.
                        // Sanitise before logging — this is the exact
                        // input shape an attacker-planted filename
                        // containing a newline or ANSI escape would land
                        // in, and WARN is rendered in CI by default.
                        getLogger().warn(
                                "Affected Tests: skipping malformed test FQN '{}' for task {} — "
                                        + "not a Java-shaped identifier, cannot correspond to a "
                                        + "real test class.",
                                LogSanitizer.sanitize(fqn), taskPath);
                        continue;
                    }
                    valid.add(fqn);
                }
                if (!valid.isEmpty()) {
                    validatedGroups.put(taskPath, valid);
                    totalValid += valid.size();
                }
            }

            int skipped = testFqns.size() - totalValid;

            // Belt-and-braces: a dispatch with zero surviving FQNs
            // across ALL modules would otherwise produce an argv of
            // `[gradlew, -x, compileJava]` with no task specified —
            // Gradle's behavior there (fail vs. default-task) is
            // environment-dependent and definitely not the safety
            // posture `runAll` promises. If we get here the WARN logs
            // above already explain which FQNs were dropped; a hard
            // fail surfaces the mis-discovery instead of silently
            // running nothing.
            if (validatedGroups.isEmpty()) {
                throw new GradleException(
                        "Affected Tests: every discovered FQN (" + testFqns.size()
                                + ") was malformed and skipped — refusing to dispatch a taskless "
                                + "Gradle invocation. See WARN logs above for the rejected FQNs.");
            }

            String skippedSuffix = skipped == 0
                    ? ""
                    : " (" + skipped + (skipped == 1 ? " malformed FQN skipped" : " malformed FQNs skipped")
                            + " — see WARN above)";
            getLogger().lifecycle("Running {} affected test classes across {} module(s):{}",
                    totalValid, validatedGroups.size(), skippedSuffix);

            // Dispatch-side emission. Lifecycle per module is bounded
            // by LIFECYCLE_FQN_PREVIEW_LIMIT; the full list stays at
            // info level (see that constant's Javadoc for why).
            for (Map.Entry<String, List<String>> entry : validatedGroups.entrySet()) {
                String taskPath = entry.getKey();
                List<String> validFqns = entry.getValue();

                args.add(taskPath);
                for (String fqn : validFqns) {
                    args.add("--tests");
                    args.add(fqn);
                }

                renderLifecycleDispatchPreview(taskPath, validFqns)
                        .forEach(getLogger()::lifecycle);
                for (String fqn : validFqns) {
                    getLogger().info("  {} -> {}", taskPath, fqn);
                }
            }
        }

        // Skip recompiling production code — testClasses (declared as a task
        // dependency by the plugin) has already been built as part of this
        // outer Gradle invocation, so the class files the nested process needs
        // are on disk. We deliberately do NOT pass -x compileTestJava: on a
        // clean checkout the test classes wouldn't exist yet, and Gradle would
        // fail with "No tests found". Letting compileTestJava run is cheap and
        // correct on all checkouts.
        args.add("-x");
        args.add("compileJava");

        int exitCode = (gradlewTimeoutSeconds > 0)
                ? runWithTimeout(args, projectDir, gradlewTimeoutSeconds)
                : runWithoutTimeout(args, projectDir);

        if (exitCode != 0) {
            throw new GradleException("Test execution failed with exit code " + exitCode);
        }
    }

    /**
     * Pre-v1.9.22 dispatch path: hand the argv to Gradle's
     * {@link ExecOperations} and wait for the child to finish with no
     * deadline. Kept as the default because {@link ExecOperations}
     * integrates with the outer Gradle build's log capture and build-
     * scan infrastructure in ways {@link ProcessBuilder} cannot match
     * from a plugin.
     */
    private int runWithoutTimeout(List<String> args, Path projectDir) {
        ExecResult execResult = getExecOperations().exec(spec -> {
            spec.commandLine(args);
            spec.workingDir(projectDir.toFile());
            spec.setIgnoreExitValue(true);
        });
        return execResult.getExitValue();
    }

    /**
     * Watchdog dispatch path used when
     * {@code affectedTests.gradlewTimeoutSeconds} is set to a positive
     * value. Spawns the child via {@link ProcessBuilder} (so we can
     * hold a {@link Process} handle and call {@link Process#destroyForcibly()}),
     * {@code inheritIO()}'s stdin/stdout/stderr so the user still sees
     * the nested Gradle output in real time, and enforces a wall-clock
     * deadline.
     *
     * <p>Termination ladder on timeout:
     * <ol>
     *   <li>{@link Process#destroy()} — polite SIGTERM; most JVMs react
     *       to this within a second or two, which gives the test
     *       runner a chance to flush coverage reports / screenshots
     *       instead of leaving them half-written.</li>
     *   <li>Wait {@value #TIMEOUT_GRACEFUL_SHUTDOWN_SECONDS}s for the
     *       polite stop to land.</li>
     *   <li>Fall back to {@link Process#destroyForcibly()} — SIGKILL —
     *       so a child that swallowed the SIGTERM cannot keep holding
     *       the CI worker hostage.</li>
     *   <li>Give the kernel another
     *       {@value #TIMEOUT_FORCIBLE_SHUTDOWN_SECONDS}s to reap the
     *       process and fail the build either way.</li>
     * </ol>
     *
     * <p>Process-tree semantics: {@code ./gradlew} almost always
     * connects to a shared Gradle daemon JVM, so the wrapper process
     * is the parent of the daemon only transiently; the test JVM
     * that's actually hung lives as a grandchild (shared daemon) or
     * a sibling (daemon reused across invocations). Killing only the
     * wrapper would therefore leave the hung workload running and
     * defeat the whole point of this knob. {@link #shutdownChild}
     * snapshots {@link ProcessHandle#descendants()} before the first
     * signal and {@code destroyForcibly}-es every still-live
     * descendant on the forcible leg. Operators who want a true
     * "no grace period, no daemon reuse" deadline can pass
     * {@code --no-daemon} at the outer build level.
     *
     * <p>Interrupt handling: if the outer Gradle build is cancelled
     * (Ctrl-C, Gradle daemon shutdown), the watchdog propagates the
     * cancellation by {@code destroyForcibly}-ing the wrapper and
     * every snapshotted descendant, then re-asserts the interrupt
     * on the caller thread.
     */
    // Package-private so AffectedTestTaskTimeoutTest can drive the
    // watchdog directly against a controllable child process. The
    // outer `executeTests` caller is still the only production call
    // site; exposing at package level is the smallest surface that
    // lets the regression test pin the kill-ladder behaviour without
    // rebuilding a DefaultTask harness.
    int runWithTimeout(List<String> args, Path projectDir, long timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(projectDir.toFile());
        // Inherit stdin/stdout/stderr so the child's output (Gradle's
        // test task output, test report progress, etc.) streams to
        // the operator's terminal exactly as it did on the
        // ExecOperations path. We trade build-scan stream capture
        // for the ability to kill the child on timeout; opting in to
        // the timeout is opting in to that trade.
        pb.inheritIO();
        Process process;
        try {
            process = pb.start();
        } catch (java.io.IOException e) {
            throw new GradleException(
                    "Affected Tests: failed to spawn nested Gradle invocation for timeout dispatch: "
                            + LogSanitizer.sanitize(e.getMessage()),
                    e);
        }
        try {
            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                return process.exitValue();
            }
            getLogger().error(
                    "Affected Tests: nested Gradle invocation exceeded configured timeout of {}s; "
                            + "terminating the child process tree.",
                    timeoutSeconds);
            shutdownChild(process);
            throw new GradleException(
                    "Affected Tests: nested Gradle invocation exceeded the configured timeout of "
                            + timeoutSeconds + "s (affectedTests.gradlewTimeoutSeconds). "
                            + "The child process has been terminated. Raise the timeout or "
                            + "investigate a hung test — see the output above for progress.");
        } catch (InterruptedException e) {
            // Outer build was cancelled or the daemon is shutting down.
            // Kill the whole tree immediately so we do not leave
            // orphaned Gradle daemons / test JVMs on the runner, then
            // re-assert the interrupt so Gradle's own shutdown path
            // can continue. Snapshotting descendants before
            // destroyForcibly on the wrapper mirrors shutdownChild —
            // once the wrapper exits, re-parented daemons become
            // unreachable from process.descendants().
            List<ProcessHandle> descendants = process.descendants()
                    .collect(Collectors.toList());
            process.destroyForcibly();
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            Thread.currentThread().interrupt();
            throw new GradleException(
                    "Affected Tests: nested Gradle invocation interrupted before completion.", e);
        }
    }

    private static final int TIMEOUT_GRACEFUL_SHUTDOWN_SECONDS = 10;
    private static final int TIMEOUT_FORCIBLE_SHUTDOWN_SECONDS = 5;

    /**
     * Shuts down the child process tree: sends {@link Process#destroy()}
     * to the wrapper first, waits for a grace period, then escalates
     * to {@link Process#destroyForcibly()} on the wrapper and every
     * still-live descendant.
     *
     * <p>Killing only the wrapper is not enough because
     * {@code ./gradlew} connects to a shared Gradle daemon JVM that
     * outlives the wrapper. On the timeout path the daemon is running
     * a test JVM — that's the actually-hung process that kept the
     * runner busy — and if we only reap the wrapper the daemon / test
     * JVM keep holding the runner hostage, which defeats the entire
     * point of the watchdog. We therefore snapshot
     * {@link ProcessHandle#descendants()} before the first signal
     * (so any daemon / test-JVM grandchildren are captured while
     * still reachable via the wrapper's process tree) and
     * {@code destroyForcibly} each descendant on the forcible leg.
     *
     * <p>The graceful leg still targets only the wrapper so SIGTERM-
     * aware test runners get their normal shutdown hook window to
     * flush coverage reports. Descendants are then reaped
     * {@code destroyForcibly} regardless of whether the wrapper
     * exited gracefully or had to be SIGKILLed, because a SIGTERM-
     * responsive wrapper (plain {@code sh}, most test runners)
     * exits on its own but leaves its children re-parented to
     * pid 1 — those grandchildren are the real hung workload and
     * would keep holding the CI worker hostage otherwise. Operators
     * who need "hard cutoff, no grace" semantics can still run the
     * outer build with {@code --no-daemon}, which re-parents the
     * test JVM as a direct child of the wrapper and makes the polite
     * leg already effective against the daemon too.
     */
    private void shutdownChild(Process process) {
        // Snapshot the descendant tree before we signal — once the
        // wrapper exits the grandchildren may re-parent to pid 1 and
        // {@link ProcessHandle#descendants()} on the wrapper will
        // return empty, leaving orphaned Gradle daemons alive.
        List<ProcessHandle> descendants = process.descendants()
                .collect(Collectors.toList());
        process.destroy();
        try {
            if (!process.waitFor(TIMEOUT_GRACEFUL_SHUTDOWN_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                // Best-effort wait — if SIGKILL itself does not reap
                // the child within this window something is deeply
                // wrong with the host, but we still want to return
                // to the caller so the build can fail cleanly rather
                // than hang here forever.
                process.waitFor(TIMEOUT_FORCIBLE_SHUTDOWN_SECONDS,
                        java.util.concurrent.TimeUnit.SECONDS);
            }
            // Reap any still-live descendants regardless of whether
            // the wrapper exited gracefully or had to be SIGKILLed.
            // A SIGTERM-responsive wrapper (plain `sh`, most test
            // runners) exits on destroy() but leaves its children
            // (daemon / test JVM) re-parented to pid 1 — those
            // grandchildren are the real hung workload and would
            // keep holding the CI worker hostage if we returned
            // here without reaping them.
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Converts a Gradle project path (as returned by
     * {@link #groupFqnsByModule}) into the test task path used for
     * both dispatch argv and the {@code --explain} Modules-block
     * preview. Package-private so the unit tests can pin the root /
     * subproject shapes directly, and shared between the two sites
     * so the two lines of output an operator diffs
     * (explain preview vs dispatch log) cannot drift out of sync.
     *
     * <p>Empty key (= root project) resolves to {@code :test} with a
     * leading colon. The nested Gradle invocation accepts both
     * {@code test} and {@code :test} for the root case, so this
     * normalisation is cosmetic on the dispatch side but keeps the
     * preview unambiguous.
     */
    static String testTaskPath(String modulePath) {
        return modulePath.isEmpty() ? ":test" : modulePath + ":test";
    }

    /**
     * Groups discovered test FQNs by the Gradle path of the subproject that
     * owns each test file. Tests under the root project fall under the
     * empty-string key and get dispatched to the root {@code test} task.
     *
     * <p>If no matching subproject is found for an FQN (e.g. the project
     * structure changed since configuration time), the FQN is routed to the
     * root project as a best-effort fallback.
     */
    private Map<String, List<String>> groupFqnsByModule(Path projectDir,
                                                       Set<String> testFqns,
                                                       Map<String, Path> fqnToPath) {
        Map<String, String> subprojectPaths = getSubprojectPaths().getOrElse(Map.of());

        // Sort entries by descending dir-length so that deeper subprojects
        // (e.g. "services/payment") win over their parents ("services").
        List<Map.Entry<String, String>> orderedEntries = new ArrayList<>(subprojectPaths.entrySet());
        orderedEntries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String fqn : testFqns) {
            Path file = fqnToPath.get(fqn);
            String moduleGradlePath = resolveOwningModule(projectDir, file, orderedEntries);
            grouped.computeIfAbsent(moduleGradlePath, k -> new ArrayList<>()).add(fqn);
        }
        return grouped;
    }

    private String resolveOwningModule(Path projectDir, Path file,
                                       List<Map.Entry<String, String>> orderedEntries) {
        if (file == null) {
            return "";
        }
        Path relative;
        try {
            relative = projectDir.relativize(file.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            return "";
        }
        String normalized = relative.toString().replace(File.separatorChar, '/');
        for (Map.Entry<String, String> entry : orderedEntries) {
            String dir = entry.getKey();
            if (dir.isEmpty()) continue;
            String prefix = dir.endsWith("/") ? dir : dir + "/";
            if (normalized.startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return "";
    }

    /**
     * Resolves the Gradle command to use. Prefers the wrapper in the project directory;
     * falls back to the bare {@code "gradle"} command name so the OS PATH is used.
     */
    private String resolveGradleCommand(Path projectDir) {
        String wrapperName = isWindows() ? "gradlew.bat" : "gradlew";
        File gradlew = projectDir.resolve(wrapperName).toFile();
        if (gradlew.exists() && gradlew.canExecute()) {
            return gradlew.getAbsolutePath();
        }
        // The wrapper is the contract we expect; falling back to a system-wide
        // gradle is a last resort. Warn loudly so broken checkouts don't pass
        // silently in CI.
        getLogger().warn("Gradle wrapper not found at {}/{}; falling back to '{}' from PATH. "
                        + "This usually indicates a broken or incomplete checkout.",
                projectDir, wrapperName, isWindows() ? "gradle.bat" : "gradle");
        return isWindows() ? "gradle.bat" : "gradle";
    }

    /**
     * Matches a dotted sequence of Java identifier segments — the shape
     * Gradle's {@code --tests} accepts. Each segment starts with a
     * letter, {@code _}, or {@code $} and continues with letters,
     * digits, {@code _}, or {@code $}. The trailing segment doubles as
     * either an inner-class name (in the {@code Outer.Inner} form
     * JavaParser emits) or a method name (in the
     * {@code com.example.Foo.someMethod} form Gradle's
     * {@code --tests} matcher expects); the regex does not — and
     * intentionally cannot — distinguish between those two cases, since
     * both are legal argv for the nested Gradle invocation. The
     * bytecode-style {@code Outer$Inner} shape is also accepted because
     * {@code $} is a valid identifier character in Java; discovery does
     * not produce that shape today but users occasionally type it by
     * hand on the command line.
     *
     * <p>Deliberately does NOT reject Java reserved words
     * ({@code if}, {@code class}, {@code return}, ...). The contract
     * of this filter is "is this argv safe to hand to
     * {@code gradle --tests}" — a compromised source tree sneaking
     * shell-like tokens or argv-flag-shaped strings into the FQN list
     * is the threat model. An FQN that happens to look like a reserved
     * word could never be produced by the discovery strategies (which
     * derive names from real {@code .java} filenames), so the only way
     * one reaches this method is adversarially, and the downstream
     * Gradle {@code --tests} matcher will simply report
     * "no tests found" for it — never a compile failure or RCE.
     * Keeping the regex broad here means we don't have to ship a
     * stale list of keywords that drifts as the JLS grows.
     */
    private static final Pattern JAVA_FQN =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    static boolean isValidFqn(String fqn) {
        return fqn != null && !fqn.isEmpty() && JAVA_FQN.matcher(fqn).matches();
    }

    private static boolean isWindows() {
        // Locale.ROOT keeps "Windows" → "windows" on Turkish-locale JVMs;
        // without it the dotted-i rules turn it into "wındows" and the
        // contains("win") check misses, routing Windows runners down the
        // non-Windows branch and picking 'gradlew' / 'gradle' where
        // 'gradlew.bat' / 'gradle.bat' is required.
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * A lifecycle log line expressed as an SLF4J-style format string plus
     * its positional arguments, so the caller can hand both to
     * {@link org.gradle.api.logging.Logger#lifecycle(String, Object...)}
     * and benefit from Gradle's placeholder parser — keeping this task
     * consistent with the other {@code lifecycle(...)} call-sites in the
     * file, and preventing any future phrase containing literal {@code {}}
     * from being swallowed by that parser.
     *
     * <p>Package-private so the log-shape test can assert on either the
     * format string or the rendered output without pulling in Gradle's
     * live logger.
     */
    record LogLine(String format, Object[] args) {
        LogLine {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(args, "args");
            // Defensive copy — otherwise a caller that mutates the array
            // after construction would change the rendered log line.
            // Cheap (always small), and stops the "Object[] component in
            // a record is secretly shared state" class of bugs dead.
            args = args.clone();
        }

        @Override
        public Object[] args() {
            return args.clone();
        }
    }

    /** Marker substring used by both the emission and the tests to pin the WARN. */
    static final String LOCAL_DISCOVERY_INCOMPLETE_WARNING_MARKER =
            "affectedTest: LOCAL mode accepted a partial selection";

    /**
     * Emits a lifecycle-level WARN when the resolved run is a LOCAL-mode
     * {@link Situation#DISCOVERY_INCOMPLETE} + {@link Action#SELECTED}
     * combination — i.e. the workstation default that silently trusts
     * a partial discovery set after the Java parser dropped one or
     * more files. Deliberately WARN (not INFO): Gradle renders WARN in
     * the default log level, so operators don't need to opt in via
     * {@code --info} to see the risk.
     *
     * <p>Called before the {@code --explain} / dispatch fork so both
     * paths surface the same concern: a diagnostic run reading the
     * same partial selection that a non-diagnostic run would actually
     * dispatch must raise the same alarm. Fires at most once per
     * task execution — there is only one resolved situation per run.
     *
     * <p>The gate logic lives in the pure {@link #shouldWarnLocalDiscoveryIncomplete}
     * and message formatting in {@link #formatLocalDiscoveryIncompleteWarning};
     * this instance method only pipes the result through the task's
     * Gradle logger. Unit tests exercise the pure pair directly so
     * the four-way gate (mode, situation, action, skipped/empty) is
     * locked in without a log-capture fixture.
     */
    void warnIfLocalDiscoveryIncompleteSelected(AffectedTestsConfig config,
                                                AffectedTestsResult result) {
        if (!shouldWarnLocalDiscoveryIncomplete(config, result)) {
            return;
        }
        // Intentionally does NOT restate the parse-failure fact — the
        // engine's own WARN (rendered a few lines above, with file-level
        // detail) already did that. This line's job is the mode-specific
        // postscript the engine can't emit (it doesn't know the mode):
        // LOCAL chose to honour a partial selection, here's how to
        // escalate if you'd rather not.
        getLogger().warn(formatLocalDiscoveryIncompleteWarning(result.testClassFqns().size()));
    }

    /**
     * Pure gate for {@link #warnIfLocalDiscoveryIncompleteSelected}.
     * Returns {@code true} iff the WARN should fire. Package-private
     * for direct unit-test coverage of each guard:
     * <ul>
     *   <li>Non-LOCAL modes never warn (CI / STRICT escalate, no
     *       under-testing risk).</li>
     *   <li>Only DISCOVERY_INCOMPLETE ever warns (parse failure is
     *       the only "selection was quietly partial" shape).</li>
     *   <li>Only SELECTED ever warns (FULL_SUITE / SKIPPED either
     *       already escalated or already bailed, so there is no
     *       silent partial selection to surface).</li>
     *   <li>Skipped results or empty FQN lists short-circuit — the
     *       engine rewrites "SELECTED with nothing to select" into
     *       {@code skipped=true}, and a zero-count WARN immediately
     *       before the task bails would contradict itself.</li>
     * </ul>
     */
    static boolean shouldWarnLocalDiscoveryIncomplete(AffectedTestsConfig config,
                                                      AffectedTestsResult result) {
        if (config.effectiveMode() != Mode.LOCAL) {
            return false;
        }
        if (result.situation() != Situation.DISCOVERY_INCOMPLETE) {
            return false;
        }
        if (result.action() != Action.SELECTED) {
            return false;
        }
        if (result.skipped() || result.testClassFqns().isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * Formats the Risk C WARN line. Package-private so tests can pin
     * both the stable marker substring (for grep-based alerting
     * assertions) and the singular/plural "test class"/"test classes"
     * toggle on the FQN count without reaching into a log capture.
     */
    static String formatLocalDiscoveryIncompleteWarning(int selectedCount) {
        String classWord = selectedCount == 1 ? "test class" : "test classes";
        return LOCAL_DISCOVERY_INCOMPLETE_WARNING_MARKER + " of " + selectedCount
                + " " + classWord + " — see the discovery WARN above for the files "
                + "that failed to parse. Fix the parse error to recover a precise "
                + "selection, or set onDiscoveryIncomplete = 'full_suite' to escalate "
                + "(CI and STRICT already do).";
    }

    /**
     * Renders an {@link EscalationReason} as a short human-readable phrase
     * suitable for a lifecycle log line. Package-private so the log-shape
     * test can pin the exact wording; kept in one place so the summary and
     * any downstream "Running ALL tests" lines cannot drift into
     * contradictory phrasing.
     *
     * <p>{@link EscalationReason#NONE} is rejected: this helper is called
     * only on {@code runAll} results, and a {@code runAll=true + NONE}
     * combination is an engine bug rather than a log-formatting concern.
     * Throwing here ensures such a drift is loud (build fails) instead of
     * silently surfacing a placeholder phrase to CI.
     */
    static String describeEscalation(EscalationReason reason) {
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case RUN_ALL_ON_NON_JAVA_CHANGE ->
                    "onUnmappedFile=FULL_SUITE — non-Java or unmapped file in diff";
            case RUN_ALL_ON_EMPTY_CHANGESET ->
                    "onEmptyDiff=FULL_SUITE — no changed files detected";
            case RUN_ALL_IF_NO_MATCHES ->
                    "onDiscoveryEmpty=FULL_SUITE — no affected tests discovered";
            case RUN_ALL_ON_ALL_FILES_IGNORED ->
                    "onAllFilesIgnored=FULL_SUITE — every changed file matched ignorePaths";
            case RUN_ALL_ON_ALL_FILES_OUT_OF_SCOPE ->
                    "onAllFilesOutOfScope=FULL_SUITE — every changed file sat under out-of-scope dirs";
            case RUN_ALL_ON_DISCOVERY_INCOMPLETE ->
                    "onDiscoveryIncomplete=FULL_SUITE — discovery observed unparseable Java files, selection may be incomplete";
            case NONE -> throw new IllegalStateException(
                    "describeEscalation must not be called for EscalationReason.NONE; "
                            + "the engine should only produce NONE on non-runAll results");
        };
    }

    /** Cap on files listed per bucket in the {@code --explain} trace. */
    static final int EXPLAIN_SAMPLE_LIMIT = 10;

    /**
     * Cap on FQNs listed at lifecycle level per module in the
     * "Running N affected test classes" dispatch output. Chosen at 5
     * to keep the preview tight enough that a reviewer can read it
     * without scrolling yet large enough to sanity-check selection on
     * most MRs, which dispatch single digits of classes per module.
     * Larger dispatches still log every FQN at info level — this cap
     * exists only to keep the default lifecycle log bounded and well
     * under the 4 MiB GitHub Actions step cap that forced the
     * per-FQN demotion in pre-v1.9.18 versions.
     */
    static final int LIFECYCLE_FQN_PREVIEW_LIMIT = 5;

    /**
     * Renders the lifecycle-level dispatch preview for a single
     * module: the summary line, then up to
     * {@link #LIFECYCLE_FQN_PREVIEW_LIMIT} FQNs indented underneath,
     * and — when the dispatch exceeds the preview limit — a single
     * "… and N more (use --info for full list)" tail.
     *
     * <p>Package-private so
     * {@code AffectedTestTaskDispatchPreviewTest} can pin the format
     * without spinning up the Gradle runtime. The helper is pure over
     * its inputs, so the test treats it as a pure function and the
     * caller in {@link #executeTests} just pipes each returned line
     * to {@link org.gradle.api.logging.Logger#lifecycle(String)}.
     *
     * @param taskPath the Gradle task path the dispatch targets (for
     *                 example {@code "application:test"})
     * @param fqns     the validated FQNs being dispatched, in the
     *                 order they will be passed to Gradle; preserving
     *                 this order in the preview keeps the mental map
     *                 from "what did I change" to "what is running"
     *                 intact for the operator
     */
    static List<String> renderLifecycleDispatchPreview(String taskPath, List<String> fqns) {
        List<String> lines = new ArrayList<>(Math.min(fqns.size(), LIFECYCLE_FQN_PREVIEW_LIMIT) + 2);
        int size = fqns.size();
        String plural = size == 1 ? "" : "es";
        lines.add("  " + taskPath + " (" + size + " test class" + plural + ")");
        int preview = Math.min(size, LIFECYCLE_FQN_PREVIEW_LIMIT);
        for (int i = 0; i < preview; i++) {
            lines.add("    " + fqns.get(i));
        }
        if (size > preview) {
            lines.add("    … and " + (size - preview) + " more (use --info for full list)");
        }
        return lines;
    }

    /**
     * Renders the human-readable decision trace produced by
     * {@code affectedTest --explain}. Returned as a list of lines so the
     * caller can hand each line to {@link org.gradle.api.logging.Logger#lifecycle(String)}
     * (no format placeholders — the content is pre-rendered) and so tests
     * can pin the exact shape without the live logger.
     *
     * <p>Every section names the source of the decision so an operator
     * can see at a glance whether the action came from an explicit
     * {@code onXxx} setting or the mode default table (the v2 two-tier
     * resolver has no other sources).
     *
     * <p>Package-private so {@code AffectedTestTaskExplainFormatTest}
     * can assert the format without spinning up Gradle.
     *
     * @param moduleGroups ordered map of {@code :module:test} task
     *                     path → list of FQNs dispatched to that
     *                     module. Pass {@link Map#of()} when no
     *                     per-module breakdown applies (every
     *                     situation other than
     *                     {@link Situation#DISCOVERY_SUCCESS +
     *                     SELECTED}). The renderer skips the
     *                     "Modules:" block when the map is empty, so
     *                     the trace stays compact on non-selective
     *                     runs.
     */
    static List<String> renderExplainTrace(AffectedTestsConfig config, AffectedTestsResult result) {
        // 2-arg overload: preserves the signature every unit test
        // was written against before v2.2 added the per-module
        // breakdown. The module block is a DISCOVERY_SUCCESS-only
        // diagnostic that every non-dispatch unit test can safely
        // skip by threading an empty map through to the 3-arg
        // renderer.
        return renderExplainTrace(config, result, Map.of());
    }

    static List<String> renderExplainTrace(AffectedTestsConfig config,
                                           AffectedTestsResult result,
                                           Map<String, List<String>> moduleGroups) {
        List<String> lines = new ArrayList<>();
        lines.add("=== Affected Tests — decision trace (--explain) ===");
        lines.add("Base ref:        " + config.baseRef());
        // config.mode() is guaranteed non-null in v2 (defaults to AUTO when
        // the user doesn't set it), so we don't branch on null here — doing
        // so would only re-introduce the pre-v2 "unset" rendering we
        // deliberately dropped. effectiveMode() is also always non-null
        // (zero-config callers get the AUTO-detected value, identical to
        // what an explicit `mode = "auto"` would have resolved to).
        lines.add("Mode:            " + config.mode().name()
                + " (effective: " + config.effectiveMode().name() + ")");
        lines.add("Changed files:   " + result.changedFiles().size());

        Buckets buckets = result.buckets();
        lines.add("Buckets:");
        lines.add("  ignored         " + buckets.ignoredFiles().size());
        lines.add("  out-of-scope    " + buckets.outOfScopeFiles().size());
        lines.add("  production .java " + buckets.productionFiles().size());
        lines.add("  test .java      " + buckets.testFiles().size());
        lines.add("  unmapped        " + buckets.unmappedFiles().size());

        appendSample(lines, "ignored",      buckets.ignoredFiles());
        appendSample(lines, "out-of-scope", buckets.outOfScopeFiles());
        appendSample(lines, "production",   buckets.productionFiles());
        appendSample(lines, "test",         buckets.testFiles());
        appendSample(lines, "unmapped",     buckets.unmappedFiles());

        ActionSource source = config.actionSourceFor(result.situation());
        lines.add("Situation:       " + result.situation().name());
        lines.add("Action:          " + result.action().name()
                + " (source: " + describeSource(source) + ")");

        String outcome;
        if (result.runAll()) {
            outcome = "FULL_SUITE — " + describeEscalation(result.escalationReason());
        } else if (result.skipped()) {
            outcome = "SKIPPED — no tests will run";
        } else if (result.action() == Action.SELECTED) {
            outcome = "SELECTED — " + result.testClassFqns().size()
                    + " test class(es) will run";
        } else {
            outcome = result.action().name();
        }
        lines.add("Outcome:         " + outcome);

        // Diagnostic hint: pick the hint that actually matches the
        // situation the operator is staring at. Earlier versions
        // unconditionally printed the out-of-scope hint on every
        // mapper-touching situation, which meant a DISCOVERY_EMPTY run
        // (no test mapped to the changed prod class) or
        // DISCOVERY_INCOMPLETE run (parse failure dropped a file from
        // the mapper) would show OOS advice that had nothing to do
        // with the actual problem. v2.2 splits the hint into three
        // targeted branches — see {@link #appendSituationHint} for the
        // full routing.
        appendSituationHint(lines, config, result);

        // Per-module dispatch preview — populated only for
        // SELECTED runs so the "what tasks will Gradle actually
        // kick off?" question can be answered directly from the
        // explain trace instead of a dry-run dispatch. On every
        // other outcome the map is empty and we skip the block
        // entirely to keep the trace compact. Shares the same
        // {@link #groupFqnsByModule} as the real dispatch path so a
        // SELECTED --explain line can never contradict what the
        // next non-explain run will actually execute.
        appendModulesBlock(lines, moduleGroups);

        // The full action matrix is cheap to print (five rows) and
        // invaluable for debugging "why did my explicit setting not
        // win?" — so we always include it, not only on ambiguous
        // branches. Rows are rendered in a stable order matching the
        // Situation javadoc's evaluation order so greps/diffs stay
        // stable across runs.
        lines.add("Action matrix (situation → action [source]):");
        for (Situation s : situationOrder()) {
            ActionSource rowSource = config.actionSourceFor(s);
            lines.add(String.format(Locale.ROOT, "  %-24s %s [%s]",
                    s.name(), config.actionFor(s).name(), describeSource(rowSource)));
        }
        lines.add("=== end --explain ===");
        return lines;
    }

    /**
     * Routes to the situation-specific hint block for the
     * {@code --explain} trace. Kept package-private so
     * {@code AffectedTestTaskExplainFormatTest} can pin the exact
     * conditions without spinning up Gradle.
     *
     * <p>Only the three mapper-touching situations produce hints in
     * the v2.2+ trace: {@link Situation#DISCOVERY_SUCCESS} carries the
     * original "out-of-scope configured but nothing matched"
     * misconfiguration tell; {@link Situation#DISCOVERY_EMPTY} calls
     * out the "mapped 0 tests" case with naming-suffix advice; and
     * {@link Situation#DISCOVERY_INCOMPLETE} flags the parse-failure
     * path so an operator doesn't silently accept a partial selection.
     * The other four situations ({@link Situation#EMPTY_DIFF},
     * {@link Situation#ALL_FILES_IGNORED},
     * {@link Situation#ALL_FILES_OUT_OF_SCOPE},
     * {@link Situation#UNMAPPED_FILE}) reach their outcome for reasons
     * none of these hints can usefully add to — so we stay silent and
     * let the {@code Outcome:} line speak for itself.
     */
    static void appendSituationHint(List<String> lines,
                                    AffectedTestsConfig config,
                                    AffectedTestsResult result) {
        // Note on the removed `changedFiles().isEmpty()` guard that
        // lived here pre-v2.2.1: the three situations the switch routes
        // (DISCOVERY_SUCCESS / DISCOVERY_EMPTY / DISCOVERY_INCOMPLETE)
        // all definitionally require at least one changed file to
        // reach them — EMPTY_DIFF is the "no files" situation and it
        // falls through the default branch. The guard was unreachable
        // in production and gave a misleading impression that
        // changed-file-count was part of the hint gate, so deleting
        // it keeps the dispatch contract legible.
        switch (result.situation()) {
            case DISCOVERY_SUCCESS -> {
                appendOutOfScopeMisconfigHint(lines, config, result);
                // Independent diagnostic from the OOS-misconfig hint —
                // both can fire on the same DISCOVERY_SUCCESS run when
                // naming over-selects across packages and the
                // out-of-scope dirs are silently misconfigured. They
                // describe orthogonal misconfigurations, so the
                // operator gets both signals; the renderer ordering
                // (OOS first, naming second) follows decreasing
                // signal-to-noise — OOS misconfig is more likely to
                // explain "why is the suite running at all" while the
                // naming hint refines "which tests in particular look
                // questionable".
                appendNamingCrossPackageHint(lines, result);
            }
            case DISCOVERY_EMPTY       -> appendDiscoveryEmptyHint(lines, config, result);
            case DISCOVERY_INCOMPLETE  -> appendDiscoveryIncompleteHint(lines, result);
            default                    -> {
                // No hint for EMPTY_DIFF / ALL_FILES_IGNORED /
                // ALL_FILES_OUT_OF_SCOPE / UNMAPPED_FILE — see
                // method-level javadoc for the per-situation rationale.
            }
        }
    }

    /**
     * Fires on {@link Situation#DISCOVERY_SUCCESS} when
     * {@code outOfScopeTestDirs} / {@code outOfScopeSourceDirs} were
     * configured but no file in the diff matched any of them. The
     * heuristic catches the silent-misconfig case that lands
     * OOS-shaped MRs (docs-only, tooling-only) into
     * DISCOVERY_SUCCESS-instead-of-SKIPPED: wrong path, wrong glob
     * shape, trailing-slash typo. Suppressed on runs where the OOS
     * bucket actually non-empty — the configuration clearly works on
     * this diff, so firing would only train reviewers to ignore the
     * hint.
     */
    private static void appendOutOfScopeMisconfigHint(List<String> lines,
                                                      AffectedTestsConfig config,
                                                      AffectedTestsResult result) {
        if (!result.buckets().outOfScopeFiles().isEmpty()) {
            return;
        }
        int testEntries = config.outOfScopeTestDirs().size();
        int sourceEntries = config.outOfScopeSourceDirs().size();
        int totalEntries = testEntries + sourceEntries;
        if (totalEntries == 0) {
            return;
        }

        List<String> configuredKnobs = new ArrayList<>(2);
        if (testEntries > 0) {
            configuredKnobs.add("outOfScopeTestDirs");
        }
        if (sourceEntries > 0) {
            configuredKnobs.add("outOfScopeSourceDirs");
        }
        String knobs = String.join(" / ", configuredKnobs);
        String verb = configuredKnobs.size() == 1 ? "is" : "are";
        String entryWord = totalEntries == 1 ? "entry" : "entries";

        lines.add("Hint:            " + knobs + " " + verb + " configured ("
                + totalEntries + " " + entryWord + ") but no file in the diff matched.");
        lines.add("                 Values are directory prefixes "
                + "(e.g. 'api-test/src/test/java') or globs (e.g. 'api-test/**').");
    }

    /**
     * Fires on {@link Situation#DISCOVERY_SUCCESS} when
     * {@link NamingConventionStrategy} added at least one test whose
     * package differs from the changed production class's package. The
     * naming strategy is intentionally package-agnostic (issue #40
     * rationale: parallel test trees and Cucumber-shape harnesses
     * legitimately keep tests outside the SUT's package, so flipping
     * to a strict same-package match would silently under-select), so
     * over-selection is the policy's known failure mode; this hint
     * surfaces it without flipping the policy. Quiet when there are
     * no cross-package matches — the silent-success path stays silent.
     *
     * <p>Caps the listed pairs at {@link #EXPLAIN_SAMPLE_LIMIT} to
     * keep the trace readable on monorepos where dozens of FQNs may
     * over-select; an "(+N more)" suffix preserves the total so an
     * operator can decide whether to dig deeper.
     */
    private static void appendNamingCrossPackageHint(List<String> lines, AffectedTestsResult result) {
        Map<String, Set<String>> matches = result.namingCrossPackageMatches();
        if (matches.isEmpty()) {
            return;
        }
        int total = matches.values().stream().mapToInt(Set::size).sum();
        String pairWord = total == 1 ? "test" : "tests";
        lines.add("Hint:            naming strategy selected " + total
                + " " + pairWord + " whose package differs from the "
                + "changed production class's package — possible "
                + "cross-package simple-name collision.");
        int rendered = 0;
        for (var entry : matches.entrySet()) {
            for (String testFqn : entry.getValue()) {
                if (rendered >= EXPLAIN_SAMPLE_LIMIT) {
                    break;
                }
                lines.add("                  * " + entry.getKey() + " → " + testFqn);
                rendered++;
            }
            if (rendered >= EXPLAIN_SAMPLE_LIMIT) {
                break;
            }
        }
        if (total > rendered) {
            lines.add("                  … (+" + (total - rendered)
                    + " more — set --info to log every naming match)");
        }
        lines.add("                 If this is a false positive (different "
                + "production class, same simple name), the test is still "
                + "selected — over-select is the documented trade-off for "
                + "naming. Move the test into the same package as the SUT, "
                + "or rename one of the colliding production classes, to "
                + "make the match precise.");
    }

    /**
     * Fires on {@link Situation#DISCOVERY_EMPTY} — the engine mapped
     * production .java changes but no test class matched any strategy.
     * v2.1 printed the OOS hint here which was actively misleading
     * because the OOS bucket by definition never influenced the
     * outcome; v2.2 swaps in the three things that actually produce
     * an empty discovery set: test-file naming mismatches the
     * configured suffix list, the test lives outside the configured
     * {@code testDirs}, or the prod class genuinely has no test
     * coverage yet. We enumerate the first two with the operator's
     * actual config values so the hint is self-checking.
     */
    private static void appendDiscoveryEmptyHint(List<String> lines,
                                                 AffectedTestsConfig config,
                                                 AffectedTestsResult result) {
        int prodFileCount = result.buckets().productionFiles().size();
        String fileWord = prodFileCount == 1 ? "file" : "files";
        lines.add("Hint:            discovery mapped 0 test classes to the "
                + prodFileCount + " changed production " + fileWord + ".");
        lines.add("                 Common causes:");
        lines.add("                  * test name does not match testSuffixes "
                + formatInlineList(config.testSuffixes())
                + " (e.g. Foo.java → FooTest.java / FooIT.java)");
        lines.add("                  * test lives outside testDirs "
                + formatInlineList(config.testDirs()));
        lines.add("                  * the production class has no test coverage yet");
    }

    /**
     * Fires on {@link Situation#DISCOVERY_INCOMPLETE} — one or more
     * Java files in the diff failed to parse, so the mapper ran with
     * missing inputs. Two shapes reach this hint:
     *
     * <ul>
     *   <li>{@link Action#SELECTED} — LOCAL-mode default. The discovered
     *       selection is definitionally partial, and the hint names
     *       that risk explicitly plus the escalation knob an operator
     *       can flip to move off the partial-selection default. Pairs
     *       with the lifecycle WARN in
     *       {@link #warnIfLocalDiscoveryIncompleteSelected}.</li>
     *   <li>{@link Action#FULL_SUITE} — CI/STRICT default, or an
     *       explicit {@code onDiscoveryIncomplete='full_suite'}
     *       override. The whole suite runs, so "partial selection"
     *       wording is actively wrong and "let onDiscoveryIncomplete
     *       escalate" is circular — escalation already happened. We
     *       render a trimmed hint that just names the parse failure
     *       and the precise-selection follow-up.</li>
     * </ul>
     *
     * <p>We don't count "parse failures" directly because the engine
     * doesn't surface that number today; an operator who wants the
     * exact file list reads the INFO-level engine log. The hint stays
     * action-shape-agnostic on the file count so it can't drift into
     * "0 files failed to parse" wording on edge cases.
     */
    private static void appendDiscoveryIncompleteHint(List<String> lines,
                                                      AffectedTestsResult result) {
        // Every branch opens with the same root-cause line — the parse
        // failure is the operator-actionable fact regardless of what
        // the mode chose to do about it. Only the follow-on guidance
        // differs per resolved Action.
        lines.add("Hint:            one or more Java files in the diff failed to parse, "
                + "so discovery ran with missing inputs.");
        switch (result.action()) {
            case SELECTED -> lines.add("                 The resolved selection is "
                    + "necessarily partial — fix the parse error to recover a precise "
                    + "selection, or set onDiscoveryIncomplete = 'full_suite' to "
                    + "escalate (CI and STRICT modes already do).");
            case FULL_SUITE -> lines.add("                 Fix the parse error to "
                    + "recover a precise selection on future runs (until then the "
                    + "resolved action above is the safe fallback).");
            // Reachable only via an explicit `onDiscoveryIncomplete =
            // 'skipped'` DSL override — unusual but legal. SKIPPED is
            // the OPPOSITE of safe here (we neither narrowed nor
            // escalated; we just didn't run tests), so the hint must
            // not call it "the safe fallback" the way FULL_SUITE does.
            // We name the opt-in knob and offer the two sane exits
            // (fix the parse error, or flip the knob to 'full_suite')
            // without implying the current state is acceptable.
            case SKIPPED -> lines.add("                 onDiscoveryIncomplete = "
                    + "'skipped' meant no tests ran for this diff — fix the parse "
                    + "error to restore coverage, or set onDiscoveryIncomplete = "
                    + "'full_suite' if silently skipping a partial-parse diff is "
                    + "not the intended policy.");
        }
    }

    private static String formatInlineList(List<String> items) {
        if (items.isEmpty()) {
            return "[]";
        }
        return items.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Renders the "Modules:" section of the {@code --explain} trace.
     * No-op when the map is empty — every non-SELECTED run threads an
     * empty map through so those traces stay compact. On SELECTED
     * runs we list each {@code :module:test} target with its class
     * count, then up to
     * {@link #LIFECYCLE_FQN_PREVIEW_LIMIT} FQNs per module, matching
     * the same preview cap the actual dispatch path uses — so an
     * operator comparing an {@code --explain} trace against a
     * subsequent non-explain run sees the same preview shape.
     *
     * <p>Package-private so
     * {@code AffectedTestTaskExplainFormatTest} can pin both the
     * empty-map fast path and the preview shape without spinning up
     * Gradle.
     */
    static void appendModulesBlock(List<String> lines, Map<String, List<String>> moduleGroups) {
        if (moduleGroups == null || moduleGroups.isEmpty()) {
            return;
        }
        int totalFqns = moduleGroups.values().stream().mapToInt(List::size).sum();
        String moduleWord = moduleGroups.size() == 1 ? "module" : "modules";
        String classWord = totalFqns == 1 ? "class" : "classes";
        lines.add("Modules:         " + moduleGroups.size() + " " + moduleWord + ", "
                + totalFqns + " test " + classWord + " to dispatch");
        for (Map.Entry<String, List<String>> entry : moduleGroups.entrySet()) {
            // Contract: callers pass the key through
            // {@link #testTaskPath(String)}, which guarantees a
            // canonical ":module:test" / ":test" shape with a leading
            // colon. We assert rather than silently re-normalise so
            // any future caller that forgets the helper breaks loudly
            // in tests instead of producing a ":x" / "x" mixed trace.
            String taskPath = entry.getKey();
            List<String> fqns = entry.getValue();
            assert taskPath.startsWith(":") : "appendModulesBlock expects testTaskPath-shaped "
                    + "keys (leading ':'); got '" + taskPath + "' — route through "
                    + "AffectedTestTask#testTaskPath before inserting into the map";
            int size = fqns.size();
            String rowClassWord = size == 1 ? "class" : "classes";
            lines.add("  " + taskPath + " (" + size + " test " + rowClassWord + ")");
            int preview = Math.min(size, LIFECYCLE_FQN_PREVIEW_LIMIT);
            for (int i = 0; i < preview; i++) {
                lines.add("    " + fqns.get(i));
            }
            if (size > preview) {
                lines.add("    … and " + (size - preview) + " more (use --info for full list)");
            }
        }
    }

    private static void appendSample(List<String> lines, String label, Set<String> files) {
        if (files.isEmpty()) {
            return;
        }
        // Filenames originate from the git diff of an (on the merge-gate,
        // attacker-controllable) MR tree. Sanitise before they reach the
        // logger — see LogSanitizer for the full log-forgery rationale.
        String preview = files.stream()
                .sorted()
                .limit(EXPLAIN_SAMPLE_LIMIT)
                .map(LogSanitizer::sanitize)
                .collect(Collectors.joining(", "));
        if (files.size() > EXPLAIN_SAMPLE_LIMIT) {
            preview = preview + ", … (+" + (files.size() - EXPLAIN_SAMPLE_LIMIT) + " more)";
        }
        lines.add("  " + label + " sample: " + preview);
    }

    /**
     * Situation order used by the explain trace and anywhere else we
     * render a full per-situation matrix. Matches the evaluation order
     * documented on {@link Situation} so operators can read the trace
     * top-to-bottom and mentally simulate the engine without cross-
     * referencing another doc.
     */
    private static List<Situation> situationOrder() {
        // Mirrors the engine's evaluation order in
        // {@link io.affectedtests.core.AffectedTestsEngine#run()}:
        // DISCOVERY_INCOMPLETE short-circuits before DISCOVERY_EMPTY /
        // DISCOVERY_SUCCESS whenever parseFailureCount > 0. The
        // --explain trace has to list the situations in the same
        // order an operator reads the engine logs, otherwise the
        // matrix reads as a lie when a parse-failure run elides
        // DISCOVERY_EMPTY.
        return List.of(
                Situation.EMPTY_DIFF,
                Situation.ALL_FILES_IGNORED,
                Situation.ALL_FILES_OUT_OF_SCOPE,
                Situation.UNMAPPED_FILE,
                Situation.DISCOVERY_INCOMPLETE,
                Situation.DISCOVERY_EMPTY,
                Situation.DISCOVERY_SUCCESS
        );
    }

    private static String describeSource(ActionSource source) {
        return switch (source) {
            case EXPLICIT     -> "explicit onXxx setting";
            case MODE_DEFAULT -> "mode default";
        };
    }

    /**
     * Builds the single summary line printed to Gradle's lifecycle log
     * for every {@code affectedTest} run. Every branch names the
     * outcome ({@link Action#SELECTED}, {@link Action#FULL_SUITE},
     * {@link Action#SKIPPED}) and the {@link Situation} that produced
     * it, followed by the file count and branch-specific details.
     *
     * <p>Shape:
     * <pre>
     * Affected Tests: SELECTED (DISCOVERY_SUCCESS) — N changed file(s), P production class(es), T test class(es) affected
     * Affected Tests: FULL_SUITE (UNMAPPED_FILE) — N changed file(s); running full suite (reason)
     * Affected Tests: SKIPPED (ALL_FILES_IGNORED) — N changed file(s); every changed file matched ignorePaths
     * </pre>
     *
     * <p>Pluralisation is deliberately fixed to {@code file(s)} and
     * {@code class(es)} across every branch so CI greps stay stable
     * across runs with different selection sizes.
     */
    static LogLine renderSummary(AffectedTestsResult result) {
        String prefix = "Affected Tests: " + result.action().name()
                + " (" + result.situation().name() + ") — ";
        if (result.runAll()) {
            // The "running full suite (reason)" phrase is the single
            // place the reason string shows up in the summary line.
            // Reason phrase is sourced from describeEscalation to
            // avoid duplicating the vocabulary across two files.
            return new LogLine(
                    prefix + "{} changed file(s); running full suite ({}).",
                    new Object[] {
                            result.changedFiles().size(),
                            describeEscalation(result.escalationReason())
                    });
        }
        if (result.skipped()) {
            return new LogLine(
                    prefix + "{} changed file(s); {}.",
                    new Object[] {
                            result.changedFiles().size(),
                            describeSkipReason(result.situation(), result.action())
                    });
        }
        return new LogLine(
                prefix + "{} changed file(s), {} production class(es), {} test class(es) affected",
                new Object[] {
                        result.changedFiles().size(),
                        result.changedProductionClasses().size(),
                        result.testClassFqns().size()
                });
    }

    /**
     * Renders a {@link Situation} as a short human-readable phrase
     * suitable for the summary line's {@code SKIPPED} branch. Only the
     * ambiguous situations can legitimately resolve to
     * {@link Action#SKIPPED}; {@link Situation#DISCOVERY_SUCCESS} is
     * rejected because the engine never skips when it found tests.
     *
     * <p>Takes the resolved {@link Action} because one situation
     * ({@link Situation#DISCOVERY_INCOMPLETE}) can legitimately reach
     * the "skipped" rendering path with {@code SELECTED} as well as
     * {@code SKIPPED}. The engine routes
     * {@code DISCOVERY_INCOMPLETE + SELECTED} with an empty selection
     * through the skipped-summary branch in the task summary (the
     * `skipped` flag is set by {@link io.affectedtests.core.AffectedTestsEngine#emptyResult}
     * whenever a non-SUCCESS situation winds up with zero tests),
     * so a situation-only rendering would print the {@code SKIPPED}
     * literal for a run the user actually configured as {@code SELECTED}.
     * Branching on the action keeps the phrase truthful without
     * requiring operators to reconcile two different action labels
     * in the same summary line.
     *
     * <p>Package-private so {@code AffectedTestTaskLogFormatTest} can
     * pin the exact wording — mirrors {@link #describeEscalation} so
     * the two halves of the summary log share one vocabulary.
     */
    static String describeSkipReason(Situation situation, Action action) {
        Objects.requireNonNull(situation, "situation");
        Objects.requireNonNull(action, "action");
        return switch (situation) {
            case EMPTY_DIFF ->
                    "no changed files detected";
            case ALL_FILES_IGNORED ->
                    "every changed file matched ignorePaths";
            case ALL_FILES_OUT_OF_SCOPE ->
                    "every changed file sat under out-of-scope dirs";
            case UNMAPPED_FILE ->
                    "onUnmappedFile=SKIPPED — non-Java or unmapped file in diff";
            case DISCOVERY_EMPTY ->
                    "no affected tests discovered";
            case DISCOVERY_INCOMPLETE -> action == Action.SELECTED
                    ? "onDiscoveryIncomplete=SELECTED — no affected tests matched the parsed files"
                    : "onDiscoveryIncomplete=SKIPPED — discovery observed unparseable files";
            case DISCOVERY_SUCCESS -> throw new IllegalStateException(
                    "describeSkipReason must not be called for DISCOVERY_SUCCESS; "
                            + "the engine only produces that situation on a non-empty "
                            + "selection, which is never skipped");
        };
    }
}
