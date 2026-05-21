package io.affectedtests.core.discovery;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.affectedtests.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Distils a parsed {@link CompilationUnit} into a strategy-agnostic
 * {@link FileMetadata} record.
 *
 * <p>Single AST walk per file: one pass over imports, one pass over
 * {@link ClassOrInterfaceType} nodes (shared between the type-ref sets
 * and per-decl supertype extraction via grouped findAll results), and
 * one pass over {@link TypeDeclaration}s for the per-decl shape.
 * Pre-stage-2 each strategy did its own walk; the extractor folds those
 * into a single pass and caches the result so subsequent strategy
 * invocations on the same file are zero-AST-cost.
 *
 * <p>The class is stateless and side-effect-free; the same
 * {@link CompilationUnit} reused across threads always returns an
 * equivalent record (and JavaParser AST nodes are not modified).
 */
final class FileMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileMetadataExtractor.class);

    private FileMetadataExtractor() {}

    /**
     * Builds a {@link FileMetadata} from a parsed compilation unit.
     *
     * <p>The compilation unit is read but not retained — callers can
     * release it as soon as this method returns. The returned record
     * is safe to share across threads.
     */
    static FileMetadata extract(CompilationUnit cu) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
        String primaryTypeName = cu.getPrimaryTypeName().orElse(null);

        List<FileMetadata.Import> imports = new ArrayList<>(cu.getImports().size());
        for (ImportDeclaration imp : cu.getImports()) {
            imports.add(new FileMetadata.Import(
                    imp.getNameAsString(),
                    imp.isStatic(),
                    imp.isAsterisk()));
        }

        Set<String> typeRefSimpleNames = new LinkedHashSet<>();
        Set<String> typeRefDottedNames = new LinkedHashSet<>();
        for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
            typeRefSimpleNames.add(type.getNameAsString());
            String scoped = nameWithScopeOrNull(type);
            if (scoped != null && scoped.indexOf('.') >= 0) {
                typeRefDottedNames.add(scoped);
            }
        }

        // findAll(TypeDeclaration.class) is the same shape Implementation
        // already used (every top-level + nested type), with the same
        // raw-types caveat — TypeDeclaration is generic in its self-type
        // and JavaParser's findAll returns the raw form.
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<TypeDeclaration<?>> declarations =
                (List<TypeDeclaration<?>>) (List) cu.findAll(TypeDeclaration.class);
        List<FileMetadata.TypeDecl> typeDecls = new ArrayList<>(declarations.size());
        for (TypeDeclaration<?> decl : declarations) {
            List<ClassOrInterfaceType> supertypes = supertypesOf(decl);
            List<String> supertypeNames = new ArrayList<>(supertypes.size());
            for (ClassOrInterfaceType s : supertypes) {
                supertypeNames.add(s.getNameAsString());
            }
            typeDecls.add(new FileMetadata.TypeDecl(decl.getNameAsString(), supertypeNames));
        }

        return new FileMetadata(
                packageName,
                primaryTypeName,
                imports,
                typeRefSimpleNames,
                typeRefDottedNames,
                typeDecls);
    }

    /**
     * Mirrors {@link com.github.javaparser.ast.type.ClassOrInterfaceType#getNameWithScope()}
     * with a try/catch to swallow the rare partial-resolution
     * RuntimeExceptions that JavaParser surfaces on malformed source.
     * Returning {@code null} skips that single node while letting the
     * rest of the file contribute — same contract as the previous
     * {@code UsageStrategy.nameWithScopeOrNull} helper this method
     * replaces.
     */
    private static String nameWithScopeOrNull(ClassOrInterfaceType type) {
        try {
            return type.getNameWithScope();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns the combined {@code extends} + {@code implements} list
     * for the supported {@link TypeDeclaration} shapes. Records,
     * enums, and annotations cannot {@code extends} another named
     * type and contribute only via {@code implements}; annotation
     * declarations have no widenable supertype at all.
     *
     * <p>Unknown {@code TypeDeclaration} subtypes (e.g. a future
     * JavaParser release) return an empty list — Implementation's
     * pre-existing WARN already covers the surfacing; here we just
     * preserve the conservative empty-supertype fallback so the
     * cached record contract stays stable.
     */
    private static List<ClassOrInterfaceType> supertypesOf(TypeDeclaration<?> decl) {
        List<ClassOrInterfaceType> result = new ArrayList<>();
        if (decl instanceof ClassOrInterfaceDeclaration c) {
            result.addAll(c.getExtendedTypes());
            result.addAll(c.getImplementedTypes());
        } else if (decl instanceof RecordDeclaration r) {
            result.addAll(r.getImplementedTypes());
        } else if (decl instanceof EnumDeclaration e) {
            result.addAll(e.getImplementedTypes());
        } else if (decl instanceof AnnotationDeclaration) {
            // Annotations cannot widen their implicit supertype
            // (java.lang.annotation.Annotation). Empty list is the
            // correct answer — not a drop.
            return result;
        } else {
            // Defensive: when a future JavaParser release introduces a
            // new TypeDeclaration subtype we want the new construct to
            // be *loud* rather than silently dropping every consumer
            // test that depended on it. WARN surfaces at the default
            // log level so operators can open a ticket; the extractor
            // keeps running and treats the type as having no known
            // supertypes, which is the correct conservative fallback
            // (cannot become a false impl match; at worst we
            // underselect for a single declaration).
            // {@code decl.getNameAsString()} originates from an
            // attacker-controllable source file — sanitised to keep
            // the log-forgery contract honest.
            log.warn("Affected Tests: [extract] unknown TypeDeclaration subtype {} for {} — "
                            + "supertype edges cannot be derived, skipping",
                    decl.getClass().getSimpleName(),
                    LogSanitizer.sanitize(decl.getNameAsString()));
        }
        return result;
    }
}
