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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The whole-repo rules, run by the {@code checkRepo} task after every module compiled. Arguments:
 * the repo root, the source directories and the index directories, each list joined with the
 * path separator. {@code --replay <root> <index directory>} is for the replay script, whose one
 * javac run over a checkout leaves one index: it skips lint-ran, which that index cannot pass.
 */
public final class RepoCheck {
    private static final String JOB_CONTEXT = "dev.dispatch.core.handler.JobContext";
    private static final Set<String> HANDLER_API =
            Set.of(JOB_CONTEXT, "dev.dispatch.core.handler.PermanentJobFailureException");
    private static final String HANDLER_GUIDE = "docs/guides/writing-a-handler.md";
    private static final Pattern TABLE_METHOD = Pattern.compile("`(\\w+)\\(");

    private RepoCheck() {}

    public static void main(String[] args) throws IOException {
        boolean replay = args[0].equals("--replay");
        Path root = Path.of(args[replay ? 1 : 0]);
        List<Path> indexDirs = paths(args[2]);
        List<String> findings = new ArrayList<>();
        if (!replay) {
            findings.addAll(sourcesThePluginMissed(root, paths(args[1]), indexDirs));
        }
        findings.addAll(membersNothingCalls(indexDirs));
        findings.addAll(handlerApiTheGuideMisstates(root, indexDirs));
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
     * unused-surface: a member declared for others to use that no code in any source set resolves
     * to. A use from a test counts. The handler API is exempt, since its callers are the handlers
     * that users of the queue write.
     */
    static List<String> membersNothingCalls(List<Path> indexDirs) throws IOException {
        List<String[]> declared = new ArrayList<>();
        Set<String> referenced = new HashSet<>();
        for (Path index : filesUnder(indexDirs, ".idx")) {
            for (String line : Files.readAllLines(index)) {
                if (line.startsWith("D ")) {
                    declared.add(line.split(" ", 5));
                } else if (line.startsWith("R ")) {
                    referenced.add(line.substring(2));
                }
            }
        }
        List<String> findings = new ArrayList<>();
        for (String[] member : declared) {
            String key = member[1];
            if (!referenced.contains(key) && !HANDLER_API.contains(key.substring(0, key.indexOf('#')))) {
                findings.add(member[3] + ": error: " + Rule.UNUSED_SURFACE.message(member[4], member[2]));
            }
        }
        return findings.stream().sorted().distinct().toList();
    }

    /**
     * handler-api: the price of the unused-surface exemption. JobContext's public methods have no
     * caller here because handler authors call them, so the guide those authors read must list
     * exactly the methods there are.
     */
    static List<String> handlerApiTheGuideMisstates(Path root, List<Path> indexDirs) throws IOException {
        Map<String, String> declared = new TreeMap<>();
        for (Path index : filesUnder(indexDirs, ".idx")) {
            for (String line : Files.readAllLines(index)) {
                String[] member = line.split(" ", 5);
                if (line.startsWith("D " + JOB_CONTEXT + "#") && member[2].equals("public")
                        && !member[1].contains("#<init>")) {
                    declared.put(member[1].substring(member[1].indexOf('#') + 1, member[1].indexOf('(')), member[3]);
                }
            }
        }
        Map<String, Integer> documented = new TreeMap<>();
        Path guide = root.resolve(HANDLER_GUIDE);
        List<String> rows = Files.exists(guide) ? Files.readAllLines(guide) : List.of();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).startsWith("|")) {
                Matcher method = TABLE_METHOD.matcher(rows.get(i).split("\\|")[1]);
                while (method.find()) {
                    documented.put(method.group(1), i + 1);
                }
            }
        }
        List<String> findings = new ArrayList<>();
        declared.forEach((name, where) -> {
            if (!documented.containsKey(name)) {
                findings.add(where + ": error: " + Rule.HANDLER_API.message(
                        "JobContext." + name + "() is public, and the table does not list it"));
            }
        });
        documented.forEach((name, line) -> {
            if (!declared.isEmpty() && !declared.containsKey(name)) {
                findings.add(HANDLER_GUIDE + ":" + line + ": error: " + Rule.HANDLER_API.message(
                        "The table lists `" + name + "()`, and JobContext has no public method of that name"));
            }
        });
        return findings;
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
