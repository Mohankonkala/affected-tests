package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-engine registry of {@link LanguageParser} implementations,
 * keyed by file extension. Single source of truth for "which parser
 * handles which extension" in a given engine run — adding a new
 * language is one entry in {@link #forConfig(AffectedTestsConfig)}
 * plus one entry in {@link SourceExtensions#EXTENSIONS}.
 *
 * <p>Pre-PR-3 of issue #76 this class was a static utility holding a
 * {@code static final Map<String, LanguageParser>}. PR #3 converts
 * to a per-engine instance because Kotlin's
 * {@code KotlinCoreEnvironment} carries lifecycle state that must
 * not be shared across engine runs (multi-MB MockApplication +
 * extension-point registry; the IntelliJ platform is not designed
 * for many MockApplications coexisting in the same JVM — see
 * docs/PHASE-2-KOTLIN-AST.md §3.4). Each {@link ProjectIndex} owns
 * a registry constructed from its
 * {@link AffectedTestsConfig#kotlinEnabled()} flag and disposes it
 * on engine shutdown.
 *
 * <p>Used by:
 *
 * <ul>
 *   <li>{@link ProjectIndex#compilationUnit(Path)} — dispatches by
 *       extension via the per-instance registry; non-Java
 *       extensions return {@code null} for the
 *       {@link com.github.javaparser.ast.CompilationUnit}-typed
 *       surface (Kotlin metadata flows through
 *       {@link LanguageParser#parseOrWarn} directly, not via a
 *       Java AST CompilationUnit).</li>
 *   <li>{@link ProjectIndex#fileMetadata(Path)} — dispatches by
 *       extension via the per-instance registry; non-Java parsers
 *       bump {@code parseFailureCount} on null so malformed Kotlin
 *       still escalates via {@code DISCOVERY_INCOMPLETE}.</li>
 *   <li>{@link UsageStrategy#metadataOrGet},
 *       {@link ImplementationStrategy#metadataOrGet},
 *       {@link TransitiveStrategy#metadataOrGet} — the no-index
 *       fallback path. Standalone strategy tests do not have an
 *       engine-scoped registry available, so they route through
 *       {@link #defaultJavaOnly()}, which is the safe-no-Kotlin
 *       posture (Kotlin participation requires the engine to bring
 *       up + dispose a {@link KotlinLanguageParser} lifecycle, and
 *       a unit test driving a strategy directly must not be
 *       responsible for that).</li>
 * </ul>
 *
 * <p>Visibility is package-private. Strategies, {@link ProjectIndex},
 * and the engine live in this package; outside callers should
 * consume {@link FileMetadata} via the strategy's public
 * {@code discoverTests} surface, not by reaching into the registry.
 */
final class LanguageParsers implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LanguageParsers.class);

    /**
     * Singleton registry containing only {@link JavaLanguageParser}.
     * Lifecycle-free (Java's parsers are thread-locals shared across
     * every engine run for the JVM's life) and shared across every
     * caller that does not need a per-engine instance:
     *
     * <ul>
     *   <li>Strategy fallback path ({@code metadataOrGet} when the
     *       supplied {@code ProjectIndex} is {@code null}, the
     *       standalone-test posture).</li>
     *   <li>Tests that probe the registry directly without spinning
     *       up a {@link ProjectIndex}.</li>
     * </ul>
     *
     * <p>Held as a {@code static final} instance so concurrent
     * unit-test callers do not race to construct redundant
     * registries. {@link #close()} is a no-op for this instance:
     * the singleton outlives every test and every engine run, so
     * disposing it would brick subsequent callers.
     */
    private static final LanguageParsers DEFAULT_JAVA_ONLY =
            new LanguageParsers(Map.of(JavaLanguageParser.INSTANCE.extension(),
                    JavaLanguageParser.INSTANCE),
                    /* lifecycleOwned */ false,
                    KotlinDiagnostics.EMPTY);

    /**
     * Map of registered parsers, keyed by lowercased extension
     * (with leading dot — see {@link LanguageParser#extension()}).
     * Insertion-ordered for deterministic iteration during
     * {@link #close()} so any post-mortem log of disposal is stable
     * across runs. Defensively copied at construction.
     */
    private final Map<String, LanguageParser> byExtension;

    /**
     * Whether {@link #close()} should walk the registered parsers
     * and dispose them. {@code false} for {@link #DEFAULT_JAVA_ONLY}
     * (the singleton must not be torn down) and any other shared
     * registry; {@code true} for instances built from a
     * per-engine config via {@link #forConfig(AffectedTestsConfig)}
     * — those instances own the parsers' resources and are
     * responsible for releasing them on engine shutdown.
     */
    private final boolean lifecycleOwned;

    /**
     * Per-engine Kotlin diagnostics carrier. Populated by
     * {@link KotlinLanguageParser} on the four adopter-visible
     * signals (issue #76 PR #4); read by the engine + AffectedTestTask
     * to render the four pinned --explain strings. Set to
     * {@link KotlinDiagnostics#EMPTY} for the Java-only registries
     * (fallback singleton + Kotlin-disabled per-engine instances) so
     * callers can read the field unconditionally without a null
     * branch.
     */
    private final KotlinDiagnostics kotlinDiagnostics;

    private LanguageParsers(Map<String, LanguageParser> byExtension,
                            boolean lifecycleOwned,
                            KotlinDiagnostics kotlinDiagnostics) {
        // Defensive copy + insertion order. {@link Map#copyOf}
        // produces an {@code ImmutableCollections.MapN} with
        // hash-based iteration for 2+ entries — wrapping a
        // {@link LinkedHashMap} in {@code Map.copyOf} would defeat
        // the order property we want to preserve. Use
        // {@link Collections#unmodifiableMap} on a defensive
        // {@link LinkedHashMap} copy to actually keep the
        // registration order so the disposal log is stable across
        // runs.
        this.byExtension =
                Collections.unmodifiableMap(new LinkedHashMap<>(byExtension));
        this.lifecycleOwned = lifecycleOwned;
        this.kotlinDiagnostics = kotlinDiagnostics == null
                ? KotlinDiagnostics.EMPTY
                : kotlinDiagnostics;
    }

    /**
     * Returns the shared Java-only registry. Used by the strategy
     * fallback path when no per-engine registry is available — the
     * standalone-test posture where strategies are exercised
     * without an engine-supplied {@link ProjectIndex}.
     *
     * <p>The singleton is lifecycle-free so callers do not need to
     * close it; in fact closing it is a no-op so a misconfigured
     * test that calls {@code defaultJavaOnly().close()} does not
     * brick subsequent tests.
     */
    static LanguageParsers defaultJavaOnly() {
        return DEFAULT_JAVA_ONLY;
    }

    /**
     * Builds a fresh registry shaped by {@code config}. Always
     * registers {@link JavaLanguageParser}; additionally registers
     * {@code KotlinLanguageParser} when
     * {@link AffectedTestsConfig#kotlinEnabled()} is {@code true}.
     *
     * <p>The returned instance owns the lifecycle of any
     * Kotlin-specific resources it constructs, so callers must close
     * it (typically via try-with-resources on the
     * {@link ProjectIndex} that wraps it) once discovery is done.
     *
     * <p>If {@code config.kotlinEnabled()} is false, the returned
     * instance is a thin wrapper around the same single-parser map
     * the {@link #defaultJavaOnly()} singleton uses, but with its
     * own {@code lifecycleOwned} flag set to {@code true}.
     * {@code close()} on a Java-only instance is still a no-op (the
     * static {@link JavaLanguageParser#INSTANCE} is process-wide),
     * but keeping the flag {@code true} means a Kotlin parser added
     * later via a different code path does get disposed — defence
     * in depth against a future refactor that loosens the contract.
     */
    static LanguageParsers forConfig(AffectedTestsConfig config) {
        LinkedHashMap<String, LanguageParser> parsers = new LinkedHashMap<>();
        parsers.put(JavaLanguageParser.INSTANCE.extension(), JavaLanguageParser.INSTANCE);
        // Allocate the diagnostics carrier unconditionally. When
        // Kotlin participation is off the carrier stays empty (no
        // parser writes to it), but holding a non-null reference
        // simplifies the engine read path: AffectedTestTask can
        // unconditionally call {@code result.kotlinDiagnostics()}
        // and skip the --explain block when {@link
        // KotlinDiagnostics#isEmpty()} is true. The bytes saved by
        // null-checking on the cold path are not worth the call-site
        // branch.
        KotlinDiagnostics kotlinDiagnostics = new KotlinDiagnostics();
        if (config.kotlinEnabled()) {
            // Construction is cheap — KotlinCoreEnvironment is built
            // lazily on the first .kt parse, not at registry build
            // time. The registry just holds the wrapper object; the
            // multi-MB embeddable bootstrap is paid only if the
            // engine actually parses a .kt file. See
            // docs/PHASE-2-KOTLIN-AST.md §3.4 for the lifecycle
            // protocol the parser implements.
            KotlinLanguageParser kotlin = new KotlinLanguageParser(kotlinDiagnostics);
            parsers.put(kotlin.extension(), kotlin);
        }
        return new LanguageParsers(parsers, /* lifecycleOwned */ true, kotlinDiagnostics);
    }

    /**
     * Returns the per-engine Kotlin diagnostics carrier shared by
     * the registered {@link KotlinLanguageParser} (if any) with the
     * engine and AffectedTestTask. Always non-null; returns
     * {@link KotlinDiagnostics#EMPTY} when Kotlin participation is
     * off so the renderer can poll without a null branch.
     */
    KotlinDiagnostics kotlinDiagnostics() {
        return kotlinDiagnostics;
    }

    /**
     * @return the parser registered for {@code file}'s lowercased
     *         extension, or {@code null} if no parser is
     *         registered. {@code null} is the canonical signal for
     *         "this extension has no parser available in the
     *         current rollout phase" (e.g. {@code .kt} when the
     *         DSL flag {@code kotlinEnabled} is set to {@code false}
     *         — the documented escape hatch since PR #4 dropped the
     *         system-property gate, defaulting Kotlin AST to on —
     *         or always for {@code .kts} / {@code .groovy} /
     *         {@code .scala}). Callers translate {@code null} into
     *         "skip this file" — the same posture
     *         {@link LanguageParser#parseOrWarn} returning
     *         {@code null} carries.
     */
    LanguageParser forFile(Path file) {
        if (file == null) return null;
        // Single source of truth for the path → extension contract.
        // {@link SourceExtensions#extensionOf} already enforces:
        // case-insensitive match, leading-dot canonical form, and
        // the non-empty-stem rule (a literal {@code .kt} dotfile
        // is rejected so the FQN pipeline never sees an empty
        // stem). It also returns {@code null} for any extension
        // outside the recognised source-language set, which is
        // strictly tighter than "any suffix after the last dot" —
        // a {@code Foo.txt} feeding the registry produces a
        // {@code null} lookup either way, but routing through
        // {@code SourceExtensions} keeps the parser-side scope a
        // strict subset of the scanner-side scope syntactically,
        // not just by convention.
        return forExtension(SourceExtensions.extensionOf(file.toString()));
    }

    /**
     * Lower-level lookup by extension string. Used by
     * {@link #forFile} and by tests that want to verify
     * registration without spinning up a {@link Path}.
     *
     * @param ext lowercased extension including the leading dot
     *            (e.g. {@code ".java"}). Comparison is exact —
     *            callers that take input from the filesystem must
     *            lowercase first.
     */
    LanguageParser forExtension(String ext) {
        if (ext == null) return null;
        return byExtension.get(ext);
    }

    /**
     * Convenience for the strategy fallback path. Looks up the
     * parser for {@code file}'s extension and forwards to its
     * {@link LanguageParser#parseOrWarn}. Returns {@code null} if
     * no parser is registered (the file silently drops out of
     * discovery — same posture the strategies took pre-PR-2 when
     * a {@code .kt} file slipped past the scanner-side filter and
     * fed JavaParser, except now it's an explicit "no parser"
     * branch instead of a parse-failure WARN).
     *
     * <p>This collapses the four-line pre-PR-2 idiom
     *
     * <pre>{@code
     * CompilationUnit cu = JavaParsers.parseOrWarn(parser, file, label);
     * return cu == null ? null : FileMetadataExtractor.extract(cu);
     * }</pre>
     *
     * into a single dispatch call that's correct regardless of
     * which language the file is in.
     */
    FileMetadata parseOrWarn(Path file, String label) {
        LanguageParser parser = forFile(file);
        if (parser == null) return null;
        return parser.parseOrWarn(file, label);
    }

    /**
     * Disposes every registered parser if this instance owns their
     * lifecycle. No-op when called on the {@link #defaultJavaOnly()}
     * singleton: the singleton is process-wide and disposing it
     * would brick every subsequent fallback-path caller. Safe to
     * call more than once — {@link LanguageParser#close()} is
     * required to be idempotent.
     *
     * <p>Per-parser disposal failures are swallowed into a
     * {@code DEBUG} log line and disposal continues with the next
     * parser. The engine must not abort shutdown because one
     * parser failed to release its resources; the bounded leak of
     * a failed Kotlin disposal (one MockApplication per Gradle
     * daemon) is preferable to a half-shut-down engine that leaves
     * file locks held.
     */
    @Override
    public void close() {
        if (!lifecycleOwned) return;
        for (LanguageParser parser : byExtension.values()) {
            try {
                parser.close();
            } catch (Exception e) {
                // Swallow — see Javadoc above for the bounded-leak
                // tradeoff. Sanitisation is unnecessary because the
                // exception message comes from the parser
                // implementation (internal text), not from
                // attacker-committable file contents.
                log.debug("Affected Tests: language parser '{}' failed to close: {}",
                        parser.extension(), e.getMessage());
            }
        }
    }
}
