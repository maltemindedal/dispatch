package dev.dispatch.lint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles one source file with the plugin loaded the way a build loads it, through -Xplugin. */
final class Fixture {
    private Fixture() {}

    static Path indexDir(Path root) {
        return root.resolve("build/dispatch-lint");
    }

    static Path sourceDir(Path root) {
        return root.resolve("src");
    }

    /** Returns every error as {@code line:column: message}. */
    static List<String> compile(Path root, String fileName, String source) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Path file = sourceDir(root).resolve(fileName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            List<String> options = List.of(
                    "-d", root.resolve("build/classes").toString(),
                    "-processorpath", System.getProperty("java.class.path"),
                    "-Xplugin:DispatchLint " + root + " " + indexDir(root).resolve("fixture.idx"));
            javac.getTask(null, files, diagnostics, options, null, files.getJavaFileObjects(file)).call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + ": "
                        + diagnostic.getMessage(Locale.ROOT))
                .toList();
    }
}
