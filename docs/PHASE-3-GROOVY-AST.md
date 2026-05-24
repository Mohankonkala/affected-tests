# Phase 3 — Groovy AST support (issue #96 / #47)

> Tech plan for Phase 3 of [#47](https://github.com/vedanthvdev/affected-tests/issues/47),
> tracked under [#96](https://github.com/vedanthvdev/affected-tests/issues/96). Follow-on
> to Phase 2 ([#76](https://github.com/vedanthvdev/affected-tests/issues/76),
> [`docs/PHASE-2-KOTLIN-AST.md`](./PHASE-2-KOTLIN-AST.md)).
> **Plan-only** — no engine code changes in this PR. Subsequent PRs reference
> this document and execute one section at a time.

---

## 0. TL;DR

Phase 2 made Kotlin a first-class source language and left the architecture
extension-agnostic at most code sites: `SourceExtensions.EXTENSIONS`,
`LanguageParsers.forFile`, the `LanguageParser` interface, and the three
AST-driven strategies' `metadataOrGet` fallbacks (Usage, Implementation,
Transitive) all dispatch by extension today. Phase 3 plugs Groovy into that
machinery; Naming consumes path-derived FQNs and is handled by widening
`scanTestFqns`'s extension scope.

The work splits into two halves. The **scaffold half** is small: widen
`SourceExtensions.EXTENSIONS` to include `.groovy` / `.gvy`, extend
`SourceSetAutoDiscovery` to walk `ss.getGroovy().getSrcDirs()` so Spock
specs under `src/test/groovy` enter the test universe without manual DSL
configuration, register `GroovyLanguageParser` in `LanguageParsers.forConfig`,
demote `.groovy` / `.gvy` from the polyglot-unmapped hint, and add `Spec` /
`Specification` to the default test-suffix list. None of that requires
touching strategy code.

The **parser half** is shipping `GroovyLanguageParser` itself: parse a
`.groovy` file via Apache Groovy 5.0.x's `CompilationUnit.compile(Phases.CONVERSION)`
(see §3.2 for why CU over `AstBuilder` — operational lifecycle parity
with Phase 2's `KotlinCoreEnvironment` shape, not parser-capability),
project the result onto the same `FileMetadata` shape Java and Kotlin
already produce, shade `org.apache.groovy.*` + `org.codehaus.groovy.*`
+ bundled antlr4 / asm / picocli copies under
`io.affectedtests.shadow.{apache,codehaus}.groovy.*` /
`io.affectedtests.shadow.groovy.*`, and plumb diagnostics through
`--explain` via a new `GroovyDiagnostics` carrier (§8 — sibling of
`KotlinDiagnostics`, no rename of the existing carrier so the Phase 2
public API stays intact). `LanguageParser` gains two default-method
hooks so `ProjectIndex` no longer needs `instanceof` branches per
parser class.

**Cost:** one new runtime dependency — Apache Groovy 5.0.x
(`org.apache.groovy:groovy:5.0.6`, latest at the time of writing).
Core jar is **7.7 MB unshaded** (measured against `groovy-5.0.5.jar`
at 8,057,694 bytes; 5.0.6 is within ±50 KB of that figure), roughly
seven times smaller than `kotlin-compiler-embeddable` (~50 MB).
Single coordinate, single jar, no transitive runtime deps (verified
against the published Gradle module metadata). Worst-case post-shade
delta lands between **+7 MB and +9 MB** on top of the v2.2.27
Kotlin-only baseline (~56 MB measured) — see §7.5 on why the raw-jar
size is a worst-case estimate, not the expected delta. Hard rollback
trigger if total exceeds 70 MB; comfortably inside the empirically
observed Plugin Portal ceiling (~90 MB;
`gradle/plugin-portal-requests#150`).

**Rollout:** three small PRs, not four. Phase 2 needed PR #2 to build the
`LanguageParser` interface; that work already shipped. Phase 3 ladder:

1. **PR #1 — path-derived Groovy mapping + Spock suffixes + Groovy
   sourceset auto-discovery** (no parser). Approach A: filename-only
   naming + directly-changed buckets covered for the dominant Spock-on-Java
   shape.
2. **PR #2 — `GroovyLanguageParser` + shading** behind `groovyEnabled`
   DSL flag, default `false`. Approach B parity for AST-driven strategies.
   Decision gate before flipping the default.
3. **PR #3 — default-on + `--explain` plumbing + functional tests**.

PR #1 and PR #2 each have an explicit decision pause before the next
PR opens; PR #3 is the terminus (default-on flip). These are
maintainer-driven pauses for fixture audits, not waits for adopter
signal — see §9.2 for the honest framing. The pause after PR #1 is
substantively meaningful (Spock specs are disproportionately
well-served by the naming strategy, so PR #2's dependency cost may
not be justified); the pause after PR #2 is more procedural
(default-on after one release cycle on the opt-in flag).

---

## 0.5 Why approach B (full AST), not just A (filename-only)

Issue #96 frames Groovy parity primarily around Spock — Java production code
with Groovy `*Spec.groovy` tests. That shape is dominated by the naming
strategy (which approach A handles, **provided** PR #1 also extends
`SourceSetAutoDiscovery` to walk `ss.getGroovy().getSrcDirs()` — see §1
and §9.1 — otherwise specs under `src/test/groovy` never enter the test
universe and the §0.5 premise collapses) and the `*Spec` suffix knob
(Section 4).

Approach A alone closes the worst gap. Approach B is the path to parity
across the three AST-driven strategies (Usage, Implementation, Transitive).
Concrete cases where A leaves a gap:

1. **Spock spec imports a Java production class via a non-conventional name.**
   Cucumber-style step files (`UserSteps.groovy`) or `@SpringBootTest`
   entrypoints (`UserApplicationTests.groovy`) don't follow `<Subject>Spec`.
   Naming strategy never selects them; UsageStrategy needs to walk imports.
   Note: even with full AST, Spock's `@Subject` annotation argument is
   **not** harvested by Phase 3 — see §3.6 for what CONVERSION-phase AST
   does and does not surface.
2. **Java test depends on a `.groovy` production class.** A change to
   `Foo.groovy` should select `FooTest.java`. PR #1 derives `com.example.Foo`
   from path + filename and feeds it into `changedProductionClasses`, so
   Naming + the test-side AST already in place pick up `FooTest.java`. No
   Groovy parser needed for this case — A is sufficient.
3. **Implementation strategy on Groovy traits / interfaces.** A change to a
   Groovy `trait` or `interface` should select tests of its implementers.
   Without a Groovy AST, `ImplementationStrategy` never sees the supertype
   edge. With a Groovy AST at `CompilePhase.CONVERSION`, the parsed
   `ClassNode.getSuperClass().getName()` returns the supertype as the
   source wrote it (`"Specification"` for `extends Specification`, FQ
   only when the source wrote it FQ). `GroovyLanguageParser` walks the
   file's import list and resolves simple-name supertypes against
   imports — a ~30-LOC helper that runs at parse time and lives in the
   parser, not at the strategy layer. That's the same shape Phase 2's
   Kotlin parser uses for its supertype rendering.
4. **Transitive walks across `.groovy` files.** A → B → C where B is
   `.groovy`: under A, the walk stops at B; under B, it crosses.

A maintainer-driven decision pause after PR #1 (§9.2) — not a wait
for adopter signal that won't arrive — decides whether B-level parity
is worth the dependency cost. The pause is substantively meaningful
because (a) Spock specs are disproportionately well-served by the
naming strategy + sourceset auto-discovery (PR #1), and (b) the
JAR-size risk that pushed Phase 2 through the full ladder is roughly
seven times smaller for Groovy. PR #1 ships unconditional path-derived
support; if the maintainer's internal Spock fixture audit shows no
concrete under-selection for cases (1), (3), or (4), Phase 3 stops
at A and documents `groovyEnabled` as the unbuilt opt-in (mirroring
Scala's "won't fix unless asked" posture on the parent epic).

---

## 1. Architectural inheritance from Phase 2

The pieces Phase 3 reuses without modification:

- **`LanguageParser` interface** (`affected-tests-core/src/main/java/io/affectedtests/core/discovery/LanguageParser.java`).
  Signature `String extension()` + `FileMetadata parseOrWarn(Path file, String label)`
  is reused unchanged — diagnostics carriers are **constructor-injected**
  per language (matching `KotlinLanguageParser`, where `KotlinDiagnostics`
  is a constructor parameter, not a method parameter).
  `LanguageParsers.forConfig` constructs each parser with the engine's
  diagnostics carrier instance.
- **Per-extension dispatch** at `ProjectIndex.fileMetadata` and
  `ProjectIndex.compilationUnit` — already routes to `LanguageParsers.forFile`
  by extension, returns `Optional.empty()` for unregistered extensions.
- **Strategy `metadataOrGet` fallbacks** — the three AST-driven strategies
  (Usage / Implementation / Transitive) all dispatch through
  `LanguageParsers.defaultJavaOnly()` for the no-index path. Phase 3 PR #2
  either registers `GroovyLanguageParser` in `defaultJavaOnly()` or
  accepts that strategy unit tests stay Java-only (same posture as Kotlin
  today, documented in `LanguageParsers.java:133–142`).
- **`SourceExtensions.stripKnownExtension`** — iterates `EXTENSIONS`;
  widening the set is sufficient for every suffix-strip site in
  `SourceFileScanner`, `PathToClassMapper`, and `UsageStrategy.extractFqn`.
- **Path-derived FQN mapping** — `PathToClassMapper.tryMapToClass` operates
  on any extension that `SourceExtensions.isSource` accepts. No code change
  beyond widening `EXTENSIONS`.
- **Cache schema versioning** — `ProjectIndexCache.SCHEMA_VERSION` (currently
  `4`) + `configHash`. Phase 3 bumps each in the same shape Phase 2 did
  (4 → 5 in PR #1 for scan-scope widening; 5 → 6 in PR #2 for parser-output
  shape).

The pieces Phase 3 must add or modify (Groovy-specific):

| Surface | Phase 3 action |
| --- | --- |
| `SourceExtensions.EXTENSIONS` | Add `.groovy`, `.gvy` (PR #1) |
| `SourceSetAutoDiscovery.from()` | When the source set carries a `GroovySourceDirectorySet` extension, walk its `getSrcDirs()`. Lookup is `ss.getExtensions().findByType(GroovySourceDirectorySet.class)` — `findByType` returns `null` when the Groovy plugin isn't applied, so no reflection or `ClassNotFoundException` guard is needed. The `GroovySourceDirectorySet` type has shipped on `gradleApi()` since Gradle 7.1, well below the plugin's documented Gradle floor (PR #1) |
| `LanguageParsers.forConfig` | Register `GroovyLanguageParser` when `config.groovyEnabled()` is true (PR #2) |
| `AffectedTestsConfig` | Add `groovyEnabled` field + builder + `configHash` term (PR #2) |
| `AffectedTestsExtension` / `AffectedTestsPlugin` / `AffectedTestTask` | Add `getGroovyEnabled()` DSL property + convention + wiring. **DSL-only** — no `affected-tests.groovy.enabled` system property; Phase 2 PR #4 removed the equivalent Kotlin one and Phase 3 ships without it from day one (PR #2) |
| `AffectedTestsEngine.AffectedTestsResult` | Adds a new `groovyDiagnostics()` accessor + record component alongside the existing `kotlinDiagnostics()`. No existing accessor signature changes — the Phase 2 public API (`kotlinDiagnostics()`, `KotlinDiagnostics.EMPTY`, etc.) is preserved unchanged (PR #2 introduces the storage; PR #3 wires it into `--explain`). |
| `GroovyDiagnostics` (new sibling of `KotlinDiagnostics`) | Mirror-shaped carrier: same counters, same `EMPTY`-singleton contract, same thread-safe storage shape, Groovy-specific semantics where they differ. Unification with `KotlinDiagnostics` deferred to Phase 4 if and when a third language lands (§8.1). (PR #2) |
| `LanguageParser` (default methods) | Two new default methods (`recordDiagnostics(...)`, `replayWarmCacheDiagnostics(...)`) — no signature break for existing implementations. `KotlinLanguageParser` and `GroovyLanguageParser` both override; `ProjectIndex` swaps its `instanceof KotlinLanguageParser` branches for virtual calls. (PR #2) |
| `GroovyLanguageParser` | New parser implementing `LanguageParser`, including a small `GroovySupertypeResolver` helper (~30 LOC) that walks `ModuleNode.getImports()` to FQ-resolve simple-name supertypes at `CompilePhase.CONVERSION` (§3.2). (PR #2) |
| `ProjectIndex.fileMetadata` | Replace the `instanceof KotlinLanguageParser` branch with a `parser.recordDiagnostics(...)` virtual call — see §8.3 (PR #2) |
| `ProjectIndex.seedFileMetadata` | Replace the `.kt`-only warm-cache hint re-emission with a `parser.replayWarmCacheDiagnostics(...)` virtual call (PR #2) |
| `AffectedTestTask` | Add parallel `appendGroovyMappingHints` (PR #1) and `appendGroovyAstHints` + `anyGroovy()` (PR #3) alongside the existing Kotlin-named methods; remove `.groovy` / `.gvy` from `polyglotExtensionOf` (PR #1). Existing Kotlin-named methods stay unchanged. |
| `affected-tests-gradle/build.gradle` | Apache Groovy 5 dependency (`compileOnly` + `testImplementation`) + shadow relocations + minimize excludes (PR #2) |

The work that doesn't fit cleanly into "pure scaffolding" (Sections 6, 7, 8,
9) is what justifies a written plan rather than a fix-up PR.

---

## 2. Code-site enumeration

Inventoried against the post-Phase-2 codebase. File paths are repo-relative.
Line numbers are accurate as of `master` at issue close on `v2.2.27`.

### 2.1 Already extension-agnostic (widen `EXTENSIONS` only)

| Site | File:line | Behaviour |
| --- | --- | --- |
| Source-file scan | `affected-tests-core/.../SourceFileScanner.java:110` | `if (SourceExtensions.isSource(file.toString()))` |
| Test-file scan | same:194 | Delegates to `collectJavaFiles` |
| Test-FQN walk | same:258–263 | `isSource` + `stripKnownExtension` |
| Path → FQN | same:325 | `stripKnownExtension(relative)` |
| Diff-side mapping gate | `affected-tests-core/.../mapping/PathToClassMapper.java:168` | `if (!SourceExtensions.isSource(filePath))` → unmapped |
| Diff-side suffix strip | same:314 | `stripKnownExtension(relativePath)` |
| Strategy fallback FQN | `affected-tests-core/.../strategies/UsageStrategy.java:314` | `stripKnownExtension(...)` |
| Per-extension parser dispatch | `affected-tests-core/.../discovery/LanguageParsers.java:233–248` | `forFile` → `extensionOf` → `forExtension` |
| Index extension dispatch | `affected-tests-core/.../discovery/ProjectIndex.java:461–476` | `compilationUnit(Path)` |
| Index unregistered-extension skip | same:511–523 | `parser == null` → no parse-failure bump |

### 2.2 Kotlin-hardcoded (refactored or extended in Phase 3)

| Site | File:line | Current behaviour | Phase 3 action |
| --- | --- | --- | --- |
| Synthetic diff-side FQN | `PathToClassMapper.java:249–256` | `if (".kt".equals(SourceExtensions.extensionOf(filePath))) { synthetic = prodFqn + "Kt"; … }` | **No Groovy analogue** (§5). Kotlin branch stays as-is. |
| Index Kotlin diagnostics branch | `ProjectIndex.java:525–607` | `instanceof KotlinLanguageParser` → call `KotlinDiagnostics` mutators | **Refactored.** §8.3 replaces the `instanceof` branch with a `parser.recordDiagnostics(...)` virtual call. Each parser owns its own carrier; `ProjectIndex` is parser-type-agnostic. (PR #2) |
| Warm-cache hint re-emission | `ProjectIndex.java:670–675` | `if (".kt".equals(extensionOf(...)))` re-records mappings | **Refactored.** Same as above — `parser.replayWarmCacheDiagnostics(...)` is the parallel virtual call. (PR #2) |
| Polyglot-hint classifier | `AffectedTestTask.java:2047–2068` | `polyglotExtensionOf` returns `.groovy` / `.gvy` (among others) | Drop both extensions; same pattern as `.kt` removal in Phase 2 PR #1. (PR #1) |
| Polyglot-hint test | `AffectedTestTaskExplainFormatTest.java:506–577` | Asserts polyglot line **present** for `.groovy` | Flip to assert **absent** + Groovy mapping line present. (PR #1) |
| Kotlin mapping hint | `AffectedTestTask.java:1912–1942` | `appendKotlinMappingHints` | **Unchanged.** PR #1 adds a parallel `appendGroovyMappingHints` method beside it; the existing Kotlin method retains its current signature and behavior. |
| Kotlin AST hints | `AffectedTestTask.java:1956–2012` | `appendKotlinAstHints` (4 strings) | **Unchanged.** PR #3 adds a parallel `appendGroovyAstHints` method beside it with the four Groovy pinned strings (§9.5). |
| `anyKotlin()` predicate | `AffectedTestTask.java:2015–2027` | Iterates unmapped + result for `.kt` | **Unchanged.** PR #3 adds a parallel `anyGroovy()` predicate beside it, dispatched from the rendering site that decides whether to call `appendGroovyAstHints`. |

### 2.3 New surfaces

| File | Status | Phase 3 PR |
| --- | --- | --- |
| `affected-tests-core/src/main/java/io/affectedtests/core/discovery/GroovyDiagnostics.java` | New (sibling of `KotlinDiagnostics`; §8) | PR #2 |
| `affected-tests-core/src/main/java/io/affectedtests/core/discovery/GroovyLanguageParser.java` | New (includes `GroovySupertypeResolver` helper, §3.2) | PR #2 |
| `affected-tests-core/src/test/java/io/affectedtests/core/discovery/GroovyDiagnosticsTest.java` | New (mirror of `KotlinDiagnosticsTest`) | PR #2 |
| `affected-tests-core/src/test/java/io/affectedtests/core/discovery/GroovyLanguageParserTest.java` | New | PR #2 |
| `affected-tests-gradle/src/test/java/io/affectedtests/gradle/SourceSetAutoDiscoveryGroovyTest.java` | New (covers `findByType(GroovySourceDirectorySet)` lookup, §1 / §9.1) | PR #1 |
| `affected-tests-gradle/src/functionalTest/resources/io/affectedtests/gradle/e2e/features/11-issue-96-groovy-path-derived-mapping.feature` | New | PR #1 |
| `affected-tests-gradle/src/functionalTest/resources/io/affectedtests/gradle/e2e/features/12-issue-96-groovy-ast.feature` | New | PR #3 |
| `affected-tests-gradle/src/functionalTest/java/io/affectedtests/gradle/ShadowJarSizeTest.java` | New (asserts shadow JAR delta within §7.5 budget) | PR #2 |

---

## 3. Parser library selection

### 3.1 Recommendation: Apache Groovy 5.0.x core

Pin `org.apache.groovy:groovy:5.0.x` (latest stable as of writing:
**5.0.6**, published 2026-05-04 — verified against Maven Central).

- **Single coordinate, single jar.** Groovy 5 ships its core compiler,
  parser, runtime, and AST infrastructure in one ~7–8 MB jar with the
  antlr4 / asm dependencies jarjar'd internally (`groovyjarjarantlr4.*`,
  `groovyjarjarasm.*`). No `kotlin-stdlib` / `kotlinx-coroutines` /
  `trove4j` equivalent — the dependency footprint is much smaller than
  Kotlin's.
- **JDK 11+ floor.** Plugin already requires JDK 21 — comfortably above.
- **Active maintenance.** Apache Groovy 5.0 was released 2025-08-21;
  patch cadence has been ~6 weeks (5.0.0 → 5.0.6 over nine months).
  4.x is also maintained in parallel for JDK 8 adopters; we only need 5.x.

### 3.2 Parsing entry point — `CompilationUnit` (committed)

Phase 3 commits to **`CompilationUnit.addSource(...).compile(Phases.CONVERSION)`**.

`AstBuilder.buildFromString(CompilePhase, boolean, String)` is the
smaller-API alternative and accepts the same `CompilePhase` parameter,
so the choice between the two is **not** about phase capability.
Phase 3 picks `CompilationUnit` for three concrete operational reasons:

1. **Lifecycle parity with Phase 2.** Phase 2's Kotlin parser owns a
   shared `KotlinCoreEnvironment` per parser instance, constructs
   per-file parse contexts against it, and disposes the environment
   at engine teardown. `CompilationUnit` + a shared `GroovyClassLoader`
   reproduces that exact shape; `AstBuilder` hides the classloader
   inside the call and re-creates it per parse (verified against the
   5.0.x source — `AstBuilder.buildFromString` constructs a fresh
   `CompilationUnit` and `GroovyClassLoader` internally on every
   call). For a 10k-file scan, that is the difference between one
   GCL bootstrap and 10k.
2. **`ModuleNode` access.** `cu.getAST()` returns the `ModuleNode`
   directly, which is the surface `FileMetadata` extracts from
   (imports, classes, fields, supertypes). `AstBuilder.buildFromString`
   returns `List<ASTNode>` with the `ModuleNode` wrapped inside;
   teasing it back out works but loses the natural fit.
3. **Phase ceiling, when we want it.** Both APIs accept `CompilePhase`,
   but `CompilationUnit` exposes the post-parse hooks needed if Phase 3
   (or a follow-up) needs to bump to `SEMANTIC_ANALYSIS` for type
   resolution. `AstBuilder` parsing past CONVERSION is documented to
   work but the post-parse intermediate state is not part of its
   public surface.

**At what phase does PR #2 ship?** `CompilePhase.CONVERSION`. At
CONVERSION, supertypes parse as the source wrote them — simple-name
supertypes resolve to a `ClassNode` whose `getName()` returns the
simple name, not the FQ name. `GroovyLanguageParser` ships a small
helper (~30 LOC) that walks the parsed `ModuleNode.getImports()` and
`getStarImports()` lists and resolves `superClass.getName()` against
them, producing the FQ supertype that `FileMetadata.typeDeclarations[].supertypeFqns`
expects. Same approach the existing `KotlinLanguageParser` uses for
its supertype rendering. Filed as `GroovySupertypeResolver` in PR #2's
unit-test surface.

A follow-up issue at PR #3 merge considers tightening to
`SEMANTIC_ANALYSIS` only if the manual resolver proves insufficient
in adopter-reported cases (e.g. wildcard imports across multi-file
fixtures, Spock metaclass mixins). The parser-entry-point choice is
fixed regardless: `CompilationUnit`.

### 3.3 Parser lifecycle (concurrency, classloader, warm-start)

Three constraints from `CompilationUnit`'s documented behaviour:

1. **`CompilationUnit` is one-shot.** Each parse constructs a fresh CU.
   Sharing one CU across files / threads is documented unsafe; the
   `addSource → compile` pair is intended as a single-pass operation.
2. **`GroovyClassLoader` is heavy to construct.** Each CU instantiation
   pulls in the parent classloader registration, ASM transformer
   loading, and a `ProtectionDomain`. Across a 10k-file scan that adds
   up.
3. **The plugin parses files in parallel** via `ProjectIndex.fileMetadata`
   (Java parallel streams).

Phase 3 ships the same shape Phase 2 settled on for `KotlinCoreEnvironment`:

- **One shared `GroovyClassLoader` per `GroovyLanguageParser` instance**,
  constructed lazily under double-checked locking on first parse, closed
  in `LanguageParser.close()` at engine teardown.
- **A fresh `CompilationUnit` per file**, constructed against the shared
  GCL. CU construction is cheap once GCL exists (the expensive part of
  CU init is GCL setup).
- **No shared mutable state across threads.** Each parse runs against
  its own CU; the GCL is read-mostly after warm-up.
- **AST-only access — never invoke class loading from parser code.**
  `GroovyLanguageParser` reads `cu.getAST()` (the `ModuleNode`) and
  walks it. It explicitly does NOT call `cu.getClass(...)`,
  `groovyClassLoader.loadClass(...)`, or `groovyClassLoader.parseClass(...)`,
  any of which would force compilation past CONVERSION and load
  generated classes into the shared GCL's class cache. Across a
  10k-file scan this would be the difference between a flat-memory
  parser and a heap-growing one. The parser is documented as AST-only
  in its class-level Javadoc; PR #2 ships a steady-state memory test
  (`GroovyLanguageParserMemoryTest`) that parses a 1k-file fixture
  and asserts the GCL-attributable retained heap after parse is
  bounded by a small multiple of the pre-parse baseline (committing
  to "no per-file class accumulation" rather than a specific MB
  number, which would be JVM-version-sensitive).

PR #2 prototype build measures warm-start cost against a 1k-file Spock
fixture before the §11 acceptance gate freezes that number ± 15%
(mirrors Phase 2's prototype gate for JAR size).

### 3.4 JDK version compatibility

Apache Groovy 5.0 supports **JDK 11–25**. `groovy:5.0.6` is built against
JDK 11. The plugin's build floor (JDK 21) and adopter compatibility (the
plugin runs in the consumer's Gradle build, so the consumer's JVM also has
to be 21+) means JDK 11 vs 17 vs 21 considerations don't constrain us.

### 3.5 Comparison with rejected options

| Option | Why not |
| --- | --- |
| `AstBuilder.buildFromString` | Constructs a fresh `CompilationUnit` + `GroovyClassLoader` per call, defeating the warm-GCL lifecycle Phase 2 settled on for the equivalent Kotlin parser. Returns `List<ASTNode>` rather than the `ModuleNode` `FileMetadata` extracts from. Same `CompilePhase` parameter, same parser internals — the rejection is operational, not capability. |
| `groovy-eclipse-batch` | Eclipse JDT-derived. Heavier (~25 MB), ships an Eclipse compiler bundle. Useful for IDE tooling, overkill for us. |
| `org.apache.groovy:groovy-all:5.0.6` (pom aggregator) | Pulls every optional module (`groovy-ant`, `groovy-jmx`, `groovy-sql`, …). All of them are runtime modules we never need; minimize would have to exclude each. |
| Apache Groovy 4.x | Older parallel branch retained for JDK 8 compatibility. Both 4.x and 5.x support JDK 21; the choice is one of "newer AST infra, ~6 months less battle-tested" vs "older AST infra, broader user base." 5.x picked because of `JDK 25` support and `record` AST coverage; 4.x retained as the explicit fallback in §3.7. |
| Hand-rolled antlr4 grammar | Groovy's grammar is large (closures, traits, dynamic dispatch, AST transforms); reimplementing the parser would be a multi-week effort with worse fidelity than the upstream lexer. |

### 3.6 What CONVERSION-phase AST surfaces for Spock specs

Phase 3's parser stops at `CompilePhase.CONVERSION`. For a typical Spock
spec (`class UserServiceSpec extends spock.lang.Specification { def "fact"() { expect: 1+1 == 2 } }`)
the AST contains:

- ✅ **`ClassNode`** with name `UserServiceSpec`, package, and supertype
  reference. **Caveat:** `superClass.getName()` returns the supertype
  exactly as the source wrote it — `"Specification"` for the typical
  `import spock.lang.Specification; class FooSpec extends Specification`
  pattern, FQ only when the source wrote `extends spock.lang.Specification`.
  PR #2's `GroovySupertypeResolver` (§3.2) walks the file's imports list
  to FQ-resolve simple-name supertypes against single-type imports first,
  then star imports as a fallback.
- ✅ **Imports** — preserved verbatim, available for UsageStrategy's
  tier-1 import-walk.
- ✅ **Field declarations** with types — feed `FileMetadata.typeRefSimpleNames`.
- ✅ **Method declarations** — declared on the class, signatures
  available.
- ⚠️ **Block labels** (`setup:`, `expect:`, `where:`, `then:`) parse as
  `LabelStatement` nodes wrapping the following block. Syntactically
  valid; the parser must not stumble on them, but their semantic
  interpretation as Spock features happens at Spock's
  `@SpockTransform` AST visitor at `CompilePhase.SEMANTIC_ANALYSIS`
  (Spock's domain, not ours).
- ⚠️ **`where:` data tables** (`a | b | c`) parse as a `BitwiseOrExpression`
  chain at CONVERSION. Syntactically valid Groovy; we don't interpret
  them — strategies consume class shape, not method bodies.
- ❌ **Spock's `@Subject` annotation arguments** — Spock applies
  `@Subject` interpretation at SEMANTIC_ANALYSIS. Phase 3 does NOT
  harvest `@Subject` arguments into `FileMetadata`. Consequence: §0.5
  case 1 ("Spock spec imports a Java production class via a
  non-conventional name") is partially served — UsageStrategy walks
  imports and selects on import match, but does NOT use `@Subject`
  as an additional signal. Filed as a Phase-3-follow-up issue when
  PR #3 merges.

### 3.7 Smoke-test policy + Apache Groovy major-version fallback

Mirroring Phase 2 §4's policy for the Kotlin embeddable:

- **Patch bumps (5.0.x → 5.0.x+1)** flow through Dependabot like any
  other dep. The `ShadowRelocationFunctionalTest` gate (§7) and the
  parse-gate test (PR #2) catch wire-level regressions.
- **Major bumps (5 → 6, 6 → 7)** get an explicit smoke-test pass
  against a representative Spock-on-Java consumer tree before merging
  the bump. Detected parser regressions trigger a temporary pin to
  the previous major.
- **Apache Groovy 6.x.** In development at the time of this plan
  (JDK 17+ floor). Migration story tracked in §12 as a Phase-4-or-later
  question — Phase 3 does not block on it.
- **4.x fallback trigger.** If PR #2's `GroovyLanguageParserTest`
  fixture coverage (basic Spock spec, trait, top-level script,
  multi-class file) cannot pass against `org.apache.groovy:groovy:5.0.x`
  due to `CompilationUnit` parse regressions, fall back to
  `org.apache.groovy:groovy:4.0.x`. JDK 21 supports both. The plan
  accepts this fallback explicitly so PR #2 doesn't need to
  re-litigate the choice if the prototype build hits a parser bug.

### 3.8 What the plan does not pre-decide

- The exact 5.0.x patch (PR #2 picks the latest at the time of land; floor
  documented in the build script comment with a Dependabot-driven update
  cadence per §3.7).
- Whether to also depend on `groovy-templates` or `groovy-ant` for any
  derived analysis — we expect not.

---

## 4. Naming strategy + Spock spec recognition

### 4.1 Current state

Default test-name suffix list (`AffectedTestsConfig.Builder:440`,
`AffectedTestsPlugin:68`):

```java
List.of("Test", "IT", "ITTest", "IntegrationTest")
```

Spock specs conventionally end in `Spec` (e.g. `UserServiceSpec.groovy`) or
`Specification` (rarer). Neither suffix is in the default list today.

### 4.2 Phase 3 PR #1 default

Add `Spec` and `Specification` to the default suffix list. New default:

```java
List.of("Test", "IT", "ITTest", "IntegrationTest", "Spec", "Specification")
```

### 4.3 Trade-off — over-selection risk

A non-Spock adopter with a class named `OpenAPISpec.java` (a
JSON-schema spec, not a test) — or any project that uses `*Spec` /
`*Specification` for non-Spock test files — will see those files
scanned as tests under the new default. The cost is real, not bounded
to a single-file edge case: a contract-testing codebase with hundreds
of `*Spec.java` files in test dirs would see broader selection across
the board on upgrade.

Mitigations the default does NOT eliminate:

- The naming strategy only matches when there is **also** a same-prefix
  production class being changed; a spurious `*Spec.java` alone doesn't
  cause selection. (Reduces noise but doesn't eliminate it for adopters
  whose changes routinely match same-prefix.)
- Files outside configured `testDirs` are not scanned by `scanTestFqns`.
  (Bounds blast radius to test trees, but doesn't help adopters with
  non-Spock `*Spec` test trees.)
- Adopters can override `testSuffixes` in the DSL to exclude `Spec` /
  `Specification`. (Reactive — adopters discover the noise on the
  first run after upgrade.)

The plan ships default-include because the alternative — every new
Spock adopter manually configuring `testSuffixes += 'Spec'` on first
install — has higher friction for the more common case (Spock is the
load-bearing Groovy use case the issue is motivated by). PR #1's
CHANGELOG entry calls out the default behaviour change explicitly so
non-Spock-but-`*Spec`-using adopters have a clear pointer to the
opt-out before they hit it in CI.

### 4.4 Documentation

`README.md` "Configuration" section gains a Spock callout:

> Spock spec files (`*Spec.groovy`, `*Specification.groovy`) are recognised
> by the naming strategy by default. Override `testSuffixes` if your
> codebase uses non-test `*Spec` classes.

---

## 5. Path-derived FQN behaviour

### 5.1 The `.kt` precedent

Phase 2 PR #1 emits **two** FQNs for every changed `.kt` file: the path-
derived `<package>.<basename>` and a synthetic `<package>.<basename>Kt`
(line 249 of `PathToClassMapper`). The synthetic covers the case where a
top-level Kotlin function is imported from Java as `<basename>Kt`.

### 5.2 Why Groovy doesn't need a synthetic

- Groovy's compiled class name for a script file is `<basename>` directly,
  not `<basename>Groovy` or similar. A top-level method in `Util.groovy`
  becomes a method on class `Util`, not `UtilGroovy`.
- Groovy interop with Java does not require the suffix gymnastics Kotlin's
  `@file:JvmName` ecosystem causes.
- Groovy script classes that delegate to a generated `_run_closure*` inner
  class are only relevant for closure execution, not for type-name
  matching against Java imports.

PR #1 emits **one** FQN per `.kt`-style synthetic call site for `.groovy` /
`.gvy` files: `<package>.<basename>`. No synthetic-name analogue is
needed; the `if (".kt".equals(extensionOf(...)))` branch in
`PathToClassMapper` stays Kotlin-only.

### 5.3 Edge cases

- **Script files without a `package` declaration.** Many `.groovy` scripts
  (especially top-level Gradle DSL helpers) omit `package`. Path-derived
  fallback uses the directory tree — same as Java/Kotlin. No special case.
- **`@PackageScope` on classes.** Annotation, not a different package
  declaration; treated like any other class annotation.
- **Multiple top-level classes per file.** Groovy allows this. Path-derived
  FQN matches the file basename, which may not be the public class.
  `FileMetadata.typeDeclarations` will contain all top-level types after
  PR #2; the path-derived FQN feeds the diff-side bucket only, where the
  basename match is sufficient for naming/directly-changed selection.

---

## 6. Build-script files (`*.gradle`)

### 6.1 Current state

`*.gradle.kts` (Kotlin DSL) is explicitly suppressed from the polyglot
hint at `AffectedTestTask.polyglotExtensionOf:2049`. There is **no
analogous suppression for `*.gradle` (Groovy DSL)**.

Today this doesn't matter because `.groovy` falls into the polyglot
bucket regardless of whether the file is a build script or a production
source — the hint fires either way.

After Phase 3 PR #1 demotes `.groovy` from the polyglot bucket, `.gradle`
build scripts become a different problem: they're parsed as `.groovy`
production sources by the Groovy parser, populating bogus `FileMetadata`
with classes like `build_gradle_$_run_closure1` that nothing in the test
universe imports.

### 6.2 Why no code-level defense is needed

`.gradle` is **not** in `SourceExtensions.EXTENSIONS` and never has been —
`SourceExtensionsTest.java:32` asserts `isSource("build.gradle") == false`.
PR #1's widening adds `.groovy` / `.gvy` only; `.gradle` files stay
excluded by virtue of their extension not matching.

The `.gradle.kts` Kotlin DSL case is similarly closed by extension:
`SourceExtensions.extensionOf("foo.gradle.kts")` returns `.kts`, not `.kt`;
`.kts` is not in `EXTENSIONS`.

The defense is purely documentation-level: PR #1 adds a comment block in
`SourceExtensions.java` near the `EXTENSIONS` constant pinning the
exclusion rationale (`*.gradle` and `*.gradle.kts` build scripts are
excluded by extension; production code uses `.groovy`/`.gvy`/`.kt`/`.java`).
No new code path, no filter, no special-case.

### 6.3 `buildSrc/src/main/groovy/**`

`buildSrc` runs as a separate Gradle build with its own source-set
space. PR #1's `SourceSetAutoDiscovery` walks the consumer's main
project sourcesets, not `buildSrc`'s, so convention-plugin sources
under `buildSrc/src/main/groovy/**` are excluded by default. Adopters
who explicitly add `buildSrc` paths to `sourceDirs` will see those
files surfaced as "changed production classes" in `--explain`; the
documented workaround is `outOfScopeSourceDirs = ['buildSrc/**']`.
A `buildSrcExclusions` knob is a follow-up open question (§14).

---

## 7. Shading namespace layout

Apache Groovy 5's published `groovy-5.0.5.jar` (8,057,694 bytes;
verified against the Maven Central artifact) ships **two** Groovy
namespaces side-by-side: `org.codehaus.groovy.*` (legacy, retained for
binary compatibility) AND `org.apache.groovy.*` (modern, where the
antlr4-based parser actually lives — `org.apache.groovy.parser.antlr4.*`).
Both must be relocated; missing the `org.apache.groovy.*` relocation
produces a `LinkageError` for adopters who also have Apache Groovy on
their classpath at a different patch version (every Spock + Gradle DSL
user).

The jar additionally carries antlr4, asm, and picocli copies under
`groovyjarjar*` prefixes (jarjar'd by Apache Groovy, not by us).
Verified top-level packages in the published jar:

```
groovy
groovyjarjarantlr4
groovyjarjarasm
groovyjarjarpicocli
org    (containing both org.apache.groovy.* and org.codehaus.groovy.*)
```

`groovyjarjarcommonscli` is **not** present — Groovy 5 dropped the
internal commons-cli copy in favour of picocli (`groovy-cli-commons`
is now a separate optional artifact). The plan does not include a
`commonscli` relocation.

### 7.1 Relocations (added to `affected-tests-gradle/build.gradle` shadowJar)

```gradle
// Phase 3 (issue #96) — Apache Groovy AST parser.
relocate 'groovy',                              'io.affectedtests.shadow.groovy'
relocate 'org.apache.groovy',                   'io.affectedtests.shadow.apache.groovy'
relocate 'org.codehaus.groovy',                 'io.affectedtests.shadow.codehaus.groovy'
relocate 'groovyjarjarantlr4',                  'io.affectedtests.shadow.groovy.antlr4'
relocate 'groovyjarjarasm',                     'io.affectedtests.shadow.groovy.asm'
relocate 'groovyjarjarpicocli',                 'io.affectedtests.shadow.groovy.picocli'
```

The exact set of relocations is verified by
`ShadowRelocationFunctionalTest` (extended in PR #2) walking the produced
jar and asserting no top-level `groovy/`, `org/apache/groovy/`,
`org/codehaus/groovy/`, or `groovyjarjar*/` packages leak. The test also
asserts every relocate rule matches **at least one** input class so
spurious / phantom rules surface as test failures rather than silent
no-ops.

### 7.2 Minimize excludes

```gradle
minimize {
    // existing entries
    exclude(dependency('org.apache.groovy:groovy:.*'))
}
```

The `minimize` exclusion prevents `shadowJar`'s reachability analysis from
pruning Groovy classes that are reflectively referenced from `AstBuilder`
or `CompilationUnit` lifecycle code. Same posture as Phase 2 took for the
Kotlin embeddable after `bazel-contrib/rules_kotlin#624` (Missing
extension point) demonstrated the failure mode.

### 7.3 `META-INF/groovy/source.Extensions` resource handling

Apache Groovy 5's published jar includes
`META-INF/groovy/org.codehaus.groovy.source.Extensions` — a Groovy-specific
service-file extensibility mechanism for declaring source extensions.
Shadow's `mergeServiceFiles()` rewrites entries under `META-INF/services/*`
but **does not** touch `META-INF/groovy/*`. After relocation, bytecode
references to the codehaus-prefixed resource path get rewritten to the
shaded prefix, but the actual file in the jar still lives at the
unshaded path.

Phase 3 PR #2 ships:

1. A custom `Transformer` registered on the `shadowJar` task that renames
   `META-INF/groovy/org.codehaus.groovy.*` to
   `META-INF/groovy/io.affectedtests.shadow.codehaus.groovy.*` (and the
   same for `org.apache.groovy.*`).
2. A parse-gate functional test (`GroovyShadowParseGateFunctionalTest`,
   mirroring Phase 2's `ShadowParseGateFunctionalTest`) that loads the
   shaded jar in an isolated classloader and parses a representative
   `.groovy` fixture. If anything reaches for a renamed source-extension
   resource and finds nothing, the parse fails with `IOException` /
   `ClassNotFoundException` and CI rejects the build.

### 7.4 Path-based excludes

Apache Groovy's runtime ships modules we don't need (REPL / `groovysh`
under `groovy/ui/`, Ivy resolver under `groovy/grape/`, AST inspection
UI under `groovy/inspect/`, picocli CLI parser under
`groovyjarjarpicocli/`). These ship in the core jar as a single bundle.

The plan does **not** add path-based excludes by default in PR #2's
initial cut — the JAR-size budget (§7.5) accommodates the unpruned core
jar. If the prototype build measures above the §7.5 hard ceiling,
PR #2 adds excludes in this priority order: `groovy/ui/**`,
`groovy/grape/**`, `groovy/inspect/**`, `groovyjarjarpicocli/**`. Each
exclude is gated by the parse-gate test in §7.3 — pruning that breaks
parser bootstrap surfaces as a CI failure, not a runtime crash.

### 7.5 JAR size budget

Pre-Phase-3 release (v2.2.27): shadow jar measured at ~56 MB on disk.
Apache Groovy 5 core is **7.7 MB unshaded** (verified: `groovy-5.0.5.jar`
is 8,057,694 bytes; 5.0.6 is within ±50 KB).

**Mental model — raw size is a worst-case ceiling, not the expected
delta.** Shadow's class-file rewrite + ZIP recompression typically
yields an on-disk delta materially below the raw jar size of the
input dependency. Phase 2's Kotlin-embeddable case is the closest
precedent: the unshaded `kotlin-compiler-embeddable` is ~50 MB, but
the v2.2.27 shadow jar landed at ~56 MB total — implying Phase 2's
Kotlin delta on top of the pre-Kotlin baseline was substantially
less than 50 MB on disk after compression. Phase 3 cannot assume the
exact Phase-2 ratio holds (Groovy's 7.7 MB is mostly antlr4 +
already-jarjar'd asm, which has a different compression profile from
Kotlin's protobuf + IR), but the directional claim — raw input size
over-estimates final delta — is robust.

**Expected total at PR #2:** somewhere between **~58 MB** (best case:
heavy ZIP-side compression) and **~65 MB** (worst case: raw delta
applied verbatim). The plan budgets the worst case.

**Verification gate (single threshold across §9.4, §10.2, this
section):** PR #2 asserts the shaded jar is **≤ 70 MB total** before
merging — ~5 MB headroom over the worst-case expected total and ~14 MB
above the Kotlin-only baseline. The §10.2 `ShadowJarSizeTest` asserts
this same 70 MB threshold; the §9.4 decision-pause criterion uses the
same threshold. The earlier `+9 MB hard rollback` framing in §9.4 was
incorrect (it conflated the upper end of the expected range with the
rollback threshold) and has been fixed.

**Hard rollback trigger:** if the prototype PR #2 build measures
**> 70 MB**, the shading approach is re-evaluated in the same PR
before merging — possible mitigations are §7.4's path-based excludes,
accepting Phase 3 PR #1 as the permanent answer (and closing #96),
or pinning to Apache Groovy 4.x if 5.0.x is the cause (per §3.7's
fallback trigger).

**Coordinate-posture verification:** the PR #2 build script declares
the Groovy dependency as `compileOnly 'org.apache.groovy:groovy:5.0.x'`
(plus a matching `testImplementation`). A `dependencies` constraint
asserts the runtime classpath does **not** contain `gpars`, `ivy`,
`jansi`, or `xstream` — Apache Groovy's optional features that flip
the size budget if accidentally pulled.

---

## 8. Diagnostics carrier — extend in place

### 8.1 Decision: parallel `GroovyDiagnostics` class + virtual dispatch on `LanguageParser`

An earlier draft of this plan proposed a `PolyglotDiagnostics` rename
of `KotlinDiagnostics` plus a `Language` enum, framed as "saving ~700
LOC vs duplicating per language." A second review pass rejected the
framing on three concrete grounds:

1. **Public API break.** Renaming the file deletes the `KotlinDiagnostics`
   class identifier from the published binary. Downstream code that
   imports `io.affectedtests.core.discovery.KotlinDiagnostics`,
   references `KotlinDiagnostics.EMPTY`, or names the type in test
   assertions breaks at compile-time. A method-level shim
   (`kotlinDiagnostics()` returning a `LanguageMetrics` view) doesn't
   preserve the type identifier and doesn't preserve the static
   surface (`KotlinDiagnostics.EMPTY`, `KotlinDiagnostics.joinFqn(...)`,
   `KotlinDiagnostics.EMBEDDABLE_VERSION`).
2. **Kotlin-shaped fields leak into "polyglot."** `LanguageMetrics`
   carries `pathPackageMismatchCount` and `mismatchSamples` — both
   motivated by Kotlin's `<package>.<basename>Kt` synthetic-name
   ecosystem. Groovy explicitly does not have this (§5.2). The
   "unified" record would contain dead fields for Groovy, and any
   future Phase-4 language whose failure modes don't fit Kotlin's
   shape would push back into the record.
3. **The "save 700 LOC vs duplicate" framing was a strawman.** The
   real alternative isn't "duplicate `KotlinDiagnostics` 1:1" — it's
   "ship a parallel `GroovyDiagnostics` class and introduce a virtual
   dispatch hook on `LanguageParser` so `ProjectIndex` doesn't need
   parallel `instanceof` branches." That's the same `instanceof`-removal
   benefit the unification claimed, without any of the public API
   churn. Net new code is comparable; net public API risk is zero.

Phase 3 ships the **extend-in-place** shape. `KotlinDiagnostics` stays
as it is. PR #2 introduces a sibling `GroovyDiagnostics` class with
the same general shape but Groovy-specific semantics, plus a
`LanguageParser`-level virtual dispatch hook so neither class needs
to be referenced by name in `ProjectIndex`.

If a third language lands in Phase 4 with similar diagnostic needs,
that's the moment to consider a unification refactor — by then there
will be two concrete carriers to factor across, instead of one
shipped + one hypothetical.

### 8.2 `GroovyDiagnostics` shape

Public API mirrors `KotlinDiagnostics` structurally with Groovy-specific
field names where the semantics differ:

```java
public final class GroovyDiagnostics {
    public static final String GROOVY_VERSION = "5.0.6";   // sourced from Apache Groovy module metadata at build time
    public static final int SAMPLE_LIMIT = 10;
    public static final GroovyDiagnostics EMPTY = new GroovyDiagnostics();

    public GroovyDiagnostics() { … }

    public static String joinFqn(String packageName, String typeName) { … }

    public int astMappedCount() { … }
    public int parseFailureCount() { … }
    public int pathPackageMismatchCount() { … }   // Groovy: path-derived basename ≠ declared single top-level class
    public Set<String> astMappedFqnSamples() { … }
    public Collection<MismatchSample> mismatchSamples() { … }
    public String parserLoadFailureCause() { … }
    public boolean isEmpty() { … }

    public record MismatchSample(Path file, String parsedPackage, String pathDerivedPackage) { … }

    // Package-private mutators (called via LanguageParser virtual dispatch — see §8.3):
    void recordAstMapped(String fqn) { … }
    void recordParseFailure() { … }
    void recordPathPackageMismatch(Path file, String parsed, String pathDerived) { … }
    void recordParserLoadFailure(String cause) { … }
}
```

The `EMPTY`-singleton-immutable contract is preserved: every mutator
short-circuits if `this == EMPTY`. Thread-safe storage shape
(`AtomicInteger` counters, `Collections.synchronizedSet` samples,
`AtomicReference<String>` failure cause) mirrors `KotlinDiagnostics`.

`GROOVY_VERSION` is sourced at build time from the resolved
`org.apache.groovy:groovy` module metadata so a Dependabot bump of
the dependency updates the rendered version string without a
hand-edit (mirroring how Phase 2's `KotlinDiagnostics.EMBEDDABLE_VERSION`
is wired against `kotlin-compiler-embeddable`).

### 8.3 Virtual dispatch on `LanguageParser`

Phase 3 PR #2 adds two default methods to the existing `LanguageParser`
interface (no signature break for existing implementations):

```java
default void recordDiagnostics(FileMetadata md, Path file, String pathDerivedPackage) {
    // Java parser: no diagnostics. Default no-op.
}

default void replayWarmCacheDiagnostics(FileMetadata cachedMd, Path file) {
    // Default no-op.
}
```

`KotlinLanguageParser` and `GroovyLanguageParser` each override these
with their own diagnostics-recording logic. The carrier instance
(`KotlinDiagnostics` or `GroovyDiagnostics`) is the constructor-injected
field already present on each parser; the default-method signatures
deliberately do NOT mention either carrier type so the interface stays
language-agnostic.

`ProjectIndex.fileMetadata` and `ProjectIndex.seedFileMetadata` swap
their existing `instanceof KotlinLanguageParser` branches for virtual
calls:

```java
parser.recordDiagnostics(parsedMetadata, file, pathDerivedPackage);
// ... and at the warm-cache rehydration site:
parser.replayWarmCacheDiagnostics(cachedMetadata, file);
```

This eliminates the existing `instanceof` chain in `ProjectIndex`
(one of the recurring lints from the Phase 2 review) and lets future
languages plug in by implementing the interface only — no engine code
change.

### 8.4 Renderer surface

`AffectedTestTask` keeps its existing Kotlin-named methods unchanged
(`appendKotlinMappingHints`, `appendKotlinAstHints`, `anyKotlin()`)
and gains parallel Groovy-named methods (`appendGroovyMappingHints`,
`appendGroovyAstHints`, `anyGroovy()`). The four pinned `--explain`
strings live in `AffectedTestTask` constants per language —
string-stability is a per-language renderer concern, not a carrier
concern, so per-language string contracts remain independently
versioned. PR #1 ships the `appendGroovyMappingHints` scaffold; PR #3
fills in `appendGroovyAstHints` with the four pinned strings.

A future Phase 4 unification refactor could introduce
`appendPolyglotMappingHints(language)` etc. — but only when there is
concrete duplication to factor. With one shipped carrier (Kotlin) +
one new carrier (Groovy), parallel methods carry less abstraction
weight than a polymorphic dispatcher.

### 8.5 Net code accounting

Honestly accounted, the extend-in-place shape vs. an unrealistic
"duplicate the carrier and the `instanceof` chain" baseline:

| Surface | Naive parallel-class | Extend-in-place (this plan) |
| --- | --- | --- |
| Carriers | `KotlinDiagnostics` + new `GroovyDiagnostics` (~350 LOC) | Same |
| Carrier tests | `KotlinDiagnosticsTest` + new `GroovyDiagnosticsTest` (~400 LOC) | Same |
| Renderer methods | 6 (per-language pair × 3 surfaces) | Same |
| `ProjectIndex` `instanceof` chain | 2 parser classes × 2 sites = 4 branches | **0** (virtual dispatch via default methods) |
| Public API break | None | None |

**Net new code (Groovy specifically):** ~750 LOC across carrier +
tests + renderer. The `instanceof`-removal benefit is delivered for
free by the virtual-dispatch hook, which is ~10 LOC of interface
default methods + ~20 LOC of override per parser.

**Phase 4 (a third language):** ~750 LOC again per language, OR a
unification refactor at that point. The decision is an honest one
made when there are two carriers to factor; Phase 3 doesn't pre-commit
either way.

---

## 9. Rollout — three PRs

### 9.1 PR #1 — Path-derived Groovy mapping + Spock suffixes + sourceset auto-discovery

**Scope:**

- `SourceExtensions.EXTENSIONS` += `.groovy`, `.gvy`.
- **`SourceSetAutoDiscovery.from()` extension** — call
  `ss.getExtensions().findByType(GroovySourceDirectorySet.class)` and
  iterate its `getSrcDirs()` when non-null; ignore otherwise.
  `findByType` returns `null` cleanly on adopters without the Groovy
  plugin applied — no reflection, no `ClassNotFoundException` guard.
  `GroovySourceDirectorySet` has shipped on `gradleApi()` since Gradle
  7.1, comfortably below the plugin's Gradle floor. This is the
  load-bearing change for §0.5's "approach A is sufficient" premise —
  without it, Spock specs under `src/test/groovy` never enter the
  test universe.
- `AffectedTestsConfig.Builder.testSuffixes` default += `Spec`,
  `Specification`. Plumb into `AffectedTestsPlugin` convention.
- Drop `.groovy`, `.gvy` from `AffectedTestTask.polyglotExtensionOf`.
- Add a new `appendGroovyMappingHints` method to `AffectedTestTask`
  (parallel to existing `appendKotlinMappingHints`, **not** a rename
  of it — see §8.4). Renders the Groovy mapping hint string. The
  existing Kotlin-named methods (`appendKotlinMappingHints`,
  `appendKotlinAstHints`, `anyKotlin()`) are unchanged in PR #1; this
  keeps PR #1 self-contained even if Phase 3 stops here.
- Update `AffectedTestTaskExplainFormatTest` polyglot section: flip
  `.groovy` polyglot-line assertion from "present" to "absent"; add a
  new assertion for the Groovy mapping hint string.
- New Cucumber feature `11-issue-96-groovy-path-derived-mapping.feature`
  (mirror of `09-issue-76-kotlin-path-derived-mapping.feature`).
- New scenario `G04 — src/test/groovy auto-discovered when groovy
  plugin applied` — fixture project applies `groovy` + the affected-tests
  plugin with no DSL config; asserts `*Spec.groovy` selection works
  out of the box.
- New unit test `SourceSetAutoDiscoveryGroovyTest` covering the
  `findByType(GroovySourceDirectorySet)` lookup: returns `null` (no
  NPE) when the Groovy plugin is absent, returns the source dirs when
  present.
- Update unit tests asserting `.groovy` is non-source (flip from
  `assertFalse` to `assertTrue` in `SourceExtensionsTest`,
  `LanguageParsersTest`).
- Cache schema bump 4 → 5; loader rejects v4 snapshots; new test
  `prePr1Phase3SchemaSnapshotInvalidatesAndForcesFullRescan` mirrors
  the existing v3→v4 test.
- README "Known limitations" entry for Groovy retired (matching the
  Kotlin retirement in Phase 2 PR #1).

**Out of scope:**

- No Groovy parser dependency.
- No `groovyEnabled` flag yet (path-derived mapping has no flag, same
  as Phase 2 PR #1).
- No AST-driven strategy parity for Groovy.
- No `Language` enum or polyglot renames (deferred — see §8.1). PR #1
  ships only the new Groovy method; existing Kotlin-named methods stay
  as-is. If Phase 3 stops at PR #1, no abstraction debt is left behind.

**Pinned `--explain` string emitted by PR #1:**

```
Hint:            Groovy source mapped via filename only; AST-driven strategies skipped (issue #96).
```

(String stability contract — see Phase 2 §9 for the rationale;
identical treatment for Groovy. PR #1 ships only the path-derived
hint above. PR #2's `groovyEnabled` opt-in pointer and PR #3's
default-on AST hints are added in those PRs only — see §9.3 / §9.5 —
so PR #1's `--explain` output never references a knob that doesn't
yet exist.)

### 9.2 Decision pause after PR #1

Honest framing first: the OSS plugin's adopter base is small and
adopter-feedback velocity in a 7–14 day window is realistically
near-zero — Phase 2's PRs #92–#95 all merged within a ~24-hour window
without any external signal driving the cadence. A "decision gate"
that depends on adopters filing issues isn't a gate, it's a wait for
nothing.

What §9.2 actually is: a **maintainer-driven pause** before committing
to PR #2's dependency cost. The pause has two concrete maintainer
checks:

1. **Internal Spock fixture audit.** The maintainer constructs a
   representative Spock-on-Java fixture (one production class change,
   one Spock spec testing it via simple-name `extends Specification`,
   one transitive Java→Java→Spock chain) and runs PR #1 against it.
   If the naming strategy + sourceset auto-discovery select the right
   tests for the dominant cases, PR #1 has covered the issue's
   high-frequency surface. If transitive selection through Groovy
   traits / interfaces under-selects, that's the demand for PR #2.
2. **Phase-2 cross-language carry-over check.** Re-run the existing
   Kotlin AST functional fixtures against PR #1's wider extension set
   to confirm no regression. Cheap, mechanical, ~5 minutes.

What the gate is **not**:

- Not a wait for external adopter signal (won't arrive on the budget).
- Not a build-scan tag (an OSS plugin has no observability into
  adopter Develocity instances, so an in-plugin "tag" emits zero data
  to the maintainer; an earlier draft of this section claimed
  otherwise — that claim is dropped).
- Not adopter-driven evidence collection (the `--explain` hint in §9.1
  is correct documentation, but the funnel "adopter runs `--explain` →
  reads the hint → comments on #96" yields near-zero in two weeks for
  a plugin with this adopter volume).

The "decision pause" can clear in a single sitting if the maintainer's
fixture audit clearly demonstrates the under-selection case. Budget
is up to one release cycle (≈ 7–14 days) but compresses to whatever
duration the audit actually needs. If no concrete under-selection
case surfaces, Phase 3 closes after PR #1 with a "won't fix unless
asked" note, mirroring the Scala posture on the parent epic.

If an adopter does file a concrete under-selection issue against
issue #96 in the meantime, that's a tiebreaker — but the plan does
not depend on it arriving.

### 9.3 PR #2 — `GroovyLanguageParser` + shading (behind `groovyEnabled` DSL flag)

**Scope:**

- New `GroovyDiagnostics` class + `GroovyDiagnosticsTest` (sibling of
  `KotlinDiagnostics`; §8.2). `KotlinDiagnostics` is **not** renamed —
  Phase 2's public API is preserved unchanged. `GROOVY_VERSION`
  constant sourced from build-time module metadata.
- New `LanguageParser.recordDiagnostics(...)` and
  `replayWarmCacheDiagnostics(...)` default-method hooks (§8.3).
  Default no-op; `KotlinLanguageParser` and `GroovyLanguageParser`
  each override with their language-specific recording. Compatibility
  refactor on the Kotlin side — same behaviour, fewer `instanceof`
  branches.
- New `GroovyLanguageParser` implementing `LanguageParser` with
  `recordDiagnostics` override. Parse via
  `CompilationUnit.compile(Phases.CONVERSION)` (committed in §3.2 — no
  in-PR re-litigation). Includes `GroovySupertypeResolver` (~30 LOC)
  walking `ModuleNode.getImports()` to FQ-resolve simple-name
  supertypes (§3.2).
- `AffectedTestsConfig.groovyEnabled` field + builder + `configHash`
  inclusion (DSL flag — not a system property; see §1 for the
  rationale).
- `AffectedTestsExtension.getGroovyEnabled()` + plugin convention
  `false` (opt-in until PR #3).
- `LanguageParsers.forConfig` — register `GroovyLanguageParser` when
  `config.groovyEnabled()` is true.
- `ProjectIndex.fileMetadata` and `seedFileMetadata` — refactored to
  call the new virtual `recordDiagnostics` / `replayWarmCacheDiagnostics`
  hooks; `instanceof KotlinLanguageParser` branches deleted in the
  same PR.
- `AffectedTestsResult` — new `groovyDiagnostics()` accessor returning
  the new carrier (defaults `GroovyDiagnostics.EMPTY` for backward
  compatibility — same shape Phase 2 PR #4 introduced for Kotlin).
  Existing `kotlinDiagnostics()` accessor unchanged.
- Update PR #1's `appendGroovyMappingHints` rendering site to also emit
  a forward-looking opt-in pointer line when the engine sees
  `groovyEnabled=false` AND a `.groovy` file participated in the diff:
  ```
  Hint:            To opt into Groovy AST-driven strategies,
                   set `affectedTests { groovyEnabled = true }`
                   (Phase 3 PR #2; default-off until PR #3).
  ```
- `affected-tests-gradle/build.gradle` — Groovy dependency
  (`compileOnly` + `testImplementation`) + shadow relocations +
  minimize excludes + custom `META-INF/groovy/source.Extensions`
  transformer (Section 7).
- Extend `ShadowRelocationFunctionalTest` to assert no top-level
  `groovy/`, `org/codehaus/groovy/`, `org/apache/groovy/`,
  `groovyjarjar*/` leaks.
- Add `ShadowedSourceExtensionsParseGateTest` — instantiate the
  shaded `CompilationUnit` from `:affected-tests-gradle:shadowJar`
  output, parse a fixture spec, assert no
  `MissingExtensionException` (§7.3).
- Add `ShadowJarSizeTest` — asserts the shadow JAR delta against the
  v2.2.27 baseline is within +7..+9 MB and the absolute total is
  under 70 MB (§7.5 budget).
- Cache schema bump 5 → 6 (parser-output shape includes Groovy-derived
  rows; `configHash` gains `|groovyEnabled:<bool>` term).
- Unit tests: `GroovyLanguageParserTest`, `GroovyDiagnosticsTest`,
  `ProjectIndexTest` Groovy AST coverage,
  `ProjectIndexCacheTest` schema 6 round-trip + v5 → invalidate.

**Out of scope:**

- No default-on flip.
- No `--explain` AST hints (still gated on `groovyEnabled` — adopters
  who opt in get nothing extra in `--explain` beyond the opt-in
  pointer string above; the four pinned AST hints land in PR #3).

### 9.4 Decision pause after PR #2

Same maintainer-driven shape as §9.2: one release cycle on the
`groovyEnabled=true` opt-in flag before flipping the default in PR #3.
No external-adopter dependence; the maintainer runs the internal
Spock-heavy fixture project with the flag on, exercises the three
AST-driven strategies, and confirms no regressions. The "regression"
bar specifically includes:

- No new `DISCOVERY_INCOMPLETE` situations on previously-clean runs.
- Shaded JAR delta stays within the §7.5 budget (+7 to +9 MB expected;
  >70 MB total triggers rollback per §7.5).
- Spock fixture: changing a `Foo.java` selects `FooSpec.groovy` AND
  `BarSpec.groovy` (transitive via `extends FooSpec`) under all
  three AST-driven strategies plus naming.

### 9.5 PR #3 — Default-on + `--explain` AST hints + functional tests

**Scope:**

- `AffectedTestsConfig.groovyEnabled` default flips to `true`.
- `AffectedTestsExtension.getGroovyEnabled()` convention flips to `true`.
- Add new `appendGroovyAstHints(GroovyDiagnostics)` method on
  `AffectedTestTask`, parallel to existing `appendKotlinAstHints` —
  rendering four pinned strings (constants on `AffectedTestTask`):

  1. `Hint:            Groovy parser failed to load: {cause}. Treating .groovy / .gvy files as unparseable for this run.`
  2. `Hint:            Groovy file failed to parse with Apache Groovy {version}; counted into DISCOVERY_INCOMPLETE.`
  3. `Hint:            Groovy file {path} declares package {parsed} but path-derives to {path-derived}; AST-driven strategies use the declared package, naming strategy uses the path-derived FQN.`
  4. `Hint:            Groovy source AST-mapped to FQN {fqn}.`

  Plus truncation tails matching the Kotlin pattern.
- Add new `anyGroovy()` predicate on `AffectedTestTask`, parallel to
  existing `anyKotlin()`. The render site decides whether to call
  `appendKotlinAstHints` or `appendGroovyAstHints` (or both) based on
  which predicate fires.
- Drop the PR-#2 forward-looking opt-in pointer line (§9.3) — the flag
  is now default-on, so the pointer is no longer meaningful. PR #3's
  CHANGELOG entry calls out the string removal explicitly so adopters
  pinning on it know.
- New Cucumber feature `12-issue-96-groovy-ast.feature` covering the
  three AST-driven strategies, the four `--explain` hints,
  parse-failure fallback, package mismatch hint, and parser bootstrap
  failure path. **Specific Spock-trait scenario (G10-T1):** changes
  to a Groovy trait ripple to specs that mix it in via `implements`
  — exercises `GroovySupertypeResolver`'s simple-name → FQ resolution
  at `CompilePhase.CONVERSION` (§3.2).
- Apache Groovy 6.0 smoke matrix scenario per §3.7 (only runs if the
  matrix job is enabled in CI; otherwise gated to a follow-up).
- README known-limitations Groovy block expands to include the four
  pinned strings + the `groovyEnabled = false` escape hatch.
- CHANGELOG entry summarising the default-on flip + the four new
  pinned strings + the dropped opt-in pointer string.

**Pinned strings remain stable across releases** once PR #3 lands (same
contract as Phase 2's four Kotlin strings, see Phase 2 §9).

---

## 10. Verification gates (per PR)

### 10.1 PR #1 gates

| Gate | Test |
| --- | --- |
| `.groovy` / `.gvy` recognised by `SourceExtensions` | `SourceExtensionsTest` flipped assertions |
| Path-derived FQN for `.groovy` | New `PathToClassMapperTest` Groovy block (mirror Kotlin block) |
| Spock spec selected for changed `Foo.java` | Cucumber: `11-issue-96-groovy-path-derived-mapping.feature` G01 |
| `.groovy` change → `SELECTED`, not `UNMAPPED_FILE` | Cucumber G02 |
| `--explain` no longer emits polyglot line for `.groovy` | `AffectedTestTaskExplainFormatTest` flipped |
| `--explain` emits "mapped via filename only" hint via `appendGroovyMappingHints` | Cucumber G03 |
| Existing `appendKotlinMappingHints` and `anyKotlin()` unchanged | `AffectedTestTaskExplainFormatTest` Kotlin block (existing tests still pass) |
| `src/test/groovy` auto-discovered via `findByType(GroovySourceDirectorySet)` when `groovy` plugin applied | Cucumber G04 + `SourceSetAutoDiscoveryGroovyTest` |
| `findByType` returns `null` cleanly on adopters without the Groovy plugin (no NPE) | `SourceSetAutoDiscoveryGroovyTest.returnsNullWhenGroovyPluginAbsent` |
| Cache v4 snapshot invalidates on first run | `ProjectIndexCacheTest.prePr1Phase3SchemaSnapshotInvalidatesAndForcesFullRescan` |
| `*.gradle` and `*.gradle.kts` build scripts not parsed | `SourceExtensionsTest` (existing `build.gradle` row stays `false`) + new `build.gradle.kts` row |

### 10.2 PR #2 gates

| Gate | Test |
| --- | --- |
| `GroovyLanguageParser` parses a typical Groovy class | `GroovyLanguageParserTest` |
| `GroovySupertypeResolver` FQ-resolves simple-name supertypes via single-type imports | `GroovyLanguageParserTest.resolvesSimpleNameSupertypeViaImport` |
| `GroovySupertypeResolver` falls back to star imports for unresolved simple names | `GroovyLanguageParserTest.resolvesSimpleNameSupertypeViaStarImport` |
| `GroovyDiagnostics` thread-safety | `GroovyDiagnosticsTest` concurrency stress |
| `GroovyDiagnostics.EMPTY` immutability | `GroovyDiagnosticsTest.emptySingletonRejectsAllMutations` |
| `KotlinDiagnostics` public API unchanged (no rename) | `KotlinDiagnosticsTest` (existing test surface still passes) |
| `instanceof KotlinLanguageParser` branches removed from `ProjectIndex` | `ProjectIndex` source has zero `instanceof LanguageParser` matches |
| `groovyEnabled=true` populates `FileMetadata` | `ProjectIndexTest` Groovy AST cases |
| Cache schema 6 round-trip + `configHash` `groovyEnabled` term | `ProjectIndexCacheTest` Groovy round-trip + v5 → invalidate |
| Forward-looking opt-in pointer rendered when `groovyEnabled=false` AND `.groovy` in diff | `AffectedTestTaskExplainFormatTest` Groovy opt-in pointer block |
| Shaded jar has no top-level `groovy/`, `org/codehaus/groovy/`, `org/apache/groovy/`, `groovyjarjar*/` | `ShadowRelocationFunctionalTest` extended |
| Shaded jar parses a fixture Spock spec without `MissingExtensionException` | `ShadowedSourceExtensionsParseGateTest` |
| Shaded jar size delta within +7..+9 MB and total under 70 MB | `ShadowJarSizeTest` (§7.5) |

### 10.3 PR #3 gates

| Gate | Test |
| --- | --- |
| Default-on selects Spock specs for all three AST-driven strategies | `12-issue-96-groovy-ast.feature` G10-N1 / G10-I1 / G10-T1 |
| Trait-mixin transitive selection (exercises `GroovySupertypeResolver` simple-name → FQ resolution) | G10-T1 trait scenario |
| All four pinned `--explain` strings render byte-for-byte from `appendGroovyAstHints` | `AffectedTestTaskExplainFormatTest` Groovy AST block |
| Parser load failure → `parserLoadFailureCause` populated, no double-hint | `12-issue-96-groovy-ast.feature` G10-LOAD-FAIL |
| Path/package mismatch surfaces in `--explain` | G10-MISMATCH |
| Warm-cache `--explain` parity (signal not lost across runs) | G10-WARM-CACHE |
| Apache Groovy 6.0 smoke parses fixture specs (if matrix enabled) | CI matrix job |
| Backward-compatible `AffectedTestsResult` constructors + `kotlinDiagnostics()` and `groovyDiagnostics()` accessors | `AffectedTestsEngineTest` deprecated-ctor calls |
| PR-#2 forward-looking opt-in pointer string is removed (default-on) | `AffectedTestTaskExplainFormatTest` Groovy opt-in pointer block (asserts absent) |

---

## 11. Issue closure

Issue [#96](https://github.com/vedanthvdev/affected-tests/issues/96) closes
when **either**:

- All three PRs land (Phase 3 ships at parity with Phase 2), OR
- PR #1 lands and the §9.2 maintainer-driven pause concludes B-level
  parity is unjustified — issue closes with a "won't fix unless asked"
  comment documenting the unbuilt `groovyEnabled` opt-in path and the
  pinned PR #1 string.

The parent epic [#47](https://github.com/vedanthvdev/affected-tests/issues/47)
already closed alongside #76 — Phase 3 doesn't reopen it.

---

## 12. Open questions for the implementer

These are genuinely undecided and the plan does not pre-decide them. Any of
these could become a Phase 3 follow-up issue if they prove load-bearing.

1. **`ImplementationStrategy` for Groovy traits with multi-trait
   conflict resolution.** Single-trait `implements`-style mixin is
   covered by §3.2's `CompilationUnit` choice and by PR #3 G10-T1.
   Multi-trait conflict resolution (where Groovy uses a linearization
   algorithm Java doesn't have) may surface edge cases the plan has
   not specified — file a follow-up if PR #3 fixtures uncover one
   rather than expanding scope.
2. **`TransitiveStrategy` Java-name bias.** The tier-0 text heuristic
   in `TransitiveStrategy.buildSimpleNameIndex` tokenises for
   "uppercase-leading identifiers — Java's type-name shape." Groovy
   `def` declarations and lowercase script vars may under-match. Plan
   does not block on this; PR #3 functional tests will flag if it
   matters in practice.
3. **`buildSrc/src/main/groovy/**` selection rules.** §6.3 documents
   the limitation: Groovy convention plugins are picked up as application
   source when the parent project applies the affected-tests plugin to
   `buildSrc`. The workaround (do not apply the plugin to `buildSrc`)
   is documented; whether to add a `buildSrcExclusions` knob is a
   future question driven by adopter demand.
4. **Apache Groovy 6.0 cutover.** §3.7 commits to a passive matrix
   smoke job once 6.0 GA ships. Whether to bump the runtime to 6.x
   in a future patch (vs holding at 5.0.x) is decided then with
   data, not now.
5. **DSL-only scope for `groovyEnabled` opt-in.** §1 commits to a
   DSL flag without a parallel `-P` / system-property override.
   Whether to add a project property override (e.g.
   `-Pgroovy.affected.tests.enabled=true`) for ephemeral CI overrides
   is a Phase 4 question, gated on adopter demand.

---

## 13. References

- Phase 2 plan: [`docs/PHASE-2-KOTLIN-AST.md`](./PHASE-2-KOTLIN-AST.md)
- Phase 2 tracking issue (closed): [#76](https://github.com/vedanthvdev/affected-tests/issues/76)
- Parent epic (closed): [#47](https://github.com/vedanthvdev/affected-tests/issues/47)
- Phase 3 tracking issue: [#96](https://github.com/vedanthvdev/affected-tests/issues/96)
- Apache Groovy 5.0 release: https://groovy.apache.org/download.html
- `CompilationUnit` Javadoc: https://docs.groovy-lang.org/next/html/gapi/org/codehaus/groovy/control/CompilationUnit.html
- Apache Groovy 5.0.6 on Maven Central: https://central.sonatype.com/artifact/org.apache.groovy/groovy/5.0.6
- `GroovySourceDirectorySet` Javadoc (Gradle 7.1+): https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/GroovySourceDirectorySet.html
- Relevant code: `affected-tests-core/src/main/java/io/affectedtests/core/discovery/{SourceExtensions,LanguageParser,LanguageParsers,KotlinLanguageParser,KotlinDiagnostics,ProjectIndex,ProjectIndexCache,SourceFileScanner}.java`
- Mapping site: `affected-tests-core/src/main/java/io/affectedtests/core/mapping/PathToClassMapper.java`
- Hint sites: `affected-tests-gradle/src/main/java/io/affectedtests/gradle/AffectedTestTask.java#{appendUnmappedFileHint,appendKotlinMappingHints,appendKotlinAstHints,polyglotExtensionOf,anyKotlin}` (Phase 3 adds parallel `appendGroovyMappingHints` / `appendGroovyAstHints` / `anyGroovy` siblings; existing names unchanged)
- Sourceset auto-discovery site: `affected-tests-gradle/src/main/java/io/affectedtests/gradle/SourceSetAutoDiscovery.java`
- Shading site: `affected-tests-gradle/build.gradle#shadowJar`

---

## 14. Explicit deferrals

Items the plan deliberately does NOT ship in Phase 3, alongside their
follow-up condition. (Findings raised during pre-publication review
are reflected in the relevant sections directly; the resolved-finding
narrative is in git history rather than carried as a permanent fixture
here.)

- **Apache Groovy 6.x cutover.** §3.7 commits to a passive matrix
  smoke once 6.0 GA ships; full runtime cutover is gated on adopter
  signal at that point. Phase 3 ships on 5.0.x.
- **`-P` / system-property override for `groovyEnabled`.** Phase 3
  ships DSL-only to match Kotlin. A project-property override (e.g.
  `-Pgroovy.affected.tests.enabled=true`) for ephemeral CI overrides
  is a Phase 4 question, gated on adopter demand (§12.5).
- **`buildSrc` convention-plugin handling.** §6.3 documents the
  known limitation with a workaround (do not apply the plugin to
  `buildSrc`). A `buildSrcExclusions` knob would be a §12.3
  follow-up driven by adopter demand, not a Phase 3 deliverable.
- **`PolyglotDiagnostics` unification refactor.** §8.1 ships parallel
  `KotlinDiagnostics` + `GroovyDiagnostics` carriers with virtual
  dispatch on `LanguageParser`, deferring unification to Phase 4
  (when there are two concrete carriers to factor across). Phase 3
  PR #2 does NOT rename `KotlinDiagnostics`.
- **`SEMANTIC_ANALYSIS`-phase parsing.** §3.2 ships at
  `CompilePhase.CONVERSION` with a small `GroovySupertypeResolver`
  helper for simple-name supertype resolution. Bumping to
  `SEMANTIC_ANALYSIS` is a follow-up only if the manual resolver
  proves insufficient against adopter-reported edge cases (wildcard
  imports across multi-file fixtures, Spock metaclass mixins).
- **Spock 1.x compatibility.** Plan targets Spock 2.x (the Groovy
  3.0+ ABI line). Spock 1.x adopters are out of scope; report issues
  if encountered.
- **`Spec`/`Specification` default-include over-selection.** §4.3
  acknowledges adopters with non-Spock `*Spec.java` files in test
  trees may see broader selection on upgrade. The `testSuffixes`
  DSL knob is the documented escape hatch; PR #1 CHANGELOG calls
  this out as a default behaviour change.

