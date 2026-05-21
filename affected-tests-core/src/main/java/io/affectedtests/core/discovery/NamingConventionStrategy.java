package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
     * lives on the strategy instance because the engine creates
     * exactly one naming strategy per run, threads it through the
     * impl and transitive constructors, and picks up the merged map
     * after the discovery pass completes.
     *
     * <p>Crucially this accumulates across multiple {@code discoverTests}
     * invocations on the same instance — that's deliberate, because
     * {@link TransitiveStrategy} re-invokes naming on the union of
     * transitive consumers, and a cross-package over-select on a
     * transitive consumer is just as much a false positive as one on a
     * directly-changed class.
     *
     * <p><strong>Thread-safety (issue #42).</strong> The container is
     * a {@link ConcurrentHashMap} keyed by changed FQN; the per-key
     * set is built via {@link Map#computeIfAbsent} into a thread-safe
     * view over a {@link LinkedHashSet}. The OUTER map's iteration
     * order is intentionally not stable under parallel discovery, and
     * neither is the per-key set's order — multiple threads (impl
     * and transitive both call back into naming) may race to add
     * entries. The engine pins a deterministic order at the filter
     * boundary
     * ({@link io.affectedtests.core.AffectedTestsEngine}{@code #filterCrossPackageMatchesToSurvivors})
     * so the {@code --explain} hint and JSON output stay stable
     * across runs even though the underlying accumulator is
     * unordered.
     */
    private final ConcurrentHashMap<String, Set<String>> crossPackageMatches =
            new ConcurrentHashMap<>();

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
        // Per-call cross-package contribution. The cumulative
        // {@code crossPackageMatches} map is shared across every
        // {@code matchTests} invocation on this instance (impl and
        // transitive both call back into naming). Logging the
        // cumulative size at each call would falsely inflate the
        // number for the second + third calls — and under parallel
        // discovery would race with concurrent {@code .add} from
        // other threads. We log the per-call count here and let the
        // engine surface the aggregate via
        // {@link #crossPackageMatches()}.
        int crossPackageThisCall = 0;

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
                        // Per-key set is a synchronizedSet over a
                        // LinkedHashSet so we keep the diagnostic
                        // ordering inside one changed FQN's matches
                        // while remaining safe to mutate from
                        // multiple threads. The outer map already
                        // serialises the computeIfAbsent call so the
                        // SET is created exactly once per key.
                        // {@code .add} returns true on first
                        // insertion, so we count strictly new
                        // contributions on this call only.
                        if (crossPackageMatches
                                .computeIfAbsent(changedFqn,
                                        k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                                .add(testFqn)) {
                            crossPackageThisCall++;
                        }
                    }
                }
            }
        }

        log.info("[naming] Discovered {} tests for {} changed classes ({} cross-package match(es))",
                discoveredTests.size(), changedProductionClasses.size(),
                crossPackageThisCall);
        return discoveredTests;
    }
}
