package dev.dispatch.lint;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTreeFactory;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;

/**
 * Every per-file rule, in one scanner over an attributed type. A new rule is one {@link Rule}
 * constant and one visit method here, with a bad and a good fixture in the tests.
 */
final class JavaRules extends TreePathScanner<Void, Void> {
    private static final String DISPLAY_NAME = "org.junit.jupiter.api.DisplayName";
    private static final List<String> TEST_ANNOTATIONS = List.of("org.junit.jupiter.api.Test",
            "org.junit.jupiter.params.ParameterizedTest", "org.junit.jupiter.api.RepeatedTest");

    private static final Set<String> ORDERED_ASSERTIONS = Set.of("containsExactly",
            "containsExactlyElementsOf", "containsSequence", "containsSubsequence", "startsWith", "endsWith",
            "isSorted", "isSortedAccordingTo");
    private static final String JOB_QUEUE = "dev.dispatch.core.engine.JobQueue";

    private final DocTrees trees;
    private final CompilationUnitTree unit;
    private final boolean inTestSources;
    private final boolean inMain;
    private final boolean inCoreMain;
    private boolean reported;

    JavaRules(DocTrees trees, CompilationUnitTree unit) {
        this.trees = trees;
        this.unit = unit;
        String path = unit.getSourceFile().toUri().getPath();
        this.inTestSources = path.contains("/src/test/") || path.contains("/src/testFixtures/");
        this.inMain = path.contains("/src/main/");
        this.inCoreMain = path.contains("/dispatch-core/src/main/");
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

    /** junit-assert: a call to a method of the JUnit Assertions class, however it was imported. */
    @Override
    public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
        if (trees.getElement(new TreePath(getCurrentPath(), call.getMethodSelect()))
                instanceof ExecutableElement method
                && method.getEnclosingElement() instanceof TypeElement owner) {
            String name = owner.getQualifiedName() + "#" + method.getSimpleName();
            if (inTestSources && owner.getQualifiedName().contentEquals("org.junit.jupiter.api.Assertions")) {
                report(call.getMethodSelect(), Rule.JUNIT_ASSERT, "Assertions." + method.getSimpleName());
            }
            boolean readsSystemClock = name.equals("java.time.Instant#now") && method.getParameters().isEmpty()
                    || name.equals("java.lang.System#currentTimeMillis")
                    || name.equals("java.time.Clock#systemUTC") && !inConstructor();
            if (inCoreMain && readsSystemClock) {
                report(call.getMethodSelect(), Rule.WALL_CLOCK, owner.getSimpleName() + "." + method.getSimpleName() + "()");
            }
        }
        return super.visitMethodInvocation(call, unused);
    }

    /** True when the nearest enclosing method is a constructor, with no lambda or class in between. */
    private boolean inConstructor() {
        for (Tree up : getCurrentPath()) {
            if (up instanceof LambdaExpressionTree || up instanceof ClassTree) {
                return false;
            }
            if (up instanceof MethodTree method) {
                return method.getName().contentEquals("<init>");
            }
        }
        return false;
    }

    /** display-name, for a test method, and no-synchronized, for a synchronized method. */
    @Override
    public Void visitMethod(MethodTree method, Void unused) {
        if (inTestSources && trees.getElement(getCurrentPath()) instanceof ExecutableElement element
                && isTestMethod(element) && !hasAnnotation(element, DISPLAY_NAME)) {
            report(method, Rule.DISPLAY_NAME, "Test method " + method.getName());
        }
        if (inTestSources && trees.getElement(getCurrentPath()) instanceof ExecutableElement element
                && isTestMethod(element)) {
            checkOrderFromTiming(method);
        }
        if (inMain && method.getModifiers().getFlags().contains(Modifier.SYNCHRONIZED)) {
            report(method, Rule.NO_SYNCHRONIZED);
        }
        return super.visitMethod(method, unused);
    }

