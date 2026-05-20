package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
 * <p>Tiers&nbsp;1b/2/3 all need to know which simple names and which dotted
 * scoped names appear as type references anywhere in the AST. Both sets are
 * built lazily in a single pass over {@code ClassOrInterfaceType} nodes and
 * shared across the three tiers — see {@link UsageStrategy.AstReferences}.
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

        JavaParser fallbackParser = (index == null) ? JavaParsers.newParser() : null;

        for (Path testFile : testFiles) {
            CompilationUnit cu = parseOrGet(testFile, index, fallbackParser);
            if (cu == null) continue;

            String testFqn = extractFqn(cu, testFile);
            if (testFqn == null) continue;

            if (changedFqns.contains(testFqn)) continue;

            if (testReferencesChangedClass(cu, changedFqns, simpleNames, simpleNameToFqns)) {
                discoveredTests.add(testFqn);
                log.debug("Usage match: {}", LogSanitizer.sanitize(testFqn));
            }
        }

        log.info("[usage] Discovered {} tests for {} changed classes",
                discoveredTests.size(), changedProductionClasses.size());
        return discoveredTests;
    }

    private CompilationUnit parseOrGet(Path file, ProjectIndex index, JavaParser fallbackParser) {
        if (index != null) {
            return index.compilationUnit(file);
        }
        return JavaParsers.parseOrWarn(fallbackParser, file, "usage");
    }

    /**
     * Checks whether a test compilation unit references any of the changed classes.
     * Tiered, with the cheapest tier first:
     *
     * <ol>
     *   <li><strong>Tier 1 — direct import match</strong>: pure scan of the
     *       compilation unit's import list. No AST walk, no allocation per
     *       changed class.</li>
     *   <li><strong>Tier 1b/2/3</strong>: any of these need to know which
     *       simple names and which dotted scoped names appear as type
     *       references anywhere in the AST. Both sets are built lazily in a
     *       single {@link CompilationUnit#findAll(Class)} walk over
     *       {@link ClassOrInterfaceType} — {@link AstReferences#of(CompilationUnit)}.
     *       Pre-this-refactor the same information was rebuilt by up to
     *       five separate AST walks per file (four inside the now-removed
     *       {@code typeNameAppearsInAst} helper, plus one explicit walk in
     *       Tier&nbsp;3); on a typical service that's a 3–5× cost on the
     *       hot path.</li>
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
    private boolean testReferencesChangedClass(CompilationUnit cu,
                                               Set<String> changedFqns,
                                               Set<String> simpleNames,
                                               Map<String, Set<String>> simpleNameToFqns) {
        Set<String> importedFqns = new HashSet<>();
        Set<String> wildcardPackages = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isStatic()) {
                // Static imports are member-scoped, not type-scoped. The name
                // reported by JavaParser for `import static a.b.C.MAX;` is
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

        // Single AST walk shared by Tier 1b, Tier 2, and Tier 3. Built lazily
        // so a file that resolves on Tier 1 (the common case for tests with
        // explicit imports) never pays for the walk.
        AstReferences refs = AstReferences.of(cu);

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
                if (refs.simpleNames.contains(simpleName)) {
                    log.debug("  Wildcard package import + type ref match: {}",
                            LogSanitizer.sanitize(changedFqn));
                    return true;
                }
            }
        }

        // Tier 2: Same-package (no import needed).
        String testPackage = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        for (String changedFqn : changedFqns) {
            String changedPkg = SourceFileScanner.packageOf(changedFqn);
            if (testPackage.equals(changedPkg)) {
                String simpleName = SourceFileScanner.simpleClassName(changedFqn);
                if (refs.simpleNames.contains(simpleName)) {
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
        for (String scoped : refs.dottedNames) {
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
     * Snapshot of every type reference in a compilation unit, in the two
     * shapes the tiered matcher needs:
     *
     * <ul>
     *   <li>{@link #simpleNames} — the unqualified leaf name of every
     *       {@link ClassOrInterfaceType} node (e.g. both {@code List} and
     *       {@code Foo} for source {@code List<Foo>}, both {@code Outer}
     *       and {@code Inner} for source {@code Outer.Inner}). Used by
     *       Tier&nbsp;1b and Tier&nbsp;2 for membership checks against
     *       changed-class simple names.</li>
     *   <li>{@link #dottedNames} — the {@code getNameWithScope()} string of
     *       every type node whose result is a dotted form (i.e. has at
     *       least one {@code .}). Used by Tier&nbsp;3 to match
     *       fully-qualified inline references.</li>
     * </ul>
     *
     * <p>Both sets are built in a single {@code findAll(ClassOrInterfaceType.class)}
     * walk. Walking once and indexing replaces the previous shape, where
     * the same information was implicitly rebuilt by repeated AST walks
     * (one per changed-class match attempt × per file). The hot path
     * — {@code testReferencesChangedClass} called per test file × per
     * changed class — drops from O(walk × matches) to O(walk + matches).
     */
    private static final class AstReferences {
        final Set<String> simpleNames;
        final Set<String> dottedNames;

        private AstReferences(Set<String> simpleNames, Set<String> dottedNames) {
            this.simpleNames = simpleNames;
            this.dottedNames = dottedNames;
        }

        static AstReferences of(CompilationUnit cu) {
            Set<String> simpleNames = new HashSet<>();
            Set<String> dottedNames = new HashSet<>();
            for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
                simpleNames.add(type.getNameAsString());
                String scoped = nameWithScopeOrNull(type);
                if (scoped != null && scoped.indexOf('.') >= 0) {
                    dottedNames.add(scoped);
                }
            }
            return new AstReferences(simpleNames, dottedNames);
        }
    }

    /**
     * Returns the dotted name of {@code type} including its enclosing
     * scope chain, or {@code null} when JavaParser throws while
     * reconstructing it (e.g. a partially-resolved type node in an
     * invalid source file). Isolating the guard here means the caller
     * never has to defensively wrap the AST walk — a best-effort null
     * is enough to skip a single type node while the rest of the file
     * still contributes to discovery.
     */
    private static String nameWithScopeOrNull(ClassOrInterfaceType type) {
        try {
            return type.getNameWithScope();
        } catch (RuntimeException e) {
            return null;
        }
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

    private String extractFqn(CompilationUnit cu, Path testFile) {
        for (String testDir : config.testDirs()) {
            Path testRoot = SourceFileScanner.findTestRoot(testFile, testDir);
            if (testRoot != null) {
                Path relative = testRoot.relativize(testFile);
                String fqn = relative.toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replace('/', '.');
                if (fqn.endsWith(".java")) {
                    fqn = fqn.substring(0, fqn.length() - 5);
                }
                return fqn;
            }
        }

        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        return cu.getPrimaryTypeName()
                .map(name -> pkg.isEmpty() ? name : pkg + "." + name)
                .orElse(null);
    }
}
