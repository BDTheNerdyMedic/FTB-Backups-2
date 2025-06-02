package net.creeperhost.ftbbackups.utils;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import io.airlift.compress.zstd.ZstdOutputStream;
import net.creeperhost.ftbbackups.FTBBackups;
import net.creeperhost.ftbbackups.config.Config;
import net.creeperhost.ftbbackups.config.ConfigData.Format;
import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utility class for file operations, including copying, compressing, and hashing files and directories.
 * Optimized for handling large directories, such as Minecraft worlds, with efficient memory usage and error handling.
 */
public class FileUtils {

    public static final long KB = 1024L;
    public static final long MB = KB * 1024L;
    public static final long GB = MB * 1024L;
    public static final long TB = GB * 1024L;

    public static final double KB_D = 1024D;
    public static final double MB_D = KB_D * 1024D;
    public static final double GB_D = MB_D * 1024D;
    public static final double TB_D = GB_D * 1024D;

    /**
     * Copies files from the specified source paths to the output directory.
     *
     * @param outputDirectory the directory to copy files to
     * @param serverRoot the root directory of the server, used for relative paths
     * @param sourcePaths the paths to copy from
     * @throws IOException if an I/O error occurs during copying
     */
    public static void copySourcePathsToDirectory(Path outputDirectory, Path serverRoot, Iterable<Path> sourcePaths) throws IOException {
        Path destDir = Files.createDirectory(outputDirectory);
        for (Path sourcePath : sourcePaths) {
            if (Files.isDirectory(sourcePath)) {
                try (var pathStream = Files.walk(sourcePath)) {
                    pathStream.filter(path -> !Files.isDirectory(path))
                            .forEach(path -> copySingleFileToDirectory(destDir, serverRoot, path));
                }
            } else {
                copySingleFileToDirectory(destDir, serverRoot, sourcePath);
            }
        }
    }

    /**
     * Copies a single file to the destination directory.
     *
     * @param destDir the destination directory
     * @param serverRoot the server root for relative paths
     * @param file the file to copy
     */
    private static void copySingleFileToDirectory(Path destDir, Path serverRoot, Path file) {
        if (shouldExcludeFileFromBackup(file)) {
            FTBBackups.LOGGER.debug("Skipping file during copy: {}", file);
            return;
        }
        try {
            Path relFile = serverRoot.relativize(file);
            if (doesFilterExcludePath(relFile, Config.getConfigData().excluded_paths)) {
                FTBBackups.LOGGER.debug("Skipping excluded file: {}", relFile);
                return;
            }
            Path destFile = destDir.resolve(relFile);
            Files.createDirectories(destFile.getParent());
            Files.copy(file, destFile);
        } catch (java.nio.file.NoSuchFileException e) {
            FTBBackups.LOGGER.debug("File disappeared during copy, skipping: {}", file);
        } catch (IOException e) {
            FTBBackups.LOGGER.warn("Error copying file {}: {}", file, e.getMessage(), e);
        }
    }