    /**
     * order-from-timing: a test method that starts a running JobQueue and then asserts an order on a
     * value that did not come from JobQueue.dispatchOnce(). A value counts as coming from it when
     * its expression, or the initializer of a local variable it reads, calls dispatchOnce.
     */
    private void checkOrderFromTiming(MethodTree method) {
        if (method.getBody() == null) {
            return;
        }
        List<TreePath> starts = new ArrayList<>();
        List<TreePath> ordered = new ArrayList<>();
        Set<Element> fromDispatchOnce = new HashSet<>();
        TreePath body = new TreePath(getCurrentPath(), method.getBody());
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                if (calls(getCurrentPath(), JOB_QUEUE, "start")) {
                    starts.add(getCurrentPath());
                } else if (isOrderedAssertion(getCurrentPath())) {
                    ordered.add(getCurrentPath());
                }
                return super.visitMethodInvocation(call, unused);
            }
        }.scan(body, null);
        if (starts.isEmpty() || ordered.isEmpty()) {
            return;
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (TreePath variable : variablesOf(body)) {
                VariableTree declaration = (VariableTree) variable.getLeaf();
                if (declaration.getInitializer() != null && !fromDispatchOnce.contains(trees.getElement(variable))
                        && mentionsDispatchOnce(new TreePath(variable, declaration.getInitializer()), fromDispatchOnce)) {
                    fromDispatchOnce.add(trees.getElement(variable));
                    grew = true;
                }
            }
        }
        for (TreePath assertionPath : ordered) {
            MethodInvocationTree assertion = (MethodInvocationTree) assertionPath.getLeaf();
            ExpressionTree actual = assertedValue(assertion);
            if (actual != null && !mentionsDispatchOnce(new TreePath(assertionPath, actual), fromDispatchOnce)) {
                report(assertion, Rule.ORDER_FROM_TIMING, method.getName(), calledName(assertion));
            }
        }
    }

    private List<TreePath> variablesOf(TreePath body) {
        List<TreePath> found = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree variable, Void unused) {
                found.add(getCurrentPath());
                return super.visitVariable(variable, unused);
            }
        }.scan(body, null);
        return found;
    }

    private boolean mentionsDispatchOnce(TreePath expression, Set<Element> fromDispatchOnce) {
        boolean[] found = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                found[0] |= calls(getCurrentPath(), JOB_QUEUE, "dispatchOnce");
                return super.visitMethodInvocation(call, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree identifier, Void unused) {
                found[0] |= fromDispatchOnce.contains(trees.getElement(getCurrentPath()));
                return super.visitIdentifier(identifier, unused);
            }
        }.scan(expression, null);
        return found[0];
    }

    /** The argument of the assertThat(...) at the root of an assertion chain, or null if it has none. */
    private static ExpressionTree assertedValue(MethodInvocationTree assertion) {
        ExpressionTree current = assertion;
        while (current instanceof MethodInvocationTree call) {
            if (call.getMethodSelect() instanceof IdentifierTree name && name.getName().contentEquals("assertThat")
                    || call.getMethodSelect() instanceof MemberSelectTree select
                    && select.getIdentifier().contentEquals("assertThat")) {
                return call.getArguments().isEmpty() ? null : call.getArguments().get(0);
            }
            current = call.getMethodSelect() instanceof MemberSelectTree select ? select.getExpression() : null;
        }
        return null;
    }

    private static String calledName(MethodInvocationTree call) {
        return call.getMethodSelect() instanceof MemberSelectTree select ? select.getIdentifier().toString()
                : call.getMethodSelect().toString();
    }

    private boolean calls(TreePath call, String ownerName, String methodName) {
        return trees.getElement(new TreePath(call, ((MethodInvocationTree) call.getLeaf()).getMethodSelect()))
                instanceof ExecutableElement method && method.getSimpleName().contentEquals(methodName)
                && method.getEnclosingElement() instanceof TypeElement owner
                && owner.getQualifiedName().contentEquals(ownerName);
    }

    /** An ordered check on an AssertJ iterable or array assertion, not on a String's startsWith. */
    private boolean isOrderedAssertion(TreePath call) {
        return trees.getElement(new TreePath(call, ((MethodInvocationTree) call.getLeaf()).getMethodSelect()))
                instanceof ExecutableElement method && ORDERED_ASSERTIONS.contains(method.getSimpleName().toString())
                && method.getEnclosingElement() instanceof TypeElement owner
                && owner.getQualifiedName().toString().startsWith("org.assertj.core.api.")
                && (owner.getSimpleName().toString().contains("Iterable")
                        || owner.getSimpleName().toString().contains("Array")
                        || owner.getSimpleName().toString().contains("List"));
    }

    /** no-deprecated: the annotation on any declaration in main sources. */
    @Override
    public Void visitAnnotation(AnnotationTree annotation, Void unused) {
        if (inMain && trees.getElement(new TreePath(getCurrentPath(), annotation.getAnnotationType()))
                instanceof TypeElement type && type.getQualifiedName().contentEquals("java.lang.Deprecated")) {
            Element declared = trees.getElement(getCurrentPath().getParentPath().getParentPath());
            report(annotation, Rule.NO_DEPRECATED, declared == null ? "This declaration" : declared.getSimpleName());
        }
        return super.visitAnnotation(annotation, unused);
    }

    /** no-synchronized, for a block. A synchronized method is reported in {@link #visitMethod}. */
    @Override
    public Void visitSynchronized(SynchronizedTree block, Void unused) {
        if (inMain) {
            report(block, Rule.NO_SYNCHRONIZED);
        }
        return super.visitSynchronized(block, unused);
    }

    /**
     * display-name, for a test class: a concrete class that declares or inherits a test method.
     * An abstract base is exempt because its subclasses carry the name, and a class with no test
     * method is a helper.
     */
    @Override
    public Void visitClass(ClassTree type, Void unused) {
        if (inTestSources && trees.getElement(getCurrentPath()) instanceof TypeElement element
                && element.getKind() == ElementKind.CLASS && !element.getModifiers().contains(Modifier.ABSTRACT)
                && hasTestMethod(element) && !hasAnnotation(element, DISPLAY_NAME)) {
            report(type, Rule.DISPLAY_NAME, "Test class " + element.getSimpleName());
        }
        return super.visitClass(type, unused);
    }

    private static boolean hasTestMethod(TypeElement type) {
        for (Element current = type; current instanceof TypeElement t;
                current = t.getSuperclass() instanceof DeclaredType parent && parent.getKind() != TypeKind.NONE
                        ? parent.asElement() : null) {
            for (Element member : t.getEnclosedElements()) {
                if (member instanceof ExecutableElement method && isTestMethod(method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTestMethod(ExecutableElement method) {
        return TEST_ANNOTATIONS.stream().anyMatch(annotation -> hasAnnotation(method, annotation));
    }

    private static boolean hasAnnotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(qualifiedName)) {
                return true;
            }
        }
        return false;
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
