package io.affectedtests.core.discovery;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The strategy-relevant projection of a parsed source file's
 * {@link com.github.javaparser.ast.CompilationUnit}, distilled into a
 * cheap-to-serialise record so the persistent
 * {@link ProjectIndexCache} (issue&nbsp;#41 stage&nbsp;2) can hand it
 * straight back to {@link UsageStrategy}, {@link ImplementationStrategy},
 * and {@link TransitiveStrategy} without re-parsing on a warm run.
 *
 * <p>Captures the union of fields the three AST-driven strategies
 * actually consume:
 * <ul>
 *   <li><strong>{@link #packageName()}</strong> — declared package, or
 *       the empty string for the default package.</li>
 *   <li><strong>{@link #primaryTypeName()}</strong> — Usage's last-resort
 *       FQN fallback for tests whose path can't be resolved against any
 *       configured test root. {@code null} when the file declares no
 *       primary type.</li>
 *   <li><strong>{@link #imports()}</strong> — raw, in-source-order
 *       {@link Import} list. Strategies normalise on read because Usage
 *       and Transitive disagree on import semantics: Usage folds static
 *       imports back to their owning class FQN, Transitive ignores
 *       static imports entirely. Caching the raw form keeps the
 *       strategy-specific normalisation contained and avoids duplicate
 *       per-strategy fields in the snapshot.</li>
 *   <li><strong>{@link #typeRefSimpleNames()}</strong> — every
 *       {@link com.github.javaparser.ast.type.ClassOrInterfaceType#getNameAsString()
 *       ClassOrInterfaceType simple name} that appears anywhere in the
 *       AST. Used by Usage's wildcard-package and same-package tiers
 *       and by Transitive's frontier filter.</li>
 *   <li><strong>{@link #typeRefDottedNames()}</strong> — every
 *       {@code ClassOrInterfaceType.getNameWithScope()} that yielded a
 *       dotted form. Used by Usage's tier&nbsp;3 fully-qualified inline
 *       reference match.</li>
 *   <li><strong>{@link #typeDeclarations()}</strong> — every
 *       {@link com.github.javaparser.ast.body.TypeDeclaration} in the
 *       file (not just the primary), each with its declared supertypes
 *       as simple names. Used by Implementation's fixpoint scan, which
 *       needs to walk records and enums (not just classes/interfaces)
 *       and respects inner-type names.</li>
 * </ul>
 *
 * <p>The record is intentionally <em>not</em> per-strategy: keeping a
 * single union shape lets one extractor pass do the work of all three
 * AST walks ({@code findAll(ClassOrInterfaceType.class)}, the import
 * loop, and the {@code findAll(TypeDeclaration.class)} loop), so a
 * cache miss costs one parse + one walk regardless of how many
 * strategies later read from it.
 */
public record FileMetadata(
        String packageName,
        String primaryTypeName,
        List<Import> imports,
        Set<String> typeRefSimpleNames,
        Set<String> typeRefDottedNames,
        List<TypeDecl> typeDeclarations) {

    /**
     * Defensive copy + immutable wrappers in the canonical constructor
     * so the cached record stays safe to share across the discovery
     * pool's worker threads. Strategies treat {@code FileMetadata} as
     * read-only; this stops a future caller from mutating a shared
     * cache entry by accident.
     */
    public FileMetadata {
        packageName = packageName == null ? "" : packageName;
        imports = List.copyOf(imports);
        typeRefSimpleNames = unmodifiableLinkedSet(typeRefSimpleNames);
        typeRefDottedNames = unmodifiableLinkedSet(typeRefDottedNames);
        typeDeclarations = List.copyOf(typeDeclarations);
    }

    private static Set<String> unmodifiableLinkedSet(Set<String> in) {
        if (in == null || in.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(in));
    }

    /**
     * Raw representation of a single Java {@code import} statement.
     *
     * <p>{@link #name()} is the {@code import}'s
     * {@code getNameAsString()} (i.e. the dotted name without the
     * leading {@code import} keyword and trailing semicolon), preserved
     * exactly as JavaParser reports it so downstream strategies can
     * normalise without losing information.
     *
     * <p>{@link #isStatic()} mirrors {@code ImportDeclaration.isStatic()}
     * — a {@code true} value means the import targets a member of the
     * named class, not the class itself. Usage strips the trailing
     * member segment to recover the owning class FQN; Transitive drops
     * static imports entirely (they never produce reverse-dependency
     * edges).
     *
     * <p>{@link #isAsterisk()} mirrors {@code ImportDeclaration.isAsterisk()}.
     * For a non-static asterisk import the name is a package; for a
     * static asterisk import the name is the owning class. Both
     * strategies handle the wildcard branch separately from the plain
     * branch.
     */
    public record Import(String name, boolean isStatic, boolean isAsterisk) {}

    /**
     * Cached projection of a single {@link
     * com.github.javaparser.ast.body.TypeDeclaration}. Carries only the
     * supertype simple names — Implementation matches by simple name
     * and resolves to an FQN itself, so caching the resolved FQN here
     * would force a re-resolve on every cache load.
     *
     * <p>{@link #simpleName()} is {@code TypeDeclaration.getNameAsString()}
     * — the type's own simple name (not the FQN; the file's
     * {@link FileMetadata#packageName()} is its prefix).
     *
     * <p>{@link #supertypeSimpleNames()} is the combined list of
     * {@code extends} + {@code implements} type names, in declaration
     * order, each via {@code ClassOrInterfaceType.getNameAsString()}.
     * Empty for annotation declarations and any future
     * {@link com.github.javaparser.ast.body.TypeDeclaration} subtype
     * the extractor doesn't yet recognise.
     */
    public record TypeDecl(String simpleName, List<String> supertypeSimpleNames) {

        public TypeDecl {
            supertypeSimpleNames = List.copyOf(supertypeSimpleNames);
        }
    }
}
