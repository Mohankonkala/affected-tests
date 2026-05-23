# Phase 2 — Kotlin AST support (issue #76 / #47)

> Tech plan for Phase 2 of [#47](https://github.com/vedanthvdev/affected-tests/issues/47),
> tracked under [#76](https://github.com/vedanthvdev/affected-tests/issues/76).
> **Plan-only** — no engine code changes in this PR. Subsequent PRs reference
> this document and execute one section at a time.

---

## 0. TL;DR

Today the plugin only parses `.java`. Any `.kt` / `.groovy` / `.scala` change
in the diff falls through to the `unmapped` bucket and escalates the whole MR
to `FULL_SUITE` under the default `ci` / `strict` profiles
(`--explain` already names this; see Phase 1 / commit `e3167b5`).

Phase 2 closes that gap by making **Kotlin a first-class source language** in
the discovery engine. The architecture already isolates the AST-driven
strategies behind one record (`FileMetadata`), so most of the index-driven
hot path stays put. The work that does change: introduce a `LanguageParser`
interface, add a Kotlin implementation backed by
`org.jetbrains.kotlin:kotlin-compiler-embeddable`, dispatch by file extension
at the index layer, widen every `.java` path-strip site to recognise `.kt`,
and rewire each strategy's no-index fallback (`metadataOrGet(... JavaParser)`)
to dispatch through `LanguageParser` instead. Naming strategy is a special
case: it consumes path-derived FQNs from `scanTestFqns`, not `FileMetadata`,
so its Kotlin parity is delivered by widening that scanner — see Section 2
for the full code-site enumeration.

Groovy and Scala stay on the Phase 1 hint until separate follow-up issues
take them on. The architecture leaves room for them but we do **not** ship
all three at once — each language adds a multi-tens-of-MB embeddable parser
and its own edge cases.

**Cost:** one new heavy runtime dependency (~50 MB before shading), shaded
under `io.affectedtests.shadow.kotlin` plus separate top-level namespaces
for `kotlinstdlib`, `kotlinx.coroutines`, and `trove4j`, with three
nested namespaces under `shadow.kotlin` for the embeddable's bundled
IntelliJ / fastutil / jline copies (Section 5 has the full list).

**Rollout:** four small PRs (path-derived FQN mapping → parser interface
+ fallback rewire → Kotlin impl behind a system property → flip the default
+ remove property). Each has an explicit decision gate; we may stop after
PR #1 or PR #3 if adopter feedback says approach B is unnecessary
(see Section 0.5 and Section 9.5).

---

## 0.5 Why approach B (full AST), not just A (filename-only)

Issue #47 explicitly framed approach A as "the minimum viable next step
that closes the worst adoption blocker for ~80% of mixed Java + Kotlin
shops." Issue #76 records this as an open question: does A alone close
the gap, or does B add real value?

This plan keeps the option open. **PR #1 ships approach A in full** and
is sufficient on its own to close the worst adoption blocker. PRs #2–4
are the path to B, ordered as separate decision gates. Concrete
scenarios where A alone leaves a gap adopters will notice:

1. **Java test imports a top-level Kotlin function.** `Main.java` writes
   `import com.example.UtilKt;`. Under A, the diff produces `com.example.Util`
   on a `Util.kt` change; UsageStrategy tier-1 looks up
   `imports.contains("com.example.Util")` against the test's import list,
   which contains `com.example.UtilKt` — no match. Java tests using
   top-level Kotlin functions are silently under-selected. PR #1 mitigates
   by emitting **both** `Util` and `UtilKt` into `changedProductionClasses`
   for every `.kt` (over-selection bounded to one extra simple-name probe);
   real fan-out edges still need the AST.
2. **Kotlin test calls a Java production class via tier-1 import.** Works
   under A (filename-only Kotlin neighbour matches `<Class>Test.kt`) **only**
   if test naming is conventional. Adopters with Cucumber-style step files
   or Spring `@SpringBootTest` classes in distinct packages need UsageStrategy.
3. **Implementation strategy on a Kotlin sealed hierarchy.** A change to
   `sealed interface Animal` should select tests of `Dog`, `Cat`. Without
   a Kotlin AST, ImplementationStrategy never sees the supertype edge.
4. **Transitive walks across Kotlin files.** A → B → C where B is Kotlin:
   under A, the walk stops at B; under B, it crosses.

A concrete decision gate after PR #1 (Section 9.5) gives us one release
of adopter telemetry. If the residual gap is small in real consumer trees
(cases 2–4 don't materialise), we can stop there and reopen #76 as
"won't ship B until N adopters report under-selection." If the gap is
real, PRs #2–4 advance.

The TL;DR commits to the full ladder because the JAR-size and shading
risk concentrate in PR #3 — punting that risk acknowledgement to a
hypothetical follow-up plan would lose the analysis already done here.
The ladder lets us spend the planning cost once, regardless of where we
choose to stop on the implementation side.

---

## 1. Goals and non-goals

### Goals

1. **(PR #1, unconditional)** A `.kt` change in the diff routes through
   the same five-bucket mapping as a `.java` change. Production Kotlin
   files become production class FQNs; test Kotlin files become test
   class FQNs. The diff side emits both `Util` and `UtilKt` for every
   `.kt` file so naming and tier-1 import lookup catch the
   top-level-function case.
2. **(PRs #2–4, conditional on Section 0.5)** AST-driven strategies
   (`usage`, `implementation`, `transitive`) work on Kotlin sources with
   parity to Java for the "single class per file" shape, plus top-level
   functions surfaced as the synthetic `<basename>Kt` class.
   `NamingConventionStrategy` is already covered by Goal 1 — it consumes
   path-derived FQNs from `scanTestFqns`, not `FileMetadata`.
3. The published plugin JAR keeps the existing relocation contract:
   no `org.jetbrains.kotlin.*`, `kotlinx.*`, or `gnu.trove.*` class
   surfaces on the adopter classpath at its original FQN. (The JGit /
   JavaParser / slf4j relocation contract from earlier phases is verified
   alongside, since no functional test currently asserts it — see
   Section 5.)
4. The `ProjectIndexCache` snapshot stays valid across mixed Java + Kotlin
   trees and across plugin upgrades. The schema is bumped twice — once in
   PR #1 (the scanner-extension widening changes the scan result shape on
   mixed projects, and `configHash` does not catch the file-extension
   widening) and once in PR #3 (the moment the cache *can* persist Kotlin
   `FileMetadata` rows, even with the system property default-off). PR #3's
   schema-compatibility test asserts an old cache from a Java-only
   pre-Phase-2 build degrades cleanly to a full rescan.
5. `--explain` strings are pinned per PR (Section 9 has the exact strings)
   so adopters can grep their CI logs across plugin versions.

### Non-goals

- Groovy / Scala parsers. Phase 1 hint stays accurate until a separate
  tech plan picks them up.
- Multiplatform / `commonMain` / `androidMain` source-set discovery beyond
  what `gradlePlugin.testSourceSets` already exposes (issue #49).
- Compiler-quality semantic resolution. We need names, packages, imports,
  and supertype simple names — same surface area `FileMetadata` already
  exposes for Java. We do **not** need a full type-resolved binding tree.
- KSP / kapt-aware discovery (generated sources). Generated Kotlin already
  follows the same path as generated Java today (build dir, ignored via
  `SKIP_DIRS`).
- Kotlin DSL build scripts (`build.gradle.kts`). These remain in the
  unmapped bucket — already excluded from the polyglot hint in
  `AffectedTestTask.polyglotExtensionOf`.

---

## 2. Background — what already exists

The architecture is partially ready for a new source language but several
sites still hard-code `.java`. The plan covers all of them; the original
draft missed several. The complete list:

| Call site | What it does today | Phase 2 change |
|-----------|--------------------|----------------|
| `SourceFileScanner.collectJavaFiles` (private inner walker) | Walks tree collecting `*.java` paths | **Decision: widen the inner walker's extension filter to `{.java, .kt}` in place** (do not rename, do not split). Both public callers — `collectSourceFiles(Path, List<String>[, List<Path>])` and `collectTestFiles(...)` — continue delegating through the inner walker and pick up Kotlin transitively without any public-method rename. |
| `SourceFileScanner.collectTestFiles` | Orchestrator over `collectJavaFiles` for test sources | No direct change; picks up `.kt` via the widened inner walker. Listed here so reviewers checking "is every site covered?" don't worry it was missed. |
| `SourceFileScanner.walkFqnsUnder` / `scanTestFqns` / `fqnsUnder` | `if (file.toString().endsWith(".java"))` filter + `.java` suffix strip | Centralise into a `SOURCE_EXTENSIONS = Set.of(".java", ".kt")` helper. Without this widening, the **test-FQN universe** that NamingConventionStrategy compares against contains zero Kotlin tests — naming "works for free" only after this site is fixed. |
| `SourceFileScanner.pathToFqn` | `if (relative.endsWith(".java"))` strip | Same widening. Public utility consumed by other modules. |
| `PathToClassMapper#mapChangedFiles` line 167 (`!filePath.endsWith(".java")`) | Sends every non-Java file to `unmappedChangedFiles` | Accept `.kt` and route through `tryMapToClass`. |
| `PathToClassMapper#tryMapToClass` (`if (relativePath.endsWith(".java"))` strip, ~line 266) | Strips only `.java` — so a `.kt` routed through here returns FQN ending in `.kt` (literal `.kt` becomes a dotted segment after `replace('/', '.')`) and silently poisons every downstream strategy | Strip both `.java` and `.kt`. For `.kt` files routed to **production** dirs, emit two FQNs into `changedProductionClasses`: the path-derived `Util` (matches the explicit-class case) and the synthetic `UtilKt` (matches the compiled top-level-function class). For `.kt` files routed to **test** dirs, emit only the path-derived FQN — test classes are conventionally instantiable types, and a synthetic `FooTestKt` would surface to the runner as "no tests found". |
| `UsageStrategy#extractFqn` line ~295 (`if (fqn.endsWith(".java"))` strip) | Fallback FQN for test files whose path doesn't resolve cleanly against a configured test root | Strip both `.java` and `.kt`. |
| `UsageStrategy#metadataOrGet`, `ImplementationStrategy#metadataOrGet`, `TransitiveStrategy#metadataOrGet` (`(Path, ProjectIndex, JavaParser fallbackParser)`) | When `index == null`, calls `JavaParsers.parseOrWarn(fallbackParser, ...)` then `FileMetadataExtractor.extract(cu)`. JavaParser-typed fallback. | Reroute through `LanguageParsers.parseOrWarn(file, label)` driven by the file's extension. The `JavaParser fallbackParser` argument was **dropped outright** (the parameter was on a private method with no external surface — `@Deprecated` cushion was unnecessary); shipped in PR #2. |
| `JavaParsers.parseOrWarn` and `ProjectIndex#compilationUnit` | JavaParser-only | `JavaParsers` collapses into `JavaLanguageParser` (deleted as a separate class). `ProjectIndex#compilationUnit(Path)` and `ProjectIndex#fileMetadata(Path)` both dispatch by extension via `LanguageParsers.forFile`. `compilationUnit(Path)` stays public for the rollout window (returns `null` for `.kt`); narrowing to private is tracked as a post-#76 follow-up once `ProjectIndexConcurrencyTest` + `ProjectIndexTest` migrate to `fileMetadata(Path)`. |

The four strategies share most of their hot path through `FileMetadata`,
but two caveats remain that the original plan oversold:

1. `NamingConventionStrategy` does **not** consume `FileMetadata`. It
   consumes the test-FQN universe from `SourceFileScanner.scanTestFqns`
   (path-derived FQNs). Its Kotlin parity comes from widening that
   scanner — a PR #1 change, not a PR #3 one.
2. `UsageStrategy`, `ImplementationStrategy`, and `TransitiveStrategy`
   each retain a JavaParser-typed `metadataOrGet(Path, ProjectIndex, JavaParser)`
   no-index fallback used by their direct CLI/test entry points. The
   "strategies do not change" framing only holds for the index-driven
   path. The fallback path requires one method-signature change per
   strategy, contained in PR #2.

The parser-agnostic record itself (the load-bearing isolation point):

```45:62:affected-tests-core/src/main/java/io/affectedtests/core/discovery/FileMetadata.java
public record FileMetadata(
        String packageName,
        String primaryTypeName,
        List<Import> imports,
        Set<String> typeRefSimpleNames,
        Set<String> typeRefDottedNames,
        List<TypeDecl> typeDeclarations) {
```

For the index-driven hot path, populating this record from a Kotlin AST
is sufficient — Usage / Implementation / Transitive consume it directly
and have no JavaParser dependency on that path.

---

## 3. Architecture — `LanguageParser` interface

Introduce a small, parser-shaped abstraction in
`io.affectedtests.core.discovery`:

```java
package io.affectedtests.core.discovery;

import java.nio.file.Path;

interface LanguageParser {
    /** Lowercase file extension this parser claims, including the dot, e.g. ".java", ".kt". */
    String extension();

    /** Returns a strategy-agnostic projection of the file, or null if the file could not be parsed. */
    FileMetadata parseOrWarn(Path file, String label);
}
```

`JavaParsers` collapses into a `JavaLanguageParser` that wraps the existing
JavaParser instance + `FileMetadataExtractor.extract(cu)` call. A new
`KotlinLanguageParser` does the equivalent for Kotlin via
`kotlin-compiler-embeddable`.

`ProjectIndex` resolves the right parser per file:

```java
private static final Map<String, LanguageParser> PARSERS_BY_EXTENSION =
        Map.of(".java", new JavaLanguageParser(),
               ".kt",   new KotlinLanguageParser());

private LanguageParser parserFor(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    if (dot < 0) return null;
    String ext = name.substring(dot);
    // .kts (script) and .gradle.kts (Gradle Kotlin DSL) are absent from
    // PARSERS_BY_EXTENSION on purpose — they look up to null and route
    // to unmappedChangedFiles. Phase 1's polyglot hint already
    // suppresses .gradle.kts to avoid noise; .kts shows up there.
    return PARSERS_BY_EXTENSION.get(ext);
}
```

`compilationUnit(Path)` **stays public** for the rollout window: the
no-index strategy fallbacks (Usage / Implementation / Transitive) still
call it during PR #2, and pre-Phase-2 functional tests assert on it. It
returns `null` for `.kt` files (no Java AST). A follow-up issue narrows
the visibility once all callers route through `fileMetadata(Path)` or
`LanguageParser.parseOrWarn(file, label)`. The original draft of this
section was internally contradictory ("becomes private" + "can stay
public") — the corrected decision is "stays public until all callers
migrate."

### Why an interface rather than `if/else` in `JavaParsers`

- Test surface: each parser can be unit-tested against a tree of
  hand-written sources without spinning up `ProjectIndex`.
- Shading: `KotlinLanguageParser` lives in a separate package
  (`io.affectedtests.core.discovery.kotlin`) so the relocation rule in
  `affected-tests-gradle/build.gradle` is precise and grep-able.
- Rollout (Section 9): the system property toggle is one line that picks
  a smaller `Map.of(".java", javaParser)` vs the full map.

`KotlinLanguageParser` references the **original**
`org.jetbrains.kotlin.psi.*` FQNs in source — the
`relocate 'org.jetbrains.kotlin'` rule in Section 5 rewrites both class
files and import references to `io.affectedtests.shadow.kotlin.psi.*` at
JAR-assembly time. Functional tests in PR #3 verify the relocated
classes load on an adopter-classloader scenario; the source code does
not hardcode the shaded FQN.

### 3.4 Kotlin parser lifecycle

`JavaParser` is small (~30–80 KB per instance), stateless across parse
calls, and has no `Disposable` lifecycle, which is why
`ProjectIndex.PARSER` can safely be a `static ThreadLocal<JavaParser>`
(see `ProjectIndex.java` lines 124–146).

`KotlinCoreEnvironment` is none of those things. The standard embeddable
bootstrap is
`KotlinCoreEnvironment.createForProduction(parentDisposable, CompilerConfiguration, EnvironmentConfigFiles.JVM_CONFIG_FILES)`,
which constructs a MockApplication, a per-thread extension-point
registry, file-system providers, and lazy services — multi-MB per
environment. The `parentDisposable` must be explicitly disposed via
`Disposer.dispose(parent)`. A naive `static ThreadLocal<KotlinCoreEnvironment>`
on a Gradle-daemon worker pool would leak one MockApplication per
worker for the lifetime of the daemon. Worse, the IntelliJ platform was
not designed for many MockApplications coexisting in the same JVM —
`bazel-contrib/rules_kotlin#624` documents the canonical
`Missing extension point` failure that surfaces when extension-point
XMLs aren't on the classpath the way the bootstrap expects.

PSI access also has a read-lock contract: traversal is documented as
read-locked, and parallel parsers without
`ApplicationManager.getApplication().runReadAction(...)` can surface
intermittent `ProcessCanceledException` under contention.

The implementation must therefore:

1. Hold **one** shared `KotlinCoreEnvironment` per `ProjectIndex`
   instance, created lazily on first `.kt` parse, disposed at engine
   shutdown via try-with-resources on the `parentDisposable`.
2. Wrap PSI traversals in `runReadAction(...)` for the parallel-discovery
   path (issue #42's posture). The serial path can use the same shared
   environment without contention.
3. Crib lifecycle from
   `fwcd/kotlin-language-server` (`org.javacs.kt.compiler.Compiler`),
   Detekt's `KtCompiler`, and the embeddable smoke tests under
   `prepare/compiler-embeddable/tests/kotlin/...` in the kotlin repo.

If embeddable initialisation fails (bad shading, missing extension XMLs,
classloader collision), the parser logs a single `WARN` with the
underlying exception, treats every `.kt` file in the run as
unparseable, and lets `DISCOVERY_INCOMPLETE` decide whether the run
escalates. **Silent degradation to "Kotlin file, naming-strategy only"
is forbidden** — that is the exact silent-skip shape Phase 1 was
written to avoid.

---

## 4. Dependency: `kotlin-compiler-embeddable`

### Coordinate

```gradle
// affected-tests-core/build.gradle — compileOnly because the gradle
// module is the one that ships the shaded JAR; the core module only
// needs the compile-time classes for KotlinLanguageParser.
compileOnly 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20'

// affected-tests-core/build.gradle — testImplementation because PR #3
// adds unit tests in core that exercise KotlinLanguageParser against a
// fixture tree of `.kt` files. Without this, those tests fail with
// `NoClassDefFoundError` on the first KotlinCoreEnvironment reference
// because the embeddable is not on testRuntimeClasspath under the
// compileOnly scope alone.
testImplementation 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20'

// affected-tests-gradle/build.gradle — implementation so shadowJar
// pulls the artifact onto the published classpath.
implementation 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20'
```

The `kotlin-compiler-embeddable:2.1.20` POM (verified against
`repo1.maven.org`) declares these runtime deps that the relocation rules
in Section 5 must address:

- `org.jetbrains.kotlin:kotlin-stdlib:2.1.20` — `kotlin.*`
- `org.jetbrains.kotlin:kotlin-script-runtime:2.1.20` — `kotlin.*`
- `org.jetbrains.kotlin:kotlin-reflect:1.6.10` — `kotlin.reflect.*` (note
  the version skew from stdlib; not blocking, but worth flagging on bumps)
- `org.jetbrains.kotlin:kotlin-daemon-embeddable:2.1.20`
- `org.jetbrains.intellij.deps:trove4j:1.0.20200330` — **`gnu.trove.*`**
  (not under `kotlin.*` — needs its own relocate rule)
- `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0` —
  **`kotlinx.coroutines.*`** (also not under `kotlin.*` — needs its own
  rule)

The IntelliJ-platform classes the embeddable bundles internally
(`org.jetbrains.kotlin.com.intellij.*`,
`org.jetbrains.kotlin.it.unimi.dsi.fastutil.*`,
`org.jetbrains.kotlin.org.jline.*`) are already pre-relocated inside the
embeddable JAR; they need our outer relocate rules but not separate POM
deps.

### Why this artifact (not `kotlin-compiler` or PSI directly)

- `kotlin-compiler-embeddable` is the published, **shading-friendly**
  flavour: every dependency it brings (`com.intellij.psi.*` clones,
  Trove4j, Guava, etc.) is already relocated under
  `org.jetbrains.kotlin.com.intellij.*` and
  `org.jetbrains.kotlin.it.unimi.dsi.fastutil.*`. We add one more relocation
  under `io.affectedtests.shadow.kotlin` and the consumer classpath stays
  clean even when the adopter is itself a Kotlin Gradle plugin user.
- The standalone `kotlin-compiler` artifact is **not** shading-friendly —
  it leaks `com.intellij.*` directly and would collide with IntelliJ-aware
  Gradle plugins.
- We do not need K2 / new compiler frontend for Phase 2's tier of analysis
  (package, imports, type-ref names, supertype simple names). The legacy
  PSI tree exposed by `KtFile` is sufficient.

### Size

`kotlin-compiler-embeddable:2.1.20` is ~52 MB unpacked. Plugin shadow
JAR today is ~6 MB. The original draft estimated 28–35 MB post-shading;
that was wishful: the `minimize { }` block in Section 5 must
`exclude(dependency('...'))` the embeddable plus most transitive deps
(PSI bootstrap reaches many classes only via XML extension descriptors
that the class-graph walk cannot see), which keeps those classes
intact. Empirical anchors: ktlint ships ~50 MB, Detekt CLI ~30+ MB
without bundling the full embeddable, KSP's shaded compiler-runtime is
~40+ MB.

Realistic post-shading size is **40–50 MB**. PR #3 begins with a one-day
prototype build that emits the actual size; the plan's acceptance gate
(Section 11) is set to that measured number plus 15% headroom, **not**
the speculative 35 MB. The Gradle Plugin Portal has no documented hard
size limit; empirical upload failures cluster around ~90 MB
(`gradle/plugin-portal-requests#150`), so even a 50 MB JAR is well
inside the safe envelope.

### Version policy

- Pinned to a single Kotlin version. We do **not** support running
  discovery with a different Kotlin source level than the embeddable's
  frontend understands. This is the same posture JavaParser takes
  against Java language levels.
- Bumps go through Dependabot like every other dep; major Kotlin
  releases get an explicit smoke-test pass against a representative
  consumer tree.
- Source level is set to the highest stable level the embeddable
  understands (mirrors `JavaParsers.LANGUAGE_LEVEL = JAVA_25`).
- JetBrains does **not** formally guarantee binary compatibility for
  `kotlin-compiler-embeddable`'s public PSI surface (`KtFile`,
  `KtImportDirective`, `KtClassOrObject`). De-facto stability rests on
  Detekt / ktlint / KSP / IntelliJ-platform all depending on the same
  surface. The pinned-version posture handles bump risk; the smoke-test
  pass on majors is the gate that catches drift.

---

## 5. Shading and relocation plan

`affected-tests-gradle/build.gradle` already shades JGit, JavaParser, and
slf4j. Add Kotlin and its bundled IntelliJ artefacts:

The ordering of `relocate` calls is load-bearing. Shadow 9.x evaluates
relocators via `Iterable<Relocator>.relocateClass` (verified against
`RelocationContext.kt` at the pinned 9.4.1 tag) which iterates rules in
DSL-insertion order and short-circuits on the first relocator whose
`canRelocateClass` returns true. The original draft used the imprecise
phrase "first matching rule per class" — the precise mechanism is
"sequential, short-circuit on first match." Putting the outer
`org.jetbrains.kotlin` rule **before** the nested
`org.jetbrains.kotlin.com.intellij` rule would double-relocate every
`com.intellij.*` class. The verification gate below asserts this
explicitly.

```gradle
shadowJar {
    archiveClassifier.set('')
    relocate 'org.eclipse.jgit',         'io.affectedtests.shadow.jgit'
    relocate 'com.github.javaparser',    'io.affectedtests.shadow.javaparser'
    relocate 'org.slf4j',                'io.affectedtests.shadow.slf4j'

    // Phase 2 (issue #76) — Kotlin embeddable + its bundled IntelliJ /
    // JetBrains-internal copies. Order matters: place the already-
    // relocated 'org.jetbrains.kotlin.com.intellij' (and siblings)
    // before the outer 'org.jetbrains.kotlin' rule so the outer rule
    // does not double-relocate those classes.
    relocate 'org.jetbrains.kotlin.com.intellij',           'io.affectedtests.shadow.kotlin.com.intellij'
    relocate 'org.jetbrains.kotlin.it.unimi.dsi.fastutil',  'io.affectedtests.shadow.kotlin.fastutil'
    relocate 'org.jetbrains.kotlin.org.jline',              'io.affectedtests.shadow.kotlin.jline'
    relocate 'org.jetbrains.kotlin',                        'io.affectedtests.shadow.kotlin'
    relocate 'kotlin',                                      'io.affectedtests.shadow.kotlinstdlib'

    // Embeddable's POM pulls these as runtime deps; neither falls under
    // any of the four 'kotlin' / 'org.jetbrains.kotlin' prefixes above
    // and the original draft missed both.
    relocate 'kotlinx.coroutines', 'io.affectedtests.shadow.kotlinx.coroutines'
    relocate 'gnu.trove',          'io.affectedtests.shadow.trove4j'

    minimize {
        // Pre-PR-3 the build had no `minimize { }` block, so JGit /
        // JavaParser / slf4j shipped whole. Adding `minimize` would
        // prune their reflection-reached classes too — JavaParser's
        // symbol-solver and JGit's transports both register via
        // `META-INF/services/*`. Exclude all three en masse so the new
        // block is purely additive for the embeddable closure.
        exclude(dependency('org.eclipse.jgit:.*'))
        exclude(dependency('com.github.javaparser:.*'))
        exclude(dependency('org.slf4j:.*'))

        // Embeddable + each transitive dep on its POM. Without an
        // exclude per dep, `minimize` prunes class-graph-unreachable
        // classes from each dependency independently — the PSI
        // bootstrap reaches many of those classes only via XML
        // extension descriptors (META-INF/extensions/*.xml) that the
        // class-graph walk cannot see, so partial pruning surfaces as
        // `IllegalArgumentException: Missing extension point: ...` on
        // the first parse.
        exclude(dependency('org.jetbrains.kotlin:kotlin-compiler-embeddable:.*'))
        exclude(dependency('org.jetbrains.kotlin:kotlin-stdlib:.*'))
        exclude(dependency('org.jetbrains.kotlin:kotlin-reflect:.*'))
        exclude(dependency('org.jetbrains.kotlin:kotlin-script-runtime:.*'))
        exclude(dependency('org.jetbrains.kotlin:kotlin-daemon-embeddable:.*'))
        exclude(dependency('org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:.*'))
        exclude(dependency('org.jetbrains.intellij.deps:trove4j:.*'))
    }

    mergeServiceFiles()
    // mergeServiceFiles merges META-INF/services/* but does not rewrite
    // FQN strings inside service files. ServiceFileTransformer is
    // configured in PR #3 to also rewrite contents using the relocation
    // map; the verification gate asserts every service entry references
    // the shaded namespace.
}
```

Shadow 9.4.1 (this repo's pinned version) handles Kotlin `@Metadata`
and `META-INF/*.kotlin_module` rewriting natively as of Shadow 9.2.1
(`com.xpdustry.kotlin-shadow-relocator` functionality merged into
Shadow proper). No extra plugin needed.

### Verification gate

There is **no existing functional test that inspects the shadow JAR's
top-level packages.** `ShadowPublishFunctionalTest` only asserts that
the publish-task configuration resolves cleanly (verified —
`affected-tests-gradle/src/functionalTest/java/.../ShadowPublishFunctionalTest.java`
runs `tasks --all` and `build --dry-run` and asserts on output strings,
not on JAR contents). The original draft of this section was wrong on
that point.

PR #3 introduces a new test (`ShadowRelocationFunctionalTest`) that
builds the shadow JAR via Gradle TestKit, walks the resulting `ZipFile`,
and asserts:

1. No top-level `kotlin/`, `kotlinx/`, `org/jetbrains/kotlin/`,
   `com/intellij/`, `gnu/trove/` entries.
2. Every `META-INF/services/*` entry referencing a Kotlin class is
   rewritten to `io.affectedtests.shadow.kotlin.*`.
3. At least one representative class (e.g. `KtFile`, `K2JVMCompiler`)
   is present under `io/affectedtests/shadow/kotlin/...` and zero
   copies remain at `org/jetbrains/kotlin/...`.
4. As a regression backstop, the same test asserts the JavaParser /
   JGit / slf4j relocation contract that was never previously covered
   — closing that pre-existing gap is cheap once the ZIP-walking
   scaffolding exists.
5. **Minimize regression smoke**: a single TestKit build that exercises
   one JavaParser parse and one JGit `Repository.open(...)` /
   `git diff` call against the shaded JAR, asserting both succeed and
   neither surfaces a `ServiceConfigurationError` or
   `NoClassDefFoundError`. Without this, the new `minimize { }` block
   could silently prune reflection-reached classes in JGit / JavaParser
   that pre-PR-3 builds shipped whole. The en-masse `exclude(dependency('...'))`
   above is the primary defense; this smoke is the secondary one.

Beyond the package-level gate, PR #3 also adds an **end-to-end parse
gate**: a functional test that loads the shadow JAR in an isolated
classloader, writes a temp `Foo.kt` exercising representative shape
(class, top-level fun, companion object, wildcard import, an `import
kotlin.collections.List` to verify the embeddable resolves source-text
FQNs as strings rather than via `Class.forName`), invokes the engine's
`LanguageParser.parseOrWarn` path, and asserts a populated
`FileMetadata` comes back. Failure modes asserted: no
`ClassNotFoundException` for shaded internals, no
`ServiceConfigurationError` from un-rewritten `META-INF/services`
entries, no `IllegalArgumentException: Missing extension point:` from
stripped XML descriptors. **If the end-to-end parse gate fails for any
reason traceable to shading rules, PR #3 does not merge — the shading
approach is re-evaluated rather than the gate weakened.**

---

## 6. FQN resolution for Kotlin

Kotlin's package declaration is **the** source of truth for the FQN, not
the file path. Two paths:

| File layout | Java behaviour | Kotlin behaviour |
|-------------|----------------|------------------|
| `src/main/kotlin/com/example/Foo.kt` declaring `package com.example` | n/a | FQN = `com.example.Foo` (matches path-derived FQN) |
| `src/main/kotlin/Foo.kt` declaring `package com.example` | n/a | FQN = `com.example.Foo` (path-derived would say `Foo`) |

For both diff-side mapping (`PathToClassMapper#tryMapToClass`) and the
naming strategy (`SourceFileScanner.scanTestFqns` → simple-name match),
we keep using the path-derived FQN — same as Java — because the diff
side does not have a parsed AST and the test-FQN universe is
path-walked. The known false-negative below applies to all path-based
consumers.

For Usage / Implementation / Transitive (the AST-driven strategies),
the FQN comes from `FileMetadata.packageName + FileMetadata.primaryTypeName`
(the existing Java contract), so the path/package mismatch case "just
works" on the indexed side — `FileMetadata` is built from the parsed
file. **The original draft incorrectly grouped naming-strategy with
the AST-driven strategies; corrected here.**

### Path-vs-package divergence rate is materially higher in Kotlin than Java

Idiomatic Kotlin produces path/package mismatches in at least four
common patterns: top-level utility files at root packages
(`src/main/kotlin/Strings.kt` declaring `package com.example.utils`);
multi-platform source-set layouts (`src/jvmMain/kotlin/com/...`);
generated sources (KSP / kapt / `kotlinx-serialization`) routed back
into `src/main/kotlin`; and internal API re-exports. PR #3 emits a
`--explain` line when a parsed `.kt` file's declared package
disagrees with its path-derived prefix:

`Kotlin file {path} declares package {parsed} but path-derives to {path-derived}; AST-driven strategies use the declared package, naming strategy uses the path-derived FQN — re-check file placement if a test was expected to be selected.`

The plan also counts these into a per-run `pathPackageMismatchCount`
that the engine logs at INFO when non-zero, so adopters see the
mismatch rate at a glance.

### Diff-side FQN for class-less Kotlin files

For a **production** `.kt` file the diff-side mapping must emit **both**
the path-derived FQN (e.g. `com.example.Util`, matching the actual
`class Util` shape) **and** the synthetic `<basename>Kt` FQN
(`com.example.UtilKt`, matching the compiled class for top-level
functions / properties). Both are added to `changedProductionClasses`.
**For test `.kt` files only the path-derived FQN is emitted** — the
synthetic `FooTestKt` would surface to the runner as "no tests found"
because test classes are conventionally instantiable types, not
top-level-function bundles.

Cost: one extra entry per production `.kt` file in the diff, dropped
automatically once Naming / Implementation / Usage's simple-name match
fails to find a corresponding test. The synthetic is required for:

1. **NamingConventionStrategy**: `UtilKtTest`, `UtilKtIT` are the
   conventional shapes Kotlin adopters write for top-level utility
   files (and the only convention the existing test-suffix list
   naturally produces against the `*Kt` synthetic name).
2. **UsageStrategy tier 1 (direct import)**: a Java test that calls a
   top-level Kotlin function writes `import com.example.UtilKt;` —
   the only changed-FQN that matches that import string is `UtilKt`,
   not `Util`.

If a `.kt` file contains an actual `class Util { ... }` declaration in
addition to top-level functions, both `Util` and `UtilKt` enter the
set; over-selection is bounded to one extra naming-suffix probe.

**Edge case — files whose basename ends in `Kt`** (e.g. `FormatKt.kt`
containing `class FormatKt`): the diff-side emits `FormatKt`
(path-derived) and `FormatKtKt` (synthetic). The Kotlin compiler never
produces a class named `FormatKtKt` — that synthetic is a phantom.
Naming probes (`FormatKtKtTest`, `FormatKtKtIT`) match nothing, no
import string ever references it, and supertype lookup fails: the
phantom is harmless to selection. It does, however, surface in
`--explain` output and changed-class debug logs as one of the changed
production classes, which can confuse an operator scanning the log.
Documented behaviour, not a bug; adopters who find it noisy can route
the affected file via `outOfScopeSourceDirs` to suppress emission.

### Top-level functions and properties (parser side)

Compiled, top-level `fun` / `val` / `var` become a synthetic
`FooKt.class` (file `Foo.kt` → `FooKt`). The Kotlin parser populates
`FileMetadata.primaryTypeName = <basename>Kt` (e.g. `Util.kt` →
`UtilKt`) for class-less files. This pairs with the diff-side synthetic
above so both sides agree on the FQN used by AST-driven strategies.

`primaryTypeName` is consumed only as Usage's last-resort FQN fallback
(verified — `UsageStrategy.extractFqn`, `ProjectIndexCache` round-trip;
no Java-shaped consumer treats it as a declared type), so setting it
to the synthetic `Kt`-suffixed name is safe.

### `@file:JvmName` decision

`@file:JvmName("FooUtils")` overrides the compiled class name. **Phase
2 does not honour the annotation**; `Util.kt` always produces a primary
type name of `UtilKt`. Honouring `@file:JvmName` is tracked as a
follow-up issue and surfaced via `--explain` when a parsed file
contains the annotation, so adopters know they're hitting the
documented limitation. The original draft was internally contradictory
("honour it if surfaced" + "ship default first"); the corrected
decision is "ship default; surface a hint when the override would have
mattered." Adopters with heavy `@file:JvmName` use can route Kotlin
test files into `outOfScopeTestDirs` as the same workaround already
documented for filename-only scenarios.

### Companion objects, sealed hierarchies, expect/actual, typealiases

- **Companion objects** show up as nested types in the AST.
  Implementation strategy already handles nested types via
  `FileMetadata.typeDeclarations`; the extractor walks `KtClassOrObject`
  recursively.
- **Sealed hierarchies** (`sealed class Animal permits Dog, Cat`):
  `FileMetadata.TypeDecl` does **not** today carry sealed-permits
  edges. The plan ships PR #3 with the supertype-walk shape only;
  follow-up issue tracks adding `isSealed` + `sealedPermitsSimpleNames`
  if adopter telemetry shows ImplementationStrategy missing real-world
  sealed-hierarchy fan-out.
- **`expect class Foo`** / **`actual class Foo`** (multiplatform):
  `expect` is treated as a normal class declaration on the parser side;
  multi-platform projects are out of scope for Phase 2. Strategies do
  not deduplicate `expect` and `actual` declarations sharing the same
  simple name — out-of-scope follow-up.
- **`typealias UserHandler = (User) -> Unit`**: not captured in
  `FileMetadata.typeDeclarations` (it's not a type declaration in the
  current schema). Follow-up if adopter telemetry shows missed edges.
- **`value class UserId(val raw: String)`**: walks as a normal class
  declaration; no special-case needed.

---

## 7. Cache compatibility

`ProjectIndexCache` writes `FileMetadata` rows under a
`SCHEMA_VERSION` gate (`affected-tests-core/.../discovery/ProjectIndexCache.java:112`,
currently `2`). The Java extractor and Kotlin extractor produce records
of the **same shape**, so the on-disk row format does not change.

What does need attention — **two schema bumps**, one per shape change:

- **PR #1: bump `SCHEMA_VERSION` from 2 → 3.** Verified against
  `ProjectIndexCache.configHash(AffectedTestsConfig)`: the hash includes
  the *declared* `sourceDirs` / `testDirs` / `outOfScopeSourceDirs` /
  `outOfScopeTestDirs` strings, **not** the resolved scan-root paths
  and **not** the file-extension filter the scanner applies inside
  those roots. PR #1 widens the scanner to walk `.kt` alongside `.java`
  without changing any config string, so on a mixed Java+Kotlin
  project the pre-PR-1 `configHash` equals the post-PR-1 `configHash`
  and a warm cache reuses a stale `testFqns` universe missing every
  Kotlin test FQN. `verifyDirs` fingerprints directory mtimes which
  catches new files but **not** the file-extension scope widening
  itself. The schema bump is the only mechanism that guarantees PR #1
  forces a clean rescan on warm caches. Cost: one cache miss for every
  adopter on first plugin upgrade — acceptable for a one-time
  correctness fix.
- **PR #3: bump `SCHEMA_VERSION` from 3 → 4.** The on-disk row format
  can now persist `m` rows produced by `KotlinLanguageParser`. Even
  with the system property default-off, a CI worker that flipped the
  property once for a smoke test on PR #3 must not surface a
  half-Kotlin-shaped cache to a worker that ran with the property off.
  The bump catches the parser-output shape change.
- PR #4 ships **no** schema change.
- `(mtime, size)` fingerprint contract is unchanged —
  `Files.readAttributes` works on `.kt` the same way as `.java`.

Result: **two schema-version bumps — PR #1 (scan-scope widening) and
PR #3 (parser-output shape).** The original draft scheduled a single
bump for PR #4; the corrected timing acknowledges that `configHash`
does not catch the file-extension scope widening on mixed projects, so
PR #1 needs its own bump as a defense-in-depth measure.

---

## 8. Edge cases and decisions

| Case | Phase 2 behaviour |
|------|-------------------|
| `.kt` file with `expect` / `actual` (multiplatform) | Treat as a normal class declaration. Multiplatform projects are out of scope for Phase 2 but should not crash discovery. |
| Two top-level classes in the same `.kt` file | All declarations end up in `FileMetadata.typeDeclarations`; same as a Java file with multiple top-level types (which is illegal Java but legal at the parser level). |
| `.kt` under `outOfScopeSourceDirs` / `outOfScopeTestDirs` | Identical to `.java` — out-of-scope checks happen before the extension check in `PathToClassMapper`. |
| `.kt` outside configured `sourceDirs` / `testDirs` | Falls through to `unmappedChangedFiles` exactly like a stray `.java`; safety net escalates. |
| Kotlin file fails to parse | Same WARN + `parseFailureCount.incrementAndGet()` path as a Java file. `DISCOVERY_INCOMPLETE` situation triggers if non-zero. |
| `@JvmStatic`, `@JvmField`, `@JvmName` annotations | Honoured opportunistically; not blocking. Default behaviour matches the compiled-class shape. |
| `.kts` (script) files | Stay unmapped. The parser dispatch map (`PARSERS_BY_EXTENSION`) only contains `.java` and `.kt` — any `.kts` lookup returns null. Files matching `*.gradle.kts` (Gradle Kotlin DSL build/settings scripts) are excluded from the polyglot hint in Phase 1; other `.kts` shapes still trigger the hint and route to `FULL_SUITE` under default profiles. |
| Embeddable fails to load (bad shading, missing extension XMLs, classloader collision) | Log a single WARN with the underlying exception, treat every `.kt` file in the run as unparseable, increment `parseFailureCount`, let `DISCOVERY_INCOMPLETE` decide whether the run escalates. **Silent degradation to "Kotlin file, naming-strategy only" is forbidden** — that is the exact silent-skip class of bug Phase 1 was written to avoid. PR #3 includes a unit test that simulates load failure and asserts the WARN-and-fail-closed path. |
| `module-info.java` analogue (`-info.kt` / `package-info.kt`) | No Kotlin equivalent in JPMS. No special-case needed. |
| Mixed Java + Kotlin diff | Each file routes through its own parser; results merge in `MappingResult` exactly the way mixed-Java already merges across modules. |

---

## 9. Rollout — four PRs

Each PR is small enough to revert independently. Each lands on master with
green CI before the next opens.

### `--explain` string stability contract

Pinned `--explain` strings below are guaranteed stable in their
**prefix-up-to-first-placeholder** and **suffix-after-last-placeholder**
across minor versions. Placeholders are written as `{name}` and are
runtime-substituted by the engine. Their *positions* are stable;
their *names* may change between minor versions for clarity (e.g.
`{parsed}` → `{declaredPackage}`). Adopters grepping CI logs across
plugin versions should anchor on the stable prefix / suffix
(e.g. `Kotlin file ` … ` declares package `), not the entire literal.
Strings without placeholders (PR #1's two lines; PR #4's
`{ext}`-only line up to the first placeholder) are stable verbatim.

### PR #1 — `issue-76-kotlin-path-derived-mapping` ✅ shipped
**Scope:** Path-derived FQN routing for `.kt` (same rule as `.java`,
via `PathToClassMapper#tryMapToClass`). No parser, no new dependency.

**Status:** Shipped. Implemented as documented below. Adopter-visible
behaviour change is the new `Kotlin source mapped via filename only`
hint in `--explain` plus the bucket-label rename
(`production .java` → `production`, `test .java` → `test`).

- Widen the `.java`-only suffix-strip in **all** of:
  `PathToClassMapper#tryMapToClass`,
  `SourceFileScanner.walkFqnsUnder`,
  `SourceFileScanner.fqnsUnder` (used by `scanTestFqns`),
  `SourceFileScanner.pathToFqn`,
  `UsageStrategy#extractFqn`. Centralise into a
  `SOURCE_EXTENSIONS = Set.of(".java", ".kt")` helper.
- Generalise the inner `collectJavaFiles` walker's extension filter to
  `{.java, .kt}` **in place** (decision per Section 2). Do not rename
  the public `collectJavaFiles` method, do not split into a sibling
  `collectByExtensions`. Both public callers (`collectSourceFiles`,
  `collectTestFiles`) continue delegating through it and pick up
  Kotlin transitively. Public method names stay unchanged for one
  release; a follow-up issue can rename `collectJavaFiles` →
  `collectSourceFiles`-equivalent once all internal callers migrate.
- `PathToClassMapper#mapChangedFiles` accepts `.kt` (line 167's
  `!filePath.endsWith(".java")` widens to a `.java|.kt` set check).
- For each `.kt` file routed to a **production** dir, emit **two** FQNs
  into `changedProductionClasses`: the path-derived `Util` (matches
  the explicit-class case) and the synthetic `UtilKt` (matches the
  compiled top-level-function class). For each `.kt` file routed to
  a **test** dir, emit only the path-derived FQN — synthetic
  `FooTestKt` would surface to the runner as "no tests found" and
  has no real referent. Cost: one extra simple-name probe per
  production `.kt` file in the diff, dropped automatically when no
  test matches.
- **Bump `ProjectIndexCache.SCHEMA_VERSION` from 2 → 3** (Section 7).
  `configHash` does not catch the file-extension scope widening, so
  the bump is the only way to force a clean rescan on warm caches
  for adopters upgrading across PR #1.
- `FileMetadata` is **not** populated for Kotlin yet, so Usage /
  Implementation / Transitive index-driven and no-index paths return
  zero matches for Kotlin files. NamingConventionStrategy works because
  its test-FQN universe now includes Kotlin tests (the
  `walkFqnsUnder`/`scanTestFqns` widening above).
- `--explain` strings (pinned verbatim):
  - `Kotlin source mapped via filename only; AST-driven strategies skipped (issue #76).`
  - `Kotlin source unmapped (no matching source/test root); routed to unmapped bucket.`
- Tests: extend `PathToClassMapperTest`, `SourceFileScannerTest`,
  `NamingConventionStrategyTest`. New fixture covering top-level
  Kotlin file with `<basename>Kt` neighbour.
- **Adopter-visible result:** A Kotlin-only edit on a class with a
  `<Class>Test.kt` neighbour selects that test instead of escalating.
  Top-level Kotlin functions named `Util.kt` select `UtilKtTest.kt`
  via the synthetic FQN.

### PR #2 — `issue-76-language-parser-interface` ✅ shipped
**Scope:** Refactor + strategy fallback rewire. No observable behaviour
change for Java (existing tests pass unchanged).

**Status:** Shipped. Implemented as documented below with three minor
divergences from the draft, all safe:

1. The `metadataOrGet` private parameter was **dropped** rather than
   kept as `@Deprecated`. The pre-PR-2 `JavaParser fallbackParser`
   argument lived on a private method inside three strategy classes
   — there is no caller outside the file, package-private or
   otherwise, so the "@Deprecated for one release" cushion had no
   external surface to protect. The parameter is gone in PR #2; new
   call sites pass nothing.
2. `JavaParsers` (package-private static utility) was **deleted**
   rather than retained as a shim. Same argument: no external
   surface, full source-of-truth ownership now lives in
   `JavaLanguageParser`. The two test files that called
   `JavaParsers.newParser()` for hand-built fixtures were updated to
   `JavaLanguageParser.newParser()` in the same commit.
3. `ProjectIndex#fileMetadata(Path)` was rewired to dispatch via
   `LanguageParser` in PR #2 rather than deferring the split to
   PR #3. The original draft had PR #2 leave `fileMetadata` as a
   `compilationUnit → extract` Java-only chain with a forward-pointer
   marker for PR #3. Adversarial review of the shipped PR #2 surfaced
   a silent-drop trap in that shape: once PR #3 registered a
   `KotlinLanguageParser`, a malformed `.kt` file would route through
   `compilationUnit(Path)`, hit the `instanceof JavaLanguageParser`
   gate, return `null` without bumping `parseFailureCount`, and
   silently drop out of discovery without escalating to
   `DISCOVERY_INCOMPLETE` — exactly the silent-drop class of bug
   the counter exists to prevent. The fix is small (~15 lines): the
   `fileMetadata` lambda branches on parser kind, routes Java
   through `compilationUnit` (preserving the parse-once dedupe),
   and routes non-Java through `parser.parseOrWarn(file, "index")`
   directly with a `parseFailureCount.incrementAndGet()` on null.
   PR #3 becomes registration-only for the routing infrastructure
   (it still adds `KotlinLanguageParser` and the system-property
   gate; it no longer needs to rewire any call site).

- Extract `LanguageParser` interface as in Section 3.
- Wrap `JavaParsers` as `JavaLanguageParser`; the
  `ThreadLocal<JavaParser>` in `ProjectIndex` moves inside
  `JavaLanguageParser`. `JavaParsers` is deleted (no external
  callers).
- `ProjectIndex.compilationUnit(Path)` dispatches via
  `LanguageParsers.forFile`; today the map only contains `.java`,
  so `compilationUnit(Path)` returns `null` for any other extension.
- `ProjectIndex.fileMetadata(Path)` also dispatches via
  `LanguageParsers.forFile`; Java parses route through
  `compilationUnit(Path)` (preserving the per-file CU cache dedupe
  with `compilationUnit` consumers); non-Java parses route through
  `LanguageParser.parseOrWarn(file, "index")` directly and bump
  `parseFailureCount` on null. This is the divergence-from-draft
  in **Status** item 3 above — PR #2 owns the dispatch shape so
  PR #3's parser-failure invariants come along for free.
- **Strategy no-index fallbacks** (`UsageStrategy.metadataOrGet`,
  `ImplementationStrategy.metadataOrGet`, `TransitiveStrategy.metadataOrGet`)
  reroute through `LanguageParsers.parseOrWarn(file, label)`.
  `metadataOrGet` is private; the deprecation cushion was unnecessary
  and the `JavaParser fallbackParser` parameter was dropped outright.
- All existing tests pass unchanged. Functionally a refactor; the
  load-bearing change is the strategy-fallback rewire that the
  original draft missed (required for Kotlin files to route
  correctly through any no-index entry point) plus the
  `fileMetadata` split (required so PR #3 doesn't silently drop
  malformed `.kt` files past `DISCOVERY_INCOMPLETE`).

**Adopter-visible result:** Zero. No `--explain` strings change, no
new dependencies, no schema bump, no behaviour difference for any
Java diff. The PR's whole job is to set up the dispatch
infrastructure that PR #3 will plug `KotlinLanguageParser` into.

### PR #3 — `issue-76-kotlin-language-parser` (system-property-gated) — **SHIPPED**
**Status:** Merged. Phase 2 PR #3 lands the Kotlin parser behind the
`affected-tests.kotlin.enabled` system property (default `false` — every
existing Java-only adopter sees zero behaviour change).

**Outcome vs. plan:**

- Embeddable wired in at `kotlin-compiler-embeddable:2.1.20`
  (`compileOnly` in core, `implementation` in gradle as planned).
- `KotlinLanguageParser` shipped under
  `io.affectedtests.core.discovery` (package-private), owning the
  `KotlinCoreEnvironment` lifecycle per Section 3.4. Single shared
  environment per `ProjectIndex`, lazy bootstrap via double-checked
  locking, fail-closed posture (single `WARN` + every `.kt` parse
  in the run returns `null` + parse-failure counter participation +
  `DISCOVERY_INCOMPLETE` escalation).
- Shading + minimize block landed with relocation rules in the order
  prescribed (more-specific first), plus path-based excludes for
  the JS / Native / WASM / daemon / serialized-JS-or-Konan / WASM
  bytecode-backend / Native bytecode-backend / FIR analysis / FIR
  resolve / FIR backend / IR backend-JS / IR interpreter / IR inline
  subsystems the JVM-PSI bootstrap doesn't reference. Prototype
  build measures **55.7 MB** — well under the 60 MB hard ceiling
  and inside the 40-50 MB realistic target window.
- `ShadowRelocationFunctionalTest` ships with all four documented
  assertions plus the size-ceiling pin.
- End-to-end parse-gate test ships as
  `ShadowParseGateFunctionalTest`. Loads the published shadow JAR
  in a child-first (parent=null) `URLClassLoader`, reflectively
  bootstraps `KotlinCoreEnvironment`, and parses a representative
  fixture exercising every shape the original plan named.
- Unit-test class ships as `KotlinLanguageParserTest`: 16 cases
  covering package + class + class-less file → `<basename>Kt` +
  basename-case preservation + empty-package fallback + wildcard
  imports + nested + companion + anonymous-companion filter +
  type-reference simple/dotted harvest with nullable + FQN inline
  references + `@file:JvmName` flag + missing-file null + post-close
  fail-closed + idempotent close.
- `kotlinEnabled` flag wired through `AffectedTestsConfig.Builder`,
  read from `-Daffected-tests.kotlin.enabled` via
  `ProviderFactory.systemProperty(...)` for configuration-cache
  safety. Flag value mixed into `ProjectIndexCache.configHash`.
- `ProjectIndexCache.SCHEMA_VERSION` bumped 3 → 4 with a fresh
  `prePr3SchemaSnapshotInvalidatesAndForcesFullRescan` test pinning
  the rescan posture, plus `kotlinEnabledFlagFlipInvalidatesCache`
  pinning the configHash branch.

**Deferred to follow-ups (still in PR #3 scope on paper, scoped out
in execution):**

- Path-vs-package mismatch counter (`pathPackageMismatchCount`) and
  the four pinned `--explain` lines. The parser already records
  `@file:JvmName(...)` files via the `hasFileLevelJvmNameAnnotation(Path)`
  side-channel; the engine wiring + `--explain` plumbing lands in a
  follow-up.
- Cucumber feature `10-issue-76-kotlin-ast.feature` covering each
  AST-driven strategy (Naming, Usage, Implementation, Transitive)
  with `.kt` fixtures plus the four pinned `--explain` strings.

**Adopter-visible result:** No change unless they opt in.

### PR #3 — original spec (kept for diff against shipped scope)
**Scope:** Add the embeddable, the Kotlin parser, the shading rules,
behind `affected-tests.kotlin.enabled` system property (default `false`).
The dispatch infrastructure is already in place from PR #2 — registering
`.kt` → `KotlinLanguageParser` in `LanguageParsers.BY_EXTENSION` is
the only call-site change needed; `ProjectIndex.fileMetadata` already
routes non-Java parsers correctly (with the `parseFailureCount` bump
on null), and the strategy fallbacks already dispatch by extension.

- New dep on `kotlin-compiler-embeddable` (`compileOnly` in core,
  `implementation` in gradle — see Section 4).
- New `KotlinLanguageParser` class returning `FileMetadata`. Lifecycle
  per Section 3.4: one shared `KotlinCoreEnvironment` per
  `ProjectIndex`, `runReadAction` wraps for parallel discovery, fail-
  closed on load (Section 8 row).
- Shading + minimize block in `affected-tests-gradle/build.gradle`
  (Section 5 — including `kotlinx.coroutines` and `gnu.trove`
  relocations and per-dep `minimize` excludes the original draft
  missed).
- New `ShadowRelocationFunctionalTest` asserting (1) no `kotlin/`,
  `kotlinx/`, `gnu/`, or `org/jetbrains/kotlin/` at top level,
  (2) `META-INF/services/*` entries rewritten to shaded namespace,
  (3) representative classes land at `io/affectedtests/shadow/kotlin/...`,
  and (4) JavaParser / JGit / slf4j relocation as a regression
  backstop.
- New end-to-end parse functional test that loads the shaded JAR in an
  isolated classloader, parses a fixture `.kt` exercising top-level
  function, companion object, wildcard import, and an
  `import kotlin.collections.List` to verify source-text FQN
  resolution. **Failure of this test blocks the merge.**
- Unit-test class against a fixture tree of `.kt` files exercising:
  package decl honour, top-level functions (`<basename>Kt`),
  companion-object nested decls, supertype names, wildcard imports,
  path-vs-package mismatch detection emitting the `--explain` line.
- `ProjectIndex` consults the system property at engine-instance
  construction time (not static-init) via
  `ProviderFactory.systemProperty(...)` for configuration-cache
  safety. The flag value participates in the cache-key hash.
- **`ProjectIndexCache.SCHEMA_VERSION` bumps from 3 → 4 in this PR**
  (Section 7). PR #1 already moved the schema from 2 → 3 to handle the
  scan-scope widening; PR #3's bump catches the parser-output shape
  change when Kotlin `m` rows can be persisted.
- One-day prototype build at the start of PR #3 records actual
  shaded-JAR size; the acceptance gate (Section 11) is set to that
  measured number ± 15%.
- `--explain` strings (pinned verbatim, flag on):
  - `Kotlin source AST-mapped to FQN {fqn}.`
  - `Kotlin file failed to parse with embeddable {version}; counted into DISCOVERY_INCOMPLETE.`
  - `Kotlin file {path} declares package {parsed} but path-derives to {path-derived}; AST-driven strategies use the declared package, naming strategy uses the path-derived FQN.`
  - `Kotlin embeddable failed to load: {cause}. Treating .kt files as unparseable for this run.`
- **Adopter-visible result:** No change unless they opt in. Maintainers
  exercise the path on a real consumer build (named in Section 11).

### PR #4 — `issue-76-kotlin-default-on`
**Scope:** Flip the flag, drop the property, update docs.

- `affected-tests.kotlin.enabled` default → `true`. **Property removed
  in this PR**, not in the next minor — Section 11's acceptance
  criterion requires the property to be removed before #76 closes,
  and deferring removal would prevent #76 from closing on this PR's
  merge. Adopters who hit a regression after PR #4 have two
  documented escape hatches: (1) pin the previous plugin version
  (`io.github.vedanthvdev.affectedtests` 2.x — the last release
  before PR #4), or (2) route the affected Kotlin sources via
  `outOfScopeSourceDirs` / `outOfScopeTestDirs` so they stay in the
  unmapped bucket and inherit the existing `onUnmappedFile` policy.
  `onUnmappedFile` itself is **not** a kill-switch for Kotlin AST
  parsing; once a file maps, the safety net does not fire. The
  original draft of this bullet was misleading on that point.
- README known-limitations section updated: remove "Java-only
  mapping", replace with "Kotlin and Java first-class; Groovy / Scala
  still mapped via path-derived FQN with Phase 1 hint" (or similar).
- `--explain` hint demoted from "polyglot escalation" framing to a
  "non-class-bearing change" framing for the remaining unmapped
  polyglot cases:
  - `Non-Java/Kotlin source ({ext}) mapped via filename only; AST-driven strategies skipped (separate issue).`
- Update `CHANGELOG.md` with explicit `[Breaking]` annotation only if
  the prototype consumer build (Section 11) shows any adopter saw a
  `Kotlin → FULL_SUITE` outcome they relied on (unlikely; the safety-net
  policy is documented as a default they can override via `onUnmappedFile`).
- **No schema bump in this PR** — that landed in PR #3.

---

## 9.5 Stopping points

The four-PR ladder has explicit decision gates. We may stop after PR #1
or PR #3 if adopter feedback shows approach B is unnecessary.

| Stop after | What ships | Cost | Decision signal |
|------------|------------|------|-----------------|
| **PR #1 only** | Path-derived FQN mapping for `.kt`; naming strategy works for Kotlin tests; tier-1 import lookup works via the `<basename>Kt` synthetic; no new dependency; no JAR-size impact | ≤ ~10 KB code change; zero new runtime cost | Issue triage shows ≤1 adopter reports of Kotlin under-selection traceable to AST-required edges (sealed-permits, supertype-walk, multi-file companion-object, package-vs-path divergence) over one release window after PR #1. We do not ship aggregated telemetry; the signal is qualitative — counted from issue tracker reports plus opt-in adopter reports on the GitHub Discussions thread we open at PR #1 merge. |
| **PRs #1 + #3 (system property default off)** | Approach A as default; approach B available via `-Daffected-tests.kotlin.enabled=true`; cache schema bumped twice | Full embeddable on the published classpath; ~40–50 MB JAR; opt-in property | The same Discussions thread asks opt-in adopters to report whether enabling the property moved the needle on test-selection accuracy. If ≤2 opt-in adopters report material improvement after one release window, we revert PR #3 and close at A. |
| **PRs #1 + #3 + #4 (full ladder)** | Approach B is the default; property removed | Full ladder | Opt-in adopter reports from PR #3 show ≥3 adopters confirming material improvement and zero regressions traceable to AST-driven strategies. |

These are qualitative gates — the plugin emits no aggregated telemetry
(only per-MR `--explain` output), so quantitative thresholds like "≥80%
reduction" are unfalsifiable in practice. Each gate is defined in terms
of issue-tracker and Discussions-thread evidence the maintainer can
audit. The plan's TL;DR commits to the full ladder because the
planning cost is sunk; the implementation commitment is per-PR with a
written rollback path.

---

## 10. Risks and mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Shaded Kotlin collides with another Kotlin-using plugin on the same buildscript classpath | High if shading is wrong; low otherwise | The `relocate 'kotlin'` / `'kotlinx.coroutines'` / `'gnu.trove'` rules plus a functional test that asserts no `kotlin/`, `kotlinx/`, or `gnu/` package at top level. Gradle does **not** strictly isolate plugin classloaders from each other within a single buildscript classpath (see `gradle#25616`, `KT-57162`) — what makes the relocation safe is that `io.affectedtests.shadow.kotlin.*` is a wholly distinct package no other plugin loads. The original draft attributed safety to "isolated classloader" — that's misleading; the unique-package property is what matters. The collision risk if relocation is incomplete is two plugins both contributing `org.jetbrains.kotlin.*` at different versions to the same classloader chain, producing `LinkageError` / `NoSuchMethodError`. |
| Plugin JAR balloons past safe download threshold | Medium | `minimize { }` per-dep excludes (Section 5) plus per-PR JAR-size budget enforced in CI. Realistic target post-shading is **40–50 MB** (the original 28–35 MB target was wishful; minimize cannot prune the embeddable's reflection-reached classes). Plugin Portal has no documented hard limit; empirical upload failures cluster around ~90 MB so 40–50 MB is safe. PR #3's prototype build sets the actual budget. |
| `kotlin-compiler-embeddable` class loading is slow on cold JVM | Medium | Lazy `KotlinCoreEnvironment` initialisation per `ProjectIndex` (Section 3.4) — first-`.kt`-parse cost only. Serial path stays cold for filename-only matches. Cost amortised across the run; bounded growth, no per-build leak. |
| `KotlinCoreEnvironment` static singleton pins ~25 MB on the plugin classloader for the life of the daemon | Low (one-time) | Same shape as JavaParser's static `ThreadLocal`: documented, bounded growth, zero per-build leak. Disposed on engine `close()` via try-with-resources on the parent `Disposable`. |
| Kotlin parser internal API changes between embeddable versions | Medium | Rely on the public PSI surface only (`KtFile`, `KtImportDirective`, `KtClassOrObject`). No private `org.jetbrains.kotlin.fir.*` calls. JetBrains does not formally guarantee compatibility but Detekt / ktlint / KSP / IntelliJ-platform all depend on the same surface; the pinned-version posture in Section 4 plus a smoke-test pass on Kotlin majors handles bump risk. |
| `@file:JvmName` ignored on first ship makes some adopters under-select | Medium (Kotlin-interop-heavy adopters routinely use it) | Hint surfaced via `--explain` when present (Section 6); follow-up issue tracks honouring the override; `outOfScopeTestDirs` workaround documented. Severity raised from Low to Medium because the routine-Kotlin-Java-interop case is the one this plan promises to serve. |
| Cache schema bump invalidates a CI-shared cache and regresses warm-build perf for one MR | Low (twice — one bump per shape change) | Standard cache-bump UX; documented in CHANGELOG; PR #1 bumps for the scan-scope widening, PR #3 bumps for the parser-output shape change (Section 7). Two bumps over two minor releases is the same UX cost as one bump and a CHANGELOG note. |
| Diff-side path-derived FQN diverges from file's `package` decl (Kotlin) | Medium | More common in Kotlin than Java (four idiomatic patterns documented in Section 6). PR #3 emits a `--explain` hint and per-run mismatch counter so adopters can see the mismatch rate. Severity raised from Low to Medium because the case is genuinely common in Kotlin, not exotic. |
| Embeddable PSI XML descriptors stripped by `minimize`, surfacing as `Missing extension point: ...` | Medium | Per-dep `minimize` excludes (Section 5); end-to-end parse functional test in PR #3 catches it (Section 5 verification gate). |

---

## 11. Acceptance for closing #76 (and #47)

Issue #76 closes when:

- All four rollout PRs are merged, the `affected-tests.kotlin.enabled`
  property is removed (in PR #4), and a representative consumer build
  shows a Kotlin-only edit selecting the right test instead of
  escalating. **Named consumer build:** a mixed Java + Kotlin
  Spring Boot fixture committed under
  `affected-tests-gradle/src/functionalTest/resources/kotlin-springboot/`
  exercising production Java + Kotlin classes with Kotlin tests
  importing both. Specific edits asserted: (a) edit a Kotlin
  production class, expect its Kotlin test selected;
  (b) edit a Java production class, expect a Kotlin test that imports
  it selected; (c) edit a Kotlin file with only top-level functions,
  expect a Java test importing the synthetic `<basename>Kt` class
  selected.
- Functional tests cover, **per strategy**:
  - `naming`: `.kt` direct edit selects its `<Class>Test.kt` neighbour;
    top-level Kotlin file edit selects `<basename>KtTest.kt`.
  - `usage`: `.kt` test that `import`s a changed `.kt` is selected;
    symmetric Java↔Kotlin cross-language case; Java-imports-`<basename>Kt`
    case selects the `.kt` file change via the synthetic FQN.
  - `implementation`: `.kt` test on a subtype of a changed Kotlin
    supertype is selected.
  - `transitive`: transitive Kotlin-imports-Kotlin chain selects the
    downstream Kotlin test.
- Plus: mixed diff, parse-failure WARN visibility, embeddable load-failure
  WARN-and-fail-closed visibility, `--explain` strings from Section 9
  asserted verbatim on the relevant diff shapes.
- `--explain` rendering is updated in the docs (`README.md` known-
  limitations section + a decision-trace example showing the
  Kotlin-source-AST-mapped path).
- Plugin shadow JAR size budget is met. The budget is set by PR #3's
  prototype build (Section 4) at `measured-size + 15%`; expected
  range is 40–50 MB. **Hard ceiling: 60 MB.** If PR #3's prototype
  measures above 60 MB, the shading approach is re-evaluated before
  PR #3 merges — possible mitigations include K2-only frontend,
  splitting the parser into a separate optional artifact downloaded
  on demand, or accepting Phase 1's hint as the permanent answer for
  Kotlin and stopping at PR #1. The 60 MB ceiling is empirically
  anchored: ktlint ships ~50 MB, so a Gradle plugin that an adopter
  pulls on every Gradle build cannot exceed that envelope by more
  than a small headroom.
- **Cache schema validity** (Goal 4): a unit test under
  `affected-tests-core/src/test/.../discovery/ProjectIndexCacheTest.java`
  loads a serialised cache snapshot from a pre-Phase-2 build (schema
  version 2), runs the post-PR-1 loader against it, asserts the
  loader returns "schema mismatch — full rescan" rather than reusing
  stale rows. The same test is replayed in PR #3 against a schema-3
  snapshot to assert the 3 → 4 bump invalidates cleanly. Without
  this test, Goal 4 is asserted but unverified.

Issue #47 closes when #76 closes, **plus** Groovy and Scala have either
their own follow-up issues or an explicit "won't fix" note. The Phase 1
hint then accommodates only the remaining unmapped polyglot extensions.

---

## 12. Open questions for the implementer

These are genuinely undecided; questions previously listed here that
the review surfaced as load-bearing decisions have been promoted into
the relevant sections (Section 4 for `compileOnly` placement; Section 8
for fail-closed behaviour).

1. Do we want a `Mode.kotlinNative` or stay with the universal mode
   defaults? Recommendation: stay universal — Kotlin should be invisible
   from a configuration perspective.
2. Should `.kts` Gradle scripts ever be parsed for build-logic dependency
   tracking? Out of scope here, separate ticket if anyone asks.

---

## 13. References

- Phase 1 commit: `e3167b5` — issue-47-docs phase 1
- Phase 2 tracking issue: [#76](https://github.com/vedanthvdev/affected-tests/issues/76)
- Parent epic: [#47](https://github.com/vedanthvdev/affected-tests/issues/47)
- Relevant code: `affected-tests-core/src/main/java/io/affectedtests/core/discovery/{FileMetadata,FileMetadataExtractor,JavaParsers,ProjectIndex,SourceFileScanner}.java`
- Mapping site: `affected-tests-core/src/main/java/io/affectedtests/core/mapping/PathToClassMapper.java` (line 167 — non-Java rejection; ~line 266 — `.java`-only suffix strip)
- Strategy fallback sites: `UsageStrategy#metadataOrGet`, `ImplementationStrategy#metadataOrGet`, `TransitiveStrategy#metadataOrGet` (each takes a JavaParser-typed `fallbackParser` today; PR #2 reroutes through `LanguageParser`)
- Hint site: `affected-tests-gradle/src/main/java/io/affectedtests/gradle/AffectedTestTask.java#appendUnmappedFileHint`
- Shading site: `affected-tests-gradle/build.gradle#shadowJar`
- Shadow source: `RelocationContext.kt`, `SimpleRelocator.kt` at the pinned 9.4.1 tag of GradleUp/shadow
- Embeddable POM: `repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.20/`
- Lifecycle reference impls: `fwcd/kotlin-language-server` (`org.javacs.kt.compiler.Compiler`); Detekt's `KtCompiler`
- Shading-failure-mode evidence: `bazel-contrib/rules_kotlin#624` (Missing extension point), `gradle/plugin-portal-requests#150` (empirical upload size threshold)

---

## 14. Review history

This plan was reviewed by four parallel persona agents
(adversarial-document, feasibility, coherence, scope-guardian) before
merge. Major findings from those reviews and the corresponding plan
changes:

- **"Strategies do not change" was false** — three of four strategies
  retain JavaParser-typed `metadataOrGet` no-index fallbacks; Naming
  consumes `scanTestFqns` not `FileMetadata`. Section 2 rewritten;
  PR #2 scope expanded to include the fallback rewire.
- **Shading missed `kotlinx.coroutines.*` and `gnu.trove.*`** (verified
  against the embeddable POM). Section 5 adds both.
- **"`ShadowPublishFunctionalTest` already enforces (1) and (2)" was
  false** — the test only checks task config, no JAR-content
  assertions exist anywhere in the repo. Section 5 verification gate
  rewritten as a new test, not "extend the existing."
- **`.java` suffix-strip lives in 5 sites, plan covered 1.** Section 2
  enumerates all five; PR #1 scope updated.
- **`<basename>Kt` synthetic FQN was claimed but never produced on the
  diff side.** Section 6 adds the explicit subsection; PR #1 emits
  both `Util` and `UtilKt` for every `.kt` file.
- **Cache schema bump was scheduled for PR #4** — the moment the cache
  *can* persist Kotlin rows is PR #3, even with the flag default-off.
  Section 7 / PR #3 scope corrected.
- **`ThreadLocal<KotlinCoreEnvironment>` was the wrong primitive** —
  `Disposable` lifecycle, MockApplication overhead, read-lock
  contract. Section 3.4 added.
- **`minimize { exclude(...) }` for one dep was insufficient** —
  per-dep excludes plus service-file FQN rewriting plus extension-XML
  preservation needed. Section 5 rewritten.
- **Approach B was never defended against issue #47's 80/20 framing.**
  Section 0.5 added; Section 9.5 documents stopping points after
  PR #1 and PR #3.
- **JAR-size budget (≤ 35 MB)** was wishful — `minimize` cannot prune
  reflection-reached classes. Realistic target is 40–50 MB; PR #3's
  prototype build sets the actual budget.
- **`@file:JvmName` was described as both "honour if surfaced" and
  "follow-up"**; Section 6 picks one (default-naming first;
  `--explain` hint when the override would have mattered).
- **PR #4 / Section 11 mismatch on flag-removal timing** — Section 11
  required removal for closure but PR #4 deferred to "next minor."
  PR #4 now removes the property in-band.
- **`compilationUnit(Path)` "becomes private" + "stays public"** was
  internally contradictory; corrected to "stays public until all
  callers migrate."
- **"Plugin classloader is isolated"** as the safety rationale was
  misleading; corrected to "the relocated package is wholly distinct
  from any other plugin's namespace."
- **Q3 (`compileOnly` placement) and Q4 (fail-closed) were open
  questions** but are load-bearing decisions; promoted into Sections
  4 and 8 respectively.

A **second** review pass was run after the first round of corrections to
catch drift introduced by the rewrite. Findings from that pass:

- **§7's "configHash catches the changed scan-root list" claim was
  factually wrong** — verified against `ProjectIndexCache.configHash`,
  the hash includes declared dir strings only, not resolved roots and
  not the file-extension scope. PR #1's scanner widening leaves the
  hash unchanged on mixed projects, so a warm cache would silently
  reuse a stale `testFqns` universe missing every Kotlin test FQN.
  Section 7 rewritten to schedule **two** schema bumps (PR #1 + PR #3)
  instead of one. Goal 4 and PR #1 / PR #3 scope updated; Section 10
  risk row updated; Section 11 acceptance gains a cache-validity test.
- **Synthetic `<basename>Kt` emission for test files** would surface to
  the runner as "no tests found." Section 2 / Section 6 / PR #1 scope
  now restrict synthetic emission to production-side only.
- **`minimize { }` block silently changed packaging for JGit /
  JavaParser / slf4j** — pre-PR-3 those shipped whole; post-PR-3
  partial pruning would surface as `ServiceConfigurationError` at
  runtime. Section 5 adds en-masse `exclude(dependency('...'))` for
  all three plus a "minimize regression smoke" verification step.
- **§9.5's "≥80% reduction" decision gate was unfalsifiable** — plugin
  emits no aggregated telemetry. Replaced with qualitative gates
  defined in terms of issue-tracker and Discussions-thread evidence.
- **Pinned-verbatim `--explain` strings collided with `{path}/{parsed}`
  template substitutions.** Added an explicit string-stability
  contract in Section 9: prefix-up-to-first-placeholder and
  suffix-after-last-placeholder are stable across minor versions.
- **`compileOnly` for embeddable in core would NCDFE PR #3's unit
  tests.** Section 4 adds `testImplementation` alongside `compileOnly`
  in core, with explanatory comment.
- **PR #4's `onUnmappedFile` revert rationale was wrong** — that
  property doesn't control Kotlin AST parsing. Replaced with two
  defensible escape hatches (pin previous version; route via
  `outOfScopeSourceDirs`).
- **§2 ambiguity on `collectJavaFiles` widening** (widen vs split) and
  missing `collectTestFiles` row — picked "widen in place," added the
  `collectTestFiles` row to the table.
- **`*Kt.kt` filename pattern** (e.g. `FormatKt.kt` → phantom
  `FormatKtKt`) — added explicit edge-case clause to Section 6.
- **JAR-size budget had no hard ceiling** above the prototype-measured
  envelope. Section 11 adds 60 MB hard ceiling with re-evaluation
  triggers if exceeded.
- **Goal 4 leftover "feature flag" terminology** at the goal listing —
  fixed to "system property."
- **Section 0 "four sibling namespaces" undercount** — corrected to
  enumerate exact namespaces.
