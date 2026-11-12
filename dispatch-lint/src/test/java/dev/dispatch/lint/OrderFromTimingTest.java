package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The order-from-timing rule")
class OrderFromTimingTest {
    /** The fixture has no dispatch-core on its classpath, so each file declares the JobQueue it calls. */
    private static final String HEADER = """
            package dev.dispatch.core.engine;

            import static org.assertj.core.api.Assertions.assertThat;

            import java.util.List;
            import java.util.concurrent.CopyOnWriteArrayList;
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;

            class JobQueue {
                JobQueue start() {
                    return this;
                }

                List<String> dispatchOnce() {
                    return List.of("a", "b");
                }
            }

            """;

    @TempDir
    Path root;

    @Test
    @DisplayName("reports an ordered assertion on a value a started queue filled, with the fix")
    void reportsAnOrderFromARunningQueue() {
        assertThat(Fixture.compile(root, "test/Bad.java", HEADER + """
                @DisplayName("x")
                class Bad {
                    private final List<String> executionOrder = new CopyOnWriteArrayList<>();

                    @Test
                    @DisplayName("x")
                    void runsInOrder() {
                        new JobQueue().start();
                        assertThat(executionOrder).containsExactly("a", "b");
                    }

                    @Test
                    @DisplayName("x")
                    void startsWithIsOrderedToo() {
                        JobQueue queue = new JobQueue();
                        queue.start();
                        assertThat(executionOrder).startsWith("a");
                    }
                }
                """)).containsExactly(
                "28:51: [order-from-timing] runsInOrder starts a running queue and asserts an order with"
                        + " containsExactly on a value that did not come from JobQueue.dispatchOnce(). A running"
                        + " dispatcher promises no order. Assert on the claim JobQueue.dispatchOnce() returns,"
                        + " and advance MutableClock to pass time (AGENTS.md, Tests).",
                "36:46: [order-from-timing] startsWithIsOrderedToo starts a running queue and asserts an order"
                        + " with startsWith on a value that did not come from JobQueue.dispatchOnce(). A running"
                        + " dispatcher promises no order. Assert on the claim JobQueue.dispatchOnce() returns,"
                        + " and advance MutableClock to pass time (AGENTS.md, Tests).");
    }

    @Test
    @DisplayName("accepts the claim dispatchOnce() returns, directly or through a local variable")
    void acceptsTheClaimFromDispatchOnce() {
        assertThat(Fixture.compile(root, "test/Good.java", HEADER + """
                @DisplayName("x")
                class Good {
                    @Test
                    @DisplayName("x")
                    void direct() {
                        JobQueue queue = new JobQueue().start();
                        assertThat(queue.dispatchOnce()).containsExactly("a", "b");
                    }

                    @Test
                    @DisplayName("x")
                    void viaVariable() {
                        JobQueue queue = new JobQueue().start();
                        List<String> claimed = queue.dispatchOnce();
                        List<String> ids = List.copyOf(claimed);
                        assertThat(ids).containsExactly("a", "b");
                    }
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("accepts an unordered assertion, a method that starts no queue, and a String's startsWith")
    void acceptsOtherShapes() {
        assertThat(Fixture.compile(root, "test/Other.java", HEADER + """
                @DisplayName("x")
                class Other {
                    private final List<String> seen = new CopyOnWriteArrayList<>();

                    @Test
                    @DisplayName("x")
                    void unordered() {
                        new JobQueue().start();
                        assertThat(seen).containsExactlyInAnyOrder("a", "b");
                    }

                    @Test
                    @DisplayName("x")
                    void notStarted() {
                        assertThat(seen).containsExactly("a", "b");
                    }

                    @Test
                    @DisplayName("x")
                    void text() {
                        new JobQueue().start();
                        assertThat("abc").startsWith("a");
                    }
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("accepts an abstract test method, which has no body to scan")
    void acceptsAbstractTestMethod() {
        assertThat(Fixture.compile(root, "test/Base.java", HEADER + """
                @DisplayName("x")
                abstract class Base {
                    @Test
                    @DisplayName("x")
                    abstract void ordered();
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("accepts module-info.java and package-info.java in test sources")
    void coversModuleAndPackageDeclarations() {
        Fixture.writeDeclarations(root, "test", "");

        assertThat(Fixture.compile(root)).isEmpty();
    }
}
