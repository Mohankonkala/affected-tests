package io.affectedtests.core.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void findsDirAtRoot() throws IOException {
        Files.createDirectories(tempDir.resolve("src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).endsWith("src/test/java"));
    }

    @Test
    void findsDirAtDepthOne() throws IOException {
        Files.createDirectories(tempDir.resolve("api/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).toString().contains("api"));
    }

    @Test
    void findsDirAtDepthTwo() throws IOException {
        Files.createDirectories(tempDir.resolve("services/core/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).toString().contains("services/core")
                || matches.get(0).toString().contains("services\\core"));
    }

    @Test
    void findsDirAtDepthThree() throws IOException {
        Files.createDirectories(tempDir.resolve("platform/services/core/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size());
    }

    @Test
    void findsMultipleDirsAtVariousDepths() throws IOException {
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Files.createDirectories(tempDir.resolve("api/src/test/java"));
        Files.createDirectories(tempDir.resolve("services/core/src/test/java"));
        Files.createDirectories(tempDir.resolve("libs/common/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(4, matches.size(), "Should find root + 3 nested modules");
    }

    @Test
    void skipsBuildAndGitDirs() throws IOException {
        // These should be skipped
        Files.createDirectories(tempDir.resolve("build/generated/src/test/java"));
        Files.createDirectories(tempDir.resolve(".git/hooks/src/test/java"));
        Files.createDirectories(tempDir.resolve(".gradle/caches/src/test/java"));

        // This should be found
        Files.createDirectories(tempDir.resolve("api/src/test/java"));

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertEquals(1, matches.size(), "Should only find api, not build/.git/.gradle");
        assertTrue(matches.get(0).toString().contains("api"));
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/test/java");

        assertTrue(matches.isEmpty());
    }

    @Test
    void collectsTestFilesFromDeeplyNestedModules() throws IOException {
        Path deepTestDir = tempDir.resolve("services/core/src/test/java/com/example");
        Files.createDirectories(deepTestDir);
        Files.writeString(deepTestDir.resolve("FooTest.java"),
                "package com.example;\npublic class FooTest {}");

        List<Path> files = SourceFileScanner.collectTestFiles(tempDir, List.of("src/test/java"));

        assertEquals(1, files.size());
        assertTrue(files.get(0).toString().endsWith("FooTest.java"));
    }

    @Test
    void rejectsSymlinkEscapingProjectRoot(@TempDir Path outside) throws IOException {
        // Threat model: merge-gate CI runs against an attacker-controlled
        // MR branch. An attacker commits `src/main/java` as a symlink to a
        // location outside the project root — e.g. the CI runner's
        // `/` or `$HOME` — and expects the scanner to walk that target,
        // either (a) blowing out the walk budget (DoS) or (b) leaking the
        // runner's directory structure into the `--explain` output via
        // discovered .java filenames.
        //
        // The fix in SourceFileScanner.stayInsideProjectRoot canonicalises
        // the candidate with toRealPath() and rejects anything whose real
        // path doesn't live under the project's real root.
        Path attackTarget = outside.resolve("runner-secrets");
        Files.createDirectories(attackTarget);
        Files.writeString(attackTarget.resolve("Secret.java"),
                "package secret;\npublic class Secret {}");

        Path srcLink = tempDir.resolve("src/main/java");
        Files.createDirectories(srcLink.getParent());
        try {
            Files.createSymbolicLink(srcLink, attackTarget);
        } catch (UnsupportedOperationException | FileSystemException e) {
            // Platform doesn't support symlinks (Windows without privilege,
            // some containerised filesystems). Skip rather than fail — the
            // attack surface does not exist on those platforms.
            return;
        }

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/main/java");

        assertTrue(matches.isEmpty(),
                "Symlinked source directory escaping the project root must not be "
                        + "returned as a match — an attacker's MR could otherwise redirect "
                        + "the scanner at arbitrary filesystem locations. Matches: " + matches);
    }

    @Test
    void acceptsSymlinkThatStaysInsideProjectRoot() throws IOException {
        // Legitimate use case: a module's src/main/java is a symlink to
        // another directory that is still under the project root (some
        // monorepo tooling does this). Must NOT be rejected.
        Path actualSrc = tempDir.resolve("actual-source/src/main/java");
        Files.createDirectories(actualSrc);

        Path moduleSrc = tempDir.resolve("module/src/main/java");
        Files.createDirectories(moduleSrc.getParent());
        try {
            Files.createSymbolicLink(moduleSrc, actualSrc);
        } catch (UnsupportedOperationException | FileSystemException e) {
            return;
        }

        List<Path> matches = SourceFileScanner.findAllMatchingDirs(tempDir, "src/main/java");

        assertFalse(matches.isEmpty(),
                "Symlink resolving to a path under the project root must still be accepted");
    }

    @Test
    void rejectsFileLevelSymlinkEscapingProjectRoot(@TempDir Path outside) throws IOException {
        // Threat model (v1.9.19 closed the directory-level leg of this
        // attack; v1.9.21 closes the per-file leg): even when
        // `src/main/java` is a normal directory inside the project,
        // an attacker MR can still commit
        //   `src/main/java/com/x/Leak.java -> /etc/passwd`
        // and the pre-fix scanner would happily enumerate the file,
        // hand it to JavaParser, and surface the filename in the
        // discovery pipeline. `visitFile` now rejects symlinks
        // unconditionally — a regular {@code .java} file whose real
        // path legitimately lives inside the tree is just a regular
        // file, not a symlink.
        Path pkg = tempDir.resolve("src/main/java/com/x");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Ok.java"), "package com.x;\nclass Ok {}");

        Path attackTarget = outside.resolve("Leak.java");
        Files.writeString(attackTarget, "package evil;\nclass Leak {}");

        Path fileLink = pkg.resolve("Leak.java");
        try {
            Files.createSymbolicLink(fileLink, attackTarget);
        } catch (UnsupportedOperationException | FileSystemException e) {
            return;
        }

        List<Path> collected = SourceFileScanner.collectSourceFiles(
                tempDir.resolve("src/main/java"));

        assertEquals(1, collected.size(),
                "Only the real Ok.java should be collected; the symlinked Leak.java "
                        + "must be skipped even if its real path is outside the project root");
        assertTrue(collected.get(0).getFileName().toString().equals("Ok.java"));
    }

    @Test
    void scansTestFqnsIgnoresFileLevelSymlinks(@TempDir Path outside) throws IOException {
        // Same hardening contract as above, but for the path that
        // feeds {@link ProjectIndex#testFqns()} — the two visitors
        // must agree or an attacker could plant a symlinked file
        // that is invisible to `collectTestFiles` but visible to
        // `scanTestFqns` (or vice versa), producing inconsistent
        // bucket counts that are hard to debug.
        Path pkg = tempDir.resolve("src/test/java/com/x");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("OkTest.java"), "package com.x;\nclass OkTest {}");

        Path attackTarget = outside.resolve("LeakTest.java");
        Files.writeString(attackTarget, "package evil;\nclass LeakTest {}");
        Path fileLink = pkg.resolve("LeakTest.java");
        try {
            Files.createSymbolicLink(fileLink, attackTarget);
        } catch (UnsupportedOperationException | FileSystemException e) {
            return;
        }

        var fqns = SourceFileScanner.scanTestFqns(tempDir, List.of("src/test/java"));

        assertTrue(fqns.contains("com.x.OkTest"));
        assertFalse(fqns.contains("com.x.LeakTest"),
                "Symlinked file must not contribute to test FQN discovery");
        assertEquals(1, fqns.size());
    }

    @Test
    void scansTestFqnsFromDeeplyNestedModules() throws IOException {
        Path depth1 = tempDir.resolve("api/src/test/java/com/example");
        Files.createDirectories(depth1);
        Files.writeString(depth1.resolve("FooTest.java"),
                "package com.example;\npublic class FooTest {}");

        Path depth2 = tempDir.resolve("services/core/src/test/java/com/example");
        Files.createDirectories(depth2);
        Files.writeString(depth2.resolve("BarTest.java"),
                "package com.example;\npublic class BarTest {}");

        var fqns = SourceFileScanner.scanTestFqns(tempDir, List.of("src/test/java"));

        assertTrue(fqns.contains("com.example.FooTest"));
        assertTrue(fqns.contains("com.example.BarTest"));
        assertEquals(2, fqns.size());
    }

    // ── Phase 2 PR #1 of issue #76 — Kotlin sources participate in
    //    every walker / FQN helper that previously hard-coded .java.
    //    Each test below pins one of the five suffix-strip /
    //    extension-filter sites listed in docs/PHASE-2-KOTLIN-AST.md
    //    §2 so a regression to Java-only behaviour is caught at
    //    unit-test latency, not after a Kotlin adopter files an issue.

    @Test
    void collectsKotlinFilesAlongsideJava() throws IOException {
        Path pkg = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Foo.java"), "package com.example; class Foo {}");
        Files.writeString(pkg.resolve("Bar.kt"), "package com.example\nclass Bar");

        List<Path> files = SourceFileScanner.collectSourceFiles(
                tempDir.resolve("src/main/java"));

        assertEquals(2, files.size(), "Both .java and .kt must be collected; got " + files);
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("Foo.java")));
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().equals("Bar.kt")));
    }

    @Test
    void scanTestFqnsIncludesKotlinTestClasses() throws IOException {
        Path pkg = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("FooTest.java"),
                "package com.example; class FooTest {}");
        Files.writeString(pkg.resolve("BarTest.kt"),
                "package com.example\nclass BarTest");

        var fqns = SourceFileScanner.scanTestFqns(tempDir, List.of("src/test/java"));

        assertTrue(fqns.contains("com.example.FooTest"));
        assertTrue(fqns.contains("com.example.BarTest"),
                "Kotlin test FQN must appear in the test-FQN universe — "
                        + "NamingConventionStrategy reads from this set, and PR #1's "
                        + "selection guarantee for Kotlin tests rests on widening this "
                        + "scanner. Got: " + fqns);
        assertEquals(2, fqns.size());
    }

    @Test
    void fqnsUnderHandlesKotlinTopLevelFunctionFile() throws IOException {
        Path src = tempDir.resolve("src/main/java");
        Path pkg = src.resolve("com/example");
        Files.createDirectories(pkg);
        // Top-level Kotlin file with no class declaration. The
        // path-derived FQN is `com.example.Util` (the file's
        // basename); the synthetic `<basename>Kt` shape is emitted
        // on the diff side by PathToClassMapper, not by fqnsUnder
        // (which is a test-FQN feeder for naming-strategy lookups).
        Files.writeString(pkg.resolve("Util.kt"),
                "package com.example\nfun greet() = \"hi\"");

        var fqns = SourceFileScanner.fqnsUnder(src);

        assertTrue(fqns.contains("com.example.Util"),
                "Path-derived FQN must strip the .kt suffix; got " + fqns);
        assertEquals(1, fqns.size(),
                "fqnsUnder is the test-FQN universe — it must NOT emit "
                        + "the synthetic <basename>Kt here (that's a diff-side "
                        + "concern handled in PathToClassMapper). Got: " + fqns);
    }

    @Test
    void pathToFqnStripsKotlinSuffix() {
        String fqn = SourceFileScanner.pathToFqn(
                Path.of("/repo/api/src/main/java/com/example/Foo.kt"),
                List.of("src/main/java"));

        assertEquals("com.example.Foo", fqn,
                "pathToFqn must strip both .java and .kt suffixes — pre-PR-1 "
                        + "the .kt suffix slipped through the dotted-segment "
                        + "transform and silently poisoned every downstream strategy "
                        + "that treated FQNs ending in `.kt` as class names.");
    }

    @Test
    void pathToFqnStripsJavaSuffix() {
        // Companion to pathToFqnStripsKotlinSuffix — the centralised
        // strip helper must keep the pre-PR-1 .java behaviour intact.
        String fqn = SourceFileScanner.pathToFqn(
                Path.of("/repo/api/src/main/java/com/example/Foo.java"),
                List.of("src/main/java"));

        assertEquals("com.example.Foo", fqn);
    }

    @Test
    void fqnsUnderDeduplicatesSameBasenameAcrossJavaAndKotlin() throws IOException {
        // Migration shape: `Foo.java` and `Foo.kt` co-exist in the
        // same package. Both produce the same path-derived FQN
        // `com.example.Foo` after suffix-strip. `walkFqnsUnder` uses
        // `putIfAbsent` so the FQN appears exactly once — which file
        // "wins" the path mapping is filesystem-walk-order-dependent
        // and intentionally unspecified, but the FQN universe must
        // stay stable in size. Without this test, a refactor that
        // accidentally switched to `put` would double-count files
        // and confuse downstream consumers that index by FQN.
        Path src = tempDir.resolve("src/main/java");
        Path pkg = src.resolve("com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Foo.java"),
                "package com.example; class Foo {}");
        Files.writeString(pkg.resolve("Foo.kt"),
                "package com.example\nclass Foo");

        var fqns = SourceFileScanner.fqnsUnder(src);

        assertEquals(1, fqns.size(),
                "Same-basename Java + Kotlin must dedupe to one FQN; got " + fqns);
        assertTrue(fqns.contains("com.example.Foo"));
    }
}
