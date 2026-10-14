package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes a {@link ProjectTree} to the file system: as a directory, or as a ZIP archive.
 *
 * <p>Neither form writes over anything unless asked to. A directory that exists and holds an entry,
 * and an archive file that exists, raise {@link TargetExistsException} before anything is written.
 * With {@code force}, a file at a path the tree also writes is replaced and every other file is left
 * where it is.
 *
 * @since 0.10
 */
public final class ProjectWriter {

    private static final Set<PosixFilePermission> EXECUTE = EnumSet.of(PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);

    private ProjectWriter() {
    }

    /**
     * Writes every file of the tree under a directory, creating it and its parents as needed.
     *
     * <p>A file the tree marks executable gets the execute permission on a file system that has
     * POSIX permissions.
     *
     * @param tree      the files to write
     * @param directory the project root
     * @param force     whether to write into a directory that is not empty
     * @throws TargetExistsException if {@code directory} is not a directory, or is not empty and
     *                               {@code force} is not set
     * @throws IOException           if creating a directory or writing a file fails
     * @since 0.10
     */
    public static void writeDirectory(ProjectTree tree, Path directory, boolean force) throws IOException {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(directory, "directory");
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) {
                throw new TargetExistsException(directory, "exists and is not a directory");
            }
            if (!force && !isEmpty(directory)) {
                throw new TargetExistsException(directory, "is not empty");
            }
        }
        for (ScaffoldFile file : tree.files()) {
            Path target = directory.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.write(target, file.content());
            if (file.executable() && Files.getFileStore(target).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
                permissions.addAll(EXECUTE);
                Files.setPosixFilePermissions(target, permissions);
            }
        }
    }

    /**
     * Writes the tree as a ZIP archive whose entries sit under one root directory, creating the
     * archive's parent directories as needed. The archive is written to a staging file beside it
     * and moved into place, so a failed write leaves no archive behind.
     *
     * @param tree    the files to archive
     * @param root    the directory every entry is placed under, a single path segment
     * @param archive the archive file
     * @param force   whether to replace an archive file that exists
     * @throws TargetExistsException if {@code archive} exists and {@code force} is not set
     * @throws IOException           if creating a directory or writing the archive fails
     * @since 0.10
     */
    public static void writeArchive(ProjectTree tree, String root, Path archive, boolean force) throws IOException {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(archive, "archive");
        Path parent = archive.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!force && Files.exists(archive)) {
            throw new TargetExistsException(archive, "exists");
        }
        Path staging = Files.createTempFile(parent != null ? parent : Path.of("."),
                "." + archive.getFileName(), ".partial");
        try {
            try (OutputStream out = Files.newOutputStream(staging, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ZipArchiveGenerator.write(tree, root, out);
            }
            if (force) {
                Files.move(staging, archive, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(staging, archive);
            }
        } catch (FileAlreadyExistsException e) {
            throw new TargetExistsException(archive, "exists", e);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }
}
