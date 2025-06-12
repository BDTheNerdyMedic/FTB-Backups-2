package net.creeperhost.ftbbackups.utils;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import io.airlift.compress.zstd.ZstdOutputStream;
import net.creeperhost.ftbbackups.BackupHandler;
import net.creeperhost.ftbbackups.config.Config;
import net.creeperhost.ftbbackups.config.ConfigData.Format;

import org.apache.logging.log4j.Logger;
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
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utility class for file operations, including copying, compressing, and hashing files and directories.
 * Optimized for handling large directories, such as Minecraft worlds, with efficient memory usage and error handling.
 */
public class FileUtils {

    // Constants for size calculations (in bytes)
    public static final long KB = 1024L;
    public static final long MB = KB * 1024L;
    public static final long GB = MB * 1024L;
    public static final long TB = GB * 1024L;

    // Constants for size conversions (as doubles for precision)
    public static final double KB_D = 1024D;
    public static final double MB_D = KB_D * 1024D;
    public static final double GB_D = MB_D * 1024D;
    public static final double TB_D = GB_D * 1024D;

    /**
     * Copies files from the specified source paths to the output directory, creating the directory if it does not exist.
     * Directories are copied recursively, while individual files are copied directly. Skips excluded files based on configuration.
     *
     * @param outputDirectory the directory to copy files to
     * @param serverRoot the root directory of the server, used for calculating relative paths
     * @param sourcePaths the paths to copy from (files or directories)
     * @throws IOException if an I/O error occurs during copying
     */
    public static void copySourcePathsToDirectory(Path outputDirectory, Path serverRoot, Iterable<Path> sourcePaths) throws IOException {
        Logger logger = BackupHandler.getCurrentLogger();
        logger.debug("Starting copy operation to directory: {}", outputDirectory);

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
     * Copies a single file to the destination directory, preserving its relative path from the server root.
     * Skips files that are excluded based on configuration or if they disappear during the operation.
     *
     * @param destDir the destination directory to copy the file into
     * @param serverRoot the server root directory for determining relative paths
     * @param file the file to copy
     */
    private static void copySingleFileToDirectory(Path destDir, Path serverRoot, Path file) {
        Logger logger = BackupHandler.getCurrentLogger();
        if (shouldExcludeFileFromBackup(file)) {
            logger.debug("Skipping file during copy: {}", file);
            return;
        }
        try {
            Path relFile = serverRoot.relativize(file);
            if (matchesAnyFilter(relFile, Config.getConfigData().excluded_paths)) {
                logger.debug("Skipping excluded file: {}", relFile);
                return;
            }
            Path destFile = destDir.resolve(relFile);
            Files.createDirectories(destFile.getParent());
            Files.copy(file, destFile);
        } catch (java.nio.file.NoSuchFileException e) {
            logger.debug("File disappeared during copy, skipping: {}", file);
        } catch (IOException e) {
            logger.warn("Error copying file {}: {}", file, e.getMessage(), e);
        }
    }

    /**
     * Compresses the specified source paths into an archive file in the given format (ZIP or ZSTD).
     * Creates the archive file and its parent directories, processes files recursively for directories,
     * and tracks the number of files processed and failures. Ensures at least one file is added to the archive.
     *
     * @param archiveFilePath the path where the archive file will be created
     * @param serverRoot the root directory of the server, used for relative paths
     * @param sourcePaths the paths to compress (files or directories)
     * @param format the archive format (ZIP or ZSTD)
     * @throws IOException if an I/O error occurs during compression or if no files are added
     */
    public static void compressSourcePathsToArchive(Path archiveFilePath, Path serverRoot, Iterable<Path> sourcePaths, Format format) throws IOException {
        Logger logger = BackupHandler.getCurrentLogger();
        logger.info("Starting compression to archive: {}", archiveFilePath);

        // Create the archive file and its parent directories
        try {
            Files.createDirectories(archiveFilePath.getParent());
            Path archivePath = Files.createFile(archiveFilePath);
            logger.debug("Backup file created at: {}", archivePath);
        } catch (FileAlreadyExistsException e) {
            logger.error("Backup file already exists: {}", archiveFilePath, e);
            throw e;
        } catch (IOException e) {
            logger.error("I/O error when creating backup file: {}", archiveFilePath, e);
            throw e;
        } catch (SecurityException e) {
            logger.error("Security exception: write access denied for backup file: {}", archiveFilePath, e);
            throw e;
        }

        // Track file processing statistics
        AtomicBoolean fileAdded = new AtomicBoolean(false);
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger failedFiles = new AtomicInteger(0);

        // Open output streams for compression
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(archiveFilePath), 8192);
                ZipOutputStream zipOut = format == Format.ZIP ? new ZipOutputStream(fileOut) : null;
                TarOutputStream tarOut = format == Format.ZSTD ? new TarOutputStream(new ZstdOutputStream(fileOut)) : null) {

            // Process each source path
            for (Path sourcePath : sourcePaths) {
                if (Files.isDirectory(sourcePath)) {
                    if (Config.getConfigData().verbose_logging) {
                        logger.debug("Starting to walk directory: {}", sourcePath);
                    }
                    try (var pathStream = Files.walk(sourcePath)) {
                        // Collect files into a list for better control
                        List<Path> files = pathStream.filter(p -> !Files.isDirectory(p)).collect(Collectors.toList());
                        if (Config.getConfigData().verbose_logging) {
                            logger.debug("Found {} files in directory: {}", files.size(), sourcePath);
                        }

                        // Process each file
                        for (Path path : files) {
                            totalFiles.incrementAndGet();
                            if (processFileForArchiveCompression(format, zipOut, tarOut, serverRoot, path)) {
                                fileAdded.set(true);
                            } else {
                                failedFiles.incrementAndGet();
                            }
                        }
                    } catch (IOException e) {
                        logger.error("Error walking directory: {}", sourcePath, e);
                    }
                    if (Config.getConfigData().verbose_logging) {
                        logger.debug("Finished walking directory: {}", sourcePath);
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

            // Check if any files were added
            if (!fileAdded.get()) {
                logger.warn("No files were added to the backup archive: {}", archiveFilePath);
                throw new IOException("No files were compressed into the backup");
            }

            // Log compression results
            if (failedFiles.get() > 0) {
                logger.warn("{} out of {} files failed to compress", failedFiles.get(), totalFiles.get());
            } else {
                logger.info("Successfully compressed {} files into {}", totalFiles.get(), archiveFilePath);
            }
        } catch (IOException e) {
            logger.error("Compression failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            // Verify the archive was created and is not empty
            if (!Files.exists(archiveFilePath) || Files.size(archiveFilePath) == 0) {
                logger.error("Backup archive was not created or is empty: {}", archiveFilePath);
                throw new IOException("Backup archive was not created or is empty");
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
        Logger logger = BackupHandler.getCurrentLogger();
        if (shouldExcludeFileFromBackup(file)) {
            logger.debug("Skipping file during compression: {}", file);
            return false;
        }
        try {
            Path relFile = serverRoot.relativize(file);
            if (matchesAnyFilter(relFile, Config.getConfigData().excluded_paths)) {
                logger.debug("Skipping excluded file: {}", relFile);
                return false;
            }
            streamFileIntoArchive(format, zipOut, tarOut, serverRoot, file);
            return true;
        } catch (java.nio.file.NoSuchFileException e) {
            logger.debug("File disappeared during compression, skipping: {}", file);
            return false;
        } catch (IOException e) {
            logger.warn("Error compressing file {}: {}", file, e.getMessage(), e);
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
        Logger logger = BackupHandler.getCurrentLogger();

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
                try {
                    while ((bytesRead = in.read(buffer)) != -1) {
                        tarOut.write(buffer, 0, bytesRead);
                    }
                    tarOut.flush();
                } catch (IOException e) {
                    logger.error("ZSTD compression error for file {}: {}", file, e.getMessage());
                    throw e;
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
    * Checks if the given relative path matches any of the provided filters.
    * Filters can include wildcards ('*' at start/end) or directory indicators ('/').
    * Examples: 'logs/*.log' (matches all .log files in logs), 'config/' (matches all in config directory).
    *
    * @param relPath the relative path to evaluate
    * @param filters the list of filter patterns to check against
    * @return true if the path matches any filter, false otherwise
    */
    public static boolean matchesAnyFilter(Path relPath, List<String> filters) {
        for (String filter : filters) {
            String normalizedFilter = filter.replaceAll("\\\\", "/");
            boolean isDirectory = normalizedFilter.endsWith("/"); // Indicates directory match
            boolean startsWithWildcard = normalizedFilter.startsWith("*");
            if (startsWithWildcard)
                normalizedFilter = normalizedFilter.substring(1);
            boolean endsWithWildcard = normalizedFilter.endsWith("*") || isDirectory;
            if (endsWithWildcard)
                normalizedFilter = normalizedFilter.substring(0, normalizedFilter.length() - 1);
            boolean hasWildcard = startsWithWildcard || endsWithWildcard;
            boolean hasPath = normalizedFilter.contains("/"); // Indicates multi-level path
            if (normalizedFilter.startsWith("/") && !startsWithWildcard)
                normalizedFilter = normalizedFilter.substring(1);

            String pathString = relPath.toString();
            if (!hasPath && !hasWildcard) { // Exact filename match
                if (relPath.getFileName().toString().equals(normalizedFilter))
                    return true;
            } else if (hasPath && !hasWildcard) { // Exact path match
                if (pathString.equals(normalizedFilter))
                    return true;
            } else if (startsWithWildcard && endsWithWildcard) { // Contains match
                if (pathString.contains(normalizedFilter))
                    return true;
            } else if (startsWithWildcard) { // Ends with match
                if (pathString.endsWith(normalizedFilter))
                    return true;
            } else { // Starts with match
                if (pathString.startsWith(normalizedFilter))
                    return true;
            }
        }
        return false;
    }

    /**
     * Generates the SHA1 hash of a file using Guava's hashing utilities.
     *
     * @param path the path to the file
     * @return the SHA1 hash as a hexadecimal string, or an empty string if an error occurs
     */
    public static String generateFileSha1(Path path) {
        Logger logger = BackupHandler.getCurrentLogger();
        try {
            HashCode sha1HashCode = com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha1());
            return sha1HashCode.toString();
        } catch (IOException e) {
            logger.error("Error generating SHA1 for file {}: {}", path, e.getMessage(), e);
            return "";
        }
    }

    /**
     * Generates the SHA1 hash of a directory by combining the hashes of all files within it.
     *
     * @param directory the path to the directory
     * @return the combined SHA1 hash as a hexadecimal string, or an empty string if an error occurs
     */
    public static String generateDirectorySha1(Path directory) {
        Logger logger = BackupHandler.getCurrentLogger();
        try {
            Hasher hasher = Hashing.sha1().newHasher();
            try (var pathStream = Files.walk(directory)) {
                pathStream.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        HashCode hash = com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha1());
                        hasher.putBytes(hash.asBytes());
                    } catch (IOException e) {
                        logger.warn("Error hashing file {}: {}", path, e.getMessage(), e);
                    }
                });
            }
            return hasher.hash().toString();
        } catch (IOException e) {
            logger.error("Error walking directory for SHA1 {}: {}", directory, e.getMessage(), e);
            return "";
        }
    }

    /**
     * Calculates the total size of a folder, including all files within it recursively.
     * Returns 0 if the path does not exist or an error occurs during traversal.
     *
     * @param folder the path to the folder or file
     * @return the total size in bytes
     */
    public static long getFolderSize(Path folder) {
        Logger logger = BackupHandler.getCurrentLogger();
        if (Config.getConfigData().verbose_logging) {
            logger.debug("Calculating size of: {}", folder);
        }
        if (!Files.exists(folder)) {
            return 0L;
        }
        if (!Files.isDirectory(folder)) {
            try {
                return Files.size(folder);
            } catch (IOException e) {
                logger.warn("Error getting size of file: {}", folder, e);
                return 0L;
            }
        }
        try (var walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            logger.warn("Error getting size of file: {}", path, e);
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            logger.warn("Error walking folder: {}", folder, e);
            return 0L;
        }
    }

    /**
     * Converts a size in bytes to a human-readable string (e.g., "1.5GB").
     *
     * @param b the size in bytes
     * @return the formatted size string (e.g., "1.5GB", "500MB")
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
     * Converts the size of a path to a human-readable string.
     *
     * @param path the path to measure
     * @return the size as a string (e.g., "1.5GB")
     */
    public static String convertSizeToReadableString(Path path) {
        return convertSizeToReadableString((double) getFolderSize(path));
    }

    /**
     * Converts the size of a file to a human-readable string.
     *
     * @param file the file to measure
     * @return the size as a string (e.g., "1.5GB")
     */
    public static String convertSizeToReadableString(File file) {
        return convertSizeToReadableString((double) getFileOrFolderSize(file));
    }

    /**
     * Gets the size of a file or directory using a stream-based approach.
     *
     * @param file the file or directory to measure
     * @return the size in bytes
     */
    public static long getFileOrFolderSize(File file) {
        Logger logger = BackupHandler.getCurrentLogger();
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
                            logger.warn("Error getting size of file: {}", path, e);
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            logger.warn("Error walking directory: {}", file, e);
            return 0L;
        }
    }

    /**
     * Checks if a path is a sub-path of another path using recursive parent traversal.
     * Note: For extremely deep directory structures, this could theoretically cause a stack overflow,
     * but such cases are rare in typical Minecraft server setups.
     *
     * @param path the path to check
     * @param parent the parent path to compare against
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