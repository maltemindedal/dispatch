package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The unused-surface rule")
class UnusedSurfaceTest {
    private static final String FIX = " and nothing in any module calls it. Delete it, or add it in the same"
            + " change as the code that calls it (AGENTS.md, Engine code).";

    @TempDir
    Path root;

    @Test
    @DisplayName("reports a public method nothing calls, with the fix")
    void reportsAnUncalledMethod() throws IOException {
        Fixture.write(root, "main/app/Queue.java", """
                package app;

                import java.util.List;

                public class Queue {
                    public int size(List<String> jobs, int[] limits) {
                        return 0;
                    }
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/Queue.java:6: error: [unused-surface] "
                + "Queue.size(List, int[]) is public" + FIX);
    }

    @Test
    @DisplayName("accepts the method once a test calls it")
    void acceptsAMethodATestCalls() throws IOException {
        Fixture.write(root, "main/app/Queue.java", """
                package app;

                public class Queue {
                    public int size() {
                        return 0;
                    }
                }
                """);
        Fixture.write(root, "test/app/QueueTest.java", """
                package app;

                class QueueTest {
                    int check(Queue queue) {
                        return queue.size();
                    }
                }
                """);

        assertThat(findings()).isEmpty();
    }

    @Test
    @DisplayName("reports an uncalled constructor, field and enum constant, and names the visibility")
    void reportsEveryKindOfMember() throws IOException {
        Fixture.write(root, "main/app/Members.java", """
                package app;

                public class Members {
                    public static final int LIMIT = 1;
                    protected int count;

                    Members(String name) {}

                    public enum Transition { CLAIM, SWEEP }

                    static Transition used() {
                        return Transition.CLAIM;
                    }
                }
                """);

        assertThat(findings()).containsExactly(
                "src/main/app/Members.java:11: error: [unused-surface] Members.used() is package-private" + FIX,
                "src/main/app/Members.java:4: error: [unused-surface] Members.LIMIT is public" + FIX,
                "src/main/app/Members.java:5: error: [unused-surface] Members.count is protected" + FIX,
                "src/main/app/Members.java:7: error: [unused-surface] Members(String) is package-private" + FIX,
                "src/main/app/Members.java:9: error: [unused-surface] Transition.SWEEP is public" + FIX);
    }

    @Test
    @DisplayName("counts a call, a method reference, a new, a field read and a switch label as a use")
    void countsEveryKindOfUse() throws IOException {
        Fixture.write(root, "main/app/Uses.java", """
                package app;

                import java.util.function.Supplier;

                public class Uses {
                    public static final int LIMIT = 1;

                    public enum State { ON, OFF }

                    public Uses() {}

                    public Uses(int limit) {
                        this();
                    }

                    public int called() {
                        return LIMIT;
                    }

                    public int referenced() {
                        return 0;
                    }

                    private Object all(State state) {
                        Supplier<Integer> supplier = this::referenced;
                        return switch (state) {
                            case ON -> new Uses(called());
                            case OFF -> supplier;
                        };
                    }
                }
                """);

        assertThat(findings()).isEmpty();
    }

    @Test
    @DisplayName("checks testFixtures like main, and leaves private members and test sources alone")
    void checksMainAndTestFixturesOnly() throws IOException {
        Fixture.write(root, "testFixtures/app/Support.java", """
                package app;

                public class Support {
                    public void helper() {}

                    private void internal() {}
                }
                """);
        Fixture.write(root, "test/app/SupportTest.java", """
                package app;

                public class SupportTest {
                    public void helper() {}
                }
                """);

        assertThat(findings()).containsExactly("src/testFixtures/app/Support.java:4: error: [unused-surface] "
                + "Support.helper() is public" + FIX);
    }

    @Test
    @DisplayName("exempts an override and an interface implementation")
    void exemptsOverrides() throws IOException {
        Fixture.write(root, "main/app/Handler.java", """
                package app;

                public interface Handler {
                    void handle();

                    static void run(Handler handler) {
                        handler.handle();
                        Handler.run(handler);
                    }
                }

                abstract class Base implements Handler {}

                class Email extends Base {
                    @Override
                    public void handle() {}

                    public String toString() {
                        return "email";
                    }
                }
                """);

        assertThat(findings()).isEmpty();
    }

    @Test
    @DisplayName("counts a call through a subtype as a use of the declaration it overrides or implements")
    void countsACallThroughASubtype() throws IOException {
        Fixture.write(root, "main/app/Stores.java", """
                package app;

                import java.util.function.IntSupplier;

                interface Store {
                    int size();

                    int capacity();

                    default int free() {
                        return 0;
                    }
                }

                interface Named {
                    String name();
                }

                interface Closed {
                    void close();
                }

                class Shared {
                    public String name() {
                        return "";
                    }

                    public void close() {}
                }

                abstract class Memory extends Shared implements Store, Named, Closed {
                    @Override
                    public int size() {
                        close();
                        return 0;
                    }
                }

                class Caller {
                    private Object use(Memory memory) {
                        IntSupplier capacity = memory::capacity;
                        return memory.size() + memory.name() + capacity;
                    }
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/Stores.java:10: error: [unused-surface] "
                + "Store.free() is public" + FIX);
    }

    @Test
    @DisplayName("exempts main(String[]), and no other method named main")
    void exemptsTheEntryPoint() throws IOException {
        Fixture.write(root, "main/app/Application.java", """
                package app;

                public class Application {
                    public static void main(String[] args) {}

                    public static void main(String arg) {}
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/Application.java:6: error: [unused-surface] "
                + "Application.main(String) is public" + FIX);
    }

    @Test
    @DisplayName("exempts a member with a Spring, Jakarta, Jackson or JUnit annotation, and no other annotation")
    void exemptsFrameworkAnnotatedMembers() throws IOException {
        annotation("org.springframework.context.annotation", "Bean");
        annotation("jakarta.annotation", "PostConstruct");
        annotation("tools.jackson.annotation", "JsonCreator");
        annotation("org.junit.jupiter.api", "BeforeEach");
        annotation("app", "Marker");
        Fixture.write(root, "main/app/Wiring.java", """
                package app;

                import jakarta.annotation.PostConstruct;
                import org.junit.jupiter.api.BeforeEach;
                import org.springframework.context.annotation.Bean;
                import tools.jackson.annotation.JsonCreator;

                public class Wiring {
                    @Bean
                    public String bean() {
                        return "";
                    }

                    @PostConstruct
                    public void start() {}

                    @JsonCreator
                    public static Wiring of() {
                        return null;
                    }

                    @BeforeEach
                    public void reset() {}

                    @Marker
                    public void marked() {}
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/Wiring.java:25: error: [unused-surface] "
                + "Wiring.marked() is public" + FIX);
    }

    @Test
    @DisplayName("exempts the constructor of a Spring-annotated class, and not its other members")
    void exemptsSpringConstructors() throws IOException {
        annotation("org.springframework.stereotype", "Component");
        Fixture.write(root, "main/app/Service.java", """
                package app;

                import org.springframework.stereotype.Component;

                @Component
                public class Service {
                    public Service(String name) {}

                    public void unused() {}
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/Service.java:9: error: [unused-surface] "
                + "Service.unused() is public" + FIX);
    }

    @Test
    @DisplayName("exempts a record's canonical constructor and accessors, and not its other members")
    void exemptsWhatARecordDeclaresFromItsComponents() throws IOException {
        Fixture.write(root, "main/app/Page.java", """
                package app;

                public record Page(int limit, int offset) {
                    public Page {
                        offset = Math.max(0, offset);
                    }

                    public Page(int limit) {
                        this(limit, 0);
                    }

                    public int limit() {
                        return limit;
                    }

                    public int end() {
                        return limit + offset;
                    }
                }
                """);
        Fixture.write(root, "main/app/Implicit.java", """
                package app;

                public record Implicit(int value) {}
                """);

        assertThat(findings()).containsExactly(
                "src/main/app/Page.java:16: error: [unused-surface] Page.end() is public" + FIX,
                "src/main/app/Page.java:8: error: [unused-surface] Page(int) is public" + FIX);
    }

    @Test
    @DisplayName("exempts an enum's values() and valueOf(), and its constructor")
    void exemptsWhatAnEnumDeclaresImplicitly() throws IOException {
        Fixture.write(root, "main/app/State.java", """
                package app;

                public enum State {
                    ON(1);

                    State(int code) {}

                    static State on() {
                        return on() == null ? ON : ON;
                    }
                }
                """);

        assertThat(findings()).isEmpty();
    }

    @Test
    @DisplayName("counts values(), valueOf() and an EnumSet built from the enum's class or a range as a use of"
            + " every constant, and no other use of the class literal")
    void countsReadingAllConstantsAsAUseOfEach() throws IOException {
        Fixture.write(root, "main/app/Parsing.java", """
                package app;

                import java.util.EnumSet;
                import java.util.List;

                public class Parsing {
                    public enum Mode { FAST, SLOW }
                    public enum Level { LOW, HIGH }
                    public enum Color { RED, GREEN }
                    public enum Shape { ROUND, SQUARE }
                    public enum Size { S, M, L }
                    public enum Side { LEFT, RIGHT }
                    public enum Kind { OLD, NEW }
                    public enum Unit { BYTE, WORD }
                    public enum Idle { NEVER }

                    private Object all(String name) {
                        return List.of(Mode.values(), Level.valueOf(name), EnumSet.allOf(Color.class),
                                EnumSet.noneOf(Shape.class), EnumSet.range(Size.S, Size.L),
                                EnumSet.complementOf(EnumSet.of(Side.LEFT)), Enum.valueOf(Kind.class, name),
                                Unit.class.getEnumConstants(), Idle.class.getName());
                    }
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/Parsing.java:15: error: [unused-surface] "
                + "Idle.NEVER is public" + FIX);
    }

    @Test
    @DisplayName("counts an enum that a @ConfigurationProperties type or a web.dto type holds as a use of every"
            + " constant, since Spring and Jackson bind them by name")
    void countsABoundEnumAsAUseOfEachConstant() throws IOException {
        annotation("org.springframework.boot.context.properties", "ConfigurationProperties");
        Fixture.write(root, "main/app/Settings.java", """
                package app;

                import java.util.List;
                import org.springframework.boot.context.properties.ConfigurationProperties;

                @ConfigurationProperties
                public record Settings(StoreType store, List<Mode[]> modes, Retry retry) {
                    public enum StoreType { JDBC, MEMORY }
                    public enum Mode { FAST, SLOW }
                    public enum Jitter { NONE, FULL }

                    public record Retry(Jitter jitter, Retry next) {}
                }

                @ConfigurationProperties
                class Bean {
                    private Level level;

                    enum Level { LOW, HIGH }
                }

                record Plain(Unbound unbound) {
                    enum Unbound { NEVER }
                }
                """);
        Fixture.write(root, "main/app/web/dto/JobRequest.java", """
                package app.web.dto;

                public record JobRequest(Priority priority) {
                    public enum Priority { LOW, HIGH }
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/Settings.java:23: error: [unused-surface] "
                + "Unbound.NEVER is public" + FIX);
    }

    @Test
    @DisplayName("exempts every member of a web.dto record, which Jackson reads, and not a class there")
    void exemptsWebDtoRecords() throws IOException {
        Fixture.write(root, "main/app/web/dto/JobResponse.java", """
                package app.web.dto;

                public record JobResponse(String id) {
                    public boolean isTerminal() {
                        return false;
                    }
                }
                """);
        Fixture.write(root, "main/app/web/dto/Mapper.java", """
                package app.web.dto;

                public class Mapper {
                    public void unused() {}
                }
                """);

        assertThat(findings()).containsExactly("src/main/app/web/dto/Mapper.java:4: error: [unused-surface] "
                + "Mapper.unused() is public" + FIX);
    }

    @Test
    @DisplayName("exempts the handler API, whose callers are the handlers that users write")
    void exemptsTheHandlerApi() throws IOException {
        Fixture.write(root, "main/dev/dispatch/core/handler/JobContext.java", """
                package dev.dispatch.core.handler;

                public final class JobContext {
                    public String payload() {
                        return "";
                    }
                }
                """);
        Fixture.write(root, "main/dev/dispatch/core/handler/PermanentJobFailureException.java", """
                package dev.dispatch.core.handler;

                public class PermanentJobFailureException extends Exception {
                    private static final long serialVersionUID = 1L;

                    public PermanentJobFailureException(String message, Throwable cause) {
                        super(message, cause);
                    }
                }
                """);
        Fixture.write(root, "main/dev/dispatch/core/handler/Other.java", """
                package dev.dispatch.core.handler;

                public class Other {
                    public void unused() {}
                }
                """);

        assertThat(findings()).containsExactly("src/main/dev/dispatch/core/handler/Other.java:4: error: "
                + "[unused-surface] Other.unused() is public" + FIX);
    }

    private void annotation(String packageName, String name) {
        Fixture.write(root, "main/" + packageName.replace('.', '/') + "/" + name + ".java",
                "package " + packageName + ";\n\npublic @interface " + name + " {}\n");
    }

    private List<String> findings() throws IOException {
        assertThat(Fixture.compile(root)).isEmpty();
        return RepoCheck.membersNothingCalls(List.of(Fixture.indexDir(root)));
    }
}
