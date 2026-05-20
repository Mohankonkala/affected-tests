# Security policy

## Supported versions

The plugin is published to the Gradle Plugin Portal as
`io.github.vedanthvdev.affectedtests`. Security fixes are issued
against the latest minor line on `master` only. Older minor lines
are not back-patched — adopters should track `master`'s latest
release.

| Version | Security fixes |
| --- | --- |
| Latest 2.x | Yes |
| Older 2.x | No — upgrade to latest 2.x |
| 1.x | No — superseded by 2.x |

## Reporting a vulnerability

**Do not open a public GitHub issue for a security report.**

Use GitHub's
[private vulnerability reporting](https://github.com/vedanthvdev/affected-tests/security/advisories/new)
flow on the repo's Security tab. The form routes the report
directly to the maintainers in a private advisory thread before
any code or detail becomes public.

If GitHub private reporting is not an option for you, email the
maintainer at the address listed on their GitHub profile and put
`[SECURITY]` in the subject line. Include:

- A description of the vulnerability and the threat it enables.
- A minimal reproduction (a Gradle project layout, a diff, or a
  command line is plenty).
- The affected plugin version (`./gradlew :buildEnvironment` will
  print it).

## What to expect

- **Acknowledgement** within 7 days of the report landing.
- **Triage** within 14 days — either a confirmed CVE-track issue
  with an ETA, or a reasoned explanation for why the report does
  not meet the threat model below.
- **Fix + advisory** issued together. The advisory will credit the
  reporter unless they ask to remain anonymous.

## Threat model — what this plugin treats as an untrusted boundary

The plugin sees three categories of input. The threat model
treats them differently and a security report is most useful when
it points at one of these surfaces:

1. **The Git diff.** File paths, branch refs, and commit SHAs come
   from the user's checkout and are treated as **untrusted** —
   filenames may contain shell metacharacters or control chars, a
   ref may be hostile, and the diff may be malicious. The
   `LogSanitizer` class is the boundary at which untrusted strings
   become loggable. Path traversal protection lives in
   `SourceFileScanner` and `PathToClassMapper`. Reports about log
   forgery, path traversal, command injection, or untrusted-input
   crashes are in scope.
2. **The Java source AST.** JavaParser runs on every `.java` file
   under the configured source dirs. Reports about JavaParser
   crashing the plugin on malformed input, or about a parse
   failure causing the plugin to silently miss tests, are in scope.
3. **The Gradle build script.** The `affectedTests { ... }` DSL is
   trusted — the plugin trusts the project that applied it. A
   report saying "I configured `outOfScopeSourceDirs = '/etc'` and
   nothing happened" is not in scope; that surface is intentional.

Out of scope:

- Vulnerabilities in third-party dependencies that the plugin
  does not surface to the adopter (`./gradlew :affected-tests-gradle:dependencies`
  for the full list). Report those upstream — Dependabot will
  pick up published advisories automatically.
- Misconfiguration on the consumer side (over-broad
  `outOfScopeSourceDirs`, `mode = 'strict'` escalating in CI, etc.)
  — those are operability bugs, not security bugs, and belong in
  a regular issue.
- Anything that requires the attacker to already own a write path
  into the consumer's repo — at that point the diff is the least
  of their problems.
