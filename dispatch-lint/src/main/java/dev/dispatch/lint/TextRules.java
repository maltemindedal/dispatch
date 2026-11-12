package dev.dispatch.lint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The text rules of {@code checkRepo}: rules about words and characters in the tracked text files,
 * whatever the language. A finding reads {@code path:line: error: [rule] text}.
 */
final class TextRules {
    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("md", "java", "yml", "yaml", "kts", "sql", "sh", "toml", "properties");
    private static final char EM_DASH = 0x2014;

    private TextRules() {}

    /** Checks the given files, named relative to {@code root}. Files that are not text are skipped. */
    static List<String> check(Path root, List<String> files) throws IOException {
        List<String> findings = new ArrayList<>();
        for (String file : files) {
            if (!isText(file)) {
                continue;
            }
            List<String> lines = Files.readAllLines(root.resolve(file), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String where = file + ":" + (i + 1) + ": error: ";
                if (line.indexOf(EM_DASH) >= 0) {
                    findings.add(where + Rule.EM_DASH.message());
                }
            }
        }
        return findings;
    }

    private static boolean isText(String file) {
        int dot = file.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(file.substring(dot + 1));
    }
}
