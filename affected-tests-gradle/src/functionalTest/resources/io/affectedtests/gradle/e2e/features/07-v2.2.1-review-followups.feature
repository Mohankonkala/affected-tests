Feature: v2.2.1 — code-review follow-ups from the v2.2 ship
  Scenarios for the Medium + Low findings raised by the v2.2 code
  review: Configuration Cache compatibility (M1), Action.SKIPPED
  hint wording (M3), empty-string `-PaffectedTestsMode` coercion
  (L1), and the AUTO-fallback error wording on an unknown `-P`
  value (L4). Every scenario here pins an operator-facing contract
  the v2.2 release shipped slightly short on — if any of these
  scenarios regress, a real adopter notices.

  Background:
    Given a freshly initialised project with a committed baseline

  # ------------------------------------------------------------------
  # M1 — Configuration Cache compatibility
  #
  # The v2.2 Callable for the Bug A `testClasses` dependency captured
  # `Project p` so a CC-enabled adopter would fail to serialise the
  # task graph ("cannot serialize object of type Project"). v2.2.1
  # fixed it by capturing `TaskProvider<?>` and `Property<Boolean>`
  # eagerly — both CC-serialisable. Pin it with a live
  # `--configuration-cache` run on the same SELECTED shape adopters
  # use daily: if the Callable ever regresses to capturing Project
  # again, this scenario turns red.
  #
  # Post-#46 follow-through: removed the task-side
  # `notCompatibleWithConfigurationCache(...)` opt-out and flipped
  # the plugin-publish `compatibility(...) configurationCache` from
  # `false` to `true`, so this scenario now also pins the *strong*
  # claim that the task is end-to-end CC-clean. The flag
  # `--configuration-cache-problems=fail` is what turns a
  # silently-degraded CC run into a hard failure.
  # ------------------------------------------------------------------
  Scenario: --configuration-cache stores and reuses the task graph without Project capture
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    And the Gradle command-line argument "--configuration-cache"
    # Post-#46: turn any CC violation into a hard failure rather than
    # a soft degradation. With the task no longer self-marked
    # incompatible, the only way this scenario stays green is for
    # every captured value (Properties, Providers, ExecOperations
    # service) to actually serialise round-trip. A regression
    # introducing a Project / Task / Build capture surfaces here as
    # an error before we reach any `output contains` assertion below.
    And the Gradle command-line argument "--configuration-cache-problems=fail"
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    # Gradle logs the CC-store event on the first run — no store line
    # means CC never engaged (e.g. because a problem was detected and
    # CC quietly degraded). The exact phrase changed between 8.x and
    # 9.x; match the stable prefix "Configuration cache entry" which
    # both minor lines emit.
    And the output contains "Configuration cache entry"
    # A Project-capture regression surfaces as this specific message
    # — pin the negative so a future edit that re-introduces it can't
    # hide behind a green "entry stored" line.
    And the output does not contain "cannot be serialized"
    And the output does not contain "cannot serialize"

  # ------------------------------------------------------------------
  # M3 — Action.SKIPPED hint no longer calls the skip "safe"
  #
  # v2.2 routed Action.SKIPPED through the FULL_SUITE hint branch,
  # which said "the resolved action above is the safe fallback".
  # Calling SKIPPED "safe" is precisely inverted advice: SKIPPED ran
  # zero tests on a partial-parse diff. v2.2.1 gives SKIPPED its own
  # hint branch that names the opt-in knob and recommends
  # `'full_suite'` if the skip wasn't intentional.
  # ------------------------------------------------------------------
  Scenario: DISCOVERY_INCOMPLETE on SKIPPED names the opt-in and offers an escape
    # Requires the opt-in: LOCAL mode with
    # `onDiscoveryIncomplete = 'skipped'` is the only path that lands
    # Action.SKIPPED on a parse-failure diff. CI/STRICT escalate
    # (their paired scenario is in feature 06).
    Given the affected-tests DSL contains:
      """
      mode = 'local'
      onDiscoveryIncomplete = 'skipped'
      """
    And a canned diff that produces the DISCOVERY_INCOMPLETE situation
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_INCOMPLETE
    And the action is SKIPPED
    And the output contains "failed to parse"
    # Hint must name both the user-set knob AND the escape hatch so
    # an operator reading the trace can tell whether the skip was
    # deliberate and, if not, exactly which value to flip.
    And the output contains "onDiscoveryIncomplete = 'skipped'"
    And the output contains "onDiscoveryIncomplete = 'full_suite'"
    # The FULL_SUITE branch's "safe fallback" wording must NOT leak
    # into the SKIPPED branch — that claim is factually wrong when
    # zero tests ran.
    And the output does not contain "safe fallback"
    # Partial-selection wording belongs to the SELECTED branch only;
    # SKIPPED ran nothing, so the selection is not partial — it's
    # non-existent.
    And the output does not contain "selection is necessarily partial"

  # ------------------------------------------------------------------
  # L1 — empty `-PaffectedTestsMode` coerces to absent
  #
  # A CI template that unconditionally emits
  # `-PaffectedTestsMode=$MODE` with an unset $MODE would send the
  # literal empty string through. v2.2 crashed parseMode with
  # "Unknown affectedTests.mode ''". v2.2.1 filters empty/whitespace
  # in the extension convention so the empty case is identical to
  # omitting the flag. The scenario below pins "no crash" rather than
  # a specific resolved mode, because the AUTO default depends on
  # whether a CI=true env var is present on the test JVM — asserting
  # on a specific mode would make the scenario environment-sensitive.
  # ------------------------------------------------------------------
  Scenario: An empty -PaffectedTestsMode behaves like the flag is unset
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    And the Gradle command-line argument "-PaffectedTestsMode="
    When the affected-tests task runs with "--explain"
    Then the task succeeds
    And the situation is DISCOVERY_SUCCESS
    # The regression we're guarding: v2.2 would surface this exact
    # error message for the empty-string case. v2.2.1 must not.
    And the output does not contain "Unknown affectedTests.mode ''"
    And the output does not contain "Unknown affectedTests.mode"

  # ------------------------------------------------------------------
  # L4 — parseMode error names the AUTO fallback
  #
  # v2.2's error listed the four legal values only, which led
  # adopters to ask "so which one is the default?". v2.2.1 adds the
  # AUTO-fallback hint plus the CI=true tripwire to the message so
  # the fix is self-service from the operator's terminal.
  # ------------------------------------------------------------------
  Scenario: An unknown -PaffectedTestsMode names AUTO and CI=true in the error
    Given a production class "com.example.FooService" with its matching test "com.example.FooServiceTest"
    And the baseline commit is captured
    And the diff modifies "src/main/java/com/example/FooService.java"
    And the Gradle command-line argument "-PaffectedTestsMode=xyzzy"
    When the affected-tests task runs with "--explain"
    Then the task fails
    # Echo-back of the bad value keeps typo diagnosis local — no need
    # to re-read the CI log to find what the template substituted.
    And the output contains "Unknown affectedTests.mode 'xyzzy'"
    # Legal-values list must stay (the v2.2 wording kept this right).
    And the output contains "auto, local, ci, strict"
    # The v2.2.1 additions: name the fallback AND the environment
    # signal so an adopter understands what "just leave -P off" will
    # actually do on each machine.
    And the output contains "AUTO"
    And the output contains "CI=true"
