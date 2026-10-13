package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a ProjectTree into a standard ZIP archive.
 */
public final class ZipArchiveGenerator {

    private ZipArchiveGenerator() {}

    /**
     * Converts a ProjectTree into a ZIP byte array with an optional root folder prefix.
     */
    public static byte[] toZipBytes(ProjectTree tree, String rootPrefix) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            writeToStream(tree, rootPrefix, baos);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate ZIP archive", e);
        }
        return baos.toByteArray();
    }

    /**
     * Writes the ProjectTree to an OutputStream as a ZIP stream.
     */
    public static void writeToStream(
            ProjectTree tree,
            String rootPrefix,
            OutputStream outputStream
    ) throws IOException {
        Objects.requireNonNull(tree, "tree must not be null");
        Objects.requireNonNull(outputStream, "outputStream must not be null");

        String prefix = "";
        if (rootPrefix != null && !rootPrefix.isBlank()) {
            prefix = rootPrefix.trim();
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
        }

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (ScaffoldFile file : tree.files()) {
                String entryName = prefix + file.path();
                ZipEntry entry = new ZipEntry(entryName);
                entry.setSize(file.content().length);
                entry.setTime(System.currentTimeMillis());
                zos.putNextEntry(entry);
                zos.write(file.content());
                zos.closeEntry();
            }
            zos.finish();
        }
    }
}
