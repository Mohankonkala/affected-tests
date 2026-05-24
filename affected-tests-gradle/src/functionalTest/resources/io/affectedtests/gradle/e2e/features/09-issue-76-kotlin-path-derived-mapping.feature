Feature: Issue #76 PR #1 — Kotlin path-derived mapping
  Phase 2 PR #1 of issue #76 lifts Kotlin sources from the unmapped
  bucket into the same mapping pipeline as `.java`. The scenarios
  below pin the adopter-visible behaviour change so a regression to
  "Kotlin in unmapped → FULL_SUITE" is caught at TestKit-build
  latency, not after a Kotlin shop files an issue.

  Phase 1 (already shipped) added the polyglot `--explain` hint.
  PR #1 narrowed it for Kotlin: a `.kt` that maps now emits a
  pinned hint, and a `.kt` that lands in the unmapped bucket emits
  the "Kotlin source unmapped" hint. PR #4 demoted PR #1's
  "mapped via filename only" hint behind the `kotlinEnabled = false`
  fallback path; the default rollout (kotlinEnabled = true) now
  emits "Kotlin source AST-mapped to FQN ..." instead. The
  scenarios below assert against the new default. See
  docs/PHASE-2-KOTLIN-AST.md §9 for the full string stability
  contract.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: K01 — production Kotlin file with matching Kotlin test selects only that test
    # The naming-convention strategy reads from the test-FQN universe
    # produced by SourceFileScanner.scanTestFqns. PR #1 widened that
    # walker to include `.kt`, so a `Foo.kt` change with a sibling
    # `FooTest.kt` selects without escalation. Pre-PR-1 this diff
    # would have routed to UNMAPPED_FILE → FULL_SUITE under the CI
    # default; post-PR-1 it routes through DISCOVERY_SUCCESS.
    Given a file at "src/main/java/com/example/Foo.kt" with content:
      """
      package com.example
      class Foo
      """
    And a file at "src/test/java/com/example/FooTest.kt" with content:
      """
      package com.example
      class FooTest
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Foo.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the outcome is "SELECTED — 1 test class(es) will run"
    And the output contains "Kotlin source AST-mapped to FQN com.example.Foo."

  Scenario: K02 — top-level Kotlin function file with <basename>Kt test neighbour selects via synthetic FQN
    # Kotlin top-level functions in `Util.kt` compile to a class
    # named `UtilKt`. PathToClassMapper emits both `Util` and
    # `UtilKt` into changedProductionClasses; the naming strategy
    # then probes `UtilKtTest` against the test-FQN universe and
    # finds it. This pins the adopter-visible behaviour for
    # Spring Boot / KGP-style codebases that mix Kotlin top-level
    # utilities with Java tests.
    Given a file at "src/main/java/com/example/Util.kt" with content:
      """
      package com.example
      fun greet(name: String) = "hi $name"
      """
    And a file at "src/test/java/com/example/UtilKtTest.kt" with content:
      """
      package com.example
      class UtilKtTest
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Util.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the outcome is "SELECTED — 1 test class(es) will run"

  Scenario: K03 — Kotlin file outside any configured source root routes to unmapped with new hint
    # A `.kt` under buildSrc / a stray top-level dir is genuinely
    # unmappable post-PR-1 — there's no source root that would yield
    # a class FQN. The new "Kotlin source unmapped" hint replaces
    # the older polyglot hint's `.kt` branch (still surfaces for
    # .groovy / .scala / .kts which remain Java-only mapped).
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And a file at "buildSrc/Util.kt" with content:
      """
      package buildSrc
      class Util
      """
    And the baseline commit is captured
    And the diff modifies "buildSrc/Util.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is UNMAPPED_FILE
    And the action is FULL_SUITE
    And the output contains "Kotlin source unmapped (no matching source/test root); routed to unmapped bucket."
    # Pin the contract that PR #1 narrowed the polyglot hint and
    # PR #4 demoted the rest: the legacy "currently maps only
    # .java" wording would contradict the new pinned hint and
    # confuse adopters reading both lines. A regression that
    # re-introduces `.kt` to `polyglotExtensionOf` or that revives
    # the pre-PR-4 "the plugin currently maps only .java" wording
    # would silently pass K03's positive assertion above without
    # these negative guards.
    And the output does not contain "currently maps only .java"
    And the output does not contain "issues/47"

  Scenario: K04 — mixed Java + Kotlin diff selects both Java-named and Kotlin-named tests
    # Mid-migration consumers — Java production class with a Kotlin
    # test, plus a Kotlin production class with a Java test — must
    # both select. Tier 1 (UsageStrategy direct-import) handles the
    # cross-language cases at PR #3; PR #1 ships the naming-strategy
    # path so the most common shape (matching `<Class>Test` neighbour
    # in either language) lights up immediately.
    Given a file at "src/main/java/com/example/JavaProd.java" with content:
      """
      package com.example;
      public class JavaProd {}
      """
    And a file at "src/main/java/com/example/KotlinProd.kt" with content:
      """
      package com.example
      class KotlinProd
      """
    And a file at "src/test/java/com/example/JavaProdTest.kt" with content:
      """
      package com.example
      class JavaProdTest
      """
    And a file at "src/test/java/com/example/KotlinProdTest.java" with content:
      """
      package com.example;
      public class KotlinProdTest {}
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/JavaProd.java"
    And the diff modifies "src/main/java/com/example/KotlinProd.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the outcome is "SELECTED — 2 test class(es) will run"
    And the output contains "Kotlin source AST-mapped to FQN com.example.KotlinProd."

  Scenario: K05 — test-only Kotlin diff selects only the touched Kotlin test
    # Symmetric to S03 (Java test-only diff). The test file IS the
    # affected test — no production-mapping needed, no synthetic
    # `<basename>Kt` emission (test-side emission is suppressed per
    # docs/PHASE-2-KOTLIN-AST.md §6 to avoid "no tests found" runner
    # errors).
    Given a file at "src/main/java/com/example/Bar.kt" with content:
      """
      package com.example
      class Bar
      """
    And a file at "src/test/java/com/example/BarTest.kt" with content:
      """
      package com.example
      class BarTest
      """
    And the baseline commit is captured
    And the diff modifies "src/test/java/com/example/BarTest.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the outcome is "SELECTED — 1 test class(es) will run"
    # Negative guard: the test-only fast path
    # ({@code AffectedTestsEngine.runTestOnlyFastPath}) must NOT
    # construct a ProjectIndex or engage KotlinLanguageParser, so no
    # AST-mapped FQN sample should be recorded for this run. A future
    # refactor that drops the fast path (or accidentally wires the
    # parser into it) would silently start emitting the AST-mapped
    # hint here without breaking the positive assertions above; the
    # negative guard makes that drift a TestKit-build failure.
    And the output does not contain "Kotlin source AST-mapped to FQN"
