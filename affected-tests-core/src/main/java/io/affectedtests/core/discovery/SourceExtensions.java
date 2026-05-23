package io.affectedtests.core.discovery;

import java.util.Locale;
import java.util.Set;

/**
 * Centralised set of source-file extensions the discovery pipeline
 * recognises, plus helpers for path-suffix stripping.
 *
 * <p>Added in PR #1 of issue #76 (Phase 2 Kotlin AST). Pre-Phase-2
 * the codebase had at least five {@code .java}-only suffix-strip
 * sites scattered across {@code SourceFileScanner},
 * {@code PathToClassMapper}, and {@code UsageStrategy}; widening any
 * of them in isolation would have left the others silently producing
 * FQNs ending in {@code .kt} (literal segment after
 * {@code replace('/', '.')}) and poisoning every downstream strategy.
 * Routing every site through this helper keeps the supported-extensions
 * list in one place so a future Groovy / Scala extension can be added
 * with a single edit.
 *
 * <p>Phase 2 of issue #76 ships path-derived FQN mapping for
 * {@code .kt} (PR #1) and full Kotlin AST parsing
 * (PR #3, gated on {@code -Daffected-tests.kotlin.enabled=true} until
 * PR #4). See {@code docs/PHASE-2-KOTLIN-AST.md}.
 */
public final class SourceExtensions {

    /**
     * Source-file extensions the discovery pipeline maps.
     *
     * <p>Each entry includes the leading dot for cheap
     * {@link String#endsWith(String)} checks. The order is not
     * significant; callers iterate when they care about specific
     * shapes (e.g. {@link #extensionOf(String)} returns the longest
     * match in insertion order).
     */
    public static final Set<String> EXTENSIONS = Set.of(".java", ".kt");

    private SourceExtensions() {
        // utility class
    }

    /**
     * @return {@code true} if {@code path}'s lowercased form ends
     *         with any of {@link #EXTENSIONS} <em>and</em> the
     *         filename has a non-empty stem before the extension.
     *         A literal dotfile named {@code ".kt"} or {@code ".java"}
     *         (no characters before the dot) is rejected — pre-fix
     *         it would have produced an empty FQN that the
     *         naming-strategy probe would then concatenate with
     *         {@code "Test"} / {@code "IT"} / {@code "ITTest"} /
     *         {@code "IntegrationTest"}, dragging in any unrelated
     *         test class with one of those bare names. The original
     *         {@code .java}-only walker had the same hole; the
     *         centralised helper closes it for both languages.
     */
    public static boolean isSource(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext) && hasNonEmptyStem(path, ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the matching extension (lowercased, with dot) for
     * {@code path}, or {@code null} if none of {@link #EXTENSIONS}
     * matches <em>or</em> the filename has no stem before the
     * extension (see {@link #isSource} for the rejection rationale).
     * Comparison is case-insensitive but the returned value is the
     * canonical lowercased form so callers can rely on {@code ".kt"}
     * / {@code ".java"} for downstream lookups.
     */
    public static String extensionOf(String path) {
        if (path == null || path.isEmpty()) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext) && hasNonEmptyStem(path, ext)) {
                return ext;
            }
        }
        return null;
    }

    /**
     * @return {@code true} when the path's filename (the segment
     *         after the last {@code /} or {@code \}) has at least
     *         one character before the {@code ext} suffix. Rejects
     *         {@code ".kt"} but accepts {@code "a.kt"},
     *         {@code "src/main/java/.kt"} (no — basename is
     *         {@code .kt}), and {@code "src/main/java/Foo.kt"}.
     */
    private static boolean hasNonEmptyStem(String path, String ext) {
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String filename = sep >= 0 ? path.substring(sep + 1) : path;
        return filename.length() > ext.length();
    }

    /**
     * Strips a recognised source-file extension suffix from {@code value}
     * if present, returning the unchanged string otherwise.
     *
     * <p>Used at every site that converts a path-relative-to-source-root
     * into an FQN. Pre-PR-1 the codebase open-coded
     * {@code if (s.endsWith(".java")) s = s.substring(0, s.length() - 5);}
     * five times; routing through this helper means a future extension
     * addition (Groovy, Scala) is a one-line change.
     *
     * <p>The match is case-insensitive on the suffix (so
     * {@code "Foo.JAVA"} strips to {@code "Foo"}), matching the
     * filesystem-case-insensitive behaviour Java itself enforces on
     * macOS / Windows runners.
     */
    public static String stripKnownExtension(String value) {
        if (value == null || value.isEmpty()) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return value.substring(0, value.length() - ext.length());
            }
        }
        return value;
    }
}
