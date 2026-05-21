package io.affectedtests.core.discovery;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-run diagnostic capturing how the discovery phase actually executed.
 *
 * <p>Issue <a href="https://github.com/vedanthvdev/affected-tests/issues/42">#42</a>
 * introduces engine-level fan-out across the four discovery strategies; this
 * record is the answer to the question "did parallel actually help?". It is
 * surfaced via {@code --explain} so adopters can see the per-strategy
 * timings on a real diff and decide whether the more invasive intra-strategy
 * parallelism (Option 1 in the issue) is worth the engineering cost on
 * their workload, or whether the cheaper engine-level fan-out (Option 2,
 * shipped here) already saturates the value.
 *
 * <p>The profile is purely diagnostic — engine semantics do not depend on
 * any field below. Production callers can observe the data via
 * {@link io.affectedtests.core.AffectedTestsEngine.AffectedTestsResult#discoveryProfile()}.
 *
 * @param parallelEnabled    whether the engine ran in parallel mode
 * @param concurrencyLevel   the actual size of the discovery thread pool
 *                           (0 when serial); typically
 *                           {@code min(strategies.size(), availableProcessors)}
 *                           but exposed here so a future change to the
 *                           sizing heuristic is observable from a real
 *                           {@code --explain} run rather than from reading
 *                           the engine source
 * @param totalDiscoveryWallTime end-to-end wall time of the discovery
 *                           phase, INCLUDING pool spin-up / tear-down for
 *                           the parallel path. Comparing this to the sum
 *                           of {@link #perStrategyWallTime} reveals how
 *                           much of the wall time was overlapped — perfect
 *                           parallelism collapses to {@code max(perStrategy)},
 *                           pessimal sequentialism stays at the sum.
 * @param perStrategyWallTime per-strategy wall time, keyed by strategy
 *                           name ({@code "naming"}, {@code "usage"},
 *                           {@code "impl"}, {@code "transitive"}). A
 *                           strategy that was disabled in this run has
 *                           NO entry — absence is the signal, not a
 *                           {@code Duration.ZERO} placeholder, so a
 *                           consumer reading the JSON dump can tell the
 *                           difference between "ran in 0ns" (rare but
 *                           possible on a no-op) and "did not run".
 * @param perStrategyTestCount number of test FQNs each strategy
 *                           contributed before the engine merged into
 *                           the cross-strategy union. Sum may exceed the
 *                           final selection count because strategies
 *                           overlap (a test that matches both naming and
 *                           usage gets counted in both buckets here).
 */
public record DiscoveryProfile(
        boolean parallelEnabled,
        int concurrencyLevel,
        Duration totalDiscoveryWallTime,
        Map<String, Duration> perStrategyWallTime,
        Map<String, Integer> perStrategyTestCount
) {

    public DiscoveryProfile {
        Objects.requireNonNull(totalDiscoveryWallTime, "totalDiscoveryWallTime");
        perStrategyWallTime = Collections.unmodifiableMap(new LinkedHashMap<>(perStrategyWallTime));
        perStrategyTestCount = Collections.unmodifiableMap(new LinkedHashMap<>(perStrategyTestCount));
    }

    /** A profile representing "discovery did not run" (test-only fast path or empty diff). */
    public static DiscoveryProfile empty() {
        return new DiscoveryProfile(false, 0, Duration.ZERO,
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Strategy with the longest wall time, or {@code null} when "dominant"
     * has no meaning. Used by consumers (currently the {@code --explain}
     * renderer + tests) to label the dominant strategy in the per-strategy
     * breakdown — so when (e.g.) transitive consumes 90% of the discovery
     * wall time on a particular workload, that's the line the operator
     * sees first and the data justifying the next round of optimisation
     * work.
     *
     * <p>Returns {@code null} when:
     * <ul>
     *   <li>no strategies ran (empty profile), or</li>
     *   <li>only one strategy ran (calling that the "dominant" of one is
     *       semantically empty), or</li>
     *   <li>two or more strategies are tied at the top (a "dominant" hint
     *       that flips on a tie would be noise — no operator should be
     *       acting on a 2ms / 2ms split).</li>
     * </ul>
     *
     * <p>This is the single source of truth: callers should rely on
     * this method rather than re-deriving the dominant rule, so the
     * record and every renderer agree on what "dominant" means.
     */
    public String dominantStrategy() {
        if (perStrategyWallTime.size() < 2) {
            return null;
        }
        String firstName = null;
        long firstNanos = -1L;
        long secondNanos = -1L;
        for (Map.Entry<String, Duration> e : perStrategyWallTime.entrySet()) {
            long n = e.getValue().toNanos();
            if (n > firstNanos) {
                secondNanos = firstNanos;
                firstNanos = n;
                firstName = e.getKey();
            } else if (n > secondNanos) {
                secondNanos = n;
            }
        }
        return firstNanos > secondNanos ? firstName : null;
    }
}
