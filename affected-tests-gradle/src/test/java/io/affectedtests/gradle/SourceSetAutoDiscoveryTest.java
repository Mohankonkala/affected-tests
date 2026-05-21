package io.affectedtests.gradle;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link SourceSetAutoDiscovery}'s contract for issue #49.
 *
 * <p>{@link ProjectBuilder} runs Gradle's project model in-memory
 * without spinning up a daemon, which is the only realistic way to
 * exercise the {@code JavaPluginExtension} → {@code SourceSetContainer}
 * → {@code Test}-task pairing. Each test wires a complete project
 * fixture so the assertions are pinned against actual Gradle metadata,
 * not a hand-rolled stub of the source-set API.
 */
class SourceSetAutoDiscoveryTest {

    @Test
    void emptyDiscoveryWhenNoJavaPluginIsApplied() {
        // A pure-tooling project (e.g. a build aggregator that only
        // applies `affectedtests` itself) has no source sets to walk.
        // Discovery must return empty lists so the static
        // ["src/main/java"] / ["src/test/java"] convention installed
        // in apply() is left untouched — silently overriding it with
        // an empty list would break every adopter who applies the
        // plugin to the root of a multi-module repo where the root
        // itself has no Java sources.
        Project project = ProjectBuilder.builder().build();

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);

