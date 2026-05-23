package io.affectedtests.core.mapping;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.discovery.SourceExtensions;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Maps file paths (from git diff output) to fully-qualified Java class names.
 * Separates production sources from test sources, tags files as ignored /
 * out-of-scope where the config says so, and surfaces everything else as
 * unmapped.
 *
 * <p>The five mutually-exclusive buckets of {@link MappingResult} are what
 * {@link io.affectedtests.core.AffectedTestsEngine} uses to pick a
 * {@link io.affectedtests.core.config.Situation}. The mapper MUST NOT drop
 * a changed file into silence: every input path appears in exactly one
 * bucket, so the engine can always answer "why did we route to X?" with
 * one bucket count.
 */
public final class PathToClassMapper {

    private static final Logger log = LoggerFactory.getLogger(PathToClassMapper.class);

    private final AffectedTestsConfig config;
    private final List<PathMatcher> ignoreMatchers;
    private final List<Predicate<String>> outOfScopeTestMatchers;
    private final List<Predicate<String>> outOfScopeSourceMatchers;

    public PathToClassMapper(AffectedTestsConfig config) {
        this.config = config;
        this.ignoreMatchers = compileIgnoreMatchers(config.ignorePaths());
        // Out-of-scope dirs are compiled once, up-front, so every diff
        // pays only the matcher cost instead of re-parsing the config
        // strings per-file. The pre-compile step is also where we decide
        // between glob and literal-prefix semantics, so users can mix
        // both shapes in the same list without surprise. The compiled
        // matcher list is shared with ProjectIndex so diff-side and
        // indexed-file-side agree on what "out of scope" means for a
        // given entry — see OutOfScopeMatchers for the rationale.
        this.outOfScopeTestMatchers = OutOfScopeMatchers.compile(
                config.outOfScopeTestDirs(), "outOfScopeTestDirs");
        this.outOfScopeSourceMatchers = OutOfScopeMatchers.compile(
                config.outOfScopeSourceDirs(), "outOfScopeSourceDirs");
    }

