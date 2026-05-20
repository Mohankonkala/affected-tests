package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Strategy: Naming Convention.
 * <p>
 * For each changed production class {@code com.example.FooBar}, looks for test
 * classes named {@code FooBarTest}, {@code FooBarIT}, {@code FooBarITTest},
 * {@code FooBarIntegrationTest}, etc. in the configured test directories.
 *
 * <p><b>Cross-package over-selection (issue #40):</b> the simple-name match
 * is intentionally package-agnostic — a test in {@code package other.pkg}
 * named {@code FooBarTest} still matches a changed
 * {@code com.example.service.FooBar} so that adopters who keep tests in
 * a deliberately different package than the SUT (parallel test trees,
 * Cucumber-shape harnesses, etc.) are not silently under-selected. The
 * cost of that policy is over-selection when two production classes
 * legitimately share a simple name across packages: a change to
 * {@code a.b.Foo} can pull in {@code e.f.FooTest} that actually
 * exercises {@code c.d.Foo}.
 *
 * <p>To make the over-selection visible without flipping the policy
 * underneath adopters, every match where {@code packageOf(testFqn)} does
 * not equal {@code packageOf(changedProductionFqn)} is also recorded in
 * {@link #crossPackageMatches()}; the engine threads that map onto
 * {@link io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult}
 * so {@code --explain} can render a hint pointing the operator at the
 * exact pairs. Same-package matches are not recorded — they're the
 * common case and noise-free.
 */
public final class NamingConventionStrategy implements TestDiscoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(NamingConventionStrategy.class);

    private final AffectedTestsConfig config;

    /**
     * Diagnostic accumulator: changed production FQN -> set of test FQNs
     * that matched on simple name but live in a different package. State
     * lives on the strategy instance (not thread-local) because the
     * engine creates exactly one strategy per run and the discovery
     * pipeline is single-threaded; the engine picks up the map after
     * every {@code discoverTests} call returns.
     *
     * <p>Crucially this accumulates across multiple {@code discoverTests}
     * invocations on the same instance — that's deliberate, because
     * {@link TransitiveStrategy} re-invokes naming on the union of
     * transitive consumers, and a cross-package over-select on a
     * transitive consumer is just as much a false positive as one on a
     * directly-changed class.
     */
    private final Map<String, Set<String>> crossPackageMatches = new LinkedHashMap<>();

    public NamingConventionStrategy(AffectedTestsConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "naming";
    }

    @Override
    public Set<String> discoverTests(Set<String> changedProductionClasses, Path projectDir) {
        Set<String> allTestFqns = SourceFileScanner.scanTestFqns(projectDir, config.testDirs());
        return matchTests(changedProductionClasses, allTestFqns);
    }

    /**
     * Discovers tests using a pre-built project index (avoids redundant file walks).
     */
    public Set<String> discoverTests(Set<String> changedProductionClasses, ProjectIndex index) {
        return matchTests(changedProductionClasses, index.testFqns());
    }

    /**
     * Returns the cross-package over-selections observed since this
     * strategy was constructed. Keyed by changed production FQN, valued
     * by the set of cross-package test FQNs that matched it. Empty
     * after a fresh construction; populated as a side effect of
     * {@link #discoverTests}. Returned as an unmodifiable view of the
     * live map — callers must not mutate it.
     *
     * <p>The engine consumes this immediately after the discovery pass
     * and feeds it onto the result record so the {@code --explain}
     * renderer can decide whether to emit the cross-package hint.
     */
    public Map<String, Set<String>> crossPackageMatches() {
        return Collections.unmodifiableMap(crossPackageMatches);
    }

    private Set<String> matchTests(Set<String> changedProductionClasses, Set<String> allTestFqns) {
        Set<String> discoveredTests = new LinkedHashSet<>();

        Map<String, Set<String>> expectedTestNames = new HashMap<>();
        for (String fqn : changedProductionClasses) {
            String simpleName = SourceFileScanner.simpleClassName(fqn);
            Set<String> candidates = new LinkedHashSet<>();
            for (String suffix : config.testSuffixes()) {
                candidates.add(simpleName + suffix);
            }
            expectedTestNames.put(fqn, candidates);
        }

        for (String testFqn : allTestFqns) {
            String testSimpleName = SourceFileScanner.simpleClassName(testFqn);
            String testPackage = SourceFileScanner.packageOf(testFqn);
            for (var entry : expectedTestNames.entrySet()) {
                if (entry.getValue().contains(testSimpleName)) {
                    discoveredTests.add(testFqn);
                    String changedFqn = entry.getKey();
                    // Both keys (changed production FQNs from the diff)
                    // and test FQNs (from the scanned source tree) are
                    // attacker-influenced on a merge-gate run.
                    log.debug("Naming match: {} → {}",
                            LogSanitizer.sanitize(changedFqn),
                            LogSanitizer.sanitize(testFqn));

                    // Same-package matches are the silent-success path
                    // and we deliberately don't record them — the hint
                    // is meant to flag the suspicious shape, not narrate
                    // every match. Cross-package matches go on the
                    // diagnostic accumulator that the engine threads
                    // onto the result for --explain.
                    if (!testPackage.equals(SourceFileScanner.packageOf(changedFqn))) {
                        crossPackageMatches
                                .computeIfAbsent(changedFqn, k -> new LinkedHashSet<>())
                                .add(testFqn);
                    }
                }
            }
        }

        log.info("[naming] Discovered {} tests for {} changed classes ({} cross-package match(es))",
                discoveredTests.size(), changedProductionClasses.size(),
                crossPackageMatches.values().stream().mapToInt(Set::size).sum());
        return discoveredTests;
    }
}
