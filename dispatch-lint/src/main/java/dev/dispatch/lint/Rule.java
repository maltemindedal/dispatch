package dev.dispatch.lint;

/**
 * Every repo rule the build enforces. The id is the tag the error starts with, and the text names
 * the fix, since the reader is usually an agent that will act on the error alone.
 */
enum Rule {
    INLINE_FQN("inline-fqn",
            "%1$s is written inline. Add `import %1$s;` and use the simple name (AGENTS.md, Engine code)."),
    JUNIT_ASSERT("junit-assert",
            "%s is a JUnit assertion. Assert with AssertJ: `assertThat(...)`, or `assertThatThrownBy(...)` "
                    + "for an exception (AGENTS.md, Tests)."),
    DISPLAY_NAME("display-name",
            "%s has no @DisplayName. Add `@DisplayName(\"<one sentence saying what it checks>\")`. "
                    + "A `name =` on @ParameterizedTest is extra, not a replacement (AGENTS.md, Tests)."),
    WALL_CLOCK("wall-clock",
            "%s reads the wall clock. Engine code takes the injected Clock, or the `now` its caller passes "
                    + "(AGENTS.md, Engine code). Only a constructor may default a Clock with Clock.systemUTC()."),
    NO_SYNCHRONIZED("no-synchronized",
            "`synchronized` pins a virtual thread to its carrier on Java 21, and handlers run on virtual "
                    + "threads. Guard the state with a ReentrantLock or a Semaphore (AGENTS.md, Engine code)."),
    NO_DEPRECATED("no-deprecated",
            "%s is marked @Deprecated, but nothing publishes this code, so no caller needs time to move. "
                    + "Migrate the callers and delete it in the same change (AGENTS.md, Engine code)."),
    ORDER_FROM_TIMING("order-from-timing",
            "%s starts a running queue and asserts an order with %s on a value that did not come from "
                    + "JobQueue.dispatchOnce(). A running dispatcher promises no order. Assert on the claim "
                    + "JobQueue.dispatchOnce() returns, and advance MutableClock to pass time (AGENTS.md, Tests)."),
    EM_DASH("em-dash",
            "`%s` is an em dash (U+2014). Write two short sentences instead (AGENTS.md, Prose, commits and PRs)."),
    VOCABULARY("vocabulary",
            "`%s` is not this project's word. Use claim (not dequeue), sweep (not reaper), dead letter "
                    + "(not dead-letter queue), manual retry (not revive or requeue), lease and selection "
                    + "(AGENTS.md, Prose, commits and PRs)."),
    TEMPLATE_RESIDUE("template-residue",
            "`%s` is placeholder text left over from a template. Write the real content, or delete the "
                    + "line if it does not apply to this project."),
    DEPENDABOT_COOLDOWN("dependabot-cooldown",
            "dependabot.yml has a `cooldown` block. The owner chose Dependabot's default delay, and PR #15 "
                    + "removed a self-invented 7-day rule. Delete the `cooldown` block (AGENTS.md, Module boundary "
                    + "and dependencies)."),
    NOT_UTF8("not-utf8",
            "This text file is not valid UTF-8, so the text rules cannot read it. Save it as UTF-8, or "
                    + "move it to an extension the text rules do not cover if it is not text."),
    UNUSED_SURFACE("unused-surface",
            "%s is %s and nothing in any module calls it. Delete it, or add it in the same change as the "
                    + "code that calls it (AGENTS.md, Engine code)."),
    HANDLER_API("handler-api",
            "%s. JobContext is the handler API, and the table under `What the context gives you` in "
                    + "docs/guides/writing-a-handler.md documents it. Make the two agree: add or fix the "
                    + "table row, or delete the method if no handler needs it."),
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
