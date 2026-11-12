package dev.dispatch.lint;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;

/**
 * Every per-file rule, in one scanner over an attributed type. A new rule is one {@link Rule}
 * constant and one visit method here, with a bad and a good fixture in the tests.
 */
final class JavaRules extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final CompilationUnitTree unit;
    private boolean reported;

    JavaRules(Trees trees, CompilationUnitTree unit) {
        this.trees = trees;
        this.unit = unit;
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

    /** True when the simple name already resolves to a different type here, so an import cannot fix it. */
    private boolean simpleNameMeansAnotherType(Element type) {
        for (Scope scope = trees.getScope(getCurrentPath()); scope != null; scope = scope.getEnclosingScope()) {
            if (namesAnotherType(scope.getLocalElements(), type) || scope.getEnclosingClass() != null
                    && namesAnotherType(scope.getEnclosingClass().getEnclosedElements(), type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesAnotherType(Iterable<? extends Element> inScope, Element type) {
        for (Element element : inScope) {
            if ((element.getKind().isClass() || element.getKind().isInterface())
                    && element.getSimpleName().contentEquals(type.getSimpleName()) && !element.equals(type)) {
                return true;
            }
        }
        return false;
    }

    boolean reported() {
        return reported;
    }

    private void report(Tree at, Rule rule, Object... args) {
        reported = true;
        trees.printMessage(Diagnostic.Kind.ERROR, rule.message(args), at, unit);
    }
}
