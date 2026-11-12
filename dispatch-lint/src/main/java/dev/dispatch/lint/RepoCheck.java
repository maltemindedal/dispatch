package dev.dispatch.lint;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * path separator. {@code --text <root>} runs only the text rules, which is what the replay script
 * needs for a checkout that has no compiled indexes.
 */
public final class RepoCheck {
    private RepoCheck() {}

    public static void main(String[] args) throws IOException {
        boolean textOnly = args[0].equals("--text");
        Path root = Path.of(args[textOnly ? 1 : 0]);
        List<String> findings = new ArrayList<>();
        if (!textOnly) {
            findings.addAll(sourcesThePluginMissed(root, paths(args[1]), paths(args[2])));
        }
        findings.addAll(TextRules.check(root, trackedFiles(root)));
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        findings.forEach(err::println);
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

    /**
     * A git command for the repository at {@code root}. Git hooks and {@code git rebase --exec} export
     * GIT_DIR and friends, which would point the command at another repository than {@code root}.
     */
    static ProcessBuilder gitIn(Path root, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
        builder.environment().keySet()
                .removeAll(List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR"));
        return builder;
    }

    /**
     * Tracked files plus untracked ones git does not ignore, so a new file is checked before it is
     * added. A tracked file deleted or moved but not yet staged is still listed by git, so only
     * files that exist in the working tree are returned.
     */
    static List<String> trackedFiles(Path root) throws IOException {
        Process git = gitIn(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String listing = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (git.waitFor() != 0) {
                throw new IOException("git ls-files failed in " + root);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while listing files", e);
        }
        return Stream.of(listing.split("\0")).filter(name -> !name.isEmpty())
                .filter(name -> Files.isRegularFile(root.resolve(name)))
                .toList();
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
