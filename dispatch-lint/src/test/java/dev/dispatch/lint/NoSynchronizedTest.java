package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The no-synchronized rule")
class NoSynchronizedTest {
    @TempDir
    Path root;

    @Test
    @DisplayName("reports a synchronized method and a synchronized block in main sources, with the fix")
    void reportsSynchronized() {
        assertThat(Fixture.compile(root, "main/Bad.java", """
                class Bad {
                    private int count;

                    synchronized void method() {
                        count++;
                    }

                    void block() {
                        synchronized (this) {
                            count++;
                        }
                    }
                }
                """)).containsExactly(
                "4:23: [no-synchronized] `synchronized` pins a virtual thread to its carrier on Java 21, and"
                        + " handlers run on virtual threads. Guard the state with a ReentrantLock or a"
                        + " Semaphore (AGENTS.md, Engine code).",
                "9:9: [no-synchronized] `synchronized` pins a virtual thread to its carrier on Java 21, and"
                        + " handlers run on virtual threads. Guard the state with a ReentrantLock or a"
                        + " Semaphore (AGENTS.md, Engine code).");
    }

    @Test
    @DisplayName("accepts a ReentrantLock in main sources, and synchronized in test sources")
    void acceptsLocksAndTestSources() {
        assertThat(Fixture.compile(root, "main/Good.java", """
                import java.util.concurrent.locks.ReentrantLock;

                class Good {
                    private final ReentrantLock lock = new ReentrantLock();
                    private int count;

                    void increment() {
                        lock.lock();
                        try {
                            count++;
                        } finally {
                            lock.unlock();
                        }
                    }
                }
                """)).isEmpty();
        assertThat(Fixture.compile(root, "test/Spec.java", """
                class Spec {
                    synchronized void method() {}
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("accepts module-info.java and package-info.java in main sources")
    void coversModuleAndPackageDeclarations() {
        Fixture.writeDeclarations(root, "main", "");

        assertThat(Fixture.compile(root)).isEmpty();
    }
}
