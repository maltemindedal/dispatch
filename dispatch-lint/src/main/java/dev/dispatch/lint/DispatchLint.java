package dev.dispatch.lint;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The javac plugin that runs the per-file repo rules inside every compile task. A violation is an
 * ordinary compile error. Arguments: the repo root, then the index file to write.
 */
public final class DispatchLint implements Plugin {
    @Override
    public String getName() {
        return "DispatchLint";
    }

    @Override
    public void init(JavacTask task, String... args) {
        Path root = Path.of(args[0]);
        Path index = Path.of(args[1]);
        Trees trees = Trees.instance(task);
        delete(index);
        task.addTaskListener(new TaskListener() {
            private final Set<String> lines = new TreeSet<>();
            private boolean clean = true;

            @Override
            public void finished(TaskEvent event) {
                switch (event.getKind()) {
                    case PARSE -> lines.add(SourceIndex.seen(root, Path.of(event.getSourceFile().toUri())));
                    case ANALYZE -> {
                        // One event per top-level type, fired before javac rewrites that type's
                        // trees. Scanning the whole unit here would read rewritten trees of the
                        // unit's earlier types and unattributed trees of its later ones.
                        if (event.getTypeElement() != null) {
                            JavaRules rules = new JavaRules(trees, event.getCompilationUnit());
                            rules.scan(trees.getPath(event.getTypeElement()), null);
                            clean &= !rules.reported();
                        }
                    }
                    case COMPILATION -> {
                        // An index vouches for the sources in it, so a run with a finding leaves none.
                        if (clean) {
                            write(index, lines);
                        }
                    }
                    default -> { }
                }
            }
        });
    }

    private static void delete(Path index) {
        try {
            Files.deleteIfExists(index);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path index, Set<String> lines) {
        try {
            Files.createDirectories(index.getParent());
            Files.write(index, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
