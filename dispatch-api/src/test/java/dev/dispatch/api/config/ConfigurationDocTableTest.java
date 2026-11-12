package dev.dispatch.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * docs/reference/configuration.md lists every {@code dispatch.*} key with its default, and the
 * values come from {@code application.yml}. {@link ApplicationYmlDefaultsTest} pins the file to
 * the engine. This pins the table to the file, so a new key or a changed default cannot ship
 * without its row.
 */
@DisplayName("configuration.md versus application.yml")
class ConfigurationDocTableTest {

    private static final Path DOC = Path.of("..", "docs", "reference", "configuration.md");
    private static final String PREFIX = "dispatch.";
    private static final Pattern CODE = Pattern.compile("`([^`]*)`");

    @EnableConfigurationProperties(QueueProperties.class)
    static class Properties {
    }

    @Test
    @DisplayName("the key and default table has one row per bound dispatch key with its default")
    void tableMatchesTheShippedFile() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Properties.class)
                .run(context -> {
                    Map<String, String> bound = boundDefaults(context.getEnvironment());
                    assertThat(bound).containsKeys("concurrency", "retry.jitter-factor");
                    assertThat(problems(bound, DOC)).as("fix docs/reference/configuration.md").isEmpty();
                });
    }

    @Test
    @DisplayName("a missing row, a stale default and a row for an unbound key each name the fix")
    void problemsNameTheRowToAddOrFix(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("configuration.md");
        Files.writeString(doc, """
                | Key | Type | Default | Effect |
                | --- | --- | --- | --- |
                | `concurrency` | int | `8` | Jobs in flight. |
                | `gone` | int | `1` | Not a key any more. |
                | Variable | Default |
                | `OTHER` | `x` |
                """);

        assertThat(problems(Map.of("concurrency", "16", "maintenance-batch-size", "500"), doc))
                .containsExactlyInAnyOrder(
                        "row `concurrency`: change the Default cell from `8` to `16`",
                        "row `gone`: application.yml binds no dispatch.gone, remove the row",
                        "no row for `maintenance-batch-size`: add | `maintenance-batch-size` |"
                                + " <type> | `500` | <effect> |");
    }

    /** Every {@code dispatch.*} property the context holds, keyed without the prefix. */
    private static Map<String, String> boundDefaults(ConfigurableEnvironment environment) {
        Map<String, String> bound = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(PREFIX)) {
                        bound.putIfAbsent(name.substring(PREFIX.length()),
                                environment.getProperty(name));
                    }
                }
            }
        }
        return bound;
    }

    /**
     * Compares the first {@code Key | ... | Default} table in {@code doc} with {@code bound}. The
     * Default cell's first code span is the value, with the quotes of an empty string dropped.
     */
    static List<String> problems(Map<String, String> bound, Path doc) throws IOException {
        Map<String, String> documented = new LinkedHashMap<>();
        int keyColumn = -1;
        int defaultColumn = -1;
        for (String line : Files.readAllLines(doc)) {
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.get(0).equals("Key")) {
                keyColumn = 0;
                defaultColumn = cells.indexOf("Default");
            } else if (cells.get(0).equals("Variable") || cells.get(0).equals("Setting")) {
                keyColumn = -1;
            } else if (keyColumn >= 0 && defaultColumn >= 0 && cells.size() > defaultColumn
                    && !cells.get(0).startsWith("---")) {
                documented.put(firstCode(cells.get(keyColumn)), firstCode(cells.get(defaultColumn)));
            }
        }

        List<String> problems = new ArrayList<>();
        bound.forEach((key, value) -> {
            String found = documented.get(key);
            if (found == null) {
                problems.add("no row for `" + key + "`: add | `" + key + "` | <type> | `" + value
                        + "` | <effect> |");
            } else if (!found.equals(value)) {
                problems.add("row `" + key + "`: change the Default cell from `" + found + "` to `"
                        + value + "`");
            }
        });
        documented.keySet().stream()
                .filter(key -> !bound.containsKey(key))
                .forEach(key -> problems.add("row `" + key + "`: application.yml binds no dispatch."
                        + key + ", remove the row"));
        return problems;
    }

    private static List<String> cells(String line) {
        String body = line.strip();
        body = body.substring(1, body.endsWith("|") ? body.length() - 1 : body.length());
        List<String> cells = new ArrayList<>();
        // A pipe inside a code span is written \| in the table.
        for (String cell : body.split("(?<!\\\\)\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static String firstCode(String cell) {
        Matcher match = CODE.matcher(cell);
        String code = match.find() ? match.group(1) : cell;
        return code.equals("\"\"") ? "" : code;
    }
}
