package io.affectedtests.core.discovery;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * {@link LanguageParser} backed by JavaParser. Owns the per-thread
 * {@link JavaParser} instance and the language-level constant.
 *
 * <p>Pre-PR-2 of issue #76 the JavaParser plumbing lived in
 * {@code JavaParsers} (a static utility holding the language-level
 * constant + a {@code parseOrWarn(JavaParser, Path, String)} helper)
 * plus a {@code static ThreadLocal<JavaParser>} on
 * {@link ProjectIndex}. PR #2 collapses both into this class so:
 *
 * <ul>
 *   <li>the parser-side dispatch is one extension → one parser, with
 *       no special cases in {@link ProjectIndex} — the prior
 *       PR #1 short-circuit + its grep marker are gone;</li>
 *   <li>the {@link ThreadLocal} life-cycle is owned by the parser
 *       implementation, not by the caller — when PR #3 adds
 *       {@code KotlinLanguageParser} (which owns a
 *       {@code KotlinCoreEnvironment} per {@link ProjectIndex}, not
 *       a {@code ThreadLocal}), {@link ProjectIndex} doesn't gain a
 *       second life-cycle protocol;</li>
 *   <li>the {@code newParser()} factory + {@code LANGUAGE_LEVEL}
 *       constant survive on this class for tests + adopters who want
 *       a raw {@link JavaParser} at the canonical level (for
 *       fixtures that build a {@link CompilationUnit} by hand and
 *       feed it to {@link FileMetadataExtractor#extract(CompilationUnit)}
 *       directly). Only the strategy / index hot-path moved.</li>
 * </ul>
 *
 * <p>Motivating bug for the language-level pin (preserved from
 * pre-PR-2 docstring): JavaParser 3.28's default
 * {@link LanguageLevel} is {@code JAVA_11}. Every strategy that
 * instantiated {@code new JavaParser()} silently failed to parse any
 * file using records, sealed types, or pattern matching — the parser
 * reported {@code isSuccessful() == false} for the whole compilation
 * unit, every call site then discarded the result, and the file
 * contributed nothing to Transitive / Implementation / Usage
 * discovery. Any consumer test whose only path to a changed class
 * went through a record (e.g.
 * {@code record UsdMoney(long cents) implements Money}) was silently
 * dropped on every MR. Setting the level to
 * {@link LanguageLevel#JAVA_25} (the highest stable level the
 * bundled parser understands) makes all stable Java syntax parse
 * cleanly. Language levels newer than the parser's build produce an
 * {@code IllegalArgumentException}, which is preferable to the
 * silent-drop failure mode we had before.
 */
final class JavaLanguageParser implements LanguageParser {

    /**
     * Highest stable (non-preview) language level supported by the
     * bundled JavaParser build (3.28.0 ships
     * {@code JAVA_1 … JAVA_25} + {@code BLEEDING_EDGE};
     * {@code JAVA_25} is the newest stable constant). Kept in one
     * place so when the parser dependency is bumped we only move
     * this constant up.
     *
     * <p>We deliberately avoid {@code BLEEDING_EDGE}: it lets
     * preview syntax leak in, which is both slower to parse and
     * more likely to behave inconsistently across JavaParser point
     * releases. Every stable level up to
     * {@link LanguageLevel#JAVA_25} is a strict syntactic superset
     * of older levels, so setting the constant at the top still
     * parses legacy projects cleanly.
     */
    static final LanguageLevel LANGUAGE_LEVEL = LanguageLevel.JAVA_25;

    /**
     * Process-wide singleton. Stateless — every parse goes through
     * the {@link #PARSER} {@link ThreadLocal} so concurrent callers
     * share neither {@code JavaParser} state nor any other mutable
     * field. {@link LanguageParsers#BY_EXTENSION} hands out this
     * instance for every {@code .java} lookup.
     */
    static final JavaLanguageParser INSTANCE = new JavaLanguageParser();

    private static final Logger log = LoggerFactory.getLogger(JavaLanguageParser.class);

    /**
     * Per-thread {@link JavaParser}. JavaParser instances are not
     * safe to share across threads: their
     * {@link ParserConfiguration} mutates during parse and the
     * symbol-resolver state is per-instance. {@link
     * ThreadLocal#withInitial} keeps the construction cost off the
     * critical path — each thread builds its parser exactly once,
     * on its first {@link #parseOrWarn} or {@link #compilationUnit}
     * call.
     *
     * <p>Held as a {@code static} field for the same reason the
     * pre-PR-2 {@code ProjectIndex.PARSER} was: an instance-level
     * {@code ThreadLocal} would entry a fresh slot in every running
     * thread's {@code ThreadLocalMap} for every engine run; on the
     * serial path the calling thread is a Gradle-daemon worker
     * that lives across builds, and the {@code JavaParser} values
     * would leak ~30–80 KB each until the worker dies
     * ({@code ThreadLocalMap} only expunges stale entries
     * opportunistically). Promoting to {@code static} gives us one
     * parser per thread, shared across every engine run the JVM
     * serves — bounded growth, zero adopter cost. Pool threads on
     * the parallel path are daemons that die at
     * {@code shutdown()}, so they shed their parser entries
     * naturally.
     *
     * <p>For the index-driven path this is a lateral move from
     * {@code ProjectIndex.PARSER} — same lifetime, same
     * per-thread shape, same growth bound. The strategy
     * standalone path's parser lifetime, however, expanded:
     * pre-PR-2 each strategy's no-index branch called
     * {@code JavaParsers.newParser()} once per
     * {@code discoverTests(...)} invocation (a fresh
     * {@link JavaParser}, GC'd at end of discovery), so a single
     * unit-test run could allocate and discard three parsers per
     * iteration. Post-PR-2 the standalone path shares this
     * thread-local with the index path — one parser per thread,
     * JVM-lifetime. Net effect: fewer GC-pressure spikes in
     * test suites that drive the strategies directly, slightly
     * larger steady-state memory for those tests (a single
     * ~30–80 KB parser instance per test thread). Unit-test
     * threads die at the end of the test class, so the leaked
     * memory bound is the test-class lifetime, not the JVM. No
     * adopter-visible posture change — the engine path was
     * already on the threadlocal.
     */
    private static final ThreadLocal<JavaParser> PARSER =
            ThreadLocal.withInitial(JavaLanguageParser::newParser);

    private JavaLanguageParser() {
    }

    /**
     * Creates a new {@link JavaParser} configured with the
     * plugin-wide language level. Use this in every place a parser
     * is constructed — do not call {@code new JavaParser()}
     * directly. Public-static so test fixtures that build a
     * {@link CompilationUnit} by hand can match the engine's
     * language-level posture.
     */
    static JavaParser newParser() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(LANGUAGE_LEVEL);
        return new JavaParser(config);
    }

    @Override
    public String extension() {
        return ".java";
    }

    @Override
    public FileMetadata parseOrWarn(Path file, String label) {
        CompilationUnit cu = compilationUnit(file, label);
        return cu == null ? null : FileMetadataExtractor.extract(cu);
    }

    /**
     * Parses {@code file} with the per-thread {@link JavaParser}
     * and returns the resulting {@link CompilationUnit}, or
     * {@code null} if the file could not be parsed. Provided as a
     * Java-specific surface in addition to the
     * {@link LanguageParser#parseOrWarn} interface method because
     * {@link ProjectIndex#compilationUnit(Path)} is part of the
     * pre-PR-2 public surface and still hands out raw
     * {@link CompilationUnit}s to callers that haven't migrated to
     * {@link FileMetadata} yet.
     *
     * <p>Emits a single {@code WARN} line on unsuccessful parses
     * so the silent-drop class of bug — a single file unparseable
     * at {@link #LANGUAGE_LEVEL} silently removes itself from
     * discovery — is visible at the plugin's default log level
     * instead of hiding under {@code DEBUG} the way the pre-v1.9.20
     * call sites did.
     *
     * <p>The {@code label} is prepended to the log line so the
     * operator can see which discovery phase failed to parse the
     * file (e.g. {@code transitive}, {@code impl}, {@code usage},
     * {@code index}).
     */
    CompilationUnit compilationUnit(Path file, String label) {
        try {
            ParseResult<CompilationUnit> result = PARSER.get().parse(file);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result.getResult().get();
            }
            // isSuccessful == false → JavaParser produced a result
            // but flagged one or more problems. Most often on
            // Java-version mismatches and partial source. Surface
            // the first problem so operators can tell whether the
            // file needs a dependency bump or is genuinely
            // malformed.
            String firstProblem = result.getProblems().isEmpty()
                    ? "no diagnostics"
                    : result.getProblems().get(0).getMessage();
            // Both {@code file} (attacker-committable filename
            // from the scanned source tree) and
            // {@code firstProblem} (parser diagnostic that can
            // embed a source-code snippet sourced from an
            // attacker-controlled file) flow into a default-
            // visible WARN line. Sanitise both so an attacker who
            // plants a file with a {@code \n} + fake authoritative
            // status line in the name or content cannot forge CI
            // log output. Label is an internal constant, not
            // sanitised.
            log.warn("Affected Tests: [{}] failed to parse {} at language level {}: {}",
                    label,
                    LogSanitizer.sanitize(String.valueOf(file)),
                    LANGUAGE_LEVEL,
                    LogSanitizer.sanitize(firstProblem));
            return null;
        } catch (Exception e) {
            // Preserve the pre-v1.9.20 behaviour of degrading the
            // exception path to DEBUG (I/O races, file-deleted-
            // under-JGit, etc. are noisy on CI) while still
            // surfacing the much-more-common isSuccessful==false
            // branch above. Sanitise the same way as the WARN
            // branch so that if an operator bumps the level to
            // DEBUG to investigate, the log cannot be
            // retroactively forged.
            log.debug("Affected Tests: [{}] error parsing {}: {}",
                    label,
                    LogSanitizer.sanitize(String.valueOf(file)),
                    LogSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }
}
