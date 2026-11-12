package dev.dispatch.lint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The lines of the index the plugin writes for each compile task and {@link RepoCheck} reads back.
 */
final class SourceIndex {
    private SourceIndex() {}

    /**
     * The line that says the plugin saw this exact content of a source file. The hash is what
     * keeps an index left over from an earlier build from vouching for a file edited since.
     */
    static String seen(Path root, Path source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
            return "S " + relative(root, source) + " " + HexFormat.of().formatHex(hash);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A member declared for others to use: its key, its visibility, where it is and what an error
     * calls it. The last field may hold spaces.
     */
    static String declared(String key, String visibility, String where, String display) {
        return "D " + key + " " + visibility + " " + where + " " + display;
    }

    /** A member some code resolves to. A key holds no space. */
    static String referenced(String key) {
        return "R " + key;
    }

    static String relative(Path root, Path source) {
        return root.relativize(source).toString();
    }
}
