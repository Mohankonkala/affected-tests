Feature: Issue #76 PR #4 — Kotlin AST first-class
  Phase 2 PR #4 of issue #76 closes the Kotlin AST rollout. The
  `kotlin-compiler-embeddable`-backed parser is now first-class
  (default ON); every `.kt` file flows through the same AST-driven
  discovery surface as `.java`. The scenarios below pin the four
  AST-driven `--explain` strings against representative diff shapes
  for each strategy (Naming, Usage, Implementation, Transitive),
  plus the parse-failure and path-vs-package-mismatch surfaces.

  See docs/PHASE-2-KOTLIN-AST.md §9 for the string stability
  contract — these strings are guaranteed stable verbatim outside
  the `{...}` placeholder slots.

  The `Kotlin source AST-mapped to FQN {fqn}.` line fires on every
  successful Kotlin parse regardless of which strategy made the
  selection, so it appears in every passing scenario below; it's
  the rollout health-check signal an adopter looks for.

  Background:
    Given a freshly initialised project with a committed baseline

  Scenario: K10-N1 — Naming strategy: .kt prod with sibling .kt test selects via AST-mapped FQN
    # Mirrors 09-K01 but pins the new PR-4 --explain line: under
    # default-on AST, the parser ran on Foo.kt → recorded
    # `com.example.Foo` as AST-mapped → the new "AST-mapped to FQN"
    # hint fires. The pre-PR-4 "mapped via filename only" hint must
    # NOT appear (it's the kotlinEnabled=false fallback path now).
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
    And the output does not contain "Kotlin source mapped via filename only"

  Scenario: K10-N2 — Naming strategy: top-level Kotlin function file routes via AST + synthetic <basename>Kt
    # `Util.kt` declares only top-level `fun greet`, no class/object.
    # The AST resolves the primary type as the synthetic `UtilKt`
    # (matching what the JVM compiler emits) and the parser records
    # `com.example.UtilKt` on the diagnostics carrier. The
    # naming-strategy test FQN universe contains `UtilKtTest`, so
    # the diff selects it without escalation. PR #1 already shipped
    # the synthetic; PR #4 surfaces it via the AST-mapped --explain
    # line so adopters can see exactly which FQN drove selection.
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
    And the output contains "Kotlin source AST-mapped to FQN com.example.UtilKt."

  Scenario: K10-U1 — Usage strategy: .kt test that imports a changed .kt production class is selected
    # The Usage strategy walks every test file's `import` statements.
    # When `BarUsesFooTest.kt` imports `com.example.Foo` and the
    # diff changes `Foo.kt`, the test must select. This pins the
    # cross-Kotlin-file usage-driven selection — the strategy that
    # PR #1's path-derived approach could NOT cover, and the
    # primary motivation for the AST work.
    Given a file at "src/main/java/com/example/Foo.kt" with content:
      """
      package com.example
      class Foo
      """
    And a file at "src/test/java/com/example/BarUsesFooTest.kt" with content:
      """
      package com.example
      import com.example.Foo
      class BarUsesFooTest {
        fun probe() = Foo()
      }
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Foo.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the outcome is "SELECTED — 1 test class(es) will run"
    And the output contains "Kotlin source AST-mapped to FQN com.example.Foo."

  Scenario: K10-I1 — Implementation strategy: .kt test on a subtype of a changed Kotlin supertype is selected
    # Implementation strategy: BaseService is changed; ConcreteService
    # extends BaseService; ConcreteServiceTest tests ConcreteService.
    # Strategy walks supertype declarations, finds ConcreteService
    # extends BaseService, selects ConcreteServiceTest. AST is
    # required because supertype names live in
    # FileMetadata.typeDecls — Phase 1 path-derived FQN couldn't
    # see them.
    Given a file at "src/main/java/com/example/BaseService.kt" with content:
      """
      package com.example
      open class BaseService
      """
    And a file at "src/main/java/com/example/ConcreteService.kt" with content:
      """
      package com.example
      class ConcreteService : BaseService()
      """
    And a file at "src/test/java/com/example/ConcreteServiceTest.kt" with content:
      """
      package com.example
      class ConcreteServiceTest
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/BaseService.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    And the output contains "Kotlin source AST-mapped to FQN com.example.BaseService."

  Scenario: K10-T1 — Transitive strategy: Kotlin field-type chain selects the downstream Kotlin test
    # Edge has Bridge as a field; Bridge has Leaf as a field; diff
    # changes Leaf. Transitive strategy walks the reverse used-by
    # graph (Java: field types only) and selects EdgeTest (Edge
    # transitively depends on Leaf). Field types — `val leaf: Leaf`
    # in Kotlin — are what the strategy indexes; using only function
    # bodies for the dependency would not register on the reverse
    # graph (mirrors the Java contract). All three production files
    # are .kt, so the AST-mapped FQN line fires for each, pinning
    # the per-FQN nature of the AST-mapped line.
    Given a file at "src/main/java/com/example/Leaf.kt" with content:
      """
      package com.example
      class Leaf
      """
    And a file at "src/main/java/com/example/Bridge.kt" with content:
      """
      package com.example
      class Bridge {
        val leaf: Leaf = Leaf()
      }
      """
    And a file at "src/main/java/com/example/Edge.kt" with content:
      """
      package com.example
      class Edge {
        val bridge: Bridge = Bridge()
      }
      """
    And a file at "src/test/java/com/example/EdgeTest.kt" with content:
      """
      package com.example
      class EdgeTest
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/Leaf.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    And the action is SELECTED
    # Per-FQN nature of the AST-mapped line: every successfully-parsed
    # .kt source on the chain emits its own line. A regression that
    # fired the line for only the changed file (Leaf) but not the
    # transitive chain (Bridge / Edge) would slip past a
    # `output contains "Leaf"` check alone.
    And the output contains "Kotlin source AST-mapped to FQN com.example.Leaf."
    And the output contains "Kotlin source AST-mapped to FQN com.example.Bridge."
    And the output contains "Kotlin source AST-mapped to FQN com.example.Edge."
    And the output contains "Kotlin source AST-mapped to FQN com.example.EdgeTest."

  Scenario: K10-FAIL — Malformed .kt routes to DISCOVERY_INCOMPLETE with the embeddable parse-failure hint
    # Pins the second pinned --explain string: a syntactically
    # broken .kt file that the embeddable PSI cannot recover from
    # bumps parseFailureCount → DISCOVERY_INCOMPLETE → strict mode
    # escalates to FULL_SUITE. The hint is verbatim "Kotlin file
    # failed to parse with embeddable {version}; counted into
    # DISCOVERY_INCOMPLETE." with the version slot filled by
    # KotlinDiagnostics.EMBEDDABLE_VERSION (currently 2.1.20).
    Given the affected-tests DSL contains:
      """
          mode = 'strict'
      """
    And a file at "src/main/java/com/example/FooService.kt" with content:
      """
      package com.example
      class FooService
      """
    And a file at "src/test/java/com/example/FooServiceTest.kt" with content:
      """
      package com.example
      class FooServiceTest
      """
    # Broken file — unbalanced brace inside an expression body, the
    # embeddable surfaces it as a PsiErrorElement past what
    # KotlinLanguageParser tolerates.
    And a file at "src/main/java/com/example/Broken.kt" with content:
      """
      package com.example
      class Broken {
        fun broken(): Int = (1 + 2
      }
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.kt"
    And the diff modifies "src/main/java/com/example/Broken.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_INCOMPLETE
    And the output contains "Kotlin file failed to parse with embeddable 2.1.20; counted into DISCOVERY_INCOMPLETE."

  Scenario: K10-MISMATCH — .kt with declared package different from path-derived emits the mismatch hint
    # Pins the third pinned --explain string: a Kotlin file whose
    # `package` declaration does not match the directory layout
    # the diff-side PathToClassMapper would derive. The AST-driven
    # strategies use the declared package; the naming strategy
    # uses the path-derived FQN. Adopters need this surfaced in
    # --explain so they can spot under-selection without grepping
    # WARN logs.
    Given a file at "src/main/java/com/example/foo/Bar.kt" with content:
      """
      package com.example.different
      class Bar
      """
    And a file at "src/test/java/com/example/foo/BarTest.kt" with content:
      """
      package com.example.foo
      class BarTest
      """
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/foo/Bar.kt"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the output contains "declares package com.example.different but path-derives to com.example.foo"
    And the output contains "AST-driven strategies use the declared package, naming strategy uses the path-derived FQN."
