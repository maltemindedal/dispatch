package dev.dispatch.lint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text rules of {@code checkRepo}: rules about words and characters in the tracked text files,
 * whatever the language. A finding reads {@code path:line: error: [rule] text}.
 */
final class TextRules {
    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("md", "java", "yml", "yaml", "kts", "sql", "sh", "toml", "properties");
    private static final char EM_DASH = 0x2014;
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    /** The words the project does not use for its own concepts. This is the one copy of the list. */
    private static final Pattern BANNED_WORD = Pattern.compile(
            "\\b(?:(?:de|re)-?queu(?:e[sd]?|eing|ing)|reapers?|revive[sd]?|reviving|dead[- ]letter[- ]queues?)\\b");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(?:NOTE:|INSERT|DESCRIBE)|TODO\\(placeholder\\)");
    /** The rule's own source and tests name the banned words, so they are exempt. */
    private static final String VOCABULARY_EXEMPT_PREFIX = "dispatch-lint/src/";

    private TextRules() {}

    /** Checks the given files, named relative to {@code root}. Files that are not text are skipped. */
    static List<String> check(Path root, List<String> files) throws IOException {
        List<String> findings = new ArrayList<>();
        for (String file : files) {
            if (!isText(file)) {
                continue;
            }
            boolean prose = file.endsWith(".md") || file.endsWith(".yml") || file.endsWith(".yaml");
            List<String> lines = Files.readAllLines(root.resolve(file), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String where = file + ":" + (i + 1) + ": error: ";
                if (line.indexOf(EM_DASH) >= 0) {
                    findings.add(where + Rule.EM_DASH.message());
                }
                Matcher placeholder = PLACEHOLDER.matcher(line);
                if (prose && placeholder.find()) {
                    findings.add(where + Rule.TEMPLATE_RESIDUE.message(placeholder.group()));
                }
                if (!file.startsWith(VOCABULARY_EXEMPT_PREFIX)) {
                    Matcher banned = BANNED_WORD.matcher(splitCamelCase(line).toLowerCase(Locale.ROOT).replace('_', ' '));
                    while (banned.find()) {
                        findings.add(where + Rule.VOCABULARY.message(banned.group()));
                    }
                }
            }
        }
        return findings;
    }

    /** Turns {@code reclaimWithRequeue} into {@code reclaim With Requeue}, so an identifier is read as words. */
    private static String splitCamelCase(String line) {
        return CAMEL_BOUNDARY.matcher(line).replaceAll(" ");
    }

    private static boolean isText(String file) {
        int dot = file.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(file.substring(dot + 1));
    }
}