    private static List<PathMatcher> compileIgnoreMatchers(List<String> ignorePaths) {
        List<PathMatcher> matchers = new ArrayList<>(ignorePaths.size());
        for (int i = 0; i < ignorePaths.size(); i++) {
            String p = ignorePaths.get(i);
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            } catch (IllegalArgumentException e) {
                // Catches both PatternSyntaxException and the raw
                // IllegalArgumentException the JVM throws for some
                // shapes like "glob:[" — both inherit from IAE and are
                // equally unhelpful on their own.
                throw new IllegalStateException(
                        "Affected Tests: invalid glob at ignorePaths[" + i + "]: '" + p
                                + "' — " + e.getMessage(), e);
            }
        }
        return List.copyOf(matchers);
    }

    /**
     * Result of mapping changed files, split into five mutually-exclusive
     * buckets so the engine can map any diff to exactly one
     * {@link io.affectedtests.core.config.Situation}.
     *
     * <p>{@link #ignoredFiles()} captures paths matched by
     * {@link AffectedTestsConfig#ignorePaths()}. {@link #outOfScopeFiles()}
     * captures paths that sit under
     * {@link AffectedTestsConfig#outOfScopeTestDirs()} or
     * {@link AffectedTestsConfig#outOfScopeSourceDirs()}.
     * {@link #unmappedChangedFiles()} is the "fallthrough" bucket — any
     * file that wasn't ignored, out-of-scope, a production Java source or
     * a test Java source ends up here (YAML, build scripts, Liquibase
     * migrations, stray {@code .java} files outside the configured source
     * trees).
     */
    public record MappingResult(
            Set<String> productionClasses,
            Set<String> testClasses,
            Set<String> changedProductionFiles,
            Set<String> changedTestFiles,
            Set<String> ignoredFiles,
            Set<String> outOfScopeFiles,
            Set<String> unmappedChangedFiles
    ) {}

    /**
     * Maps a set of changed file paths to production and test class FQNs.
     *
     * @param changedFiles relative file paths from git diff
     * @return mapping result with production and test class FQNs
     */
    public MappingResult mapChangedFiles(Set<String> changedFiles) {
        Set<String> productionClasses = new LinkedHashSet<>();
        Set<String> testClasses = new LinkedHashSet<>();
        Set<String> changedProductionFiles = new LinkedHashSet<>();
        Set<String> changedTestFiles = new LinkedHashSet<>();
        Set<String> ignoredFiles = new LinkedHashSet<>();
        Set<String> outOfScopeFiles = new LinkedHashSet<>();
        Set<String> unmappedChangedFiles = new LinkedHashSet<>();

        for (String filePath : changedFiles) {
            // Reject path-traversal shapes up-front. Git never emits `..`
            // segments in diff paths, so any such segment is either a
            // malformed input or an attempt to confuse the mapper
            // (e.g. `../../etc/passwd.java` would otherwise be handed to
            // tryMapToClass which would happily produce an FQN starting
            // with `..`). Treating them as unmapped — instead of a hard
            // throw — keeps the tool resilient and lets the
            // UNMAPPED_FILE safety net escalate to a full run, matching
            // the general "we don't know what this is" contract.
            if (hasTraversalSegment(filePath)) {
                // Debug level (not warn) so a hostile MR can't blast
                // the build output with N lines per traversal path —
                // the engine's downstream `unmappedChangedFiles()`
                // warning already surfaces the escalation once per
                // diff at WARN level, which is the operator-facing
                // signal that matters.
                log.debug("Rejecting path with '..' segment as unmapped: {}",
                        LogSanitizer.sanitize(filePath));
                unmappedChangedFiles.add(filePath);
                continue;
            }

            // Ignore rules are evaluated FIRST: a user's explicit
            // {@code ignorePaths} entry is a contract that nothing about
            // the file should influence the engine, including nudging it
            // into the out-of-scope bucket.
            // All {@code filePath} values below originate from the git
            // diff against an attacker-controllable MR branch, so every
            // log site routes through {@link LogSanitizer}. Even
            // DEBUG-level sites are worth sanitising because operators
            // bumping to DEBUG to investigate incidents is exactly when
            // log-forgery escalation is most likely to matter.
            if (isIgnored(filePath)) {
                ignoredFiles.add(filePath);
                log.debug("Ignored by pattern: {}", LogSanitizer.sanitize(filePath));
                continue;
            }

            // Out-of-scope dirs are evaluated BEFORE the Java mapper so a
            // {@code .java} file under {@code api-test/src/test/java} is
            // not mis-filed as an in-scope test class. Source-dir check is
            // first because real code is more common in diffs than test
            // code under an out-of-scope test dir.
            if (matchesAny(filePath, outOfScopeSourceMatchers)
                    || matchesAny(filePath, outOfScopeTestMatchers)) {
                outOfScopeFiles.add(filePath);
                log.debug("Out-of-scope: {}", LogSanitizer.sanitize(filePath));
                continue;
            }

            if (!SourceExtensions.isSource(filePath)) {
                log.debug("Non-source file flagged as unmapped: {}", LogSanitizer.sanitize(filePath));
                unmappedChangedFiles.add(filePath);
                continue;
            }

            // module-info.java (JPMS descriptor) and package-info.java
            // (package-level annotations / docs) are not production
            // classes: they have no instantiable type and no FQN that
            // tests can reference, so tryMapToClass would either
            // produce a non-FQN ("module-info") or a dotted form like
            // "com.example.package-info" that poisons every downstream
            // strategy that treats it as a class name. Both shapes are
            // genuine changes the operator cares about — a package
            // annotation can affect every test in the package, and a
            // module-info change alters JPMS visibility — so route
            // them to the unmapped bucket and let the UNMAPPED_FILE
            // safety net decide (FULL_SUITE in CI mode by default).
            //
            // Kotlin equivalents: there is no Kotlin module-info; the
            // Kotlin convention for package-level annotations is the
            // top-of-file `@file:` block on a normal `.kt` file, so we
            // do not need a `package-info.kt` carve-out — the file's
            // path-derived FQN is meaningful even when the body is
            // pure annotations.
            String fileName = extractFileName(filePath);
            if ("module-info.java".equals(fileName) || "package-info.java".equals(fileName)) {
                log.debug("Java marker file ({}) flagged as unmapped: {}",
                        LogSanitizer.sanitize(fileName),
                        LogSanitizer.sanitize(filePath));
                unmappedChangedFiles.add(filePath);
                continue;
            }

            String testFqn = tryMapToClass(filePath, config.testDirs());
            if (testFqn != null) {
                // Test files: emit only the path-derived FQN. The
                // synthetic `<basename>Kt` shape is production-only
                // (top-level functions get compiled to a class
                // named `<basename>Kt`); test classes are
                // conventionally instantiable types, so emitting
                // `FooTestKt` here would surface to the runner as
                // "no tests found for class FooTestKt" — the exact
                // silent-skip behaviour Phase 1 was written to
                // avoid. See docs/PHASE-2-KOTLIN-AST.md §6.
                testClasses.add(testFqn);
                changedTestFiles.add(filePath);
                log.debug("Mapped test file: {} → {}",
                        LogSanitizer.sanitize(filePath),
                        LogSanitizer.sanitize(testFqn));
                continue;
            }

            String prodFqn = tryMapToClass(filePath, config.sourceDirs());
            if (prodFqn != null) {
                productionClasses.add(prodFqn);
                changedProductionFiles.add(filePath);
                log.debug("Mapped production file: {} → {}",
                        LogSanitizer.sanitize(filePath),
                        LogSanitizer.sanitize(prodFqn));
                // Phase 2 PR #1 (issue #76): for production .kt files
                // emit a second synthetic FQN ending in `Kt` to match
                // the compiled-class shape Kotlin uses for top-level
                // functions and properties (file `Util.kt` with no
                // explicit class declaration compiles to class
                // `UtilKt`). Tests written against the top-level
                // functions therefore import `UtilKt`, not `Util`,
                // and the existing simple-name probes
                // (NamingConventionStrategy looking for `UtilKtTest`,
                // UsageStrategy tier-1 matching `import …UtilKt;`)
                // need the synthetic FQN in `productionClasses` to
                // fire. Bounded over-selection: a `.kt` that contains
                // an explicit class declaration adds one extra
                // simple-name probe (e.g. `Util` and `UtilKt`); the
                // probe drops out automatically when no test matches.
                // Edge case: `FormatKt.kt` with `class FormatKt`
                // produces the phantom `FormatKtKt` synthetic — the
                // Kotlin compiler never emits a class with that
                // name, no real import or supertype edge references
                // it, so the over-selection bound stays at one
                // naming probe per file.
                if (".kt".equals(SourceExtensions.extensionOf(filePath))) {
                    String synthetic = prodFqn + "Kt";
                    if (productionClasses.add(synthetic)) {
                        log.debug("Synthetic Kotlin top-level FQN: {} → {}",
                                LogSanitizer.sanitize(filePath),
                                LogSanitizer.sanitize(synthetic));
                    }
                }
                continue;
            }

            // A source file outside the configured source/test dirs —
            // still unmappable, still a potential safety-net escalation
            // trigger. Pre-PR-1 this branch only fired for stray
            // .java files; .kt files now reach it on the same terms.
            log.debug("Source file outside configured source/test dirs flagged as unmapped: {}",
                    LogSanitizer.sanitize(filePath));
            unmappedChangedFiles.add(filePath);
        }

        log.info("Mapped {} production, {} test, {} ignored, {} out-of-scope, {} unmapped "
                        + "(total {} changed files)",
                productionClasses.size(), testClasses.size(),
                ignoredFiles.size(), outOfScopeFiles.size(), unmappedChangedFiles.size(),
                changedFiles.size());

        return new MappingResult(productionClasses, testClasses,
                changedProductionFiles, changedTestFiles,
                ignoredFiles, outOfScopeFiles, unmappedChangedFiles);
    }

    /**
     * Tries to map a file path to an FQN given a list of source directories.
     * Handles multi-module paths like "module/src/main/java/com/example/Foo.java".
     *
     * <p>Matching is <em>boundary-aware</em> — the source dir must either
     * start the path or be preceded by {@code '/'}. A plain
     * {@code indexOf(sourceDir)} would happily pick up
     * {@code "notsrc/main/java/Foo.java"} and classify it as production
     * {@code Foo}, which in turn would keep it out of the "unmapped"
     * bucket that drives the {@link io.affectedtests.core.config.Situation#UNMAPPED_FILE}
     * safety net — exactly the silent-skip behaviour that escalation
     * exists to prevent.
     */
    private String tryMapToClass(String filePath, java.util.List<String> sourceDirs) {
        String normalized = filePath.replace('\\', '/');

        for (String sourceDir : sourceDirs) {
            String normalizedDir = sourceDir.replace('\\', '/');
            if (!normalizedDir.endsWith("/")) {
                normalizedDir += "/";
            }

            int idx;
            if (normalized.startsWith(normalizedDir)) {
                idx = 0;
            } else {
                int boundary = normalized.indexOf("/" + normalizedDir);
                if (boundary < 0) {
                    continue;
                }
                idx = boundary + 1;
            }

            String relativePath = normalized.substring(idx + normalizedDir.length());
            relativePath = SourceExtensions.stripKnownExtension(relativePath);
            return relativePath.replace('/', '.');
        }
        return null;
    }

    private static String extractFileName(String filePath) {
        String normalized = filePath.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    private static boolean hasTraversalSegment(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        String normalized = filePath.replace('\\', '/');
        // Match `..` as a standalone path segment only — `foo..bar/baz`
        // (a legitimate, if ugly, filename) must not trip this guard.
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    private boolean isIgnored(String filePath) {
        java.nio.file.Path path;
        try {
            path = java.nio.file.Path.of(filePath);
        } catch (InvalidPathException e) {
            // Symmetry with the out-of-scope glob branch: a file whose
            // name the platform refuses to parse (e.g. a Linux-committed
            // "foo:bar.md" arriving on a Windows CI runner) can't match
            // any ignore glob. Fail closed — treat as "not ignored" so
            // the engine routes it through the unmapped bucket and the
            // safety net picks up the oddity, rather than blowing up
            // the whole task with an unhandled InvalidPathException.
            log.debug("Ignore check skipped for unparseable path {}: {}",
                    LogSanitizer.sanitize(filePath),
                    LogSanitizer.sanitize(e.getMessage()));
            return false;
        }
        for (PathMatcher matcher : ignoreMatchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the pre-compiled out-of-scope matcher list against the
     * file path. Normalises separators first so Windows path inputs
     * match the same globs/prefixes Linux users write in
     * {@code build.gradle}.
     */
    private static boolean matchesAny(String filePath, List<Predicate<String>> matchers) {
        if (matchers.isEmpty()) return false;
        return OutOfScopeMatchers.matchesAny(filePath.replace('\\', '/'), matchers);
    }
}
