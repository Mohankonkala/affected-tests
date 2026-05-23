package io.affectedtests.core.config;

/**
 * One of the named decision branches the engine can end up on. Every engine
 * run resolves to exactly one situation, which is then mapped to an
 * {@link Action} via the per-situation config in
 * {@link AffectedTestsConfig}.
 *
 * <p>The seven situations are deliberately exhaustive and mutually exclusive,
 * so a human reading the {@code --explain} output never has to reconstruct
 * which of several overlapping "safety net" branches the engine actually
 * took. The priority in {@link io.affectedtests.core.AffectedTestsEngine}
 * evaluates them in a fixed order — empty diff, all-ignored,
 * all-out-of-scope, unmapped, discovery-incomplete, discovery-empty,
 * discovery-success — so any diff maps to a single situation even when it
 * nominally touches several buckets.
 *
 * <p>Note: {@link io.affectedtests.core.mapping.PathToClassMapper} already
 * routes a file to at most one of its buckets (ignore, out-of-scope,
 * production, test, unmapped) by evaluating the same rules in the same
 * order, so the "all X" situations and {@link #UNMAPPED_FILE} are by
 * construction non-overlapping — there is no diff that is simultaneously
 * "all ignored" and "contains an unmapped file".
 */
public enum Situation {
    /** Git diff yielded zero changed files. */
    EMPTY_DIFF,

    /**
     * The diff contained at least one file that the mapper could not resolve
     * to a Java class under the configured source/test dirs, after honouring
     * ignore and out-of-scope rules. The classic example is
     * {@code application.yml} or {@code build.gradle}.
     */
    UNMAPPED_FILE,

    /**
     * Every file in the diff matched {@link AffectedTestsConfig#ignorePaths()}.
     * Distinct from {@link #ALL_FILES_OUT_OF_SCOPE} so users can treat
     * "purely docs changes" differently from "purely api-test changes".
     */
    ALL_FILES_IGNORED,

    /**
     * Every file in the diff sat under {@link AffectedTestsConfig#outOfScopeTestDirs()}
     * or {@link AffectedTestsConfig#outOfScopeSourceDirs()}. Primary motivator:
     * repos that want {@code api-test} / Cucumber / performance-test source
     * sets to silently skip {@code unitTest} dispatch instead of forcing a
     * full suite.
     */
    ALL_FILES_OUT_OF_SCOPE,

    /**
     * Discovery ran, but produced an empty test selection — either no
     * production change had any matching test by any strategy, or the diff
     * contained only production files with no test coverage at all. This is
     * the post-discovery counterpart to {@link #EMPTY_DIFF}.
     */
    DISCOVERY_EMPTY,

    /**
     * Discovery ran but at least one scanned Java file failed to parse, so
     * the selection (empty or non-empty) is known to be under-reported. The
     * engine routes here in preference to {@link #DISCOVERY_EMPTY} /
     * {@link #DISCOVERY_SUCCESS} whenever
     * {@link io.affectedtests.core.discovery.ProjectIndex#parseFailureCount()}
     * is non-zero.
     *
     * <p>Motivating class of bug: a test file using a Java language level
     * newer than {@link io.affectedtests.core.discovery.JavaLanguageParser} knows
     * about (or genuinely malformed source) produces {@code null} from
     * {@code JavaLanguageParser.compilationUnit} and silently drops out of usage /
     * transitive discovery. Before v1.9.22 the only surfacing was a WARN
     * at parse time; the engine still routed through {@code DISCOVERY_EMPTY}
     * or {@code DISCOVERY_SUCCESS}, and a caller grepping the
     * {@code Situation} could not tell "nothing matched" from "matching
     * failed because we couldn't read half the files". That asymmetry is
     * especially dangerous on merge-gate runs: the caller is optimising
     * test runtime, the parse failure silently under-selects, and the
     * lost coverage only shows up on main.
     *
     * <p>Defaults in the {@link AffectedTestsConfig} resolver are
     * conservative on purpose: {@link Mode#CI} and {@link Mode#STRICT}
     * escalate to {@link Action#FULL_SUITE}, {@link Mode#LOCAL} stays on
     * {@link Action#SELECTED} (dev sees the WARN and decides). Callers
     * can override per-situation via {@code onDiscoveryIncomplete}.
     */
    DISCOVERY_INCOMPLETE,

    /** Discovery ran and produced a non-empty test selection. */
    DISCOVERY_SUCCESS
}
