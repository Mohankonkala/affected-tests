Feature: source-set auto-discovery (issue #49)
  Pins the contract that an adopter with extra source sets
  (`integrationTest`, `e2eTest`, `internal-test/java`, …) does
  not need to manually list every source / test directory in
  `affectedTests { ... }`. The plugin walks `JavaPluginExtension`
  source sets at `gradle.projectsEvaluated` time and seeds the
  `sourceDirs` / `testDirs` conventions from the live source-set
  graph. An explicit DSL override still wins, since Gradle's
  Property semantics put a `set()` ahead of any `convention()`.

  Background:
    Given a freshly initialised project with a committed baseline

  # ------------------------------------------------------------------
  # Headline #49 acceptance: an integrationTest source set is picked
  # up without any explicit `affectedTests.testDirs = [...]` line.
  # The scenario routes a production change through the plugin and
  # asserts the corresponding integration test fires; without
  # auto-discovery, `src/integrationTest/java` would not be in the
  # default ["src/test/java"] testDirs and the IT would never make
  # it to the SELECTED set.
  # ------------------------------------------------------------------
  Scenario: An integrationTest source set is auto-discovered into testDirs
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    # The auto-discovery walk pairs `integrationTest` with the
    # Test task of the same name via testClassesDirs ↔ classesDirs
    # overlap. Wiring it inline so the scenario doesn't depend on
    # JvmTestSuitePlugin (which Gradle 8.x has but earlier 7.x does
    # not) keeps the scenario portable across the matrix.
    And the build script also contains:
      """
      sourceSets {
          integrationTest {
              java.srcDir 'src/integrationTest/java'
          }
      }
      tasks.register('integrationTest', Test) {
          testClassesDirs = sourceSets.integrationTest.output.classesDirs
          classpath = sourceSets.integrationTest.runtimeClasspath
      }
      """
    # The IT shares the same simple-name suffix ("Test") as the
    # production class, so the naming strategy will pick it up
    # — but ONLY if the auto-discovery has put
    # `src/integrationTest/java` into testDirs. Without that, the
    # IT lives in a directory the plugin doesn't know about, and
    # the assertion below fails.
    And a file at "src/integrationTest/java/com/example/FooServiceIT.java" with content:
      """
      package com.example;
      public class FooServiceIT {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    # The headline assertion: the IT under the auto-discovered
    # source set is in the SELECTED set, proving the plugin
    # walked `JavaPluginExtension` and seeded testDirs from it.
    And the selected tests include "com.example.FooServiceIT"
    # The default test source set is still in scope — the
    # auto-discovery aggregates, it doesn't replace.
    And the selected tests include "com.example.FooServiceTest"

  # ------------------------------------------------------------------
  # Override contract: an explicit `affectedTests.testDirs = [...]`
  # in build.gradle MUST survive the auto-discovery walk. Gradle's
  # convention semantics deliver this for free, but a regression in
  # the plugin (e.g. switching from `convention()` to `set()` in
  # the `seed` helper) would silently clobber it. This scenario
  # locks the contract.
  # ------------------------------------------------------------------
  Scenario: An explicit testDirs setting survives auto-discovery
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    # Pin testDirs to a directory that does NOT match either the
    # default Java layout or the integrationTest source set, so a
    # regression that lets auto-discovery override the explicit
    # setting becomes immediately observable: FooServiceTest under
    # `src/test/java` would no longer be discovered, the SELECTED
    # set would be empty, and DISCOVERY_EMPTY would surface
    # instead of DISCOVERY_SUCCESS.
    And the affected-tests DSL contains:
      """
      testDirs = ['src/quirky-tests/java']
      """
    And the build script also contains:
      """
      sourceSets {
          integrationTest {
              java.srcDir 'src/integrationTest/java'
          }
      }
      tasks.register('integrationTest', Test) {
          testClassesDirs = sourceSets.integrationTest.output.classesDirs
          classpath = sourceSets.integrationTest.runtimeClasspath
      }
      """
    # Move the test under the quirky path the user pinned, so
    # discovery only finds it via the explicit override.
    And a file at "src/quirky-tests/java/com/example/FooServiceTest.java" with content:
      """
      package com.example;
      public class FooServiceTest {}
      """
    # And drop a FooServiceIT under the integrationTest source set
    # so the negative assertion below is meaningful: a regression
    # that lets auto-discovery override the explicit testDirs
    # would surface this IT in the SELECTED set, since the IT
    # would now be visible to discovery via the implicitly-added
    # `src/integrationTest/java` suffix.
    And a file at "src/integrationTest/java/com/example/FooServiceIT.java" with content:
      """
      package com.example;
      public class FooServiceIT {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    # Explicit override wins → only the test under the pinned
    # directory is discovered; the integrationTest source set is
    # invisible because it wasn't explicitly added to testDirs.
    And the situation is DISCOVERY_SUCCESS
    And the selected tests include "com.example.FooServiceTest"
    # Negative: the IT under src/integrationTest/java must NOT be
    # discovered, since auto-discovery did not override the
    # explicit testDirs.
    And the output does not contain "com.example.FooServiceIT"
