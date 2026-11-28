package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a {@link ProjectTree} as a ZIP archive whose entries sit under one root directory.
 *
 * <p>The archive is a function of the tree and the root name: entries are written in the tree's
 * order, and every entry carries the same fixed modification time, so the same request gives the
 * same bytes on every run.
 *
 * @since 0.10
 */
public final class ZipArchiveGenerator {

    /** The modification time of every entry: 2000-01-01T00:00:00Z, inside the DOS date range. */
    private static final FileTime ENTRY_TIME = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));

    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])");

    private ZipArchiveGenerator() {
    }

    /**
     * Writes the tree to a stream as a ZIP archive and finishes the archive. The stream is not
     * closed.
     *
     * @param tree   the files to archive
     * @param root   the directory every entry is placed under, a single path segment that is not made
     *               only of dots, does not end in a dot or a space and holds no {@code :}
     *               and is not a Windows device name, compared without regard to case, before its first dot
     * @param output the stream the archive is written to
     * @throws IOException              if writing to {@code output} fails
     * @throws IllegalArgumentException if {@code root} is blank, holds a path separator or a {@code :},
     *                                  is made only of dots, ends in a dot or a space, or
     *                                  is a Windows device name before its first dot
     * @since 0.10
     */
    public static void write(ProjectTree tree, String root, OutputStream output) throws IOException {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(output, "output");
        if (root == null || root.isBlank() || root.contains("/") || root.contains("\\")
                || root.contains(":") || root.endsWith(".") || root.endsWith(" ")) {
            throw new IllegalArgumentException(
                    "root must be one path segment that does not end in a dot or a space and holds no ':': " + root);
        }
        int dot = root.indexOf('.');
        if (WINDOWS_DEVICE.matcher(dot < 0 ? root : root.substring(0, dot)).matches()) {
            throw new IllegalArgumentException("root must not start with a Windows device name: " + root);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new KeepOpen(output))) {
            for (ScaffoldFile file : tree.files()) {
                ZipEntry entry = new ZipEntry(root + "/" + file.path());
                entry.setLastModifiedTime(ENTRY_TIME);
                zip.putNextEntry(entry);
                zip.write(file.content());
                zip.closeEntry();
            }
        }
    }

    /** Passes everything through except {@code close()}, which leaves the caller's stream open. */
    private static final class KeepOpen extends FilterOutputStream {

        KeepOpen(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
