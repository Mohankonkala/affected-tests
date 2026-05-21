package io.affectedtests.core;

import io.affectedtests.core.AffectedTestsEngine.EscalationReason;
import io.affectedtests.core.config.Action;
import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.config.Mode;
import io.affectedtests.core.config.Situation;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AffectedTestsEngineTest {

    @TempDir
    Path tempDir;

    private Git initRepoWithInitialCommit() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).call();
        File readme = tempDir.resolve("README.md").toFile();
        Files.writeString(readme.toPath(), "# init");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("initial commit").call();
        return git;
    }

    @Test
    void discoversTestByNamingFromCommittedChange() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add FooService").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.testClassFqns().contains("com.example.FooServiceTest"),
                    "Should discover test via naming strategy");
            assertFalse(result.runAll());
            assertEquals(EscalationReason.NONE, result.escalationReason(),
                    "A normal filtered selection must not report an escalation reason");
            // Result must expose the test file path for per-module routing.
            assertNotNull(result.testFqnToPath().get("com.example.FooServiceTest"));
        }
    }

    @Test
    void returnsEmptyWhenNoChanges() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String head = git.log().call().iterator().next().getName();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(head)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.testClassFqns().isEmpty());
            assertTrue(result.changedFiles().isEmpty());
            assertFalse(result.runAll());
            assertEquals(EscalationReason.NONE, result.escalationReason());
        }
    }

    @Test
    void emptyChangesetWithRunAllIfNoMatchesReportsItsOwnReason() throws Exception {
        // Guards a subtle bug: before this test, an empty changeset with
        // the empty-diff escalation enabled shared the RUN_ALL_IF_NO_MATCHES
        // reason with the post-discovery empty branch, so the task logged
        // "no affected tests discovered" even though discovery had never
        // actually run. The two branches must be distinguishable from the
        // outside, otherwise the lifecycle log actively lies about why we
        // flipped to a full suite.
        try (Git git = initRepoWithInitialCommit()) {
            String head = git.log().call().iterator().next().getName();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(head)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .onEmptyDiff(Action.FULL_SUITE)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(),
                    "Empty changeset + onEmptyDiff=FULL_SUITE must still flip runAll");
            assertTrue(result.changedFiles().isEmpty());
            assertTrue(result.testClassFqns().isEmpty());
            assertEquals(EscalationReason.RUN_ALL_ON_EMPTY_CHANGESET, result.escalationReason(),
                    "Empty changeset must have its own reason so the task log names an honest trigger");
        }
    }

    @Test
    void runAllWhenNoMatchesAndFlagEnabled() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("Orphan.java"),
                    "package com.example;\npublic class Orphan {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add orphan").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .onDiscoveryEmpty(Action.FULL_SUITE)
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(), "Should set runAll when onDiscoveryEmpty=FULL_SUITE and no tests match");
            assertEquals(EscalationReason.RUN_ALL_IF_NO_MATCHES, result.escalationReason(),
                    "A discovery-empty runAll must report RUN_ALL_IF_NO_MATCHES so the task logs the right trigger");
        }
    }

    @Test
    void deletedTestFilesAreNotReportedAsAffected() throws Exception {
        // Regression test for C2: an old FQN for a deleted test file must not
        // propagate into the result set — otherwise Gradle's --tests filter
        // fails with "No tests found".
        try (Git git = initRepoWithInitialCommit()) {
            // Create + commit both a production class and its test
            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Path oldTest = testDir.resolve("FooServiceTest.java");
            Files.writeString(oldTest,
                    "package com.example;\npublic class FooServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add service + test").call();
            String base = git.log().call().iterator().next().getName();

            // Modify the service and delete the test file in the next commit
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService { void bar() {} }");
            Files.delete(oldTest);
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            git.commit().setMessage("modify service; delete test").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.testClassFqns().contains("com.example.FooServiceTest"),
                    "Deleted test FQN must not be returned to callers");
            assertFalse(result.testFqnToPath().containsKey("com.example.FooServiceTest"));
        }
    }

    @Test
    void testOnlyDiffTakesFastPathAndStillFiltersDeletedTests() throws Exception {
        // Regression for #45: a test-only diff (zero production classes
        // changed) takes the runTestOnlyFastPath branch which skips
        // ProjectIndex.build() entirely. Behaviour-preservation against
        // the existing index path is what this test pins:
        //
        //   * the surviving (modified) test FQN is in the result with
        //     its absolute path correctly mapped, AND
        //   * the deleted test FQN is filtered out before reaching
        //     downstream Gradle dispatch (otherwise --tests fails with
        //     "No tests found").
        //
        // Same on-disk filter contract the index-driven path enforces;
        // the fast-path implementation just runs Files.exists against
        // each diff entry instead of consulting the workspace-wide
        // testFqnToPath map.
        try (Git git = initRepoWithInitialCommit()) {
            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Path keptTest = testDir.resolve("KeptTest.java");
            Files.writeString(keptTest,
                    "package com.example;\npublic class KeptTest {}");
            Path goneTest = testDir.resolve("GoneTest.java");
            Files.writeString(goneTest,
                    "package com.example;\npublic class GoneTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline two tests").call();
            String base = git.log().call().iterator().next().getName();

            Files.writeString(keptTest,
                    "package com.example;\npublic class KeptTest { void t() {} }");
            Files.delete(goneTest);
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            git.commit().setMessage("modify Kept; delete Gone").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.DISCOVERY_SUCCESS, result.situation(),
                    "Test-only diff with at least one surviving test must "
                            + "still report DISCOVERY_SUCCESS — same outcome "
                            + "the index-driven path produces.");
            assertEquals(Action.SELECTED, result.action());
            assertTrue(result.testClassFqns().contains("com.example.KeptTest"),
                    "Modified test must be selected.");
            assertFalse(result.testClassFqns().contains("com.example.GoneTest"),
                    "Deleted test FQN must be filtered out — otherwise "
                            + "Gradle's --tests fails with 'No tests found' "
                            + "and the whole outer build dies.");
            assertNotNull(result.testFqnToPath().get("com.example.KeptTest"),
                    "Surviving test must map to its on-disk path so the "
                            + "Gradle dispatch can route it to the right module.");
            assertFalse(result.runAll());
            assertFalse(result.skipped());
        }
    }

    @Test
    void testOnlyDiffWithAllTestsDeletedRoutesToDiscoveryEmpty() throws Exception {
        // Companion: when the fast path filters out every candidate
        // (every test in the diff was deleted), the engine must still
        // produce a well-formed DISCOVERY_EMPTY result so the configured
        // action for that situation decides the outcome — same shape the
        // index-driven path produces.
        try (Git git = initRepoWithInitialCommit()) {
            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Path doomedTest = testDir.resolve("DoomedTest.java");
            Files.writeString(doomedTest,
                    "package com.example;\npublic class DoomedTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("baseline test").call();
            String base = git.log().call().iterator().next().getName();

            Files.delete(doomedTest);
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            git.commit().setMessage("delete test").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .mode(Mode.LOCAL)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.DISCOVERY_EMPTY, result.situation());
            assertTrue(result.testClassFqns().isEmpty());
            assertTrue(result.skipped(),
                    "DISCOVERY_EMPTY in LOCAL mode must skip — no tests to run "
                            + "and no escalation justified by a pure-delete diff.");
        }
    }

    @Test
    void forcesRunAllWhenNonJavaFileChangesAndFlagIsDefault() throws Exception {
        // Policy: "run more, never less" — any change to a file we cannot
        // resolve to a Java source or test class under the configured dirs
        // (e.g. application.yml, build.gradle, a Liquibase migration) must
        // force the full suite. The flag is on by default.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            // A pure YAML-only change — no Java at all.
            Path resourcesDir = tempDir.resolve("src/main/resources");
            Files.createDirectories(resourcesDir);
            Files.writeString(resourcesDir.resolve("application.yml"),
                    "spring:\n  application.name: demo\n");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add application.yml").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(),
                    "Non-Java-only change must force runAll under default policy");
            assertTrue(result.testClassFqns().isEmpty(),
                    "runAll result should not carry a filtered test FQN set");
            assertEquals(EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE, result.escalationReason(),
                    "Non-Java escalation must be tagged so the task log names the correct trigger");
        }
    }

    @Test
    void nonJavaFileAlongsideJavaChangeStillForcesRunAll() throws Exception {
        // Even when a Java change is also present (and discovery would normally
        // select a specific test), the presence of any unmapped file must still
        // escalate to runAll — we refuse to gamble that the YAML change is
        // harmless just because the Java change happens to have a named test.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");

            Path resourcesDir = tempDir.resolve("src/main/resources");
            Files.createDirectories(resourcesDir);
            Files.writeString(resourcesDir.resolve("application.yml"),
                    "spring:\n  application.name: demo\n");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add service + yaml together").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertTrue(result.runAll(),
                    "Mixed Java + non-Java change must still force runAll");
            assertTrue(result.testClassFqns().isEmpty(),
                    "A runAll escalation must not also carry a filtered FQN set — the downstream "
                            + "task would then have to decide which to honour, which is exactly "
                            + "the ambiguity the escalation exists to avoid");
            assertEquals(EscalationReason.RUN_ALL_ON_NON_JAVA_CHANGE, result.escalationReason());
        }
    }

    @Test
    void javaOnlyChangeDoesNotForceRunAll() throws Exception {
        // Guardrail for the opposite side: a pure Java change under a known
        // source dir must continue to produce a filtered selection, not
        // trigger the runAll escalation.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("java-only change").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.runAll(),
                    "Pure Java-under-sourceDirs change must not escalate to runAll");
            assertEquals(EscalationReason.NONE, result.escalationReason());
            assertTrue(result.testClassFqns().contains("com.example.FooServiceTest"));
        }
    }

    @Test
    void excludedPathsDoNotForceRunAll() throws Exception {
        // If the user has explicitly excluded a path, that's a clear opt-out
        // signal — the safety escalation must respect it and not force runAll.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path generatedDir = tempDir.resolve("src/main/java/generated");
            Files.createDirectories(generatedDir);
            Files.writeString(generatedDir.resolve("Stub.java"),
                    "package generated;\npublic class Stub {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add generated stub").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .ignorePaths(java.util.List.of("**/generated/**"))
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.runAll(),
                    "Ignored path should not trigger the non-Java safety escalation");
        }
    }

    @Test
    void unmappedFileEscalationCanBeDisabled() throws Exception {
        // Escape hatch: a user who wants the old "silent skip on YAML"
        // behaviour can explicitly opt out of the safety net via the v2
        // onUnmappedFile setter.
        //
        // We pin mode(LOCAL) because once the unmapped-file branch is
        // disabled, a yaml-only diff falls through to discovery with
        // zero production classes and lands on DISCOVERY_EMPTY. Under
        // the zero-config Mode.AUTO default that resolves to CI on any
        // GitHub-Actions-style runner (env var `CI=true`), and the CI
        // profile escalates DISCOVERY_EMPTY to FULL_SUITE — so an
        // unpinned test would pass locally and fail on CI, which is
        // exactly what happened on PR #34. This pin mirrors the v1→v2
        // migration path we document in README/CHANGELOG for users
        // coming off `runAllOnNonJavaChange = false`.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path resourcesDir = tempDir.resolve("src/main/resources");
            Files.createDirectories(resourcesDir);
            Files.writeString(resourcesDir.resolve("application.yml"),
                    "spring:\n  application.name: demo\n");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("yaml only").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .mode(Mode.LOCAL)
                    .onUnmappedFile(Action.SELECTED)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertFalse(result.runAll(),
                    "With onUnmappedFile=SELECTED the old silent-skip behaviour must be preserved");
            assertTrue(result.testClassFqns().isEmpty());
            assertEquals(EscalationReason.NONE, result.escalationReason(),
                    "Opting out of the unmapped-file escalation must also clear its reason tag");
        }
    }

    @Test
    void apiTestOnlyDiffRoutesToOutOfScopeAndSkips() throws Exception {
        // Canonical v2 scenario: a Cucumber/api-test-only diff must
        // short-circuit to SKIPPED without dragging the unit-test
        // dispatcher into a full-suite run.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path apiTestDir = tempDir.resolve("api-test/src/test/java/com/example/api");
            Files.createDirectories(apiTestDir);
            Files.writeString(apiTestDir.resolve("FooSteps.java"),
                    "package com.example.api;\npublic class FooSteps {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("api-test only").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .outOfScopeTestDirs(java.util.List.of("api-test/src/test/java"))
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.ALL_FILES_OUT_OF_SCOPE, result.situation());
            assertEquals(Action.SKIPPED, result.action());
            assertTrue(result.skipped());
            assertFalse(result.runAll());
            assertTrue(result.testClassFqns().isEmpty());
            assertEquals(EscalationReason.NONE, result.escalationReason());
        }
    }

    @Test
    void markdownOnlyDiffRoutesToAllFilesIgnoredAndSkips() throws Exception {
        // Markdown is in the default ignore list — a docs-only diff must
        // land on ALL_FILES_IGNORED and skip tests, not fall through to
        // the unmapped-file safety net.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Files.writeString(tempDir.resolve("docs.md"), "# docs");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("docs only").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.ALL_FILES_IGNORED, result.situation());
            assertEquals(Action.SKIPPED, result.action());
            assertTrue(result.skipped());
            assertFalse(result.runAll());
        }
    }

    @Test
    void mixedIgnoredAndOutOfScopeDiffRoutesToAllFilesOutOfScope() throws Exception {
        // Regression for batch-4 finding: an MR that combined a
        // markdown change (ignored by default globs) with an api-test
        // change (out-of-scope under the pilot config) previously
        // landed nowhere. Neither bucket alone matched the whole
        // diff, so the engine dropped through to mapping → empty
        // production/test sets → DISCOVERY_EMPTY, which in CI mode
        // defaults to FULL_SUITE. That is exactly the "quietly escalate
        // a no-op MR into a full CI run" shape v2 was built to prevent.
        // The fix: when ignored + out-of-scope together cover the
        // whole diff, route straight to ALL_FILES_OUT_OF_SCOPE (skip)
        // just as either bucket would individually.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            // One ignored file (markdown) + one out-of-scope file
            // (api-test). Neither bucket on its own matches the diff
            // — only the union does.
            Files.writeString(tempDir.resolve("docs.md"), "# docs");

            Path apiTestDir = tempDir.resolve("api-test/src/test/java/com/example/api");
            Files.createDirectories(apiTestDir);
            Files.writeString(apiTestDir.resolve("FooSteps.java"),
                    "package com.example.api;\npublic class FooSteps {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("mixed ignored+out-of-scope").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .outOfScopeTestDirs(java.util.List.of("api-test/src/test/java"))
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.ALL_FILES_OUT_OF_SCOPE, result.situation(),
                    "Mixed ignored+out-of-scope must route to ALL_FILES_OUT_OF_SCOPE, "
                            + "not fall through to DISCOVERY_EMPTY");
            assertEquals(Action.SKIPPED, result.action(),
                    "Mixed ignored+out-of-scope must default to SKIPPED — otherwise "
                            + "markdown+api-test MRs silently kick off full CI runs");
            assertTrue(result.skipped());
            assertFalse(result.runAll());
            assertTrue(result.testClassFqns().isEmpty());
        }
    }

    @Test
    void modeCiEscalatesDiscoveryEmpty() throws Exception {
        // Mode.CI's DISCOVERY_EMPTY default is FULL_SUITE — a CI user who
        // opts into mode=CI without also setting the legacy boolean still
        // gets the full-suite safety net on "found nothing" diffs.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("Orphan.java"),
                    "package com.example;\npublic class Orphan {}");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("orphan").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .mode(Mode.CI)
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.DISCOVERY_EMPTY, result.situation());
            assertEquals(Action.FULL_SUITE, result.action());
            assertTrue(result.runAll());
            assertFalse(result.skipped());
            assertEquals(EscalationReason.RUN_ALL_IF_NO_MATCHES, result.escalationReason());
        }
    }

    @Test
    void multiModuleTestPathsArePreservedInResult() throws Exception {
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            // Create a production class + matching test in nested module layout.
            Path prodDir = tempDir.resolve("moduleA/src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("moduleA/src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add module A").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            Path expected = tempDir.resolve("moduleA/src/test/java/com/example/FooServiceTest.java");
            Path actual = result.testFqnToPath().get("com.example.FooServiceTest");
            assertNotNull(actual, "Should expose absolute file path for each discovered test");
            assertEquals(expected.toAbsolutePath().normalize(),
                    actual.toAbsolutePath().normalize());
        }
    }

    @Test
    void discoveryIncompleteEscalatesToFullSuiteInCI() throws Exception {
        // Regression for B6-#9: when any Java file in the on-disk index
        // fails to parse, CI mode must stop pretending discovery is
        // complete. Before the fix parseOrWarn swallowed the failure
        // and the engine routed through DISCOVERY_EMPTY / DISCOVERY_SUCCESS
        // based on whatever survived. That produced silent
        // under-selection on any partially-migrated branch or
        // half-committed syntax error — exactly the case where you
        // most need the full suite. Defaults in CI mode must escalate
        // to FULL_SUITE and tag the escalation reason so the Gradle
        // task can print a useful explanation.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");
            // Unparseable test sibling — UsageStrategy will try to
            // parse every test file to look for references to the
            // changed production FQN and ProjectIndex#compilationUnit
            // will record the failure at the cache boundary. This
            // mirrors the real-world case we're hardening against:
            // a half-committed / mid-refactor test file that JavaParser
            // can't make sense of, silently dropped from the scan
            // before v1.9.22.
            Files.writeString(testDir.resolve("BrokenTest.java"),
                    "package com.example; public class BrokenTest {");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add FooService + broken test sibling").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .mode(Mode.CI)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("usage"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.DISCOVERY_INCOMPLETE, result.situation(),
                    "Unparseable Java file must route the engine through DISCOVERY_INCOMPLETE");
            assertEquals(Action.FULL_SUITE, result.action(),
                    "CI mode default for DISCOVERY_INCOMPLETE must be FULL_SUITE (safety net)");
            assertTrue(result.runAll(),
                    "FULL_SUITE action must set runAll so the Gradle task triggers the whole suite");
            assertEquals(EscalationReason.RUN_ALL_ON_DISCOVERY_INCOMPLETE,
                    result.escalationReason(),
                    "Escalation reason must expose that the cause was parse failures, not generic no-match");
        }
    }

    @Test
    void discoveryIncompleteStillSelectsInLocalMode() throws Exception {
        // Regression for B6-#9 (local-mode half): developers don't
        // want to pay a full-suite tax for a syntax error they're
        // actively editing. LOCAL mode must surface the same
        // DISCOVERY_INCOMPLETE situation so --explain can warn, but
        // keep the filtered selection instead of escalating. This
        // pins the asymmetry between CI (safety-first) and LOCAL
        // (iteration-speed-first) defaults.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("FooServiceTest.java"),
                    "package com.example;\npublic class FooServiceTest {}");
            Files.writeString(testDir.resolve("BrokenTest.java"),
                    "package com.example; public class BrokenTest {");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add FooService + broken test sibling").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .mode(Mode.LOCAL)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    // Need both — usage forces a parse of the broken
                    // file so we hit DISCOVERY_INCOMPLETE, naming then
                    // supplies FooServiceTest so we can also assert
                    // the selection survives parse failure.
                    .strategies(Set.of("usage", "naming"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.DISCOVERY_INCOMPLETE, result.situation(),
                    "LOCAL mode must still observe DISCOVERY_INCOMPLETE so --explain / logs stay truthful");
            assertEquals(Action.SELECTED, result.action(),
                    "LOCAL mode default for DISCOVERY_INCOMPLETE is SELECTED — iteration speed wins");
            assertFalse(result.runAll(),
                    "SELECTED must not trigger a full suite run even with parse failures");
            assertTrue(result.testClassFqns().contains("com.example.FooServiceTest"),
                    "Naming-based tests still get discovered from the files that did parse");
        }
    }

    @Test
    void discoveryIncompleteRespectsExplicitOnDiscoveryIncompleteOverride() throws Exception {
        // Regression for B6-#9 config wiring: the new
        // onDiscoveryIncomplete builder knob must override the
        // mode-based default. Without this test a future refactor
        // that flips the switch inside defaultFor(...) could silently
        // ignore explicit user configuration. Here we force CI mode
        // (default FULL_SUITE) but explicitly ask for SKIPPED, which
        // is the moral equivalent of "I know parse failures exist,
        // don't run anything, I'll deal with it."
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("FooService.java"),
                    "package com.example;\npublic class FooService {}");

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("BrokenTest.java"),
                    "package com.example; public class BrokenTest {");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("add FooService + broken test sibling").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .mode(Mode.CI)
                    .onDiscoveryIncomplete(Action.SKIPPED)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("usage"))
                    .transitiveDepth(0)
                    .build();

            AffectedTestsEngine engine = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult result = engine.run();

            assertEquals(Situation.DISCOVERY_INCOMPLETE, result.situation());
            assertEquals(Action.SKIPPED, result.action(),
                    "Explicit onDiscoveryIncomplete must win over mode default in CI");
            assertFalse(result.runAll());
            assertTrue(result.testClassFqns().isEmpty(),
                    "SKIPPED must yield no selected tests");
        }
    }

    @Test
    void coldRunAndWarmRunSelectIdenticalTestsAcrossAllStrategies() throws Exception {
        // End-to-end Stage 2 contract: a warm run (Project​IndexCache hit
        // — every {@code FileMetadata} row seeded from disk, zero AST
        // re-parses for unchanged files) MUST yield byte-identical
        // SELECTED output to a cold run (cache miss — every metadata
        // row freshly extracted). If a strategy ever forgets to thread
        // a field through the cache rewire, the warm run will quietly
        // miss tests the cold run picks up — the symptom is "the second
        // build of the same diff selects fewer tests than the first",
        // which is exactly the silent regression Stage 2's per-file
        // invalidation must prevent.
        //
        // Scope: run all three index-backed strategies (usage,
        // implementation, transitive) plus naming so a single drop in
        // any cached field is loud at the engine level.
        try (Git git = initRepoWithInitialCommit()) {
            String base = git.log().call().iterator().next().getName();

            Path prodDir = tempDir.resolve("src/main/java/com/example");
            Files.createDirectories(prodDir);
            Files.writeString(prodDir.resolve("Greeter.java"), """
                    package com.example;
                    public interface Greeter { String greet(String n); }
                    """);
            Files.writeString(prodDir.resolve("EnglishGreeter.java"), """
                    package com.example;
                    public class EnglishGreeter implements Greeter {
                        public String greet(String n) { return "hi " + n; }
                    }
                    """);
            Files.writeString(prodDir.resolve("GreetService.java"), """
                    package com.example;
                    public class GreetService {
                        private final Greeter g;
                        public GreetService(Greeter g) { this.g = g; }
                        public String run(String n) { return g.greet(n); }
                    }
                    """);

            Path testDir = tempDir.resolve("src/test/java/com/example");
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("EnglishGreeterTest.java"), """
                    package com.example;
                    public class EnglishGreeterTest {
                        EnglishGreeter g = new EnglishGreeter();
                    }
                    """);
            Files.writeString(testDir.resolve("GreetServiceTest.java"), """
                    package com.example;
                    public class GreetServiceTest {
                        GreetService s = new GreetService(new EnglishGreeter());
                    }
                    """);
            Files.writeString(testDir.resolve("GreeterContractTest.java"), """
                    package com.example;
                    public class GreeterContractTest {
                        Greeter g;
                    }
                    """);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("seed sources + tests").call();

            // Modify Greeter (the interface) so the diff drives every
            // strategy: usage matches anything that references {@code
            // Greeter}, implementation matches {@code EnglishGreeter}'s
            // implements clause, transitive walks
            // {@code GreetService} -> Greeter -> tests in one hop.
            Files.writeString(prodDir.resolve("Greeter.java"), """
                    package com.example;
                    public interface Greeter {
                        String greet(String n);
                        default String shout(String n) { return greet(n).toUpperCase(); }
                    }
                    """);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("widen Greeter contract").call();

            AffectedTestsConfig config = AffectedTestsConfig.builder()
                    .baseRef(base)
                    .includeUncommitted(false)
                    .includeStaged(false)
                    .strategies(Set.of("naming", "usage", "implementation", "transitive"))
                    .transitiveDepth(2)
                    .build();

            // Cold run: no Stage 1 dir snapshot, no Stage 2 metadata
            // rows on disk, every CompilationUnit must be parsed fresh.
            AffectedTestsEngine cold = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult coldResult = cold.run();

            // Warm run: same workspace, same diff, same engine config —
            // but now build/affected-tests/index/v1/ has both a Stage 1
            // dir snapshot and Stage 2 metadata rows that match every
            // file's current (mtime, size). Strategies should consume
            // the cached FileMetadata, NOT re-parse from source.
            AffectedTestsEngine warm = new AffectedTestsEngine(config, tempDir);
            AffectedTestsEngine.AffectedTestsResult warmResult = warm.run();

            assertEquals(coldResult.testClassFqns(), warmResult.testClassFqns(),
                    "Warm run (Stage 2 cache hit) must select the same tests as the cold "
                            + "run (cache miss). A drift here means a strategy is reading "
                            + "a field that the cache rewire dropped.");
            assertFalse(coldResult.testClassFqns().isEmpty(),
                    "Test scaffold must produce a non-empty selection — otherwise the "
                            + "equality assertion above is vacuously true and the "
                            + "regression net catches nothing");
            assertEquals(coldResult.runAll(), warmResult.runAll(),
                    "runAll flag is part of the SELECTED contract — flipping it across "
                            + "warm/cold would break downstream task wiring silently");
            assertEquals(coldResult.escalationReason(), warmResult.escalationReason(),
                    "Escalation reason must be deterministic across warm/cold; a drift "
                            + "indicates the cache changed an upstream signal that drives "
                            + "escalation, not just discovery output");
        }
    }
}
