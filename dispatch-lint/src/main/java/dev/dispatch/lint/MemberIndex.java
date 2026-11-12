package dev.dispatch.lint;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The member lines of the index, for the unused-surface rule. Over one attributed type it records
 * every member the type declares for others to use, and every member its code resolves to.
 * {@link RepoCheck} subtracts the second set from the first across all compile tasks.
 *
 * <p>A declaration is left out when something other than a caller in this repo reaches it: an
 * override or implementation, {@code main(String[])}, a member a framework finds by its
 * annotation, a constructor of a Spring-annotated class, what a record declares from its
 * components, and any member of a {@code web.dto} record, which Jackson reads.
 */
final class MemberIndex extends TreePathScanner<Void, Void> {
    private static final List<String> FRAMEWORKS =
            List.of("org.springframework.", "jakarta.", "tools.jackson.", "org.junit.");
    private static final Set<ElementKind> MEMBERS = Set.of(
            ElementKind.METHOD, ElementKind.CONSTRUCTOR, ElementKind.FIELD, ElementKind.ENUM_CONSTANT);

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final CompilationUnitTree unit;
    private final String file;
    private final boolean declares;
    private final Set<String> lines;

    MemberIndex(Trees trees, Elements elements, Types types, CompilationUnitTree unit, String file,
            Set<String> lines) {
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.unit = unit;
        this.file = file;
        this.declares = file.contains("src/main/") || file.contains("src/testFixtures/");
        this.lines = lines;
    }

    @Override
    public Void visitMethod(MethodTree method, Void unused) {
        declare(method);
        return super.visitMethod(method, unused);
    }

    @Override
    public Void visitVariable(VariableTree variable, Void unused) {
        declare(variable);
        return super.visitVariable(variable, unused);
    }

    @Override
    public Void visitIdentifier(IdentifierTree identifier, Void unused) {
        reference();
        return super.visitIdentifier(identifier, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree select, Void unused) {
        reference();
        return super.visitMemberSelect(select, unused);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
        reference();
        return super.visitMemberReference(reference, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree creation, Void unused) {
        reference();
        return super.visitNewClass(creation, unused);
    }

    private void reference() {
        Element member = trees.getElement(getCurrentPath());
        if (member != null && MEMBERS.contains(member.getKind())
                && member.getEnclosingElement() instanceof TypeElement owner) {
            lines.add(SourceIndex.referenced(key(owner, member)));
        }
    }

    private void declare(Tree tree) {
        Element member = trees.getElement(getCurrentPath());
        if (!declares || member == null || !MEMBERS.contains(member.getKind())
                || member.getModifiers().contains(Modifier.PRIVATE)
                || elements.getOrigin(member) != Elements.Origin.EXPLICIT
                || !(member.getEnclosingElement() instanceof TypeElement owner)
                || owner.getNestingKind() == NestingKind.LOCAL || owner.getNestingKind() == NestingKind.ANONYMOUS
                || reachedWithoutACaller(member, owner)) {
            return;
        }
        long line = unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, tree));
        lines.add(SourceIndex.declared(key(owner, member), visibility(member), file + ":" + line,
                display(owner, member)));
    }

    private boolean reachedWithoutACaller(Element member, TypeElement owner) {
        if (hasAnnotationFrom(member, FRAMEWORKS)) {
            return true;
        }
        if (owner.getKind() == ElementKind.RECORD && isInWebDto(owner)) {
            return true;
        }
        if (member instanceof ExecutableElement executable) {
            return member.getKind() == ElementKind.CONSTRUCTOR
                    ? hasAnnotationFrom(owner, List.of("org.springframework.")) || elements.isCanonicalConstructor(executable)
                    : isMain(executable) || isRecordAccessor(executable, owner) || overrides(executable, owner);
        }
        return false;
    }

    private static boolean hasAnnotationFrom(Element element, List<String> packages) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            String name = ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
            if (packages.stream().anyMatch(name::startsWith)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInWebDto(TypeElement type) {
        PackageElement home = elements.getPackageOf(type);
        return home.getQualifiedName().toString().endsWith(".web.dto");
    }

    private boolean isMain(ExecutableElement method) {
        return method.getSimpleName().contentEquals("main") && method.getModifiers().contains(Modifier.STATIC)
                && method.getParameters().size() == 1
                && method.getParameters().get(0).asType().toString().equals("java.lang.String[]");
    }

    private static boolean isRecordAccessor(ExecutableElement method, TypeElement owner) {
        for (RecordComponentElement component : owner.getRecordComponents()) {
            if (method.equals(component.getAccessor())) {
                return true;
            }
        }
        return false;
    }

    private boolean overrides(ExecutableElement method, TypeElement owner) {
        Deque<TypeMirror> supertypes = new ArrayDeque<>(types.directSupertypes(owner.asType()));
        while (!supertypes.isEmpty()) {
            TypeMirror supertype = supertypes.pop();
            if (types.asElement(supertype) instanceof TypeElement type) {
                for (Element inherited : type.getEnclosedElements()) {
                    if (inherited instanceof ExecutableElement candidate
                            && elements.overrides(method, candidate, owner)) {
                        return true;
                    }
                }
                supertypes.addAll(types.directSupertypes(supertype));
            }
        }
        return false;
    }

    /** The identity both line kinds share: {@code owner#name(erased,parameter,types)}, or {@code owner#name} for a field. */
    private String key(TypeElement owner, Element member) {
        String name = owner.getQualifiedName() + "#" + member.getSimpleName();
        return member instanceof ExecutableElement executable
                ? name + parameters(executable, type -> types.erasure(type).toString(), ",") : name;
    }

    /** What the error calls the member: {@code JobQueue.config()}, {@code JobQueue(Clock)}, {@code JobState.DEAD}. */
    private String display(TypeElement owner, Element member) {
        if (member instanceof ExecutableElement executable) {
            String name = member.getKind() == ElementKind.CONSTRUCTOR ? owner.getSimpleName().toString()
                    : owner.getSimpleName() + "." + member.getSimpleName();
            return name + parameters(executable, this::simpleName, ", ");
        }
        return owner.getSimpleName() + "." + member.getSimpleName();
    }

    private static String parameters(ExecutableElement executable,
            Function<TypeMirror, String> name, String separator) {
        return executable.getParameters().stream().map(parameter -> name.apply(parameter.asType()))
                .collect(Collectors.joining(separator, "(", ")"));
    }

    private String simpleName(TypeMirror type) {
        TypeMirror erased = types.erasure(type);
        if (erased instanceof ArrayType array) {
            return simpleName(array.getComponentType()) + "[]";
        }
        return erased.getKind() == TypeKind.DECLARED
                ? ((DeclaredType) erased).asElement().getSimpleName().toString() : erased.toString();
    }

    private static String visibility(Element member) {
        return member.getModifiers().contains(Modifier.PUBLIC) ? "public"
                : member.getModifiers().contains(Modifier.PROTECTED) ? "protected" : "package-private";
    }
}
