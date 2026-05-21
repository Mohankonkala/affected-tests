<!--
Thanks for the PR. The structure below mirrors the one used for
v2.2.x — it is short by design. The "Validation" and "Test plan"
boxes are the ones reviewers actually use; please don't skip them.

Branch + commit conventions (full detail in CONTRIBUTING.md):
  * One branch, one commit. Amend + force-with-lease on follow-ups.
  * Commit message: `<branch>: <one-line summary>` then a blank
    line then a paragraph explaining what and why.
  * Close the linked issue with `Closes #NN` at the end of the body.
-->

## Summary

<!-- One paragraph: what changed and why. Resist the urge to list every file. -->

## What changed

<!-- Short bullets of the user-visible or behaviour-visible deltas. -->

## Validation

<!--
Local validation commands you ran and their outcome. CI may or may
not run on this PR (credit-bound). The maintainer relies on this
section to know what was actually checked.

Suggested baseline:
  - `./gradlew :affected-tests-core:test :affected-tests-gradle:test`
  - `./gradlew :affected-tests-gradle:functionalTest`
  - For Configuration-Cache-touching changes: re-run the above
    with `--configuration-cache --configuration-cache-problems=fail`.
  - For performance-touching changes: a before/after wall-clock
    delta against a representative diff.
-->

## Test plan

- [ ] Unit + functional suites pass locally
- [ ] Linter / formatter clean (`./gradlew check`)
- [ ] No behaviour change for adopters on default config (or, if there is, it's called out below)
- [ ] CHANGELOG.md updated (if user-visible)

## Behaviour-change callouts

<!--
Skip this section if the change is internal-only.
For everything else, name the affected surface explicitly:

  * Situation/action mapping change (which situation, which mode)?
  * New DSL knob (default value, opt-in or opt-out)?
  * `--explain` output change (sample old → new)?
  * Log format change (sample old → new)?
-->

Closes #