    /**
     * Compresses the specified source paths into an archive file.
     *
     * @param archiveFilePath the path to the archive file to create
     * @param serverRoot the root directory of the server, used for relative paths
     * @param sourcePaths the paths to compress
     * @param format the format of the archive (ZIP or ZSTD)
     * @throws IOException if an I/O error occurs during compression
     */
    public static void compressSourcePathsToArchive(Path archiveFilePath, Path serverRoot, Iterable<Path> sourcePaths, Format format) throws IOException {
        try {
            Files.createDirectories(archiveFilePath.getParent());
            Path archivePath = Files.createFile(archiveFilePath);
            FTBBackups.LOGGER.debug("Backup file created at: {}", archivePath);
        } catch (FileAlreadyExistsException e) {
            FTBBackups.LOGGER.error("Backup file already exists: {}", archiveFilePath, e);
            throw e;
        } catch (IOException e) {
            FTBBackups.LOGGER.error("I/O error when creating backup file: {}", archiveFilePath, e);
            throw e;
        } catch (SecurityException e) {
            FTBBackups.LOGGER.error("Security exception: write access denied for backup file: {}", archiveFilePath, e);
            throw e;
        }

        AtomicBoolean fileAdded = new AtomicBoolean(false);
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger failedFiles = new AtomicInteger(0);

        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(archiveFilePath), 8192);
                ZipOutputStream zipOut = format == Format.ZIP ? new ZipOutputStream(fileOut) : null;
                TarOutputStream tarOut = format == Format.ZSTD ? new TarOutputStream(new ZstdOutputStream(fileOut))
                        : null) {

            for (Path sourcePath : sourcePaths) {
                if (Files.isDirectory(sourcePath)) {
                    try (var pathStream = Files.walk(sourcePath)) {
                        pathStream.filter(p -> !Files.isDirectory(p)).forEach(path -> {
                            totalFiles.incrementAndGet();
                            if (processFileForArchiveCompression(format, zipOut, tarOut, serverRoot, path)) {
                                fileAdded.set(true);
                            } else {
                                failedFiles.incrementAndGet();
                            }
                        });
                    }
                } else {
                    totalFiles.incrementAndGet();
                    if (processFileForArchiveCompression(format, zipOut, tarOut, serverRoot, sourcePath)) {
                        fileAdded.set(true);
                    } else {
                        failedFiles.incrementAndGet();
                    }
                }
            }

            if (!fileAdded.get()) {
                FTBBackups.LOGGER.warn("No files were added to the backup archive: {}", archiveFilePath);
                throw new IOException("No files were compressed into the backup");
            }

            if (failedFiles.get() > 0) {
                FTBBackups.LOGGER.warn("{} out of {} files failed to compress", failedFiles.get(), totalFiles.get());
            } else {
                FTBBackups.LOGGER.info("Successfully compressed {} files into {}", totalFiles.get(), archiveFilePath);
            }
        }
    }

    /**
     * Processes a single file for inclusion in the compressed archive.
     *
     * @param format the archive format
     * @param zipOut the ZIP output stream (if ZIP format)
     * @param tarOut the TAR output stream (if ZSTD format)
     * @param serverRoot the server root for relative paths
     * @param file the file to process
     * @return true if the file was successfully processed and added, false otherwise
     */
    private static boolean processFileForArchiveCompression(Format format, ZipOutputStream zipOut, TarOutputStream tarOut, Path serverRoot, Path file) {
        if (shouldExcludeFileFromBackup(file)) {
            FTBBackups.LOGGER.debug("Skipping file during compression: {}", file);
            return false;
        }
        try {
            Path relFile = serverRoot.relativize(file);
            if (doesFilterExcludePath(relFile, Config.getConfigData().excluded_paths)) {
                FTBBackups.LOGGER.debug("Skipping excluded file: {}", relFile);
                return false;
            }
            streamFileIntoArchive(format, zipOut, tarOut, serverRoot, file);
            return true;
        } catch (java.nio.file.NoSuchFileException e) {
            FTBBackups.LOGGER.debug("File disappeared during compression, skipping: {}", file);
            return false;
        } catch (IOException e) {
            FTBBackups.LOGGER.warn("Error compressing file {}: {}", file, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Streams a file directly into the archive's output stream.
     *
     * @param format the archive format
     * @param zipOut the ZIP output stream (if ZIP format)
     * @param tarOut the TAR output stream (if ZSTD format)
     * @param serverRoot the server root for relative paths
     * @param file the file to stream
     * @throws IOException if an I/O error occurs
     */
    private static void streamFileIntoArchive(Format format, ZipOutputStream zipOut, TarOutputStream tarOut, Path serverRoot, Path file) throws IOException {
        Path relFile = serverRoot.relativize(file);
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);

        if (format == Format.ZIP) {
            ZipEntry zipEntry = new ZipEntry(relFile.toString());
            zipEntry.setLastModifiedTime(attrs.lastModifiedTime());
            zipEntry.setCreationTime(attrs.creationTime());
            zipOut.putNextEntry(zipEntry);
            try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(file), 8192)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    zipOut.write(buffer, 0, bytesRead);
                }
            }
            zipOut.closeEntry();
        } else if (format == Format.ZSTD) {
            TarEntry tarEntry = new TarEntry(file.toFile(), relFile.toString());
            tarEntry.setModTime(attrs.lastModifiedTime().toMillis());
            tarOut.putNextEntry(tarEntry);
            try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(file), 8192)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    tarOut.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    /**
     * Determines if a file should be excluded from backup operations.
     *
     * @param file the file to evaluate
     * @return true if the file should be excluded, false otherwise
     */
    private static boolean shouldExcludeFileFromBackup(Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.equals("session.lock")) {
            return true;
        }
        if (!Files.exists(file) || !Files.isReadable(file)) {
            return true;
        }
        return false;
    }

    /**
     * Checks if a path is excluded by any of the provided filters.
     *
     * @param relPath the relative path to evaluate
     * @param filters the list of exclusion filters
     * @return true if the path is excluded by any filter, false otherwise
     */
    public static boolean doesFilterExcludePath(Path relPath, List<String> filters) {
        for (String filter : filters) {
            filter = filter.replaceAll("\\\\", "/");
            boolean directory = filter.endsWith("/");
            boolean sw = filter.startsWith("*");
            if (sw) filter = filter.substring(1);
            boolean ew = filter.endsWith("*") || directory;
            if (ew) filter = filter.substring(0, filter.length() - 1);
            boolean wildCard = sw || ew;
            boolean path = filter.contains("/");
            if (filter.startsWith("/") && !sw) filter = filter.substring(1);

            if (!path && !wildCard) {
                if (relPath.getFileName().toString().equals(filter)) return true;
            } else if (path && !wildCard) {
                if (relPath.toString().equals(filter)) return true;
            } else if (sw && ew) {
                if (relPath.toString().contains(filter)) return true;
            } else if (sw) {
                if (relPath.toString().endsWith(filter)) return true;
            } else {
                if (relPath.toString().startsWith(filter)) return true;
            }
        }
        return false;
    }

    /**
     * Generates the SHA1 hash of a file.
     *
     * @param path the path to the file
     * @return the SHA1 hash as a string, or an empty string if an error occurs
     */
    public static String generateFileSha1(Path path) {
        try {
            HashCode sha1HashCode = com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha1());
            return sha1HashCode.toString();
        } catch (IOException e) {
            FTBBackups.LOGGER.error("Error generating SHA1 for file {}: {}", path, e.getMessage(), e);
            return "";
        }
    }

    /**
     * Generates the SHA1 hash of a directory by hashing all files within it.
     *
     * @param directory the path to the directory
     * @return the combined SHA1 hash as a string, or an empty string if an error occurs
     */
    public static String generateDirectorySha1(Path directory) {
        try {
            Hasher hasher = Hashing.sha1().newHasher();
            try (var pathStream = Files.walk(directory)) {
                pathStream.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        HashCode hash = com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha1());
                        hasher.putBytes(hash.asBytes());
                    } catch (IOException e) {
                        FTBBackups.LOGGER.warn("Error hashing file: {}", path, e);
                    }
                });
            }
            return hasher.hash().toString();
        } catch (IOException e) {
            FTBBackups.LOGGER.error("Error walking directory for SHA1: {}", directory, e);
            return "";
        }
    }

    /**
     * Gets the total size of a folder, including all files within it.
     *
     * @param folder the path to the folder
     * @return the total size in bytes
     */
    public static long getFolderSize(Path folder) {
        if (!Files.exists(folder)) {
            return 0L;
        }
        if (!Files.isDirectory(folder)) {
            try {
                return Files.size(folder);
            } catch (IOException e) {
                FTBBackups.LOGGER.warn("Error getting size of file: {}", folder, e);
                return 0L;
            }
        }
        try (var walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            FTBBackups.LOGGER.warn("Error getting size of file: {}", path, e);
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            FTBBackups.LOGGER.warn("Error walking folder: {}", folder, e);
            return 0L;
        }
    }

    /**
     * Converts the size of a path to a human-readable string.
     *
     * @param path the path to measure
     * @return the size as a string (e.g., "1.5GB")
     */
    public static String convertSizeToReadableString(Path path) {
        long size = getFolderSize(path);
        return convertSizeToReadableString((double) size);
    }

    /**
     * Converts the size of a file to a human-readable string.
     *
     * @param file the file to measure
     * @return the size as a string (e.g., "1.5GB")
     */
    public static String convertSizeToReadableString(File file) {
        long size = getFileOrFolderSize(file);
        return convertSizeToReadableString((double) size);
    }

    /**
     * Converts a size in bytes to a human-readable string.
     *
     * @param b the size in bytes
     * @return the formatted size string (e.g., "1.5GB")
     */
    public static String convertSizeToReadableString(double b) {
        if (b >= TB_D) {
            return String.format("%.1fTB", b / TB_D);
        } else if (b >= GB_D) {
            return String.format("%.1fGB", b / GB_D);
        } else if (b >= MB_D) {
            return String.format("%.1fMB", b / MB_D);
        } else if (b >= KB_D) {
            return String.format("%.1fKB", b / KB_D);
        }
        return ((long) b) + "B";
    }

    /**
     * Gets the size of a file or directory using a stream-based approach.
     *
     * @param file the file or directory to measure
     * @return the size in bytes
     */
    public static long getFileOrFolderSize(File file) {
        if (!file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        try (var walk = Files.walk(file.toPath())) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            FTBBackups.LOGGER.warn("Error getting size of file: {}", path, e);
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            FTBBackups.LOGGER.warn("Error walking directory: {}", file, e);
            return 0L;
        }
    }

    /**
     * Checks if a path is a sub-path of another path.
     *
     * @param path the path to check
     * @param parent the parent path
     * @return true if the path is a sub-path of the parent, false otherwise
     */
    public static boolean isSubPathOf(Path path, Path parent) {
        if (path == null) {
            return false;
        }
        if (path.equals(parent)) {
            return true;
        }
        return isSubPathOf(path.getParent(), parent);
    }
}