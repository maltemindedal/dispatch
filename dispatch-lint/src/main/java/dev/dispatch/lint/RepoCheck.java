package dev.dispatch.lint;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The whole-repo rules, run by the {@code checkRepo} task after every module compiled. Arguments:
 * the repo root, the source directories and the index directories, each list joined with the
 * path separator.
 */
public final class RepoCheck {
    private RepoCheck() {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        List<String> findings = sourcesThePluginMissed(root, paths(args[1]), paths(args[2]));
        findings.forEach(System.err::println);
        if (!findings.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * lint-ran: a per-file rule that did not run looks exactly like a clean file. So every source
     * file must be in an index with its current content.
     */
    static List<String> sourcesThePluginMissed(Path root, List<Path> sourceDirs, List<Path> indexDirs)
            throws IOException {
        Set<String> seen = new HashSet<>();
        for (Path index : filesUnder(indexDirs, ".idx")) {
            seen.addAll(Files.readAllLines(index));
        }
        List<String> findings = new ArrayList<>();
        for (Path source : filesUnder(sourceDirs, ".java")) {
            if (!seen.contains(SourceIndex.seen(root, source))) {
                findings.add(SourceIndex.relative(root, source) + ": error: " + Rule.LINT_RAN.message());
            }
        }
        return findings;
    }

    private static List<Path> filesUnder(List<Path> dirs, String suffix) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(file -> file.toString().endsWith(suffix)).sorted().forEach(files::add);
                }
            }
        }
        return files;
    }

    private static List<Path> paths(String joined) {
        return Stream.of(joined.split(File.pathSeparator)).map(Path::of).toList();
    }
}
