package dev.dispatch.lint;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
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
 *
 * <p>An enum constant is also referenced when code reads all constants of its enum at once
 * ({@code values()}, {@code valueOf}, an {@code EnumSet} over the whole enum), and when Spring or
 * Jackson binds it by name into a {@code @ConfigurationProperties} type or a {@code web.dto} type.
 * A factory method is referenced by the {@code @MethodSource} that names it.
 */
final class MemberIndex extends TreePathScanner<Void, Void> {
    private static final List<String> FRAMEWORKS =
            List.of("org.springframework.", "jakarta.", "tools.jackson.", "org.junit.");
    private static final Set<ElementKind> MEMBERS = Set.of(
            ElementKind.METHOD, ElementKind.CONSTRUCTOR, ElementKind.FIELD, ElementKind.ENUM_CONSTANT);
    private static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    private static final String METHOD_SOURCE = "org.junit.jupiter.params.provider.MethodSource";
    /** JDK methods whose result holds or is drawn from every constant of the enum in their return type. */
    private static final Set<String> READS_ALL_CONSTANTS = Set.of(
            "java.util.EnumSet#allOf", "java.util.EnumSet#noneOf", "java.util.EnumSet#range",
            "java.util.EnumSet#complementOf", "java.lang.Enum#valueOf", "java.lang.Class#getEnumConstants");

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
    public Void visitClass(ClassTree declaration, Void unused) {
        if (trees.getElement(getCurrentPath()) instanceof TypeElement type
                && (isInWebDto(type) || hasAnnotationFrom(type, List.of(CONFIGURATION_PROPERTIES)))) {
            referenceBoundEnums(type, new HashSet<>());
        }
        return super.visitClass(declaration, unused);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method
                && method.getEnclosingElement() instanceof TypeElement owner
                && READS_ALL_CONSTANTS.contains(owner.getQualifiedName() + "#" + method.getSimpleName())) {
            referenceEnumsIn(trees.getTypeMirror(getCurrentPath()));
        }
        return super.visitMethodInvocation(invocation, unused);
    }

    @Override
    public Void visitMethod(MethodTree method, Void unused) {
        declare(method);
        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement test
                && test.getEnclosingElement() instanceof TypeElement owner) {
            referenceMethodSources(test, owner);
        }
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
            if (readsAllConstants(member, owner)) {
                referenceConstants(owner);
            }
            if (member instanceof ExecutableElement method) {
                referenceOverridden(method, owner);
            }
        }
    }

    /**
     * A call that resolves to an override is also a call of each declaration it overrides. The
     * receiver's type is searched as well as the owner, since a method may implement an interface
     * only in a subclass that inherits it.
     */
    private void referenceOverridden(ExecutableElement method, TypeElement owner) {
        Set<ExecutableElement> overridden = new HashSet<>(overridden(method, owner));
        if (receiver() instanceof TypeElement receiver && !receiver.equals(owner)) {
            overridden.addAll(overridden(method, receiver));
        }
        for (ExecutableElement declaration : overridden) {
            lines.add(SourceIndex.referenced(key((TypeElement) declaration.getEnclosingElement(), declaration)));
        }
    }

    /** The type the member at the current path is looked up in: the qualifier's, or the enclosing class. */
    private Element receiver() {
        TreePath path = getCurrentPath();
        Tree qualifier = switch (path.getLeaf()) {
            case MemberSelectTree select -> select.getExpression();
            case MemberReferenceTree reference -> reference.getQualifierExpression();
            default -> null;
        };
        if (qualifier != null) {
            return types.asElement(trees.getTypeMirror(new TreePath(path, qualifier)));
        }
        while (path != null && !(path.getLeaf() instanceof ClassTree)) {
            path = path.getParentPath();
        }
        return path == null ? null : trees.getElement(path);
    }

    /** The {@code values()} or {@code valueOf(String)} that every enum has. An enum cannot declare either itself. */
    private static boolean readsAllConstants(Element member, TypeElement owner) {
        return owner.getKind() == ElementKind.ENUM && member instanceof ExecutableElement method
                && method.getModifiers().contains(Modifier.STATIC)
                && (method.getSimpleName().contentEquals("values") && method.getParameters().isEmpty()
                        || method.getSimpleName().contentEquals("valueOf") && method.getParameters().size() == 1);
    }

    /**
     * Spring and Jackson turn a name into a constant of each enum the fields of {@code type} hold.
     * They bind a nested type the same way, so this follows field types whose source is in this
     * compile task.
     */
    private void referenceBoundEnums(TypeElement type, Set<TypeElement> followed) {
        if (!followed.add(type)) {
            return;
        }
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.FIELD && !member.getModifiers().contains(Modifier.STATIC)) {
                for (TypeElement held : typesIn(member.asType(), new HashSet<>())) {
                    if (held.getKind() == ElementKind.ENUM) {
                        referenceConstants(held);
                    } else if (trees.getTree(held) != null) {
                        referenceBoundEnums(held, followed);
                    }
                }
            }
        }
    }

    private void referenceEnumsIn(TypeMirror type) {
        typesIn(type, new HashSet<>()).stream().filter(held -> held.getKind() == ElementKind.ENUM)
                .forEach(this::referenceConstants);
    }

    /** The declared types {@code type} names: itself, its type arguments and its array component. */
    private Set<TypeElement> typesIn(TypeMirror type, Set<TypeElement> found) {
        if (type instanceof ArrayType array) {
            typesIn(array.getComponentType(), found);
        } else if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            found.add(element);
            declared.getTypeArguments().forEach(argument -> typesIn(argument, found));
        }
        return found;
    }

    /**
     * JUnit calls the factories a {@code @MethodSource} names: {@code name} in the test's own
     * class, {@code some.Type#name} in another, or the test's own name when it gives none.
     */
    private void referenceMethodSources(ExecutableElement test, TypeElement owner) {
        for (AnnotationMirror annotation : test.getAnnotationMirrors()) {
            if (!annotationName(annotation).equals(METHOD_SOURCE)) {
                continue;
            }
            List<String> factories = new ArrayList<>();
            annotation.getElementValues().forEach((element, value) -> {
                if (element.getSimpleName().contentEquals("value") && value.getValue() instanceof List<?> names) {
                    names.forEach(name -> factories.add(((AnnotationValue) name).getValue().toString()));
                }
            });
            if (factories.isEmpty()) {
                factories.add(test.getSimpleName().toString());
            }
            for (String factory : factories) {
                String named = factory.replaceFirst("\\(.*", "");
                int hash = named.indexOf('#');
                TypeElement type = hash < 0 ? owner
                        : elements.getTypeElement(named.substring(0, hash).replace('$', '.'));
                if (type != null) {
                    referenceMethodsNamed(named.substring(hash + 1), type, test);
                }
            }
        }
    }

    private void referenceMethodsNamed(String name, TypeElement type, ExecutableElement except) {
        for (Element member : elements.getAllMembers(type)) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)
                    && !member.equals(except) && member.getEnclosingElement() instanceof TypeElement declaring) {
                lines.add(SourceIndex.referenced(key(declaring, member)));
            }
        }
    }

    private void referenceConstants(TypeElement enumType) {
        for (Element constant : enumType.getEnclosedElements()) {
            if (constant.getKind() == ElementKind.ENUM_CONSTANT) {
                lines.add(SourceIndex.referenced(key(enumType, constant)));
            }
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
                    : isMain(executable) || isRecordAccessor(executable, owner)
                            || !overridden(executable, owner).isEmpty();
        }
        return false;
    }

    private static boolean hasAnnotationFrom(Element element, List<String> packages) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (packages.stream().anyMatch(annotationName(annotation)::startsWith)) {
                return true;
            }
        }
        return false;
    }

    private static String annotationName(AnnotationMirror annotation) {
        return ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
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

    /** Every declaration in a supertype of {@code in} that {@code method} overrides or implements there. */
    private List<ExecutableElement> overridden(ExecutableElement method, TypeElement in) {
        List<ExecutableElement> overridden = new ArrayList<>();
        if (method.getKind() != ElementKind.METHOD || method.getModifiers().contains(Modifier.STATIC)) {
            return overridden;
        }
        Set<TypeElement> searched = new HashSet<>();
        Deque<TypeMirror> supertypes = new ArrayDeque<>(List.of(in.asType()));
        while (!supertypes.isEmpty()) {
            TypeMirror supertype = supertypes.pop();
            if (types.asElement(supertype) instanceof TypeElement type && searched.add(type)) {
                for (Element inherited : type.getEnclosedElements()) {
                    if (inherited instanceof ExecutableElement candidate && !candidate.equals(method)
                            && candidate.getSimpleName().equals(method.getSimpleName())
                            && elements.overrides(method, candidate, in)) {
                        overridden.add(candidate);
                    }
                }
                supertypes.addAll(types.directSupertypes(supertype));
            }
        }
        return overridden;
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
