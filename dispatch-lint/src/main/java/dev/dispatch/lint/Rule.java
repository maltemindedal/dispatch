package dev.dispatch.lint;

/**
 * Every repo rule the build enforces. The id is the tag the error starts with, and the text names
 * the fix, since the reader is usually an agent that will act on the error alone.
 */
enum Rule {
    INLINE_FQN("inline-fqn",
            "%1$s is written inline. Add `import %1$s;` and use the simple name (AGENTS.md, Engine code)."),
    LINT_RAN("lint-ran",
            "No clean run of the dispatch-lint javac plugin has seen this file as it is now, so no "
                    + "rule vouches for it. Every JavaCompile needs the plugin: restore the "
                    + "dispatch-lint wiring in the root build.gradle.kts, or extend it to this module.");

    private final String id;
    private final String fix;

    Rule(String id, String fix) {
        this.id = id;
        this.fix = fix;
    }

    String message(Object... args) {
        return "[" + id + "] " + fix.formatted(args);
    }
}
