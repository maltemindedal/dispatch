package dev.dispatch.lint;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocTreeFactory;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Every per-file rule, in one scanner over an attributed type. A new rule is one {@link Rule}
 * constant and one visit method here, with a bad and a good fixture in the tests.
 */
final class JavaRules extends TreePathScanner<Void, Void> {
    private final DocTrees trees;
    private final CompilationUnitTree unit;
    private boolean reported;

    JavaRules(DocTrees trees, CompilationUnitTree unit) {
        this.trees = trees;
        this.unit = unit;
    }

    /**
     * Scans what one ANALYZE event attributed. That is a top-level type, or the declaration in a
     * package-info.java or module-info.java, whose element has no tree of its own.
     */
    void check(TypeElement analyzed) {
        TreePath type = trees.getPath(analyzed);
        if (type != null) {
            scan(type, null);
        } else {
            TreePath file = new TreePath(unit);
            Tree declaration = unit.getModule() != null ? unit.getModule() : unit.getPackage();
            scan(new TreePath(file, declaration), null);
        }
    }

    /** inline-fqn: a type named through its package where an import and the simple name would do. */
    @Override
    public Void visitMemberSelect(MemberSelectTree select, Void unused) {
        Element type = trees.getElement(getCurrentPath());
        Element qualifier = trees.getElement(new TreePath(getCurrentPath(), select.getExpression()));
        if (type != null && (type.getKind().isClass() || type.getKind().isInterface())
                && qualifier != null && qualifier.getKind() == ElementKind.PACKAGE
                && isWrittenInSource(select) && !simpleNameMeansAnotherType(type)) {
            report(select, Rule.INLINE_FQN, select);
            return null;
        }
        return super.visitMemberSelect(select, unused);
    }

    /**
     * javac synthesizes type trees the author never wrote, for `var` and for implicit lambda
     * parameters. Those have no source position.
     */
    private boolean isWrittenInSource(Tree tree) {
        return trees.getSourcePositions().getStartPosition(unit, tree) >= 0;
    }

    /**
     * True when the simple name already resolves to a different type here, so an import would
     * change what the name means or could not take effect.
     */
    private boolean simpleNameMeansAnotherType(Element type) {
        Element named = resolve(type.getSimpleName());
        return named != null && !named.equals(type) && (named.getKind().isClass()
                || named.getKind().isInterface() || named.getKind() == ElementKind.TYPE_PARAMETER);
    }

    /**
     * What javac resolves a name to at the current position. Its only public entry to scope
     * resolution is the reference of a doc comment link, so this asks as if {@code {@link name}}
     * stood here.
     */
    private Element resolve(CharSequence name) {
        DocTreeFactory docs = trees.getDocTreeFactory();
        DocTreePath comment = new DocTreePath(getCurrentPath(), docs.newDocCommentTree(List.of(), List.of()));
        return trees.getElement(new DocTreePath(comment, docs.newReferenceTree(name.toString())));
    }

    boolean reported() {
        return reported;
    }

    private void report(Tree at, Rule rule, Object... args) {
        reported = true;
        trees.printMessage(Diagnostic.Kind.ERROR, rule.message(args), at, unit);
    }
}
