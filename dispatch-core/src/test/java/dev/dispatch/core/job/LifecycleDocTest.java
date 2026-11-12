package dev.dispatch.core.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The lifecycle doc's diagram")
class LifecycleDocTest {

    private static final Path DOC = Path.of("..", "docs", "architecture", "job-lifecycle.md");

    private static final Pattern EDGE =
            Pattern.compile("^\\s*(\\[\\*]|[A-Z_]+)\\s*-->\\s*(\\[\\*]|[A-Z_]+)\\s*(?::\\s*(.*?))?\\s*$");

    @Test
    @DisplayName("has exactly the edges the engine makes, submission and the terminal state included")
    void diagramMatchesTheEngine() throws IOException {
        Map<String, String> documented = documentedEdges();
        Map<String, String> actual = engineEdges();

        List<String> missing = new ArrayList<>(actual.keySet());
        missing.removeAll(documented.keySet());
        List<String> extra = new ArrayList<>(documented.keySet());
        extra.removeAll(actual.keySet());

        assertThat(missing).as("%s", report(missing, extra, documented, actual)).isEmpty();
        assertThat(extra).as("%s", report(missing, extra, documented, actual)).isEmpty();
    }

    /** Edges as {@code "FROM --> TO"} mapped to the label the doc gives them. */
    private static Map<String, String> documentedEdges() throws IOException {
        Map<String, String> edges = new LinkedHashMap<>();
        boolean inBlock = false;
        boolean found = false;
        for (String line : Files.readAllLines(DOC)) {
            if (line.startsWith("```mermaid")) {
                inBlock = true;
                found = true;
            } else if (inBlock && line.startsWith("```")) {
                inBlock = false;
            } else if (inBlock) {
                Matcher edge = EDGE.matcher(line);
                if (edge.matches()) {
                    edges.put(edge.group(1) + " --> " + edge.group(2),
                            edge.group(3) == null ? "" : edge.group(3));
                }
            }
        }
        assertThat(found).as("a mermaid block in %s", DOC).isTrue();
        return edges;
    }

    private static Map<String, String> engineEdges() {
        Map<String, String> edges = new LinkedHashMap<>();
        // Submission is not a Transition: it creates a job rather than moving one.
        edges.put("[*] --> " + JobState.PENDING, "");
        edges.put("[*] --> " + JobState.SCHEDULED, "");
        for (JobState from : JobState.values()) {
            if (from.allowedTransitions().isEmpty()) {
                edges.put(from + " --> [*]", "");
            }
            for (JobState to : from.allowedTransitions()) {
                edges.put(from + " --> " + to, "");
            }
        }
        return edges;
    }

    private static String report(List<String> missing, List<String> extra,
            Map<String, String> documented, Map<String, String> actual) {
        StringBuilder block = new StringBuilder("    stateDiagram-v2\n");
        for (String edge : actual.keySet()) {
            String label = documented.getOrDefault(edge, "describe this move");
            block.append("    ").append(edge).append(label.isEmpty() ? "" : " : " + label)
                    .append('\n');
        }
        return "docs/architecture/job-lifecycle.md disagrees with the engine. Missing from the doc: "
                + missing + ". Not made by the engine: " + extra
                + ". Replace the body of the mermaid block with:\n" + block;
    }
}
