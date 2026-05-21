package io.affectedtests.core.discovery;

import io.affectedtests.core.config.AffectedTestsConfig;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists and reloads the cheap-to-derive-but-expensive-to-walk parts
 * of {@link ProjectIndex} between {@code affectedTest} runs.
 *
 * <p>Issue <a href="https://github.com/vedanthvdev/affected-tests/issues/41">#41</a>
 * tracks the broader perf goal: on a 10k-class monorepo the index
 * phase ({@code SourceFileScanner.collectSourceFiles} +
 * {@code collectTestFiles} + {@code scanTestFqnsWithFiles} +
 * {@code findAllMatchingDirs}) dominates wall time of a small-diff
 * invocation, and there is no persistence — two consecutive runs
 * against an unchanged source tree do the same work twice.
 *
 * <p>Stage 1 (this class) caches the four index aggregates:
 * <ul>
 *   <li>{@code sourceFiles} — absolute paths of every {@code .java} file under {@code sourceDirs}</li>
 *   <li>{@code testFiles} — absolute paths of every {@code .java} file under {@code testDirs}</li>
 *   <li>{@code testFqnToPath} — ordered map of test FQN to its file</li>
 *   <li>{@code sourceFqns} — set of production FQNs</li>
 * </ul>
 * Per-file derived AST data (imports, simple-name refs, supertypes) is
 * deliberately out of scope for Stage 1 — that is a follow-up.
 *
 * <p>Cache file layout: line-based TSV at {@code
 * <projectDir>/build/affected-tests/index/v1/snapshot.tsv}. Hand-rolled
 * format keeps the runtime dependency footprint flat (no Jackson) and
 * stays grep-friendly under {@code build/} for debugging. Forward-
 * compatible by ignoring unknown line types: future snapshot writers
 * can add new sections without breaking older readers.
 *
 * <p><strong>Validity contract.</strong> The cache is considered
 * usable when, in order:
 * <ol>
 *   <li>The snapshot's {@code schemaVersion} matches {@link #SCHEMA_VERSION}.</li>
 *   <li>The snapshot's {@code configHash} matches the current config
 *       (any change to {@code sourceDirs}, {@code testDirs},
 *       {@code outOfScopeSourceDirs}, {@code outOfScopeTestDirs} forces
 *       a full rebuild — those are the inputs to file selection).</li>
 *   <li>Every recorded directory under any scan root (recursively)
 *       still exists, has the same mtime, and has the same direct
 *       child count. We snapshot every reachable directory — not
 *       just the scan roots themselves — because adding a file to
 *       {@code src/test/java/com/example/} updates {@code com/example/}'s
 *       mtime but leaves {@code src/test/java/}'s mtime untouched.
 *       Verifying a few hundred dir stats on a 10k-file project costs
 *       ~5-15ms; re-walking 10k files would cost ~150ms. Mtime is
 *       the canonical "did anything add or remove inside me" signal
 *       on POSIX/HFS+/APFS/NTFS, and child count catches the rare
 *       coalesced-add-and-remove case where mtime resolution rounds
 *       two changes into the same second.</li>
 *   <li>The total number of directories under each scan root has not
 *       changed — catches the case where a brand-new package
 *       directory was added (the new dir was not in the snapshot,
 *       so per-dir checks alone would miss it).</li>
 * </ol>
 * Any single failure aborts the load and falls through to a full
 * rebuild — partial-cache reuse is intentionally NOT supported in
 * Stage 1 to keep the safety contract trivial to reason about.
 *
 * <p><strong>What the cache deliberately does NOT verify.</strong>
 * Per-file mtimes for {@code .java} content edits do not need to
 * invalidate the Stage 1 cache, because every aggregate it stores is
 * path-derived (FQN comes from relativising the file path against its
 * source root, not from parsing the {@code package} declaration). A
 * vim save inside an existing file will not change the dir mtime,
 * leaves the file list unchanged, and is therefore correctly served
 * from the cache. When Stage 2 (AST-derived data) lands, that layer
 * will need its own per-file fingerprint.
 */
