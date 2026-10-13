package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;
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

    private ZipArchiveGenerator() {
    }

    /**
     * Writes the tree to a stream as a ZIP archive and finishes the archive. The stream is not
     * closed.
     *
     * @param tree   the files to archive
     * @param root   the directory every entry is placed under, a single path segment
     * @param output the stream the archive is written to
     * @throws IOException              if writing to {@code output} fails
     * @throws IllegalArgumentException if {@code root} is blank or holds a {@code /}
     * @since 0.10
     */
    public static void write(ProjectTree tree, String root, OutputStream output) throws IOException {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(output, "output");
        if (root == null || root.isBlank() || root.contains("/") || root.contains("\\")) {
            throw new IllegalArgumentException("root must be one path segment: " + root);
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
