package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The inline-fqn rule")
class InlineFqnTest {
    @TempDir
    Path root;

    @Test
    @DisplayName("reports each type written through its package, with the import to add")
    void reportsInlineQualifiedTypes() {
        assertThat(Fixture.compile(root, "Bad.java", """
                class Bad {
                    java.time.Instant started = java.time.Instant.now();
                    java.util.Map.Entry<String, String> entry;
                }
                """)).containsExactly(
                "2:14: [inline-fqn] java.time.Instant is written inline. Add `import java.time.Instant;`"
                        + " and use the simple name (AGENTS.md, Engine code).",
                "2:42: [inline-fqn] java.time.Instant is written inline. Add `import java.time.Instant;`"
                        + " and use the simple name (AGENTS.md, Engine code).",
                "3:14: [inline-fqn] java.util.Map is written inline. Add `import java.util.Map;`"
                        + " and use the simple name (AGENTS.md, Engine code).");
    }

    @Test
    @DisplayName("accepts imported types, and the qualified types javac infers for var and lambdas")
    void acceptsImportedAndInferredTypes() {
        assertThat(Fixture.compile(root, "Good.java", """
                import java.time.Instant;
                import java.util.List;
                import java.util.Map;
                import java.util.function.Function;

                class Good {
                    String text = "No enum constant java.time.Instant.NOW";
                    Map.Entry<String, Instant> entry;

                    int inferred() {
                        var now = Instant.now();
                        Function<List<Instant>, Integer> size = times -> times.size();
                        return size.apply(List.of(now));
                    }
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("reports in every top-level type of a file")
    void reportsInEveryTopLevelType() {
        assertThat(Fixture.compile(root, "First.java", """
                class First {
                    java.time.Instant one;
                }

                class Second {
                    java.time.Duration two;
                }
                """)).extracting(error -> error.substring(0, error.indexOf(" is written")))
                .containsExactly("2:14: [inline-fqn] java.time.Instant", "6:14: [inline-fqn] java.time.Duration");
    }

    @Test
    @DisplayName("accepts a qualified type whose simple name already means another type in the file")
    void acceptsATypeAnImportCannotName() {
        assertThat(Fixture.compile(root, "Clash.java", """
                import java.util.Date;

                class Clash {
                    Date imported;
                    java.sql.Date qualified;
                    java.util.List<String> besideANestedType;

                    interface List {}
                }
                """)).isEmpty();
    }
}