public final class ProjectIndexCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexCache.class);

    /** Version of the on-disk format. Bumped when any line shape changes incompatibly. */
    static final int SCHEMA_VERSION = 1;

    private static final String CACHE_DIR_REL = "build/affected-tests/index/v1";
    private static final String SNAPSHOT_FILE = "snapshot.tsv";

    /** TSV separator. Tabs are vanishingly rare in source-tree paths and make hand-debugging trivial. */
    private static final char SEP = '\t';

    private ProjectIndexCache() {}

    /**
     * Snapshot of the directories that contributed to a {@link ProjectIndex} build.
     * Captured during {@link ProjectIndex#build} so {@link #persist} can fingerprint
     * exactly what was walked, without {@link ProjectIndexCache} needing to re-resolve
     * the {@code sourceDirs} / {@code testDirs} glob / suffix shapes itself.
     */
    public record ScannedDirs(List<Path> sourceRoots, List<Path> testRoots) {
        public ScannedDirs {
            sourceRoots = List.copyOf(sourceRoots);
            testRoots = List.copyOf(testRoots);
        }
    }

    /**
     * Attempts to load and verify a cached snapshot for {@code projectDir} against the
     * current {@code config}. Returns {@link Optional#empty()} on any verification
     * failure, missing file, schema drift, or I/O error — the caller falls through
     * to a full rebuild.
     *
     * <p>This method is deliberately exception-safe at the IO boundary: any
     * surfaced failure returns empty rather than propagating. The cost of an
     * unnecessary rebuild is bounded; the cost of a stale cache is unbounded
     * coverage drift, so we err toward rebuild on any uncertainty.
     */
    public static Optional<ProjectIndex> tryLoad(Path projectDir, AffectedTestsConfig config) {
        Path snapshot = projectDir.resolve(CACHE_DIR_REL).resolve(SNAPSHOT_FILE);
        if (!Files.isRegularFile(snapshot)) {
            return Optional.empty();
        }
        Snapshot loaded;
        try (BufferedReader reader = Files.newBufferedReader(snapshot, StandardCharsets.UTF_8)) {
            loaded = Snapshot.read(reader);
        } catch (IOException | RuntimeException e) {
            log.debug("ProjectIndex cache: failed to read snapshot at {}: {} — falling back to full build",
                    LogSanitizer.sanitize(snapshot.toString()),
                    LogSanitizer.sanitize(e.getMessage()));
            return Optional.empty();
        }
        if (loaded.schemaVersion != SCHEMA_VERSION) {
            log.debug("ProjectIndex cache: schemaVersion mismatch (cached={}, expected={}) — full build",
                    loaded.schemaVersion, SCHEMA_VERSION);
            return Optional.empty();
        }
        String currentHash = configHash(config);
        if (!currentHash.equals(loaded.configHash)) {
            log.debug("ProjectIndex cache: configHash mismatch — full build");
            return Optional.empty();
        }
        if (!verifyDirs(loaded.dirSnapshots)) {
            return Optional.empty();
        }
        log.info("ProjectIndex cache HIT: {} source files, {} test files, {} test FQNs, {} source FQNs",
                loaded.sourceFiles.size(), loaded.testFiles.size(),
                loaded.testFqnToPath.size(), loaded.sourceFqns.size());
        return Optional.of(materialise(loaded));
    }

    /**
     * Persists a freshly-built {@link ProjectIndex} for re-use on the next run.
     * Failures here are intentionally non-fatal: a project that cannot write under
     * {@code build/} (read-only mount, permission gap, full disk) should still
     * succeed at the {@code affectedTest} task itself — caching is a perf win, not
     * a correctness requirement.
     */
    public static void persist(Path projectDir,
                               AffectedTestsConfig config,
                               ProjectIndex index,
                               ScannedDirs dirs) {
        Path cacheDir = projectDir.resolve(CACHE_DIR_REL);
        Path snapshot = cacheDir.resolve(SNAPSHOT_FILE);
        try {
            Files.createDirectories(cacheDir);
            // Atomic-ish write: stage to a sibling tmp file then move into
            // place, so a partial write (or a concurrent affectedTest run
            // racing the same write) cannot leave the cache half-written
            // and trip the next reader.
            Path tmp = cacheDir.resolve(SNAPSHOT_FILE + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                Snapshot.write(writer, configHash(config), dirs, index);
            }
            try {
                Files.move(tmp, snapshot,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some FUSE / NFS-on-CI mounts reject ATOMIC_MOVE. Fall back
                // to plain replace — the .tmp staging still keeps a partial
                // write off the canonical filename.
                Files.move(tmp, snapshot, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("ProjectIndex cache: persisted snapshot to {}", LogSanitizer.sanitize(snapshot.toString()));
        } catch (IOException | RuntimeException e) {
            log.warn("ProjectIndex cache: failed to persist snapshot at {}: {} — next run will rebuild",
                    LogSanitizer.sanitize(snapshot.toString()),
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }

    /**
     * Removes the on-disk snapshot for this project. Used by tests and as an
     * escape hatch for adopters who want a clean rebuild without flipping the
     * disable knob ({@code rm -rf build/affected-tests/index} is the obvious
     * alternative; this method just centralises the path logic).
     */
    public static void clear(Path projectDir) {
        Path snapshot = projectDir.resolve(CACHE_DIR_REL).resolve(SNAPSHOT_FILE);
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException e) {
            log.debug("ProjectIndex cache: failed to clear snapshot at {}: {}",
                    LogSanitizer.sanitize(snapshot.toString()),
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }

    // ── Verification ────────────────────────────────────────────────────

    private static boolean verifyDirs(List<DirSnapshot> dirSnapshots) {
        // Group cached snapshots by their scan-root prefix so we can
        // re-walk each tree once and answer two questions in a single
        // pass: (a) does every cached dir still exist, with the same
        // mtime + child count? (b) has the total number of dirs under
        // the root changed? Question (b) catches new dirs that didn't
        // exist when the snapshot was written and so are not in the
        // per-dir map.
        Map<String, List<DirSnapshot>> byRoot = new LinkedHashMap<>();
        for (DirSnapshot d : dirSnapshots) {
            byRoot.computeIfAbsent(d.scanRoot, k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<DirSnapshot>> entry : byRoot.entrySet()) {
            String scanRoot = entry.getKey();
            Map<String, DirSnapshot> cachedByPath = new LinkedHashMap<>();
            for (DirSnapshot d : entry.getValue()) {
                cachedByPath.put(d.path, d);
            }
            Path rootPath = Path.of(scanRoot);
            if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
                log.debug("ProjectIndex cache: scan root {} no longer exists — full build",
                        LogSanitizer.sanitize(scanRoot));
                return false;
            }
            // Walk the live tree, ticking off cached entries and
            // counting current dirs at the same time.
            int[] currentDirCount = {0};
            boolean[] mismatched = {false};
            try {
                Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (attrs.isSymbolicLink()) {
                            // Don't follow symlinks — same security posture
                            // as SourceFileScanner uses on its own walk.
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        currentDirCount[0]++;
                        DirSnapshot cached = cachedByPath.get(dir.toAbsolutePath().toString());
                        if (cached == null) {
                            // Live dir was not in the snapshot — a new
                            // directory was added since the cache was
                            // written. Could be a new package, could be
                            // a new module under a glob root.
                            log.debug("ProjectIndex cache: new dir {} appeared under {} — full build",
                                    LogSanitizer.sanitize(dir.toString()),
                                    LogSanitizer.sanitize(scanRoot));
                            mismatched[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        long currentMtime = attrs.lastModifiedTime().toMillis();
                        if (currentMtime != cached.mtime) {
                            log.debug("ProjectIndex cache: dir {} mtime drifted ({} -> {}) — full build",
                                    LogSanitizer.sanitize(dir.toString()),
                                    cached.mtime, currentMtime);
                            mismatched[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        int currentChildren = countDirectChildren(dir);
                        if (currentChildren != cached.fileCount) {
                            log.debug("ProjectIndex cache: dir {} child count drifted ({} -> {}) — full build",
                                    LogSanitizer.sanitize(dir.toString()),
                                    cached.fileCount, currentChildren);
                            mismatched[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.debug("ProjectIndex cache: walk failed under {}: {} — full build",
                        LogSanitizer.sanitize(scanRoot),
                        LogSanitizer.sanitize(e.getMessage()));
                return false;
            }
            if (mismatched[0]) {
                return false;
            }
            if (currentDirCount[0] != cachedByPath.size()) {
                // A dir from the snapshot is no longer present (e.g.
                // a package was deleted). The per-dir tick above only
                // visits LIVE dirs, so a missing cached dir would not
                // mark mismatched[0] — the totals check catches it.
                log.debug("ProjectIndex cache: dir count under {} drifted ({} -> {}) — full build",
                        LogSanitizer.sanitize(scanRoot),
                        cachedByPath.size(), currentDirCount[0]);
                return false;
            }
        }
        return true;
    }

    /** Count of *direct* entries (files + subdirs) under {@code dir}, excluding the dir itself. */
    private static int countDirectChildren(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return (int) stream.count();
        } catch (IOException e) {
            return -1;
        }
    }

    // ── Hash computation ────────────────────────────────────────────────

    /**
     * SHA-256 of a canonical, stable serialisation of the config keys that
     * actually drive index contents. We deliberately exclude knobs that do
     * NOT influence what files are scanned (e.g. {@code mode}, {@code
     * baseRef}, {@code testTaskNames}) — those affect dispatch but not
     * indexing, so cache reuse across mode flips is safe and cheap.
     */
    static String configHash(AffectedTestsConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("sourceDirs:");
        appendList(sb, config.sourceDirs());
        sb.append("|testDirs:");
        appendList(sb, config.testDirs());
        sb.append("|outOfScopeSourceDirs:");
        appendList(sb, config.outOfScopeSourceDirs());
        sb.append("|outOfScopeTestDirs:");
        appendList(sb, config.outOfScopeTestDirs());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec — this branch is
            // unreachable on any sane runtime, but we throw so a
            // genuinely-broken JDK fails loudly instead of silently
            // disabling the cache via a degenerate hash.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendList(StringBuilder sb, List<String> values) {
        // Sort for stability — a config that lists ['a','b'] and one that
        // lists ['b','a'] index identical files, so they MUST hash the
        // same. Without sorting, a benign DSL reorder would force a
        // cache miss.
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        sb.append(sorted);
    }

    // ── Materialisation ─────────────────────────────────────────────────

    private static ProjectIndex materialise(Snapshot s) {
        List<Path> sourceFiles = new ArrayList<>(s.sourceFiles.size());
        for (String p : s.sourceFiles) sourceFiles.add(Path.of(p));
        List<Path> testFiles = new ArrayList<>(s.testFiles.size());
        for (String p : s.testFiles) testFiles.add(Path.of(p));
        LinkedHashMap<String, Path> testFqnToPath = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.testFqnToPath.entrySet()) {
            testFqnToPath.put(e.getKey(), Path.of(e.getValue()));
        }
        Set<String> sourceFqns = new LinkedHashSet<>(s.sourceFqns);
        return ProjectIndex.fromCache(sourceFiles, testFiles, testFqnToPath, sourceFqns);
    }

    // ── On-disk schema ──────────────────────────────────────────────────

    /**
     * In-memory representation of the on-disk snapshot. Internal — kept
     * package-private to keep the public API surface ({@link #tryLoad} /
     * {@link #persist}) thin.
     */
    static final class Snapshot {
        int schemaVersion;
        String configHash;
        List<DirSnapshot> dirSnapshots = new ArrayList<>();
        List<String> sourceFiles = new ArrayList<>();
        List<String> testFiles = new ArrayList<>();
        LinkedHashMap<String, String> testFqnToPath = new LinkedHashMap<>();
        List<String> sourceFqns = new ArrayList<>();

        static Snapshot read(BufferedReader reader) throws IOException {
            Snapshot s = new Snapshot();
            s.schemaVersion = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int firstSep = line.indexOf(SEP);
                if (firstSep < 0) continue;
                String type = line.substring(0, firstSep);
                String rest = line.substring(firstSep + 1);
                switch (type) {
                    case "v"     -> s.schemaVersion = Integer.parseInt(rest);
                    case "cfg"   -> s.configHash = rest;
                    case "d"     -> s.dirSnapshots.add(DirSnapshot.parse(rest));
                    case "sf"    -> s.sourceFiles.add(rest);
                    case "tf"    -> s.testFiles.add(rest);
                    case "tfqn"  -> {
                        int sep = rest.indexOf(SEP);
                        if (sep > 0) {
                            s.testFqnToPath.put(rest.substring(0, sep), rest.substring(sep + 1));
                        }
                    }
                    case "sfqn"  -> s.sourceFqns.add(rest);
                    default      -> { /* forward-compat: ignore unknown line type */ }
                }
            }
            if (s.schemaVersion < 0) {
                throw new IOException("ProjectIndex cache: missing schemaVersion line");
            }
            return s;
        }

        static void write(BufferedWriter writer,
                          String configHash,
                          ScannedDirs dirs,
                          ProjectIndex index) throws IOException {
            writer.write("# affected-tests project-index snapshot v" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("v" + SEP + SCHEMA_VERSION);
            writer.newLine();
            writer.write("cfg" + SEP + configHash);
            writer.newLine();
            // Snapshot every directory reachable from each scan root.
            // The per-root grouping keys the on-disk row to its scan
            // root so verifyDirs can re-walk one root at a time and
            // ask "did this tree change?" without needing to also
            // re-resolve the sourceDirs / testDirs glob shapes.
            for (Path d : dirs.sourceRoots()) writeRootSubtree(writer, d);
            for (Path d : dirs.testRoots())   writeRootSubtree(writer, d);
            for (Path p : index.sourceFiles()) {
                writer.write("sf" + SEP + p.toAbsolutePath().toString());
                writer.newLine();
            }
            for (Path p : index.testFiles()) {
                writer.write("tf" + SEP + p.toAbsolutePath().toString());
                writer.newLine();
            }
            for (Map.Entry<String, Path> e : index.testFqnToPath().entrySet()) {
                writer.write("tfqn" + SEP + e.getKey() + SEP + e.getValue().toAbsolutePath().toString());
                writer.newLine();
            }
            for (String fqn : index.sourceFqns()) {
                writer.write("sfqn" + SEP + fqn);
                writer.newLine();
            }
        }

        private static void writeRootSubtree(BufferedWriter writer, Path scanRoot) throws IOException {
            String scanRootAbs = scanRoot.toAbsolutePath().toString();
            Files.walkFileTree(scanRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        // Skip symlinked subtrees — same posture as
                        // SourceFileScanner. Without this, an attacker-
                        // influenced branch could plant a symlink to
                        // /etc and the cache would happily snapshot it.
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    int children;
                    try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                        children = (int) stream.count();
                    }
                    writer.write("d" + SEP + scanRootAbs
                            + SEP + dir.toAbsolutePath().toString()
                            + SEP + attrs.lastModifiedTime().toMillis()
                            + SEP + children);
                    writer.newLine();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    record DirSnapshot(String scanRoot, String path, long mtime, int fileCount) {
        static DirSnapshot parse(String row) {
            String[] parts = row.split(String.valueOf(SEP), -1);
            if (parts.length < 4) {
                throw new UncheckedIOException(new IOException(
                        "Malformed ProjectIndex cache 'd' row: " + LogSanitizer.sanitize(row)));
            }
            return new DirSnapshot(parts[0], parts[1], Long.parseLong(parts[2]), Integer.parseInt(parts[3]));
        }
    }
}