        assertFalse(discovery.hasMainDirs(),
                "No JavaPluginExtension anywhere → no main dirs to seed");
        assertFalse(discovery.hasTestDirs(),
                "No JavaPluginExtension anywhere → no test dirs to seed");
        assertFalse(discovery.hasTestTaskNames(),
                "No JavaPluginExtension anywhere → no Test tasks to enumerate");
        assertEquals(List.of(), discovery.sourceDirs());
        assertEquals(List.of(), discovery.testDirs());
        assertEquals(List.of(), discovery.testTaskNames());
    }

    @Test
    void defaultJavaProjectDiscoversMainAndTestSourceSets() {
        // The zero-config Gradle Java project (just `apply java`) has
        // exactly two source sets — `main` and `test`. Discovery must
        // map them to ["src/main/java"] / ["src/test/java"] so a
        // single-module adopter sees no behavioural change after
        // turning auto-discovery on.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);

        assertEquals(List.of("src/main/java"), discovery.sourceDirs(),
                "Default `main` source set must surface as 'src/main/java'");
        assertEquals(List.of("src/test/java"), discovery.testDirs(),
                "Default `test` source set must surface as 'src/test/java'");
        assertEquals(List.of("test"), discovery.testTaskNames(),
                "The default `test` Test task must be enumerated");
    }

    @Test
    void customIntegrationTestSourceSetIsClassifiedAsATestSet() {
        // The headline #49 scenario: an adopter registers an
        // `integrationTest` source set + Test task. Auto-discovery
        // must put `src/integrationTest/java` under testDirs (not
        // sourceDirs), pin the task name, and leave the default
        // `test` entry intact so both source sets work side-by-side.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");

        JavaPluginExtension jpe = project.getExtensions()
                .getByType(JavaPluginExtension.class);
        SourceSetContainer sourceSets = jpe.getSourceSets();
        SourceSet integrationTest = sourceSets.create("integrationTest");

        // Wire a Test task so the source set is paired correctly.
        // Without the testClassesDirs wire-up auto-discovery would
        // fall back to the name-based heuristic — which works here
        // because the task is named after the source set, but pinning
        // the explicit wiring makes the test exercise the
        // FileCollection-overlap path that production-ready adopters
        // use (Gradle's own JvmTestSuitePlugin sets it up the same
        // way).
        project.getTasks().register("integrationTest",
                org.gradle.api.tasks.testing.Test.class, t ->
                        t.setTestClassesDirs(integrationTest.getOutput().getClassesDirs()));

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);

        assertTrue(discovery.testDirs().contains("src/test/java"),
                "Default test source set must remain in testDirs alongside the new entry");
        assertTrue(discovery.testDirs().contains("src/integrationTest/java"),
                "src/integrationTest/java must be classified as a TEST directory, "
                        + "not as a production directory — that's the whole #49 contract");
        assertFalse(discovery.sourceDirs().contains("src/integrationTest/java"),
                "src/integrationTest/java must NOT bleed into sourceDirs — a regression "
                        + "here would make PathToClassMapper resolve test classes as "
                        + "production classes and silently turn every IT change into "
                        + "a full-suite escalation");

        assertTrue(discovery.testTaskNames().contains("test"),
                "Default `test` task must remain in testTaskNames");
        assertTrue(discovery.testTaskNames().contains("integrationTest"),
                "`integrationTest` task must surface so #48's dispatch path can route "
                        + "discovered IT FQNs to it");
    }

    @Test
    void fixtureLikeSourceSetWithoutTestTaskFallsToMainBucket() {
        // A source set with no paired Test task (the shape used for
        // shared fixtures, internal API surfaces, etc.) must be
        // treated as production. The fixture sources are consumed by
        // tests at compile time, so PathToClassMapper needs to be
        // able to resolve their FQNs as production classes. Routing
        // them to testDirs would be silently wrong: a change in
        // FixtureBuilder.java would then look like a "test-only diff"
        // and skip discovery entirely on a paranoid mode profile.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");

        JavaPluginExtension jpe = project.getExtensions()
                .getByType(JavaPluginExtension.class);
        jpe.getSourceSets().create("fixtures");

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);

        assertTrue(discovery.sourceDirs().contains("src/fixtures/java"),
                "An unpaired source set's srcDirs go into sourceDirs (production bucket) "
                        + "since fixtures are production-like helpers consumed by tests");
        assertFalse(discovery.testDirs().contains("src/fixtures/java"),
                "Unpaired source sets must NOT pollute testDirs — that would silently "
                        + "expand the test-discovery surface and either over-select or "
                        + "(under STRICT mode) fail the build on an unparseable fixture file");
    }

    @Test
    void customJavaSrcDirIsRelativisedToTheProjectRoot() {
        // An adopter who points the `main` source set at
        // `src/cool/java` (e.g. a legacy Maven-converted project)
        // must have that exact suffix surface in sourceDirs — not
        // an absolute path, and not the conventional
        // "src/main/java" Gradle would have used otherwise.
        // Anything else and SourceFileScanner's suffix-match would
        // miss every production file in the project and route every
        // production-file diff through UNMAPPED_FILE → FULL_SUITE.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");

        JavaPluginExtension jpe = project.getExtensions()
                .getByType(JavaPluginExtension.class);
        SourceSet main = jpe.getSourceSets().getByName("main");
        File coolDir = new File(project.getProjectDir(), "src/cool/java");
        main.getJava().setSrcDirs(Set.of(coolDir));

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);

        assertEquals(List.of("src/cool/java"), discovery.sourceDirs(),
                "Custom-layout main source set must round-trip through "
                        + "projectDir-relativisation as a clean suffix");
    }

    @Test
    void srcDirOutsideTheProjectRootIsSkipped() {
        // A srcDir that points outside the project tree (e.g. an
        // adopter wiring a sibling project's sources via an absolute
        // path — wrong but observed in the wild) cannot be expressed
        // as a project-relative suffix without breaking
        // SourceFileScanner's containment guarantee. Skipping it is
        // the conservative choice: the user can opt it in explicitly
        // via affectedTests.sourceDirs in their build.gradle, where
        // the explicit setting is itself a safety review surface.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");

        JavaPluginExtension jpe = project.getExtensions()
                .getByType(JavaPluginExtension.class);
        SourceSet main = jpe.getSourceSets().getByName("main");
        // Use the project's parent dir as a guaranteed-outside path —
        // ProjectBuilder gives us a temp dir, and its parent is
        // always outside our tree.
        File outside = project.getProjectDir().getParentFile();
        main.getJava().setSrcDirs(Set.of(outside));

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);

        assertEquals(List.of(), discovery.sourceDirs(),
                "An out-of-tree srcDir must not surface as a relative suffix — "
                        + "the user has to opt it in explicitly so the safety review "
                        + "surface stays at the build.gradle level");
    }

    @Test
    void multiModuleProjectAggregatesSubprojectSourceSets() {
        // The common monorepo shape: root has no Java sources, each
        // subproject does. Discovery must walk every subproject and
        // union the unique suffixes into a single seed list. A
        // missing subproject walk would silently default the root
        // task to ["src/main/java"] / ["src/test/java"], which would
        // fail to discover anything in a `services/orders` /
        // `src/integrationTest/java` layout.
        Project root = ProjectBuilder.builder().withName("monorepo").build();
        Project orders = ProjectBuilder.builder()
                .withName("orders").withParent(root).build();
        Project payments = ProjectBuilder.builder()
                .withName("payments").withParent(root).build();

        orders.getPlugins().apply("java");
        payments.getPlugins().apply("java");

        // Add an integrationTest source set + task to one subproject.
        JavaPluginExtension paymentsJpe = payments.getExtensions()
                .getByType(JavaPluginExtension.class);
        SourceSet paymentsIt = paymentsJpe.getSourceSets().create("integrationTest");
        payments.getTasks().register("integrationTest",
                org.gradle.api.tasks.testing.Test.class, t ->
                        t.setTestClassesDirs(paymentsIt.getOutput().getClassesDirs()));

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(root);

        assertTrue(discovery.sourceDirs().contains("src/main/java"),
                "Common main suffix must surface from at least one subproject");
        assertTrue(discovery.testDirs().contains("src/test/java"),
                "Common test suffix must surface from at least one subproject");
        assertTrue(discovery.testDirs().contains("src/integrationTest/java"),
                "Subproject-only integrationTest suffix must surface in the root-level "
                        + "discovery — without aggregation a mono-repo adopter would "
                        + "have to explicitly list every per-module source dir");
        // Deduplication: both subprojects contribute "src/main/java",
        // but it must surface exactly once. A duplicate would force
        // SourceFileScanner to walk the same physical directory twice
        // per discovery and silently double the wall time of every
        // ./gradlew affectedTest run.
        long mainCount = discovery.sourceDirs().stream()
                .filter("src/main/java"::equals).count();
        assertEquals(1L, mainCount,
                "Duplicate suffixes from multiple subprojects must be deduplicated");
        long testCount = discovery.testDirs().stream()
                .filter("src/test/java"::equals).count();
        assertEquals(1L, testCount,
                "Duplicate suffixes from multiple subprojects must be deduplicated");
    }

    @Test
    void multipleSourceSetsWiredToOneTestTaskPairFirstSetAndIgnoreOthers() {
        // Edge case: two source sets that share the same Test task's
        // classesDirs (a JvmTestSuite that's been split across two
        // physical srcDirs is the realistic shape). Discovery must
        // treat the first overlap as canonical so the testTaskNames
        // list has no duplicates and downstream consumers (issue
        // #48's dispatch) don't get a (module × task) cross-product
        // explosion. The other source set's srcDirs still surface
        // in testDirs because they're paired through the same task.
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");

        JavaPluginExtension jpe = project.getExtensions()
                .getByType(JavaPluginExtension.class);
        SourceSet customTest = jpe.getSourceSets().create("customTest");
        // Repurpose the existing `test` Test task by pointing it at
        // both source sets — uncommon in production but exactly the
        // shape that triggers the multi-source-set edge case if the
        // pairing-detection logic is naive.
        org.gradle.api.tasks.testing.Test testTask =
                (org.gradle.api.tasks.testing.Test) project.getTasks().getByName("test");
        testTask.setTestClassesDirs(
                project.files(
                        jpe.getSourceSets().getByName("test").getOutput().getClassesDirs(),
                        customTest.getOutput().getClassesDirs()));

        SourceSetAutoDiscovery discovery = SourceSetAutoDiscovery.from(project);

        assertEquals(List.of("test"), discovery.testTaskNames(),
                "Even when two source sets share one Test task's classesDirs, the "
                        + "task name surfaces exactly once — duplicates would force "
                        + "the dispatch path to invoke the same Gradle target twice");
        assertTrue(discovery.testDirs().contains("src/test/java")
                        && discovery.testDirs().contains("src/customTest/java"),
                "Both paired source sets' srcDirs surface in testDirs so discovery "
                        + "scans both. Got: " + discovery.testDirs());
    }
}
