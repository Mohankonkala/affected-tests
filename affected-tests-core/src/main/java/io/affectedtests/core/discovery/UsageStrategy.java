package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Strategy: Usage / Reference scanning.
 *
 * <p>For each test source file, decides whether it transitively depends on
 * any of the changed production classes. The decision flows through four
 * tiers, each cheaper than the next would be:
 * <ol>
 *   <li><strong>Tier&nbsp;1 — direct import.</strong> Scans the test file's
 *       import list. Cheapest tier; no AST walk. Hits the common case
 *       (test imports the changed class explicitly).</li>
 *   <li><strong>Tier&nbsp;1b — wildcard import.</strong> Two flavours:
 *       <code>import pkg.Foo.*;</code> (class-member wildcard, treated as a
 *       direct dependency on {@code pkg.Foo} regardless of body refs) and
 *       <code>import pkg.*;</code> (package wildcard, gated on the simple
 *       name actually appearing in the body so it does not over-select).</li>
 *   <li><strong>Tier&nbsp;2 — same package.</strong> No import is needed
 *       when test and changed class share a package; gates on the simple
 *       name appearing in the body.</li>
 *   <li><strong>Tier&nbsp;3 — fully-qualified inline reference.</strong>
 *       Catches code that types {@code com.example.Foo} inline without
 *       importing it (cucumber steps, generated code, etc.).</li>
 * </ol>
 *
 * <p>All four tiers consume the strategy-agnostic {@link FileMetadata}
 * record produced by {@link FileMetadataExtractor} — imports, type-ref
 * simple/dotted names, and the declared package. Per-file extraction is
 * a single AST pass; on a warm run {@link ProjectIndexCache} stage&nbsp;2
 * (issue&nbsp;#41) hands back the cached record without parsing.
 */
public final class UsageStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(UsageStrategy.class);

    private final AffectedTestsConfig config;

    public UsageStrategy(AffectedTestsConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        List<Path> testFiles = SourceFileScanner.collectTestFiles(projectDir, config.testDirs());
        return scanTestFiles(changedProductionClasses, testFiles, null);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks
     * and AST parses).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        return scanTestFiles(changedProductionClasses, index.testFiles(), index);
    }

    private Set<String> scanTestFiles(Set<String> changedProductionClasses,
                                      List<Path> testFiles, ProjectIndex index) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        if (changedProductionClasses.isEmpty()) {
            return discoveredTests;
        }

        Map<String, Set<String>> simpleNameToFqns = new HashMap<>();
        Set<String> changedFqns = new HashSet<>(changedProductionClasses);
        Set<String> simpleNames = new HashSet<>();
        for (String fqn : changedProductionClasses) {
            String simpleName = SourceFileScanner.simpleClassName(fqn);
            simpleNameToFqns.computeIfAbsent(simpleName, k -> new HashSet<>()).add(fqn);
            simpleNames.add(simpleName);
        }

        for (Path testFile : testFiles) {
            FileMetadata md = metadataOrGet(testFile, index);
            if (md == null) continue;

            String testFqn = extractFqn(md, testFile);
            if (testFqn == null) continue;

            if (changedFqns.contains(testFqn)) continue;

            if (testReferencesChangedClass(md, changedFqns, simpleNames, simpleNameToFqns)) {
                discoveredTests.add(testFqn);
                log.debug("Usage match: {}", LogSanitizer.sanitize(testFqn));
            }
        }

        log.info("[usage] Discovered {} tests for {} changed classes",
                discoveredTests.size(), changedProductionClasses.size());
        return discoveredTests;
    }

    /**
     * Resolves the {@link FileMetadata} for {@code file}.
     * <p>Index-backed path (the engine-level run) returns the cached
     * record from {@link ProjectIndex#fileMetadata(Path)} — Stage&nbsp;2
     * of issue&nbsp;#41 lets that succeed without parsing on a warm run.
     * <p>Standalone path (unit tests + the legacy
     * {@code projectDir}-based entry point) dispatches via
     * {@link LanguageParsers#parseOrWarn} which looks up the parser
     * for {@code file}'s extension and produces {@link FileMetadata}
     * directly. Pays the parse cost every time but keeps the
     * strategy independent from {@link ProjectIndex} for tests that
     * don't want the index plumbing.
     *
     * <p>Pre-PR-2 of issue #76 this method took a
     * {@code JavaParser fallbackParser} argument and hard-coded
     * Java-only parsing in the standalone branch — even after PR #1
     * widened the scanner to admit {@code .kt} files. Post-PR-2 the
     * dispatch is extension-driven, so a {@code .kt} test file
     * fed to the standalone path will reach the (still-absent
     * pre-PR-3) Kotlin parser and silently drop out via
     * {@code null}, matching the contract every other parser-side
     * call site already has.
     */
    private FileMetadata metadataOrGet(Path file, ProjectIndex index) {
        if (index != null) {
            return index.fileMetadata(file);
        }
        // Standalone fallback (no engine-supplied registry):
        // {@link LanguageParsers#defaultJavaOnly()} is the safe-no-Kotlin
        // posture. PR #3 made the registry per-engine because Kotlin
        // owns lifecycle state that must be disposed; a unit test
        // driving the strategy directly does not have that lifecycle
        // and must not be required to construct + dispose one. {@code .kt}
        // files routed through this path silently drop with no WARN
        // (the {@code defaultJavaOnly} registry has no parser registered
        // for {@code .kt}, so {@code parseOrWarn} returns {@code null}
        // immediately) — matching the pre-PR-3 contract.
        return LanguageParsers.defaultJavaOnly().parseOrWarn(file, "usage");
    }

    /**
     * Checks whether a test file references any of the changed classes,
     * driven entirely off the cached {@link FileMetadata} so a warm run
     * reads zero AST nodes.
     *
     * <p>Tiered with the cheapest tier first:
     *
     * <ol>
     *   <li><strong>Tier 1 — direct import match</strong>: pure scan of the
     *       cached import list. No type-ref iteration, no allocation per
     *       changed class.</li>
     *   <li><strong>Tier 1b/2/3</strong>: read pre-built type-reference
     *       sets from the metadata record. Pre-stage-2 each tier walked
     *       the AST itself; the extractor now folds those walks into a
     *       single pass that runs only on a cache miss.</li>
     * </ol>
     *
     * <p>All {@code changedFqn} and {@code imported} values flowing into the
     * log statements below are diff-derived and may legitimately carry odd
     * but-valid characters that still need control-char sanitisation before
     * they hit the logger — a malicious MR can craft an import line like
     * {@code import com.evil.\u001b[m;}. Sanitisation is applied even at
     * DEBUG because operators bumping level to chase a false-positive
     * selection is exactly when forgery-resistance matters most.
     */
    private boolean testReferencesChangedClass(FileMetadata md,
                                               Set<String> changedFqns,
                                               Set<String> simpleNames,
                                               Map<String, Set<String>> simpleNameToFqns) {
        Set<String> importedFqns = new HashSet<>();
        Set<String> wildcardPackages = new HashSet<>();
        for (FileMetadata.Import imp : md.imports()) {
            String name = imp.name();
            if (imp.isStatic()) {
                // Static imports are member-scoped, not type-scoped. The
                // name reported by JavaParser for `import static a.b.C.MAX;` is
                // `a.b.C.MAX` and for `import static a.b.C.*;` is `a.b.C`
                // (with isAsterisk=true). The thing a test actually depends
                // on in both cases is the class `a.b.C`, so we normalise
                // back to the class FQN for direct-import matching.
                String classFqn = imp.isAsterisk()
                        ? name
                        : stripLastSegment(name);
                if (classFqn != null) {
                    importedFqns.add(classFqn);
                }
            } else if (imp.isAsterisk()) {
                wildcardPackages.add(name);
            } else {
                importedFqns.add(name);
            }
        }

        // Tier 1: Direct import match. `innerClassMatch` also fires when an
        // import targets a nested class of the changed outer — e.g. the test
        // writes `import c.d.Outer.Inner;` and the diff touches `c.d.Outer`
        // (PathToClassMapper is file-based, so it only surfaces the outer FQN
        // for the nested class's change). Pre-this-tier, a test that only
        // uses the inner class was silently missed.
        for (String changedFqn : changedFqns) {
            if (importedFqns.contains(changedFqn)) {
                log.debug("  Direct import match: {}", LogSanitizer.sanitize(changedFqn));
                return true;
            }
            String innerPrefix = changedFqn + ".";
            for (String imported : importedFqns) {
                if (imported.startsWith(innerPrefix)) {
                    log.debug("  Inner-class import match: {} <- {}",
                            LogSanitizer.sanitize(changedFqn),
                            LogSanitizer.sanitize(imported));
                    return true;
                }
            }
        }

        Set<String> typeRefSimpleNames = md.typeRefSimpleNames();
        Set<String> typeRefDottedNames = md.typeRefDottedNames();

        // Tier 1b: Wildcard import match. Two shapes have to be handled:
        //
        //   * `import com.example.service.*;`      — a package wildcard;
        //     the test may reference any simple type inside that package.
        //     We gate on the simple name actually appearing in the AST so
        //     we don't over-select.
        //
        //   * `import com.example.Outer.*;`        — a class-member
        //     wildcard. Every member of `Outer` (including its nested
        //     types and public static members) is visible in the test
        //     without further qualification, so a change to `Outer.java`
        //     — which PathToClassMapper reports as a change to
        //     `com.example.Outer` — must pull the test in unconditionally;
        //     the test doesn't have to mention `Outer` by name at all.
        for (String changedFqn : changedFqns) {
            if (wildcardPackages.contains(changedFqn)) {
                log.debug("  Wildcard class-member import match: {}",
                        LogSanitizer.sanitize(changedFqn));
                return true;
            }
            String pkg = SourceFileScanner.packageOf(changedFqn);
            if (wildcardPackages.contains(pkg)) {
                String simpleName = SourceFileScanner.simpleClassName(changedFqn);
                if (typeRefSimpleNames.contains(simpleName)) {
                    log.debug("  Wildcard package import + type ref match: {}",
                            LogSanitizer.sanitize(changedFqn));
                    return true;
                }
            }
        }

        // Tier 2: Same-package (no import needed).
        String testPackage = md.packageName();
        for (String changedFqn : changedFqns) {
            String changedPkg = SourceFileScanner.packageOf(changedFqn);
            if (testPackage.equals(changedPkg)) {
                String simpleName = SourceFileScanner.simpleClassName(changedFqn);
                if (typeRefSimpleNames.contains(simpleName)) {
                    log.debug("  Same-package type ref match: {}",
                            LogSanitizer.sanitize(changedFqn));
                    return true;
                }
            }
        }

        // Tier 3: Fully-qualified inline references that never went through
        // an import. Catches
        //   `com.example.other.Thing t = new com.example.other.Thing();`
        //   `(com.example.other.Thing) x`
        //   `com.example.other.Thing.Inner nested = ...;`
        // anywhere the test author typed the full dotted name of the changed
        // class at a use site. The bare-name case is already handled by
        // Tier 1 / 1b / 2; the dotted set is filtered to dotted entries only
        // to avoid double-counting.
        for (String scoped : typeRefDottedNames) {
            for (String changedFqn : changedFqns) {
                if (scoped.equals(changedFqn) || scoped.startsWith(changedFqn + ".")) {
                    log.debug("  Inline fully-qualified reference: {} -> {}",
                            LogSanitizer.sanitize(scoped),
                            LogSanitizer.sanitize(changedFqn));
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns {@code name} with the final {@code .segment} removed. For
     * {@code "a.b.C.MAX"} returns {@code "a.b.C"}; for a single-segment
     * input returns {@code null} (the input is already as stripped as it
     * can be and clearly wasn't a qualified member reference).
     */
    static String stripLastSegment(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            return null;
        }
        return name.substring(0, idx);
    }

    private String extractFqn(FileMetadata md, Path testFile) {
        for (String testDir : config.testDirs()) {
            Path testRoot = SourceFileScanner.findTestRoot(testFile, testDir);
            if (testRoot != null) {
                Path relative = testRoot.relativize(testFile);
                String fqn = relative.toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replace('/', '.');
                fqn = SourceExtensions.stripKnownExtension(fqn);
                return fqn;
            }
        }

        String pkg = md.packageName();
        String primary = md.primaryTypeName();
        if (primary == null) return null;
        return pkg.isEmpty() ? primary : pkg + "." + primary;
    }
}
