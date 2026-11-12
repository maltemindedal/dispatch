package dev.dispatch.lint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles a source directory with the plugin loaded the way a build loads it, through -Xplugin. */
final class Fixture {
    private Fixture() {}

    static Path indexDir(Path root) {
        return root.resolve("build/dispatch-lint");
    }

    static Path sourceDir(Path root) {
        return root.resolve("src");
    }

    static void write(Path root, String fileName, String source) {
        try {
            Path file = sourceDir(root).resolve(fileName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the file, compiles it, and returns every error as {@code line:column: message}. */
    static List<String> compile(Path root, String fileName, String source) {
        write(root, fileName, source);
        return compile(root).stream().map(error -> error.substring(error.indexOf(' ') + 1)).toList();
    }

    /** Compiles every file written so far. Returns every error as {@code File.java line:column: message}. */
    static List<String> compile(Path root) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null);
                Stream<Path> sources = Files.walk(sourceDir(root))) {
            List<String> options = List.of(
                    "-d", root.resolve("build/classes").toString(),
                    "-processorpath", System.getProperty("java.class.path"),
                    "-Xplugin:DispatchLint " + root + " " + indexDir(root).resolve("fixture.idx"));
            List<Path> java = sources.filter(Files::isRegularFile).sorted().toList();
            javac.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(java)).call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> (diagnostic.getSource() == null ? "-"
                        : Path.of(diagnostic.getSource().toUri()).getFileName()) + " " + diagnostic.getLineNumber()
                        + ":" + diagnostic.getColumnNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
                .toList();
    }
}
