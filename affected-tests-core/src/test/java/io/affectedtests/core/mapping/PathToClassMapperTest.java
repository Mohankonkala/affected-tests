package io.affectedtests.core.mapping;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.mapping.PathToClassMapper.MappingResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathToClassMapperTest {

    private final AffectedTestsConfig config = AffectedTestsConfig.builder().build();
    private final PathToClassMapper mapper = new PathToClassMapper(config);

    @Test
    void mapsProductionJavaFileToFqn() {
        Set<String> changed = Set.of("src/main/java/com/example/service/FooBar.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.service.FooBar"));
        assertTrue(result.testClasses().isEmpty());
    }

    @Test
    void mapsTestJavaFileToFqn() {
        Set<String> changed = Set.of("src/test/java/com/example/service/FooBarTest.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.testClasses().contains("com.example.service.FooBarTest"));
        assertTrue(result.productionClasses().isEmpty());
    }

    @Test
    void mapsMultiModulePaths() {
        Set<String> changed = Set.of(
                "api/src/main/java/com/example/api/FooDto.java",
                "application/src/test/java/com/example/FooDtoTest.java"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.api.FooDto"));
        assertTrue(result.testClasses().contains("com.example.FooDtoTest"));
    }

    @Test
    void nonJavaFilesAreRecordedAsUnmapped() {
        // README.md is deliberately omitted from this test: v2 added
        // "**}{@code /*.md}" to the default ignore list, so a markdown
        // file no longer contributes to the unmapped bucket. See
        // {@link #markdownRoutesToIgnoredBucketOnDefaults} for the
        // positive-path assertion on that.
        Set<String> changed = Set.of(
                "build.gradle",
                "src/main/resources/application.yml"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
        assertEquals(changed, result.unmappedChangedFiles(),
                "Non-Java files must be surfaced as unmapped so the engine can escalate to runAll");
    }

    @Test
    void markdownRoutesToIgnoredBucketOnDefaults() {
        // v2 default ignorePaths contains "**}{@code /*.md}" so a pure
        // markdown diff routes through Situation.ALL_FILES_IGNORED rather
        // than dragging the engine into the unmapped-file safety net.
        Set<String> changed = Set.of("README.md", "docs/guide.md");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "Markdown must not feed the unmapped bucket under v2 defaults");
        assertEquals(changed, result.ignoredFiles(),
                "Markdown must land in the ignored bucket so the engine can route to ALL_FILES_IGNORED");
    }

    @Test
    void outOfScopeTestDirsFileRoutesToOutOfScopeBucket() {
        // Regression for the api-test use case: a diff that only touches
        // api-test sources must be surfaced as "out of scope" so the
        // engine can skip the unit-test dispatch entirely, instead of
        // trying to map the file as an in-scope test class. The test
        // directory entries are the on-disk paths the plugin sees
        // (no trailing slash, one per concrete sibling dir) so mixed
        // diffs that touch {@code /java} and {@code /resources} both
        // route to the same bucket.
        AffectedTestsConfig oosConfig = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(java.util.List.of(
                        "api-test/src/test/java",
                        "api-test/src/test/resources"))
                .build();
        PathToClassMapper oosMapper = new PathToClassMapper(oosConfig);

        Set<String> changed = Set.of(
                "api-test/src/test/java/com/example/api/FooSteps.java",
                "api-test/src/test/resources/feature.feature");
        MappingResult result = oosMapper.mapChangedFiles(changed);

        assertTrue(result.testClasses().isEmpty(),
                "Out-of-scope test file must not become an in-scope test class");
        assertEquals(changed, result.outOfScopeFiles(),
                "Out-of-scope diff must populate the out-of-scope bucket");
        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "An out-of-scope file must not leak into the unmapped bucket");
    }

    @Test
    void outOfScopeTestDirsAcceptGlobPattern() {
        // Users reach for glob patterns (e.g. "api-test/**") as often as
        // literal prefixes because the `ignorePaths` knob one line above
        // accepts globs too. The matcher must accept both shapes and fail
        // closed on patterns it can't interpret, otherwise users silently
        // lose their out-of-scope safety net while the config file still
        // looks intentional.
        AffectedTestsConfig globConfig = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(java.util.List.of("api-test/**"))
                .build();
        PathToClassMapper globMapper = new PathToClassMapper(globConfig);

        Set<String> changed = Set.of(
                "api-test/src/test/java/com/example/api/FooSteps.java",
                "api-test/src/test/resources/feature.feature");
        MappingResult result = globMapper.mapChangedFiles(changed);

        assertEquals(changed, result.outOfScopeFiles(),
                "'api-test/**' glob must route every api-test file to the out-of-scope bucket");
        assertTrue(result.testClasses().isEmpty());
        assertTrue(result.unmappedChangedFiles().isEmpty());
    }

    @Test
    void outOfScopeTestDirsGlobMatchesNestedModules() {
        // Real multi-module repos put api-test under a services/ parent,
        // so the matcher must support a `**/` prefix that crosses any
        // number of directories — exactly the shape users copy from
        // Ant/Gradle docs without thinking. A literal-prefix-only
        // implementation would quietly miss the nested case.
        AffectedTestsConfig globConfig = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(java.util.List.of("**/api-test/**"))
                .build();
        PathToClassMapper globMapper = new PathToClassMapper(globConfig);

        Set<String> changed = Set.of(
                "services/orders/api-test/src/test/java/com/example/OrderSteps.java");
        MappingResult result = globMapper.mapChangedFiles(changed);

        assertEquals(changed, result.outOfScopeFiles(),
                "Nested api-test dir must match the '**/api-test/**' glob");
    }

    @Test
    void outOfScopeSourceDirsAcceptGlobPattern() {
        // Symmetry: whatever shape outOfScopeTestDirs accepts,
        // outOfScopeSourceDirs must accept too. Users configure both at
        // the same time for mono-repo setups where a whole module is
        // carved out of the unit/integration dispatch.
        AffectedTestsConfig globConfig = AffectedTestsConfig.builder()
                .outOfScopeSourceDirs(java.util.List.of("legacy-service/**"))
                .build();
        PathToClassMapper globMapper = new PathToClassMapper(globConfig);

        Set<String> changed = Set.of(
                "legacy-service/src/main/java/com/example/LegacyFoo.java");
        MappingResult result = globMapper.mapChangedFiles(changed);

        assertEquals(changed, result.outOfScopeFiles(),
                "'legacy-service/**' glob on outOfScopeSourceDirs must route under "
                        + "legacy-service/ to the out-of-scope bucket");
        assertTrue(result.productionClasses().isEmpty(),
                "A file bucketed as out-of-scope must not also be mapped as production");
    }

    @Test
    void outOfScopeLiteralPrefixStillWorksAfterGlobSupportAdded() {
        // Regression guard: the existing literal-prefix shape documented
        // in the README ("api-test/src/test/java") must keep working.
        // Losing this would silently break every adopter who migrated to
        // v2 before glob support existed.
        AffectedTestsConfig prefixConfig = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(java.util.List.of(
                        "api-test/src/test/java",
                        "api-test/src/test/resources"))
                .build();
        PathToClassMapper prefixMapper = new PathToClassMapper(prefixConfig);

        Set<String> changed = Set.of(
                "api-test/src/test/java/com/example/FooSteps.java",
                "api-test/src/test/resources/feature.feature");
        MappingResult result = prefixMapper.mapChangedFiles(changed);

        assertEquals(changed, result.outOfScopeFiles());
    }

    @Test
    void outOfScopeGlobDoesNotMatchSiblingDirectory() {
        // Positive-boundary: 'api-test/**' must NOT match a directory
        // whose name merely starts with 'api-test' ("api-test-utils/...").
        // The glob layer needs to preserve the same guarantee the literal
        // prefix already had via trailing-slash normalisation.
        AffectedTestsConfig globConfig = AffectedTestsConfig.builder()
                .outOfScopeTestDirs(java.util.List.of("api-test/**"))
                .build();
        PathToClassMapper globMapper = new PathToClassMapper(globConfig);

        Set<String> changed = Set.of(
                "api-test-utils/src/main/java/com/example/Foo.java");
        MappingResult result = globMapper.mapChangedFiles(changed);

        assertTrue(result.outOfScopeFiles().isEmpty(),
                "'api-test/**' must not claim 'api-test-utils/...' — name prefixes are not paths");
    }

    @Test
    void javaFileOutsideConfiguredDirsIsUnmapped() {
        // A .java file that does not sit under any configured source or test
        // directory (e.g. a root-level scratch file or a docs/examples path)
        // can still affect runtime behaviour, so it must be surfaced as
        // unmapped rather than silently dropped.
        Set<String> changed = Set.of("docs/examples/Snippet.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
        assertEquals(changed, result.unmappedChangedFiles());
    }

    @Test
    void excludedFilesAreNotReportedAsUnmapped() {
        // Explicit opt-out must stay silent — an excluded file is neither
        // mapped nor flagged as unmapped.
        AffectedTestsConfig excludeConfig = AffectedTestsConfig.builder()
                .ignorePaths(java.util.List.of("**/generated/**"))
                .build();
        PathToClassMapper excludeMapper = new PathToClassMapper(excludeConfig);

        Set<String> changed = Set.of("src/main/java/generated/Stub.java");
        MappingResult result = excludeMapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "Excluded files must not trigger the runAll escalation");
    }

    @Test
    void mappedJavaFilesDoNotAppearAsUnmapped() {
        Set<String> changed = Set.of(
                "src/main/java/com/example/Foo.java",
                "src/test/java/com/example/FooTest.java"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "Properly mapped Java sources must not leak into the unmapped bucket");
    }

    @Test
    void handlesBothProductionAndTestChanges() {
        Set<String> changed = Set.of(
                "src/main/java/com/example/Foo.java",
                "src/test/java/com/example/FooTest.java",
                "src/main/java/com/example/Bar.java"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertEquals(2, result.productionClasses().size());
        assertEquals(1, result.testClasses().size());
        assertTrue(result.productionClasses().contains("com.example.Foo"));
        assertTrue(result.productionClasses().contains("com.example.Bar"));
        assertTrue(result.testClasses().contains("com.example.FooTest"));
    }

    @Test
    void tryMapToClassRequiresBoundaryBeforeSourceDir() {
        // Regression: a plain indexOf("src/main/java/") treats
        // "notsrc/main/java/Foo.java" as a production hit and classifies
        // it as FQN "Foo". That would silently swallow the file — it
        // would neither appear in changedProductionFiles (no real Java
        // sitting under our configured dirs) nor in unmappedChangedFiles
        // (which drives the runAllOnNonJavaChange safety net), defeating
        // the whole "run more, never less" guarantee.
        Set<String> changed = Set.of("notsrc/main/java/Foo.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty(),
                "A path whose source-dir-like prefix is a substring of some other directory "
                        + "must not be mis-classified as production");
        assertTrue(result.testClasses().isEmpty());
        assertEquals(changed, result.unmappedChangedFiles(),
                "The boundary-violating path must land in the unmapped bucket so the engine "
                        + "can escalate to runAll under the default safety policy");
    }

    @Test
    void tryMapToClassHandlesSourceDirAsPathPrefix() {
        // Positive counterpart to the boundary test: when the source dir
        // *does* start the path, matching must still succeed.
        Set<String> changed = Set.of("src/main/java/com/example/Foo.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.Foo"));
        assertTrue(result.unmappedChangedFiles().isEmpty());
    }

    @Test
    void unparseablePathNameDoesNotCrashIsIgnored() {
        // Regression for the InvalidPathException catch in isIgnored.
        // The JDK's default FileSystem rejects paths containing a NUL
        // byte on every platform, so "src/main/java/com/example/Foo\0"
        // is the portable way to exercise the branch. Before the
        // catch existed, any such filename arriving in a diff —
        // surfacing as a CVE-style probe or as a cross-platform-
        // checkout artifact — blew up the whole task with an
        // unhandled InvalidPathException; now it routes through the
        // unmapped bucket and the safety net picks it up.
        String malformed = "src/main/java/com/example/Foo\u0000";
        Set<String> changed = Set.of(malformed);

        assertDoesNotThrow(() -> mapper.mapChangedFiles(changed),
                "Unparseable path names must not crash the mapper");

        MappingResult result = mapper.mapChangedFiles(changed);
        assertTrue(result.ignoredFiles().isEmpty(),
                "An unparseable path cannot be matched against any ignore glob");
        // The .java suffix survives the NUL byte in the string so the
        // mapper still attempts the source-dir mapping. What this test
        // locks in is the *absence of a crash*, not the final bucket.
    }

    @Test
    void malformedIgnorePathsGlobFailsWithHelpfulMessage() {
        // Parity test with OutOfScopeMatchersTest — the out-of-scope
        // path had a regression test for malformed globs since
        // v1.9.16, but the ignorePaths compile path, which uses the
        // same IAE-catching shape, did not. Lock in that a user
        // writing a bracket expression they forgot to close gets a
        // message pointing at ignorePaths[index] and the specific
        // entry, not a raw PatternSyntaxException stacktrace.
        AffectedTestsConfig badConfig = AffectedTestsConfig.builder()
                .ignorePaths(java.util.List.of("**/valid/**", "**/broken["))
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new PathToClassMapper(badConfig));

        String message = error.getMessage();
        assertTrue(message.contains("ignorePaths[1]"),
                "Error message must identify which entry is broken, got: " + message);
        assertTrue(message.contains("'**/broken['"),
                "Error message must quote the offending entry, got: " + message);
    }

    @Test
    void tryMapToClassHandlesSourceDirAfterModulePrefix() {
        // Positive counterpart: when the source dir is preceded by a `/`
        // (standard multi-module layout), matching must still succeed.
        Set<String> changed = Set.of("api-gateway/src/main/java/com/example/Foo.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.Foo"));
        assertTrue(result.unmappedChangedFiles().isEmpty());
    }

    @Test
    void rejectsPathsWithTraversalSegmentAsUnmapped() {
        // Defence-in-depth: git never emits `..` segments in diff paths,
        // so any such segment is either malformed input or a deliberate
        // attempt to confuse tryMapToClass. The old behaviour handed
        // these to the source-dir mapper, which could produce absurd
        // FQNs like `..com.example.Foo` and quietly classify them as
        // production — dangerous because the test-dispatcher would then
        // try to run those FQNs as test classes. The fix routes them
        // into the unmapped bucket, which trips the UNMAPPED_FILE
        // situation and escalates to a full run.
        Set<String> changed = Set.of(
                "../../etc/passwd.java",
                "src/main/java/../../../../etc/Foo.java",
                "legit/src/main/java/com/example/Foo.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.unmappedChangedFiles().contains("../../etc/passwd.java"),
                "Leading ../ must land in unmapped, got: " + result.unmappedChangedFiles());
        assertTrue(result.unmappedChangedFiles().contains("src/main/java/../../../../etc/Foo.java"),
                "Embedded /../ must land in unmapped, got: " + result.unmappedChangedFiles());
        assertTrue(result.productionClasses().contains("com.example.Foo"),
                "Clean path in the same batch must still be mapped normally");
    }

    @Test
    void moduleInfoRoutesToUnmappedNotProduction() {
        // Regression: `module-info.java` is the JPMS descriptor, not a
        // production type. The old path handed it to tryMapToClass,
        // which produced the non-FQN "module-info" and classified it
        // as a production class — poisoning every downstream strategy
        // (naming/usage/impl all try to derive a simple class name
        // from the FQN and match `module-info` against real source
        // classes, which never matches and silently skips all the
        // tests that the module-descriptor change could actually
        // affect). Routing it to unmapped hands control to the
        // UNMAPPED_FILE safety net, which defaults to FULL_SUITE in
        // CI mode — the correct conservative choice because a
        // module-info change alters visibility for every consumer.
        Set<String> changed = Set.of("src/main/java/module-info.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty(),
                "module-info must never become a production FQN");
        assertTrue(result.unmappedChangedFiles().contains("src/main/java/module-info.java"),
                "module-info must land in the unmapped bucket so the safety "
                        + "net can escalate, got: " + result.unmappedChangedFiles());
    }

    @Test
    void packageInfoRoutesToUnmappedNotProduction() {
        // Same failure mode as module-info: `package-info.java` is a
        // marker file that carries package-level annotations/docs, not
        // a production type. Pre-fix it became the FQN
        // `com.example.package-info`, which is syntactically illegal
        // as a Java identifier but which tryMapToClass happily
        // produced. Every strategy then silently failed to resolve
        // the dotted form to a real class. A change to
        // package-info.java can legitimately affect every test in
        // that package (e.g. adding a package-wide `@Nullable`
        // default), so the UNMAPPED_FILE escalation is the correct
        // conservative routing.
        Set<String> changed = Set.of("src/main/java/com/example/package-info.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty(),
                "package-info must never become a production FQN");
        assertTrue(result.unmappedChangedFiles()
                        .contains("src/main/java/com/example/package-info.java"),
                "package-info must land in the unmapped bucket");
    }

    @Test
    void allowsLegitimateFilenamesThatContainDotDotSubstring() {
        // Negative: a file literally named `foo..bar.java` is legal on
        // POSIX and must not be mistaken for traversal. The guard is
        // segment-aware exactly to avoid this false positive.
        Set<String> changed = Set.of("src/main/java/com/example/foo..bar.java");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.foo..bar"),
                "File with '..' inside a name but not as a standalone segment "
                        + "must still map, got: " + result.productionClasses());
        assertTrue(result.unmappedChangedFiles().isEmpty(),
                "No traversal warning should be emitted for legitimate names");
    }

    // ── Phase 2 PR #1 of issue #76 — Kotlin path-derived mapping.
    //    Behaviour pinned: .kt routes through tryMapToClass like
    //    .java; production .kt files emit two FQNs (path-derived +
    //    synthetic <basename>Kt); test .kt files emit only the
    //    path-derived FQN; .kt outside source/test roots routes
    //    to unmapped.

    @Test
    void mapsProductionKotlinFileToBothPathDerivedAndSyntheticFqn() {
        Set<String> changed = Set.of("src/main/java/com/example/service/Util.kt");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.service.Util"),
                "Path-derived FQN must be emitted for the explicit-class case");
        assertTrue(result.productionClasses().contains("com.example.service.UtilKt"),
                "Synthetic <basename>Kt FQN must be emitted so a Java test that "
                        + "imports the compiled top-level-fn class (UtilKt) selects "
                        + "via UsageStrategy tier-1 import lookup");
        assertEquals(2, result.productionClasses().size(),
                "Bounded over-selection: exactly two FQNs per production .kt — "
                        + "any more and downstream strategies would do redundant probes. "
                        + "Got: " + result.productionClasses());
        assertTrue(result.testClasses().isEmpty());
        assertTrue(result.changedProductionFiles().contains("src/main/java/com/example/service/Util.kt"));
    }

    @Test
    void mapsTestKotlinFileToPathDerivedFqnOnly() {
        // Production-only synthetic emission rule — see
        // docs/PHASE-2-KOTLIN-AST.md §6 "Diff-side FQN for class-less
        // Kotlin files." Emitting `FooTestKt` into testClasses would
        // surface to the runner as "no tests found" because the
        // compiler never produces a test class with that name.
        Set<String> changed = Set.of("src/test/java/com/example/FooTest.kt");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertEquals(Set.of("com.example.FooTest"), result.testClasses(),
                "Test .kt must emit ONLY the path-derived FQN — "
                        + "never the synthetic FooTestKt that names no real class");
        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.changedTestFiles().contains("src/test/java/com/example/FooTest.kt"));
    }

    @Test
    void mapsMixedJavaAndKotlinDiff() {
        Set<String> changed = Set.of(
                "api/src/main/java/com/example/api/FooDto.java",
                "core/src/main/java/com/example/core/Util.kt",
                "core/src/test/java/com/example/core/UtilKtTest.kt"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.api.FooDto"));
        assertTrue(result.productionClasses().contains("com.example.core.Util"));
        assertTrue(result.productionClasses().contains("com.example.core.UtilKt"));
        assertTrue(result.testClasses().contains("com.example.core.UtilKtTest"));
        assertEquals(3, result.productionClasses().size());
        assertEquals(1, result.testClasses().size());
        assertTrue(result.unmappedChangedFiles().isEmpty());
    }

    @Test
    void kotlinFileOutsideConfiguredSourceRootsRoutesToUnmapped() {
        // Pre-PR-1 a .kt file went to unmapped because of the
        // `!filePath.endsWith(".java")` guard. Post-PR-1 the file
        // gets through that guard but then fails source/test-root
        // matching — the unmapped fallthrough is what catches it.
        Set<String> changed = Set.of("buildSrc/Util.kt");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty());
        assertTrue(result.testClasses().isEmpty());
        assertEquals(Set.of("buildSrc/Util.kt"), result.unmappedChangedFiles());
    }

    @Test
    void formatKtClassFileEmitsBothPathDerivedAndPhantomSynthetic() {
        // Edge case documented in docs/PHASE-2-KOTLIN-AST.md §6:
        // a file named `FormatKt.kt` (perhaps containing
        // `class FormatKt`) produces a phantom synthetic
        // `FormatKtKt` that names no real class. Bounded harmless
        // over-selection: NamingConventionStrategy probes
        // `FormatKtKtTest` against the test-FQN universe and finds
        // nothing (no test is ever named that), no real import
        // string ever references the synthetic. Documented
        // behaviour, not a bug.
        Set<String> changed = Set.of("src/main/java/com/example/FormatKt.kt");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("com.example.FormatKt"));
        assertTrue(result.productionClasses().contains("com.example.FormatKtKt"),
                "Phantom synthetic is emitted unconditionally for *.kt files — "
                        + "it costs one extra naming probe and adds no real-world "
                        + "false positives; suppression is reserved for adopters "
                        + "who route the file via outOfScopeSourceDirs.");
    }

    @Test
    void kotlinFileUnderOutOfScopeSourceDirRoutesToOutOfScope() {
        // Out-of-scope routing happens before the .kt extension check,
        // so the existing escape hatch works for Kotlin too. Adopters
        // who hit a noisy synthetic can configure outOfScopeSourceDirs
        // exactly the same way they would for Java.
        AffectedTestsConfig oosConfig = AffectedTestsConfig.builder()
                .outOfScopeSourceDirs(java.util.List.of("legacy/**"))
                .build();
        PathToClassMapper oosMapper = new PathToClassMapper(oosConfig);

        Set<String> changed = Set.of("legacy/src/main/java/com/example/Old.kt");
        MappingResult result = oosMapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().isEmpty(),
                "OOS .kt must not contribute to productionClasses");
        assertEquals(changed, result.outOfScopeFiles());
    }

    @Test
    void mapsDefaultPackageKotlinFileToBothFqns() {
        // Edge case: a Kotlin file with no package declaration,
        // dropped at the immediate root of `src/main/java`. The
        // path-derived FQN is just the basename — no dots. The
        // synthetic emission is dot-count-agnostic (it's a string
        // concatenation), so we should still get exactly two
        // entries: `Util` and `UtilKt`. A future refactor that
        // conditionally suppressed the synthetic when `prodFqn`
        // has no dot would silently break this — pin the contract.
        Set<String> changed = Set.of("src/main/java/Util.kt");
        MappingResult result = mapper.mapChangedFiles(changed);

        assertTrue(result.productionClasses().contains("Util"));
        assertTrue(result.productionClasses().contains("UtilKt"));
        assertEquals(2, result.productionClasses().size(),
                "Default-package .kt must still emit both FQNs; got: "
                        + result.productionClasses());
    }

    @Test
    void coexistingJavaAndKotlinWithSameBasenameEmitDeduplicatedFqnsPlusSynthetic() {
        // Java → Kotlin migration shape: a class converted from
        // `Foo.java` to `Foo.kt` may transiently exist in both
        // forms (or under different module paths during the
        // migration). The path-derived FQN collides on
        // `com.example.Foo`; the set deduplicates. The synthetic
        // `FooKt` emits because of the .kt file. Both file paths
        // surface in `changedProductionFiles` so downstream
        // observability (--explain bucket sample, JSON output) can
        // show the diff accurately.
        Set<String> changed = Set.of(
                "src/main/java/com/example/Foo.java",
                "src/main/java/com/example/Foo.kt"
        );
        MappingResult result = mapper.mapChangedFiles(changed);

        assertEquals(Set.of("com.example.Foo", "com.example.FooKt"),
                result.productionClasses(),
                "Java + Kotlin same-basename collision must produce exactly "
                        + "{Foo, FooKt} — set semantics dedupe the path-derived "
                        + "FQN, the synthetic comes from the .kt side only");
        assertTrue(result.changedProductionFiles().contains("src/main/java/com/example/Foo.java"));
        assertTrue(result.changedProductionFiles().contains("src/main/java/com/example/Foo.kt"));
        assertEquals(2, result.changedProductionFiles().size());
    }

    @Test
    void kotlinFileUnderIgnorePathRoutesToIgnored() {
        AffectedTestsConfig ignoreConfig = AffectedTestsConfig.builder()
                .ignorePaths(java.util.List.of("**/generated/**"))
                .build();
        PathToClassMapper ignoreMapper = new PathToClassMapper(ignoreConfig);

        Set<String> changed = Set.of("src/main/java/com/example/generated/Auto.kt");
        MappingResult result = ignoreMapper.mapChangedFiles(changed);

        assertEquals(changed, result.ignoredFiles());
        assertTrue(result.productionClasses().isEmpty());
    }
}
