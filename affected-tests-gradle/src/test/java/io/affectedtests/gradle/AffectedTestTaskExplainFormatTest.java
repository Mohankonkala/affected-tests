package io.affectedtests.gradle;

import io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult;
import io.affectedtests.core.AffectedTestsEngine.Buckets;
import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.config.Situation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the {@code affectedTest --explain} trace so the
 * operator experience — the only visible contract for this flag — cannot
 * regress unnoticed. The renderer is a pure function over
 * {@link AffectedTestsConfig} and {@link AffectedTestsResult}, so the
 * tests call it directly instead of spinning up a Gradle test runtime.
 *
 * <p>Every assertion targets a separate guarantee: the trace carries the
 * bucket breakdown, names the situation, names the action, exposes which
 * tier of the priority ladder picked that action, and always emits the
 * full per-situation matrix so operators can see setting interactions at
 * a glance.
 */
class AffectedTestTaskExplainFormatTest {

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void traceIncludesHeaderAndFooterFromAnySituation() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.startsWith("=== Affected Tests — decision trace (--explain) ==="),
                "Header must be the first line so operators (and log greps) can pin trace start");
        assertTrue(trace.endsWith("=== end --explain ==="),
                "Footer must close the trace so multi-run CI logs stay parseable");
    }

    @Test
    void bucketBreakdownReportsAllFiveCountsWithSamples() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        Buckets buckets = new Buckets(
                Set.of("README.md"),
                Set.of("api-test/src/test/java/FooSteps.java"),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("src/test/java/com/example/FooTest.java"),
                Set.of("build.gradle", "application.yml"));
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("README.md", "build.gradle", "application.yml",
                        "src/main/java/com/example/Foo.java",
                        "src/test/java/com/example/FooTest.java",
                        "api-test/src/test/java/FooSteps.java"),
                Set.of("com.example.Foo"), Set.of("com.example.FooTest"),
                buckets,
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("ignored         1"),
                "Ignored bucket count must surface — otherwise operators can't tell that a "
                        + "zero-config README-only MR didn't just 'do nothing for no reason'");
        assertTrue(trace.contains("out-of-scope    1"),
                "Out-of-scope count must surface separately from ignored — they come from "
                        + "different configuration surfaces and mixing them would mask bugs");
        assertTrue(trace.contains("production .java 1"));
        assertTrue(trace.contains("test .java      1"));
        assertTrue(trace.contains("unmapped        2"),
                "Unmapped count must always surface so Yaml/Gradle/Liquibase diffs don't hide");
        assertTrue(trace.contains("ignored sample: README.md"),
                "A non-empty bucket must list its sample so the operator sees WHICH file caused it");
    }

    @Test
    void bucketSampleIsTruncatedWithRemainderCount() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        // 15 files > EXPLAIN_SAMPLE_LIMIT (10): the trace must say "+5 more"
        // rather than dumping 15 paths into a single line and burying the
        // signal in CI scrollback.
        java.util.Set<String> many = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 15; i++) {
            many.add("build-scripts/file-" + i + ".gradle");
        }
        Buckets buckets = new Buckets(Set.of(), Set.of(), Set.of(), Set.of(), many);
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                many, Set.of(), Set.of(),
                buckets,
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("(+5 more)"),
                "Truncation suffix must name the exact remainder so a 15-file diff can't "
                        + "masquerade as a 10-file diff in the trace");
    }

    @Test
    void actionSourceSurfacesExplicitSettingWhenOnXxxWins() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .onAllFilesIgnored(Action.FULL_SUITE)
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md"), Set.of(), Set.of(),
                new Buckets(Set.of("README.md"), Set.of(), Set.of(), Set.of(), Set.of()),
                true, false,
                Situation.ALL_FILES_IGNORED,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_ALL_FILES_IGNORED);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("source: explicit onXxx setting"),
                "Explicit setting must surface distinctly from the mode-default source — "
                        + "otherwise the operator can't tell what survives a future default change");
    }

    @Test
    void actionSourceSurfacesModeDefaultWhenOnlyModeIsSet() {
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .mode(Mode.CI)
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/com/example/Orphan.java"),
                Set.of("com.example.Orphan"), Set.of(),
                Buckets.empty(),
                true, false,
                Situation.DISCOVERY_EMPTY,
                Action.FULL_SUITE,
                EscalationReason.RUN_ALL_IF_NO_MATCHES);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("source: mode default"),
                "When only mode is set the trace must name mode as the source, not guess "
                        + "'explicit' — otherwise audits of CI flakiness lose the real lead");
        assertTrue(trace.contains("Mode:            CI (effective: CI)"),
                "Mode line must always render both configured + effective so AUTO resolution "
                        + "stays visible in CI logs");
    }

    @Test
    void actionSourceSurfacesModeDefaultForZeroConfig() {
        // v2.0 removed the pre-v2 hardcoded-default tier — zero-config
        // installs now resolve straight to a mode default (AUTO → LOCAL
        // or CI based on env detection). The --explain trace must name
        // that honestly so operators know the resolved action can shift
        // between LOCAL and CI based on where the build runs.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("source: mode default"),
                "Zero-config installs must surface MODE_DEFAULT in v2.0 — the pre-v2 "
                        + "hardcoded-default tier has been removed and every action now "
                        + "resolves through a concrete mode");
    }

    @Test
    void matrixIncludesEveryNonSuccessSituationInStableOrder() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        // Every Situation must appear in the matrix so operators never have
        // to ask "what would've happened if the diff had been different?" —
        // the trace already answers it.
        for (Situation s : Situation.values()) {
            assertTrue(trace.contains(s.name()),
                    "Matrix must mention every Situation (missing: " + s + ")");
        }

        // Evaluation order is the contract. Each situation must appear
        // before the next one per the Situation javadoc.
        List<Situation> expectedOrder = List.of(
                Situation.EMPTY_DIFF,
                Situation.ALL_FILES_IGNORED,
                Situation.ALL_FILES_OUT_OF_SCOPE,
                Situation.UNMAPPED_FILE,
                // DISCOVERY_INCOMPLETE must appear before
                // DISCOVERY_EMPTY / DISCOVERY_SUCCESS because the
                // engine evaluates parse-failure escalation first
                // (see AffectedTestsEngine#run). Without this ordering
                // pin the --explain matrix would silently drift to any
                // row position and stop mirroring the engine.
                Situation.DISCOVERY_INCOMPLETE,
                Situation.DISCOVERY_EMPTY,
                Situation.DISCOVERY_SUCCESS);
        int previous = -1;
        for (Situation s : expectedOrder) {
            // Use the matrix row prefix (two leading spaces) to avoid
            // matching the "Situation: ..." header line.
            int idx = trace.indexOf("\n  " + s.name());
            assertTrue(idx > previous,
                    "Matrix must list " + s + " after every earlier Situation so the trace "
                            + "mirrors the engine's evaluation order");
            previous = idx;
        }
    }

    @Test
    void outcomeLineDistinguishesFullSuiteSkippedAndSelected() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();

        AffectedTestsResult runAll = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("build.gradle"), Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(), Set.of(), Set.of(), Set.of("build.gradle")),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);
        AffectedTestsResult skipped = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("README.md"), Set.of(), Set.of(),
                new Buckets(Set.of("README.md"), Set.of(), Set.of(), Set.of(), Set.of()),
                false, true,
                Situation.ALL_FILES_IGNORED, Action.SKIPPED,
                EscalationReason.NONE);
        AffectedTestsResult selected = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                Buckets.empty(),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String runAllTrace = joined(AffectedTestTask.renderExplainTrace(config, runAll));
        String skippedTrace = joined(AffectedTestTask.renderExplainTrace(config, skipped));
        String selectedTrace = joined(AffectedTestTask.renderExplainTrace(config, selected));

        assertTrue(runAllTrace.contains("Outcome:         FULL_SUITE"),
                "FULL_SUITE outcome must name itself so the reader never has to infer from counts");
        assertTrue(runAllTrace.contains("non-Java or unmapped"),
                "FULL_SUITE outcome must carry the same phrase as the summary line so CI greps stay stable");
        assertTrue(skippedTrace.contains("Outcome:         SKIPPED"),
                "Skipped outcome must say so explicitly — ambiguous w/ 'SELECTED with empty selection' otherwise");
        assertTrue(selectedTrace.contains("1 test class(es) will run"),
                "Selected outcome must name the actual dispatch size, not just 'SELECTED'");
        // Narrow match to the Outcome line specifically — the matrix below
        // it legitimately prints FULL_SUITE for other situations and we do
        // not want to assert on that here.
        assertFalse(selectedTrace.contains("Outcome:         FULL_SUITE"),
                "SELECTED result must not render FULL_SUITE on the Outcome line");
    }

    @Test
    void hintFiresWhenOutOfScopeTestDirsIsConfiguredButNoFilesMatch() {
        // Sanity-testing the v1.9.13 adopter surfaced exactly this silent
        // failure: a config entry like 'api-test/**' looked intentional
        // but matched nothing, and the operator only noticed when a full
        // CI run burned 30 minutes. The trace must call this out on every
        // run where out-of-scope dirs are configured but zero diff files
        // landed in the out-of-scope bucket.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Hint:"),
                "A silent zero-match on configured out-of-scope dirs must produce a Hint line "
                        + "— that's the failure mode sanity testing caught on v1.9.13 adopters");
        assertTrue(trace.contains("outOfScopeTestDirs") || trace.contains("outOfScopeSourceDirs"),
                "Hint must name the knob that didn't bite so the operator knows where to look");
    }

    @Test
    void hintFiresWhenOutOfScopeSourceDirsIsConfiguredButNoFilesMatch() {
        // Symmetry: the same misconfiguration on outOfScopeSourceDirs has
        // the same silent failure mode, so the hint must fire for it too.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeSourceDirs(List.of("legacy-service/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Hint:"),
                "Hint must fire for outOfScopeSourceDirs zero-match too, not just the test-dirs knob");
    }

    @Test
    void hintDoesNotFireWhenOutOfScopeBucketIsPopulated() {
        // Negative case: when the config IS biting, the trace already
        // shows a non-zero 'out-of-scope' count and the hint would be
        // noise. Keep the trace clean so the hint's rarity is itself a
        // signal.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();
        Buckets buckets = new Buckets(Set.of(),
                Set.of("api-test/src/test/java/com/example/FooSteps.java"),
                Set.of(), Set.of(), Set.of());
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("api-test/src/test/java/com/example/FooSteps.java"),
                Set.of(), Set.of(),
                buckets,
                false, true,
                Situation.ALL_FILES_OUT_OF_SCOPE, Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Hint:"),
                "Hint must not render when the out-of-scope bucket is non-empty — "
                        + "the config is working and a hint would just be log noise");
    }

    @Test
    void hintDoesNotFireWhenOutOfScopeDirsAreNotConfigured() {
        // Negative case: zero-config users never opted in, so no hint is
        // warranted. Rendering one here would train operators to ignore
        // the hint line, defeating its purpose.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Hint:"),
                "Hint must stay silent for zero-config installs — nobody opted into out-of-scope dirs");
    }

    @Test
    void hintDoesNotFireOnAllFilesIgnored() {
        // Regression for Finding 1 from the v1.9.17 sanity-test pass on
        // security-service: a markdown-only MR routed through
        // ALL_FILES_IGNORED, yet the hint fired saying "outOfScopeTestDirs
        // is configured but no file in the diff matched". Literally true
        // but pure noise — the diff contained nothing a source-tree
        // matcher could ever have bitten, so there is nothing for the
        // operator to diagnose. Suppressing here prevents the hint from
        // training reviewers to ignore it on every docs-only MR.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("docs/README.md"),
                Set.of(), Set.of(),
                new Buckets(Set.of("docs/README.md"),
                        Set.of(), Set.of(), Set.of(), Set.of()),
                false, true,
                Situation.ALL_FILES_IGNORED, Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Hint:"),
                "Hint must stay silent on ALL_FILES_IGNORED — the diff contained nothing "
                        + "that could have matched an out-of-scope pattern, so diagnosis is impossible");
    }

    @Test
    void hintDoesNotFireOnUnmappedFileEscalation() {
        // Regression for Finding 1: a gradle-file-only MR routed through
        // UNMAPPED_FILE → FULL_SUITE, yet the hint fired. The gradle file
        // was never a candidate for out-of-scope matching (it is not
        // under any source tree), so the hint adds no diagnostic value
        // and obscures the already-present "why did we run the full
        // suite" signal. Also covers mixed diffs that route through
        // UNMAPPED_FILE via an unrelated non-Java file.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("build.gradle"),
                Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of(), Set.of(),
                        Set.of("build.gradle")),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Hint:"),
                "Hint must stay silent on UNMAPPED_FILE — the diff's unmapped files "
                        + "could not have matched any out-of-scope source/test pattern");
    }

    @Test
    void hintFiresOnUnmappedFileWhenKotlinSourcesPresent() {
        // Issue #47 phase 1: when the unmapped bucket contains Kotlin
        // sources, surface the Java-only-mapping limitation explicitly
        // rather than letting adopters discover it by deploying the
        // plugin and watching every MR run the full suite. Keying the
        // hint on a polyglot extension (not just any UNMAPPED_FILE
        // run) preserves the silence regression for non-source
        // unmapped files (build.gradle, application.yml, etc.).
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/kotlin/com/example/Foo.kt"),
                Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(), Set.of(), Set.of(),
                        Set.of("src/main/kotlin/com/example/Foo.kt")),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Hint:"),
                "UNMAPPED_FILE with Kotlin sources must emit the polyglot hint");
        assertTrue(trace.contains("Kotlin"),
                "Hint text must name Kotlin so adopters know which language is the "
                        + "blocker without re-reading the bucket sample");
        assertTrue(trace.contains("issues/47"),
                "Hint must link to issue #47 so Phase 2 progress is one click away");
    }

    @Test
    void hintFiresOnUnmappedFileWithMixedPolyglotSources() {
        // A real-world Spring Boot + Kotlin + Groovy + Scala harness
        // (rare but possible) lands all three extensions in the
        // unmapped bucket. The hint must list all the languages it
        // saw, not just the first one — otherwise the operator fixes
        // Kotlin support, re-runs, and gets surprised by the Groovy
        // diagnostic on the second iteration.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/kotlin/A.kt",
                        "src/main/groovy/B.groovy",
                        "src/main/scala/C.scala"),
                Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(), Set.of(), Set.of(),
                        Set.of("src/main/kotlin/A.kt",
                                "src/main/groovy/B.groovy",
                                "src/main/scala/C.scala")),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Kotlin"), "Hint must list Kotlin");
        assertTrue(trace.contains("Groovy"), "Hint must list Groovy");
        assertTrue(trace.contains("Scala"),  "Hint must list Scala");
    }

    @Test
    void hintStaysSilentOnUnmappedFileWhenOnlyGradleKotlinDslPresent() {
        // build.gradle.kts is Kotlin-shaped but configuration-typed
        // — firing the polyglot hint would mislead adopters into
        // thinking the plugin can't see their production .kt files
        // when they only have a build script. Pin the carve-out
        // explicitly so polyglotExtensionOf can never silently start
        // recognising .gradle.kts as a candidate again.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("build.gradle.kts"),
                Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(), Set.of(), Set.of(),
                        Set.of("build.gradle.kts")),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Hint:"),
                "Hint must stay silent — build.gradle.kts is configuration, not "
                        + "production Kotlin source");
    }

    @Test
    void hintFiresOnUnmappedFileWhenMixedJavaAndKotlinPresent() {
        // Real adoption shape: an MR touches one Java config helper
        // and one Kotlin business class. The Java file is unmapped
        // (no .java entry in the source bucket means PathToClassMapper
        // couldn't resolve it, e.g. it's outside any sourceDir), but
        // the Kotlin file is the substantive cause. The hint must
        // still fire — silencing on "any non-polyglot file present"
        // would defeat the purpose for the most common mixed shape.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("scripts/legacy.java",
                        "src/main/kotlin/com/example/Service.kt"),
                Set.of(), Set.of(),
                new Buckets(Set.of(), Set.of(), Set.of(), Set.of(),
                        Set.of("scripts/legacy.java",
                                "src/main/kotlin/com/example/Service.kt")),
                true, false,
                Situation.UNMAPPED_FILE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Kotlin"),
                "Hint must fire on any polyglot file in the bucket, even when other "
                        + "non-source unmapped files are also present");
    }

    @Test
    void hintFiresOnDiscoveryEmptyFullSuiteEscalation() {
        // Positive case: when the diff changed real production code but
        // discovery found zero affected tests, the engine escalates to
        // FULL_SUITE under the CI mode's safety net. Pre-v2.2 the hint
        // here was the OOS-misconfig text; v2.2 replaces it with the
        // "discovery mapped 0 test classes" advice (naming suffixes,
        // testDirs, no-coverage-yet). Either way the `Hint:` line
        // must be present — its absence would silence the most
        // expensive diagnostic signal the plugin emits.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeSourceDirs(List.of("legacy-service/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                true, false,
                Situation.DISCOVERY_EMPTY, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_IF_NO_MATCHES);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Hint:"),
                "Hint must fire on DISCOVERY_EMPTY too — that's the case where an "
                        + "over-broad source file that should have been out-of-scope is what "
                        + "drove the unnecessary FULL_SUITE escalation");
    }

    @Test
    void hintDoesNotFireOnAllFilesOutOfScope() {
        // Gate invariant: when every file landed in the out-of-scope
        // bucket there is nothing silent to diagnose — the config IS
        // biting, just hard enough to empty the discovery input. The
        // pre-existing bucket-non-empty guard in appendOutOfScopeHint
        // suppresses this case today; pinning the situation-gate here
        // keeps the two guards from drifting apart if either gets
        // refactored.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("api-test/src/test/java/com/example/ApiFooTest.java"),
                Set.of(), Set.of(),
                new Buckets(Set.of(),
                        Set.of("api-test/src/test/java/com/example/ApiFooTest.java"),
                        Set.of(), Set.of(), Set.of()),
                false, true,
                Situation.ALL_FILES_OUT_OF_SCOPE, Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Hint:"),
                "Hint must stay silent on ALL_FILES_OUT_OF_SCOPE — the config is biting as "
                        + "intended and there is nothing silent to diagnose");
    }

    @Test
    void hintDoesNotFireOnEmptyDiff() {
        // Negative case: with no changed files the hint has nothing to
        // diagnose — there's no diff for the config to have bitten. We
        // silence it so an empty-diff CI re-run doesn't spam a false
        // alarm every time master gets a merge and the next MR rebases.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF, Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Hint:"),
                "Hint must stay silent when there are no changed files — nothing to diagnose");
    }

    // ------------------------------------------------------------------
    // v2.2 — situation-specific Hint branches
    //
    // Pre-v2.2 every mapper-touching situation produced the same
    // "outOfScopeTestDirs is configured but no file matched" hint.
    // That was actively misleading on DISCOVERY_EMPTY runs (where the
    // OOS knob was not the reason discovery mapped zero tests) and
    // on DISCOVERY_INCOMPLETE runs (where a Java parse failure, not
    // an OOS matcher, dropped files from the mapper). v2.2 splits
    // the single hint into three targeted branches; these tests
    // lock in each branch's content so regressions show up as test
    // failures rather than as user-invisible wording drift.
    // ------------------------------------------------------------------

    @Test
    void discoveryEmptyHintNamesNamingSuffixesAndTestDirs() {
        // When the engine maps zero tests to a production change, the
        // three realistic causes are: test-suffix mismatch, a test
        // file outside the configured testDirs, or genuinely no test
        // coverage yet. The hint must list all three in that order
        // with the user's actual config values so they can self-check
        // "does my testSuffixes list include 'IT'?" without leaving
        // the trace. Verifying the content here (not just "Hint:"
        // presence) would have caught the pre-v2.2 behaviour where
        // this situation silently printed out-of-scope-dirs advice.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .testSuffixes(List.of("Test", "IT"))
                .testDirs(List.of("src/test/java"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                true, false,
                Situation.DISCOVERY_EMPTY, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_IF_NO_MATCHES);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Hint:            discovery mapped 0 test classes"),
                "DISCOVERY_EMPTY hint must lead with 'mapped 0 test classes' — that names the "
                        + "actual shape of the problem, not an unrelated OOS misconfig claim. Got:\n" + trace);
        assertTrue(trace.contains("testSuffixes [Test, IT]"),
                "Hint must echo the user's configured testSuffixes so they can verify "
                        + "the naming convention matches their test files. Got:\n" + trace);
        assertTrue(trace.contains("testDirs [src/test/java]"),
                "Hint must echo testDirs so the operator can spot a test file placed "
                        + "outside the configured roots. Got:\n" + trace);
        assertTrue(trace.contains("no test coverage yet"),
                "Hint must explicitly call out the no-coverage case — otherwise users with "
                        + "genuinely untested classes will loop on naming suggestions. Got:\n" + trace);
        assertFalse(trace.contains("outOfScopeTestDirs") || trace.contains("outOfScopeSourceDirs"),
                "DISCOVERY_EMPTY hint must NOT mention out-of-scope knobs — OOS is orthogonal "
                        + "to why discovery mapped 0 tests, and surfacing it was the exact confusion "
                        + "v2.1 produced. Got:\n" + trace);
    }

    @Test
    void discoveryIncompleteHintCallsOutPartialSelectionRisk() {
        // Parse failure means the mapper ran with missing inputs.
        // The v2.1 trace printed "outOfScopeTestDirs is configured"
        // here too, even when the user had no OOS entries — pure
        // noise. v2.2 replaces that with the thing the operator
        // actually needs to know: the selection is partial, and the
        // fix is to either repair the parse error or escalate via
        // onDiscoveryIncomplete.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_INCOMPLETE, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("failed to parse"),
                "DISCOVERY_INCOMPLETE hint must name the parse-failure cause so the "
                        + "operator understands why selection is partial. Got:\n" + trace);
        assertTrue(trace.contains("selection is necessarily partial"),
                "Hint must explicitly warn that the selection is partial — that's the "
                        + "silent correctness risk v2.2 was raised to surface. Got:\n" + trace);
        assertTrue(trace.contains("onDiscoveryIncomplete"),
                "Hint must name the knob that toggles this behaviour so the operator "
                        + "has an actionable next step. Got:\n" + trace);
    }

    @Test
    void discoveryIncompleteHintOnFullSuiteDropsPartialSelectionWording() {
        // v2.2.1 fix (H3 from CAR-5190 code review): CI/STRICT
        // defaults — and explicit operator overrides — route
        // DISCOVERY_INCOMPLETE to FULL_SUITE. The old shared hint
        // claimed the "selection is necessarily partial" which is
        // factually wrong (the full suite ran) and advised escalating
        // via onDiscoveryIncomplete which is circular (escalation
        // already happened). Pin the corrected wording: the hint
        // names the parse failure as the root cause but does NOT
        // claim a partial selection, and does NOT re-recommend the
        // escalation that the current run already took.
        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.CI).build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                true, false,
                Situation.DISCOVERY_INCOMPLETE, Action.FULL_SUITE,
                EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("failed to parse"),
                "FULL_SUITE hint must still name the parse-failure cause so the operator "
                        + "can fix it next run. Got:\n" + trace);
        assertFalse(trace.contains("selection is necessarily partial"),
                "FULL_SUITE ran the whole suite — claiming the selection was partial "
                        + "contradicts the resolved action and actively misleads the operator. "
                        + "Got:\n" + trace);
        assertFalse(trace.contains("onDiscoveryIncomplete = 'full_suite' to escalate"),
                "The escalation has already happened on this run — repeating the 'set it "
                        + "to full_suite' advice is circular and trains operators to "
                        + "ignore the hint. Got:\n" + trace);
    }

    @Test
    void discoveryIncompleteHintOnSkippedNamesTheOptInAndOffersAnEscape() {
        // v2.2.1 fix (M3 from v2.2 code review): when the operator
        // has explicitly set `onDiscoveryIncomplete = 'skipped'`, the
        // plugin runs zero tests on a partial-parse diff. The v2.2
        // hint lumped SKIPPED into the FULL_SUITE branch and printed
        // "the resolved action above is the safe fallback" — which
        // is precisely wrong: SKIPPED is the OPPOSITE of safe here
        // (neither selection narrowed nor suite escalated). Pin the
        // corrected wording: the hint names the opt-in knob, names
        // the parse failure, and points at `'full_suite'` as the
        // fix — without ever calling the skip "safe".
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, true, // runAll=false, skipped=true
                Situation.DISCOVERY_INCOMPLETE, Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("failed to parse"),
                "SKIPPED hint must still name the parse-failure cause — the opt-in "
                        + "doesn't change why discovery was incomplete. Got:\n" + trace);
        assertTrue(trace.contains("onDiscoveryIncomplete = 'skipped'"),
                "Hint must name the exact knob the operator set so a second reader of "
                        + "the trace can locate the override. Got:\n" + trace);
        assertTrue(trace.contains("onDiscoveryIncomplete = 'full_suite'"),
                "Hint must offer 'full_suite' as the escape hatch — SKIPPED is the only "
                        + "branch where escalation advice is not circular. Got:\n" + trace);
        assertFalse(trace.contains("safe fallback"),
                "SKIPPED ran no tests — calling that the 'safe fallback' directly "
                        + "contradicts the correctness posture the plugin is supposed to "
                        + "defend. Got:\n" + trace);
        assertFalse(trace.contains("selection is necessarily partial"),
                "Nothing was selected — the partial-selection wording belongs to the "
                        + "SELECTED branch only. Got:\n" + trace);
    }

    @Test
    void discoverySuccessHintKeepsV2dot1OutOfScopeMisconfigWording() {
        // Regression: v2.2's hint refactor must preserve the single
        // DISCOVERY_SUCCESS case v2.1 actually diagnosed correctly —
        // "outOfScopeTestDirs is configured (N entries) but no file
        // in the diff matched." Losing that would take the plugin
        // backwards, since the silent-OOS-misconfig is exactly the
        // failure mode the pre-v1.9.17 sanity-test caught.
        AffectedTestsConfig config = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(List.of("api-test/**"))
                .build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("outOfScopeTestDirs is configured (1 entry)"),
                "DISCOVERY_SUCCESS must still surface the OOS-configured-but-nothing-matched "
                        + "misconfig — that's the v2.1 behaviour we are NOT breaking. Got:\n" + trace);
    }

    // ------------------------------------------------------------------
    // v2.2 — Modules: per-task dispatch preview in --explain
    // ------------------------------------------------------------------

    @Test
    void modulesBlockIsAbsentWhenNoGroupsPassed() {
        // Every pre-v2.2 caller (and every non-SELECTED situation in
        // v2.2) threads an empty map — the trace must stay compact
        // in that case. Otherwise every EMPTY_DIFF / SKIPPED run
        // would grow a useless "Modules: 0 modules, 0 test classes"
        // line.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF, Action.SKIPPED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result, Map.of()));

        assertFalse(trace.contains("Modules:"),
                "Modules block must be suppressed on empty map — SELECTED is the only "
                        + "situation where dispatch preview is meaningful. Got:\n" + trace);
    }

    @Test
    void modulesBlockRendersPerTaskBreakdownForMultiModuleSelection() {
        // The real-world adoption scenario: production change in one
        // module (`api/`) dispatches tests into another (`application/`).
        // v2.1's --explain trace gave only a total test count and left
        // the operator guessing which task Gradle would actually
        // invoke. v2.2 prints the per-:module:test breakdown matching
        // the real dispatch output so an --explain run answers the
        // "what will Gradle kick off?" question directly.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest", "com.example.BarTest", "com.example.BazTest"),
                Map.of(),
                Set.of("api/src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest", "com.example.BarTest", "com.example.BazTest"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("api/src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        groups.put(":application:test", List.of("com.example.FooTest", "com.example.BarTest"));
        groups.put(":api:test", List.of("com.example.BazTest"));

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result, groups));

        assertTrue(trace.contains("Modules:         2 modules, 3 test classes to dispatch"),
                "Modules summary must count modules and classes so the operator can eyeball "
                        + "the dispatch shape before running. Got:\n" + trace);
        assertTrue(trace.contains(":application:test (2 test classes)"),
                "Per-module row must name the task path and class count — exact same preview "
                        + "shape as the non-explain dispatch log, so switching between modes is "
                        + "a cognitive no-op. Got:\n" + trace);
        assertTrue(trace.contains(":api:test (1 test class)"),
                "Singular form 'test class' must render for 1-class modules — keeps the "
                        + "preview grammatical on small dispatches. Got:\n" + trace);
        assertTrue(trace.contains("    com.example.FooTest")
                        && trace.contains("    com.example.BarTest"),
                "Per-module FQN preview must indent the FQNs under their module row so the "
                        + "hierarchy reads naturally. Got:\n" + trace);
    }

    @Test
    void testTaskPathHelperNormalisesRootProjectToLeadingColon() {
        // v2.2.1 (N1 from the code review): both the explain-block
        // preview and the dispatch argv go through the shared
        // AffectedTestTask#testTaskPath helper so the two operator-
        // facing strings cannot drift. Pin the root-project case
        // ("" → ":test") directly on the helper — the explain-side
        // rendering just iterates this helper's output, so a
        // per-renderer test would duplicate coverage.
        assertTrue(":test".equals(AffectedTestTask.testTaskPath("")),
                "Empty project path (root project) must render as ':test' with a leading "
                        + "colon so explain and dispatch name the task identically");
        assertTrue(":api:test".equals(AffectedTestTask.testTaskPath(":api")),
                "Non-root project path must suffix ':test' — regression on this shape "
                        + "would make every non-root dispatch target the wrong task name");
    }

    @Test
    void modulesBlockRendersHelperNormalisedRootTask() {
        // Paired assertion: once the helper returns ':test', the
        // renderer must pass it through verbatim. Prevents a drift
        // where someone "fixes" the helper but leaves the renderer
        // stripping the colon for root-module aesthetic reasons.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result,
                Map.of(AffectedTestTask.testTaskPath(""),
                        List.of("com.example.FooTest"))));

        assertTrue(trace.contains(":test (1 test class)"),
                "Root-project task must render as ':test' in the explain trace so every row "
                        + "reads as a Gradle task path. Got:\n" + trace);
    }

    @Test
    void modulesBlockRendersDistinctRowsForMultipleTestTasksInOneModule() {
        // Issue #48: an adopter with `testTaskNames = ['test',
        // 'integrationTest']` ends up with two task keys for the
        // same subproject — :app:test and :app:integrationTest.
        // The renderer must surface both rows distinctly so the
        // operator can see, at a glance, which source set fired.
        // A regression that collapses them into a single :app:test
        // row would silently misrepresent dispatch in --explain
        // even while the actual command line stayed correct,
        // which is the kind of trace/dispatch drift v2.2.1's
        // testTaskPath contract was raised to prevent.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest", "com.example.FooIT"), Map.of(),
                Set.of("app/src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest", "com.example.FooIT"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("app/src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        // Both keys must originate from testTaskPath so the
        // renderer's leading-colon assertion stays satisfied.
        Map<String, List<String>> moduleGroups = new java.util.LinkedHashMap<>();
        moduleGroups.put(AffectedTestTask.testTaskPath(":app", "test"),
                List.of("com.example.FooTest"));
        moduleGroups.put(AffectedTestTask.testTaskPath(":app", "integrationTest"),
                List.of("com.example.FooIT"));

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result, moduleGroups));

        assertTrue(trace.contains(":app:test (1 test class)"),
                "Same-module 'test' row must render alongside its integrationTest sibling. "
                        + "Got:\n" + trace);
        assertTrue(trace.contains(":app:integrationTest (1 test class)"),
                "Same-module 'integrationTest' row must render distinct from the 'test' row "
                        + "so the source set is visible in --explain. Got:\n" + trace);
        // Header arithmetic must reflect 2 keys, not 1 (a
        // collapse-bug would say "1 module"; this guards both
        // numerator and denominator of the explain header).
        assertTrue(trace.contains("Modules:         2 modules, 2 test classes to dispatch"),
                "Header must count distinct task keys, not distinct subprojects — otherwise "
                        + "a multi-source-set adopter sees '1 module, 2 classes' which "
                        + "obscures the routing. Got:\n" + trace);
    }

    @Test
    void modulesBlockRejectsKeysThatSkipTheTestTaskPathHelper() {
        // v2.2.1 fix (L2 from v2.2 code review): appendModulesBlock
        // used to silently re-normalise keys missing a leading colon,
        // masking bugs where a future caller forgot to route through
        // testTaskPath(). The renderer now asserts the contract so
        // the failure surface moves into tests instead of into a
        // split operator trace (":api:test" next to "core:test").
        //
        // Asserted with `assert`, so this test only trips when
        // assertions are on — which is how every Gradle test JVM is
        // configured. Pins both the positive signal (helper output
        // flows through) and the negative (raw module path does not).
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.of("com.example.FooTest"),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        // A key without a leading colon violates the helper contract.
        // Canonical Java idiom: the assign-inside-assert executes
        // only when assertions are enabled, so the flag stays false
        // on a JVM running with `-da` and we skip the negative half.
        // Gradle's test JVM runs with assertions on by default.
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) {
            return;
        }

        Map<String, List<String>> bad = Map.of("api:test",
                List.of("com.example.FooTest"));
        try {
            AffectedTestTask.renderExplainTrace(config, result, bad);
            throw new IllegalStateException("appendModulesBlock must reject keys missing "
                    + "the leading colon so a future caller that forgets testTaskPath() "
                    + "fails loudly instead of silently emitting split traces");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage() != null
                            && expected.getMessage().contains("testTaskPath"),
                    "Assertion message must name the helper so the fix is obvious. Got: "
                            + expected.getMessage());
        }
    }

    @Test
    void modulesBlockTruncatesWithAndNMoreLineOverPreviewLimit() {
        // Match the dispatch-path preview behaviour: print the first
        // LIFECYCLE_FQN_PREVIEW_LIMIT FQNs, then a single "… and N
        // more (use --info for full list)" trailer. Parity with the
        // dispatch preview means an --explain run and a subsequent
        // non-explain run show the same first N names — no cognitive
        // load to map one to the other.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        List<String> fqns = new java.util.ArrayList<>();
        for (int i = 0; i < AffectedTestTask.LIFECYCLE_FQN_PREVIEW_LIMIT + 3; i++) {
            fqns.add("com.example.Test" + i);
        }
        AffectedTestsResult result = new AffectedTestsResult(
                Set.copyOf(fqns), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"),
                Set.copyOf(fqns),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result,
                Map.of(":application:test", fqns)));

        assertTrue(trace.contains("… and 3 more (use --info for full list)"),
                "Trailer must name the remainder count and point at --info for the full set "
                        + "— any other phrasing drifts from the dispatch-path preview and trains "
                        + "operators to mistrust the trace. Got:\n" + trace);
    }

    // ------------------------------------------------------------------
    // v2.2 — Risk C: LOCAL + DISCOVERY_INCOMPLETE + SELECTED WARN gate
    //
    // The instance method that actually emits the WARN is one-liner
    // plumbing over the Gradle logger; all decision logic lives in
    // shouldWarnLocalDiscoveryIncomplete (pure) and the message in
    // formatLocalDiscoveryIncompleteWarning (pure). The tests exercise
    // each guard on the pure gate so a regression on any of the four
    // conditions (mode, situation, action, skipped/empty) surfaces in
    // ms — without requiring a log-capture fixture — and mirrors what
    // the Javadoc already promised.
    // ------------------------------------------------------------------
    private static AffectedTestsConfig configWithMode(Mode mode) {
        return AffectedTestsConfig.builder().mode(mode).build();
    }

    private static AffectedTestsResult makeResult(Situation situation,
                                                  Action action,
                                                  boolean skipped,
                                                  int fqnCount) {
        Set<String> fqns = new java.util.LinkedHashSet<>();
        for (int i = 0; i < fqnCount; i++) {
            fqns.add("com.example.Test" + i);
        }
        return new AffectedTestsResult(
                fqns, Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.copyOf(fqns),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, skipped,
                situation, action,
                EscalationReason.NONE);
    }

    @Test
    void riskCWarnFiresOnLocalDiscoveryIncompleteSelectedWithFqns() {
        // The one combination the v2.2 fix was added for: LOCAL keeps
        // `onDiscoveryIncomplete = SELECTED` by design, so the engine
        // hands back a non-empty partial selection and the task must
        // surface that silently-partial result to the operator.
        AffectedTestsConfig config = configWithMode(Mode.LOCAL);
        AffectedTestsResult result = makeResult(
                Situation.DISCOVERY_INCOMPLETE, Action.SELECTED, false, 3);

        assertTrue(AffectedTestTask.shouldWarnLocalDiscoveryIncomplete(config, result),
                "LOCAL + DISCOVERY_INCOMPLETE + SELECTED + non-empty FQNs is the exact "
                        + "v2.2 Risk C scenario — dropping the WARN here re-opens the silent "
                        + "partial-selection hole that CAR-5190 surfaced");
    }

    @Test
    void riskCWarnSuppressedInCiModeOnIdenticalResult() {
        // CI (and STRICT) default onDiscoveryIncomplete to FULL_SUITE
        // so the engine never hands them a "SELECTED on incomplete
        // discovery" result through the mode default. But a CI
        // operator who explicitly overrode that to SELECTED should
        // still NOT see a LOCAL-branded WARN — it would name the
        // wrong mode and mis-guide the operator. Pin the gate on
        // effectiveMode, not on the situation alone.
        AffectedTestsConfig config = configWithMode(Mode.CI);
        AffectedTestsResult result = makeResult(
                Situation.DISCOVERY_INCOMPLETE, Action.SELECTED, false, 3);

        assertFalse(AffectedTestTask.shouldWarnLocalDiscoveryIncomplete(config, result),
                "CI must never emit the LOCAL-specific WARN — wording names LOCAL's "
                        + "onDiscoveryIncomplete default and would be factually wrong in CI");
    }

    @Test
    void riskCWarnSuppressedOnDiscoverySuccess() {
        // DISCOVERY_SUCCESS with no parse failures is the happy path.
        // The WARN is for parse-failure-induced partial selections
        // specifically — any other situation triggering it would train
        // operators that the signal means nothing.
        AffectedTestsConfig config = configWithMode(Mode.LOCAL);
        AffectedTestsResult result = makeResult(
                Situation.DISCOVERY_SUCCESS, Action.SELECTED, false, 3);

        assertFalse(AffectedTestTask.shouldWarnLocalDiscoveryIncomplete(config, result),
                "DISCOVERY_SUCCESS must never emit the WARN — there is no incomplete "
                        + "discovery to warn about");
    }

    @Test
    void riskCWarnSuppressedOnFullSuiteEscalation() {
        // An operator running LOCAL mode with an explicit
        // `onDiscoveryIncomplete='full_suite'` override gets
        // DISCOVERY_INCOMPLETE + FULL_SUITE. No silent partial
        // selection exists (the full suite will run), so the WARN
        // would contradict the explicit operator choice.
        AffectedTestsConfig config = configWithMode(Mode.LOCAL);
        AffectedTestsResult result = makeResult(
                Situation.DISCOVERY_INCOMPLETE, Action.FULL_SUITE, false, 0);

        assertFalse(AffectedTestTask.shouldWarnLocalDiscoveryIncomplete(config, result),
                "FULL_SUITE escalation removes the under-testing risk — the WARN must "
                        + "NOT fire or it would contradict the operator's explicit choice to "
                        + "escalate");
    }

    @Test
    void riskCWarnSuppressedWhenEngineRewritesToSkipped() {
        // The engine treats SELECTED with nothing to dispatch as
        // skipped=true (`action == SELECTED && situation != DISCOVERY_SUCCESS`
        // → skipped). Without this guard the WARN would fire
        // "(0 test classes)" and then the task would bail before
        // running anything — two contradictory log lines from the
        // same code path.
        AffectedTestsConfig config = configWithMode(Mode.LOCAL);
        AffectedTestsResult result = makeResult(
                Situation.DISCOVERY_INCOMPLETE, Action.SELECTED, true, 0);

        assertFalse(AffectedTestTask.shouldWarnLocalDiscoveryIncomplete(config, result),
                "skipped=true means the engine already routed this run to nothing-to-dispatch "
                        + "— firing the WARN would claim a partial selection was accepted when "
                        + "nothing was actually dispatched");
    }

    @Test
    void riskCWarnSuppressedWhenFqnListIsEmptyWithoutSkipped() {
        // Defensive: should-never-happen but if a future code path
        // lands SELECTED + empty FQNs without the skipped flag, the
        // WARN must still suppress so an operator is never told a
        // zero-count selection was "accepted".
        AffectedTestsConfig config = configWithMode(Mode.LOCAL);
        AffectedTestsResult result = makeResult(
                Situation.DISCOVERY_INCOMPLETE, Action.SELECTED, false, 0);

        assertFalse(AffectedTestTask.shouldWarnLocalDiscoveryIncomplete(config, result),
                "Empty FQN list means no partial selection was actually honoured — the "
                        + "WARN must suppress even if the engine forgot to mark the result skipped");
    }

    @Test
    void riskCWarnMessageNamesMarkerAndEscalationKnob() {
        // The marker substring doubles as a grep target for CI
        // alerting (see LOCAL_DISCOVERY_INCOMPLETE_WARNING_MARKER
        // javadoc) and the message must name the exact knob an
        // operator can flip — anything vaguer sends the operator
        // grepping through the CHANGELOG.
        String message = AffectedTestTask.formatLocalDiscoveryIncompleteWarning(3);

        assertTrue(message.contains(AffectedTestTask.LOCAL_DISCOVERY_INCOMPLETE_WARNING_MARKER),
                "Message must contain the stable marker substring so grep-based alerting "
                        + "keeps working across wording refreshes");
        assertTrue(message.contains("3 test classes"),
                "Plural form must render the count — ambiguity on "
                        + "'how much did we actually accept' defeats the WARN's purpose. Got: "
                        + message);
        assertTrue(message.contains("onDiscoveryIncomplete = 'full_suite'"),
                "Message must name the exact knob so the fix is mechanical, not a "
                        + "CHANGELOG-grep exercise. Got: " + message);
    }

    @Test
    void crossPackageNamingHintListsThePairsOnDiscoverySuccess() {
        // Regression for #40: when NamingConventionStrategy adds a
        // test whose package differs from the changed production
        // class, --explain must surface that as a Hint with the
        // exact pair listed. Without this an operator never sees
        // "you ran 200 unrelated tests because of a simple-name
        // collision", and the cost of the package-agnostic naming
        // policy stays invisible.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("e.f.FooTest"), Map.of(),
                Set.of("src/main/java/a/b/Foo.java"),
                Set.of("a.b.Foo"), Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/a/b/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE,
                Map.of("a.b.Foo", Set.of("e.f.FooTest")));

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("naming strategy selected 1 test"),
                "Hint must headline with the count so operators "
                        + "can immediately judge severity. Got:\n" + trace);
        assertTrue(trace.contains("a.b.Foo → e.f.FooTest"),
                "Hint must list the exact (changed → test) pair — "
                        + "without the FQNs the operator can't tell "
                        + "whether the over-select is a real bug or "
                        + "a deliberate parallel-test-tree choice. "
                        + "Got:\n" + trace);
        assertTrue(trace.contains("over-select is the documented trade-off"),
                "Hint must explain that the test is still selected and "
                        + "WHY, otherwise it reads as 'something went "
                        + "wrong'. Got:\n" + trace);
    }

    @Test
    void crossPackageNamingHintIsSilentWhenNoCrossPackageMatches() {
        // Companion: the silent-success path stays silent. A
        // healthy DISCOVERY_SUCCESS run with no cross-package
        // matches must not fire the hint. Otherwise --explain
        // trains operators to ignore the hint on every run.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/com/example/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE,
                Map.of());

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("naming strategy selected"),
                "Healthy run with no cross-package matches must not "
                        + "fire the hint. Got:\n" + trace);
        assertFalse(trace.contains("possible cross-package simple-name collision"),
                "The silent-success path must stay silent. Got:\n" + trace);
    }

    @Test
    void crossPackageNamingHintTruncatesOver10PairsWithRemainderCount() {
        // Same EXPLAIN_SAMPLE_LIMIT contract the bucket samples
        // honour. A monorepo can produce hundreds of cross-package
        // matches; dumping all of them buries the signal. Cap at
        // 10 with a "+N more" tail so the operator gets the shape
        // and can opt into --info for the full list.
        AffectedTestsConfig config = AffectedTestsConfig.builder().build();
        java.util.LinkedHashMap<String, Set<String>> matches = new java.util.LinkedHashMap<>();
        matches.put("a.b.Foo", new java.util.LinkedHashSet<>(java.util.List.of(
                "p0.FooTest", "p1.FooTest", "p2.FooTest", "p3.FooTest",
                "p4.FooTest", "p5.FooTest", "p6.FooTest", "p7.FooTest",
                "p8.FooTest", "p9.FooTest", "p10.FooTest", "p11.FooTest")));

        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of("src/main/java/a/b/Foo.java"),
                Set.of("a.b.Foo"), Set.of(),
                new Buckets(Set.of(), Set.of(),
                        Set.of("src/main/java/a/b/Foo.java"),
                        Set.of(), Set.of()),
                false, false,
                Situation.DISCOVERY_SUCCESS, Action.SELECTED,
                EscalationReason.NONE,
                matches);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("naming strategy selected 12 tests"),
                "Header must report the total count, not just the "
                        + "rendered subset. Got:\n" + trace);
        assertTrue(trace.contains("p0.FooTest"),
                "First pair must render. Got:\n" + trace);
        assertTrue(trace.contains("p9.FooTest"),
                "Tenth (last under cap) pair must render. Got:\n" + trace);
        assertFalse(trace.contains("p10.FooTest"),
                "Pairs past the cap must not render or the trace gets "
                        + "buried in scrollback. Got:\n" + trace);
        assertTrue(trace.contains("(+2 more"),
                "Truncation must surface the remainder count so the "
                        + "operator knows to opt into --info. Got:\n" + trace);
    }

    @Test
    void riskCWarnMessageUsesSingularForExactlyOneFqn() {
        // Plurality drift between "1 test class" and "1 test classes"
        // is the sort of micro-bug that makes the message read like
        // it was written by someone who doesn't use English, and
        // operators lose trust fast. Pin it.
        String message = AffectedTestTask.formatLocalDiscoveryIncompleteWarning(1);

        assertTrue(message.contains("1 test class "),
                "Singular 1 must render as '1 test class' (no trailing s) — got: " + message);
        assertFalse(message.contains("1 test classes"),
                "Plural fallthrough on count=1 would look like a generated-from-template "
                        + "bug and cost the signal its credibility. Got: " + message);
    }

    // ─────────────────────────────────────────────────────────────────
    // Issue #42: Discovery profile rendering
    // ─────────────────────────────────────────────────────────────────

    @Test
    void discoveryBlockSurfacesParallelMetadataAndPerStrategyTimings() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.LOCAL).build();
        Buckets buckets = new Buckets(Set.of(), Set.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of(), Set.of());
        io.affectedtests.core.discovery.DiscoveryProfile profile =
                new io.affectedtests.core.discovery.DiscoveryProfile(
                        true, 4,
                        java.time.Duration.ofMillis(13),
                        java.util.Map.of(
                                "naming",     java.time.Duration.ofMillis(2),
                                "usage",      java.time.Duration.ofMillis(4),
                                "impl",       java.time.Duration.ofMillis(3),
                                "transitive", java.time.Duration.ofMillis(11)),
                        java.util.Map.of(
                                "naming", 2, "usage", 5, "impl", 1, "transitive", 3));
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                buckets,
                false, false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE,
                Map.of(),
                profile);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Discovery:       parallel (4 threads, 13ms total)"),
                "Discovery header must report parallel + thread count + total wall "
                        + "time so adopters can judge whether parallel is helping. "
                        + "Got:\n" + trace);
        assertTrue(trace.contains("naming     :    2ms (2 tests)"),
                "Per-strategy line must show name, ms, and contribution count. Got:\n" + trace);
        assertTrue(trace.contains("transitive :   11ms (3 tests, dominant)"),
                "Dominant strategy must be flagged so operators know which strategy "
                        + "is the wall-time hog — that's the key data for deciding "
                        + "whether Option 1 (intra-strategy parallelism) is worth "
                        + "the engineering cost. Got:\n" + trace);
    }

    @Test
    void discoveryBlockReportsSerialModeWhenParallelDisabled() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.LOCAL).build();
        Buckets buckets = new Buckets(Set.of(), Set.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of(), Set.of());
        io.affectedtests.core.discovery.DiscoveryProfile profile =
                new io.affectedtests.core.discovery.DiscoveryProfile(
                        false, 0,
                        java.time.Duration.ofMillis(20),
                        java.util.Map.of(
                                "naming", java.time.Duration.ofMillis(8),
                                "usage",  java.time.Duration.ofMillis(12)),
                        java.util.Map.of("naming", 1, "usage", 1));
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                buckets,
                false, false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE,
                Map.of(),
                profile);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Discovery:       serial (20ms total)"),
                "Serial mode must be visibly different from parallel — adopters who "
                        + "hit the kill switch (-PaffectedTestsParallelDiscovery=false) "
                        + "need to be able to verify the flag took effect. Got:\n" + trace);
        assertFalse(trace.contains("threads"),
                "Serial mode must not name a thread count — there is no pool. "
                        + "Got:\n" + trace);
    }

    @Test
    void discoveryBlockOmittedOnEmptyProfile() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.LOCAL).build();
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of(), Map.of(),
                Set.of(), Set.of(), Set.of(),
                Buckets.empty(),
                false, true,
                Situation.EMPTY_DIFF,
                Action.SKIPPED,
                EscalationReason.NONE,
                Map.of(),
                io.affectedtests.core.discovery.DiscoveryProfile.empty());

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertFalse(trace.contains("Discovery:"),
                "Empty profile (test-only fast path / EMPTY_DIFF) must NOT render "
                        + "a Discovery block — printing '0ms total' for a path that "
                        + "didn't run discovery would clutter the trace and mislead "
                        + "operators into thinking discovery is broken. Got:\n" + trace);
    }

    @Test
    void discoveryBlockNeverFlagsDominantOnSingleStrategyRun() {
        AffectedTestsConfig config = AffectedTestsConfig.builder().mode(Mode.LOCAL).build();
        Buckets buckets = new Buckets(Set.of(), Set.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of(), Set.of());
        io.affectedtests.core.discovery.DiscoveryProfile profile =
                new io.affectedtests.core.discovery.DiscoveryProfile(
                        false, 0,
                        java.time.Duration.ofMillis(5),
                        java.util.Map.of("naming", java.time.Duration.ofMillis(5)),
                        java.util.Map.of("naming", 1));
        AffectedTestsResult result = new AffectedTestsResult(
                Set.of("com.example.FooTest"), Map.of(),
                Set.of("src/main/java/com/example/Foo.java"),
                Set.of("com.example.Foo"), Set.of(),
                buckets,
                false, false,
                Situation.DISCOVERY_SUCCESS,
                Action.SELECTED,
                EscalationReason.NONE,
                Map.of(),
                profile);

        String trace = joined(AffectedTestTask.renderExplainTrace(config, result));

        assertTrue(trace.contains("Discovery:       serial (5ms total)"),
                "Single-strategy serial run must still show the discovery block. "
                        + "Got:\n" + trace);
        assertFalse(trace.contains(", dominant"),
                "Dominant marker must NOT fire when only one strategy ran — "
                        + "calling a sole strategy 'dominant' has no semantic content "
                        + "and would mislead operators into thinking parallel "
                        + "would have helped. Got:\n" + trace);
    }
}
