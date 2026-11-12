package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The wall-clock rule")
class WallClockTest {
    private static final String CORE_MAIN = "dispatch-core/src/main/Bad.java";

    @TempDir
    Path root;

    @Test
    @DisplayName("reports each read of the system clock in core main, with the fix")
    void reportsWallClockReads() {
        assertThat(Fixture.compile(root, CORE_MAIN, """
                import java.time.Clock;
                import java.time.Instant;

                class Bad {
                    Instant a = Instant.now();
                    long b = System.currentTimeMillis();
                    Clock c() {
                        return Clock.systemUTC();
                    }
                }
                """)).containsExactly(
                "5:24: [wall-clock] Instant.now() reads the wall clock. Engine code takes the injected Clock,"
                        + " or the `now` its caller passes (AGENTS.md, Engine code). Only a constructor may"
                        + " default a Clock with Clock.systemUTC().",
                "6:20: [wall-clock] System.currentTimeMillis() reads the wall clock. Engine code takes the"
                        + " injected Clock, or the `now` its caller passes (AGENTS.md, Engine code). Only a"
                        + " constructor may default a Clock with Clock.systemUTC().",
                "8:21: [wall-clock] Clock.systemUTC() reads the wall clock. Engine code takes the injected"
                        + " Clock, or the `now` its caller passes (AGENTS.md, Engine code). Only a"
                        + " constructor may default a Clock with Clock.systemUTC().");
    }

    @Test
    @DisplayName("accepts the injected clock, and a constructor that defaults one with Clock.systemUTC()")
    void acceptsInjectedAndDefaultedClocks() {
        assertThat(Fixture.compile(root, "dispatch-core/src/main/Good.java", """
                import java.time.Clock;
                import java.time.Instant;

                class Good {
                    private final Clock clock;

                    Good() {
                        this(Clock.systemUTC());
                    }

                    Good(Clock clock) {
                        this.clock = clock == null ? Clock.systemUTC() : clock;
                    }

                    Instant read() {
                        return Instant.now(clock);
                    }
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("reports Clock.systemUTC() in a lambda inside a constructor")
    void reportsALambdaInAConstructor() {
        assertThat(Fixture.compile(root, "dispatch-core/src/main/Lambda.java", """
                import java.time.Clock;
                import java.util.function.Supplier;

                class Lambda {
                    Lambda() {
                        Supplier<Clock> later = () -> Clock.systemUTC();
                    }
                }
                """)).extracting(error -> error.substring(0, error.indexOf(" reads")))
                .containsExactly("6:44: [wall-clock] Clock.systemUTC()");
    }

    @Test
    @DisplayName("ignores other modules and test sources")
    void ignoresOtherSources() {
        assertThat(Fixture.compile(root, "dispatch-api/src/main/Api.java", """
                import java.time.Clock;

                class Api {
                    Clock clock() {
                        return Clock.systemUTC();
                    }
                }
                """)).isEmpty();
        assertThat(Fixture.compile(root, "dispatch-core/src/test/Spec.java", """
                import java.time.Instant;

                class Spec {
                    Instant now = Instant.now();
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("accepts module-info.java and package-info.java in engine main sources")
    void coversModuleAndPackageDeclarations() {
        Fixture.writeDeclarations(root, "dispatch-core/src/main", "");

        assertThat(Fixture.compile(root)).isEmpty();
    }
}
