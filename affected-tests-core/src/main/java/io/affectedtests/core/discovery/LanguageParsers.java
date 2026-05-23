package io.affectedtests.core.discovery;

import java.nio.file.Path;
import java.util.Map;

/**
 * Registry of {@link LanguageParser} implementations, keyed by file
 * extension. Single source of truth for "which parser handles which
 * extension" — adding a new language is one entry here plus one
 * entry in {@link SourceExtensions#EXTENSIONS}.
 *
 * <p>Introduced in PR #2 of issue #76 (Phase 2 Kotlin AST). Today
 * the map only contains {@code .java} → {@link JavaLanguageParser}.
 * PR #3 of the rollout adds {@code .kt} →
 * {@code KotlinLanguageParser} (gated on
 * {@code -Daffected-tests.kotlin.enabled=true}); PR #4 flips the
 * flag default so Kotlin is registered unconditionally.
 *
 * <p>Used by:
 *
 * <ul>
 *   <li>{@link ProjectIndex#compilationUnit(Path)} — dispatches by
 *       extension; non-Java extensions return {@code null} for the
 *       {@code CompilationUnit}-typed surface (Kotlin metadata
 *       comes from {@link LanguageParser#parseOrWarn} directly,
 *       not via {@code CompilationUnit}).</li>
 *   <li>{@link UsageStrategy#metadataOrGet},
 *       {@link ImplementationStrategy#metadataOrGet},
 *       {@link TransitiveStrategy#metadataOrGet} — the no-index
 *       fallback path. Pre-PR-2 each of these called
 *       {@code JavaParsers.parseOrWarn(fallbackParser, file, label)}
 *       and ran the extractor inline, hard-coding Java even when
 *       the file was a {@code .kt} that PR #1 had widened the
 *       scanner to admit. Now they call
 *       {@link #parseOrWarn(Path, String)} which dispatches by
 *       extension.</li>
 * </ul>
 *
 * <p>Visibility is package-private. Strategies + {@link
 * ProjectIndex} live in this package; outside callers should
 * consume {@link FileMetadata} via the strategy's public
 * {@code discoverTests} surface, not by reaching into the registry.
 */
final class LanguageParsers {

    /**
     * Extension → parser map. Kept as a static-final so a typo at
     * registration time fails class-load, not at the first
     * adopter's first parse. Order is not significant; lookup is
     * keyed by lowercased extension (with leading dot).
     *
     * <p>{@code .kts} (Kotlin script) and {@code .gradle.kts}
     * (Gradle Kotlin DSL) are absent from this map on purpose.
     * They look up to {@code null} and route to
     * {@code unmappedChangedFiles}; Phase 1's polyglot hint
     * surfaces them separately.
     */
    private static final Map<String, LanguageParser> BY_EXTENSION =
            Map.of(JavaLanguageParser.INSTANCE.extension(), JavaLanguageParser.INSTANCE);

    private LanguageParsers() {
        // utility class
    }

    /**
     * @return the parser registered for {@code file}'s lowercased
     *         extension, or {@code null} if no parser is
     *         registered. {@code null} is the canonical signal for
     *         "this extension has no parser available in the
     *         current rollout phase" (e.g. {@code .kt} pre-PR-3,
     *         or always for {@code .kts} / {@code .groovy} /
     *         {@code .scala}). Callers translate {@code null} into
     *         "skip this file" — the same posture
     *         {@code parseOrWarn} returning {@code null} carries.
     */
    static LanguageParser forFile(Path file) {
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
    static LanguageParser forExtension(String ext) {
        if (ext == null) return null;
        return BY_EXTENSION.get(ext);
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
    static FileMetadata parseOrWarn(Path file, String label) {
        LanguageParser parser = forFile(file);
        if (parser == null) return null;
        return parser.parseOrWarn(file, label);
    }
}
