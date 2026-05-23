package io.affectedtests.core.discovery;

import java.nio.file.Path;

/**
 * Cross-language abstraction for the parser side of the discovery
 * pipeline. Each implementation owns the language-specific machinery
 * (JavaParser instance, Kotlin {@code KotlinCoreEnvironment}, …) and
 * surfaces a strategy-agnostic projection of the parsed file as a
 * {@link FileMetadata}.
 *
 * <p>Introduced in PR #2 of issue #76 (Phase 2 Kotlin AST). Pre-PR-2
 * the discovery pipeline hard-coded {@code JavaParsers.parseOrWarn}
 * everywhere a source file needed parsing — {@link ProjectIndex},
 * {@link UsageStrategy#metadataOrGet}, {@link
 * ImplementationStrategy#metadataOrGet},
 * {@link TransitiveStrategy#metadataOrGet}. PR #1 widened the
 * scanner-side extension scope to {@code .kt} but every parser-side
 * call site still assumed Java; the {@code .kt} short-circuit in
 * {@code ProjectIndex.compilationUnit} flagged this as a temporary
 * hack via a grep-able PR #1 marker. PR #2 introduces this
 * interface, registers a {@link JavaLanguageParser}, and routes every
 * parser-side site through {@link LanguageParsers#forFile} dispatch.
 * PR #3 plugs in {@code KotlinLanguageParser}; PR #4 flips the
 * {@code affected-tests.kotlin.enabled} flag default.
 *
 * <p>Implementations must be thread-safe for parallel discovery
 * (issue #42's posture). The Java implementation owns a
 * {@link ThreadLocal} of {@code JavaParser} (parser instances mutate
 * their {@code ParserConfiguration} during parse). Kotlin owns a
 * single shared {@code KotlinCoreEnvironment} per
 * {@link ProjectIndex} and wraps PSI traversals in
 * {@code runReadAction(...)} per the design in
 * {@code docs/PHASE-2-KOTLIN-AST.md} §3.4.
 *
 * <p>Implementations are {@link AutoCloseable} so a per-engine
 * registry can shed any language-specific resources at engine
 * shutdown. The default {@link #close()} is a no-op (Java has nothing
 * to dispose — its parsers are thread-locals collected with the
 * thread). {@code KotlinLanguageParser} overrides to dispose its
 * {@code parentDisposable}, which tears down the shared
 * {@code KotlinCoreEnvironment} + MockApplication. Calling
 * {@link #close()} more than once must be benign — the registry
 * may close a parser twice on shutdown if a malformed config
 * accidentally registers the same instance under two extensions.
 *
 * <p>Interface visibility is package-private on purpose. Only callers
 * inside {@code io.affectedtests.core.discovery} should look up
 * parsers; outside callers (the strategies, the engine) consume
 * {@link FileMetadata} via {@link ProjectIndex#fileMetadata(Path)}
 * or via {@link LanguageParsers#parseOrWarn(Path, String)} on the
 * standalone fallback path.
 */
interface LanguageParser extends AutoCloseable {

    /**
     * The lowercase file extension this parser claims, including
     * the leading dot (e.g. {@code ".java"}, {@code ".kt"}). Used
     * by {@link LanguageParsers} to register and look up the
     * parser. Returned value must be one of
     * {@link SourceExtensions#EXTENSIONS} so the scanner-side and
     * parser-side extension scopes stay in lock-step — adding a
     * new language is a two-line change (one extension entry, one
     * parser registration), not a five-site sweep.
     */
    String extension();

    /**
     * Parses {@code file} and returns the strategy-relevant
     * projection as {@link FileMetadata}, or {@code null} if the
     * file could not be parsed. Implementations emit a single
     * {@code WARN} line on parse failures so the silent-drop class
     * of bug — a single unparseable file removing itself from
     * Usage / Implementation / Transitive discovery — is visible
     * at the plugin's default log level.
     *
     * <p>The {@code label} string is prepended to the WARN line so
     * the operator can tell which discovery phase failed to parse
     * the file (e.g. {@code "index"}, {@code "usage"},
     * {@code "impl"}, {@code "transitive"}). Implementations
     * should treat the label as an internal constant — not
     * sanitised — and the file path as attacker-committable
     * (sanitise via
     * {@link io.affectedtests.core.util.LogSanitizer} before
     * logging).
     */
    FileMetadata parseOrWarn(Path file, String label);

    /**
     * Releases any per-engine resources the parser is holding. The
     * default implementation is a no-op so {@link JavaLanguageParser}
     * (whose state lives in {@code static ThreadLocal}s shared
     * across every engine run for the JVM's life) does not need to
     * override.
     *
     * <p>{@code KotlinLanguageParser} overrides this to dispose its
     * {@code parentDisposable}, which in turn disposes the
     * {@code KotlinCoreEnvironment} and the underlying
     * MockApplication / extension-point registry. The plan
     * (docs/PHASE-2-KOTLIN-AST.md §3.4) requires the disposal because
     * a leaked MockApplication leaves multi-MB of pinned state on the
     * Gradle-daemon classloader for the life of the daemon, plus the
     * IntelliJ platform was not designed for many MockApplications
     * coexisting in the same JVM.
     *
     * <p>Implementations must make this idempotent. {@link
     * AutoCloseable#close()} declares {@code throws Exception} but
     * implementations should swallow internal disposal failures into
     * a {@code DEBUG} log line — a parser that fails to release its
     * environment must not block engine shutdown.
     */
    @Override
    default void close() {
    }
}
