package net.creeperhost.ftbbackups.utils;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import io.airlift.compress.zstd.ZstdOutputStream;
import net.creeperhost.ftbbackups.FTBBackups;
import net.creeperhost.ftbbackups.config.Config;
import net.creeperhost.ftbbackups.config.Format;
import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileUtils {

    public static final long KB = 1024L;
    public static final long MB = KB * 1024L;
    public static final long GB = MB * 1024L;
    public static final long TB = GB * 1024L;

    public static final double KB_D = 1024D;
    public static final double MB_D = KB_D * 1024D;
    public static final double GB_D = MB_D * 1024D;
    public static final double TB_D = GB_D * 1024D;

    public static void copy(Path outputDirectory, Path serverRoot, Iterable<Path> sourcePaths) throws IOException {
        Path destDir = Files.createDirectory(outputDirectory);
        for (Path sourcePath : sourcePaths) {
            if (Files.isDirectory(sourcePath)) {
                try (Stream<Path> pathStream = Files.walk(sourcePath)) {
                    pathStream.filter(path -> !Files.isDirectory(path))
                            .forEach(path -> copyFile(destDir, serverRoot, path));
                }
            } else {
                copyFile(destDir, serverRoot, sourcePath);
            }
        }
    }

    private static void copyFile(Path destDir, Path serverRoot, Path file) {
        if (shouldSkipFile(file)) {
            FTBBackups.LOGGER.debug("Skipping file during copy: {}", file);
            return;
        }
        try {
            Path relFile = serverRoot.relativize(file);
            if (matchesAny(relFile, Config.cached().excluded)) {
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

    public static void compress(Path zipFilePath, Path serverRoot, Iterable<Path> sourcePaths, Format format)
            throws IOException {
        Path archivePath = Files.createFile(zipFilePath);
        try (OutputStream fileOut = Files.newOutputStream(archivePath);
                ZipOutputStream zipOut = format == Format.ZIP ? new ZipOutputStream(fileOut) : null;
                TarOutputStream tarOut = format == Format.ZSTD ? new TarOutputStream(new ZstdOutputStream(fileOut)) : null) {
            for (Path sourcePath : sourcePaths) {
                if (Files.isDirectory(sourcePath)) {
                    try (Stream<Path> pathStream = Files.walk(sourcePath)) {
                        pathStream.filter(path -> !Files.isDirectory(path))
                                .forEach(path -> compressFile(format, zipOut, tarOut, serverRoot, path));
                    }
                } else {
                    compressFile(format, zipOut, tarOut, serverRoot, sourcePath);
                }
            }
        }
    }

    private static void compressFile(Format format, ZipOutputStream zipOut, TarOutputStream tarOut, Path serverRoot, Path file) {
        if (shouldSkipFile(file)) {
            FTBBackups.LOGGER.debug("Skipping file during compression: {}", file);
            return;
        }
        try {
            Path relFile = serverRoot.relativize(file);
            if (matchesAny(relFile, Config.cached().excluded)) {
                FTBBackups.LOGGER.debug("Skipping excluded file: {}", relFile);
                return;
            }
            if (format == Format.ZIP) {
                packIntoZip(zipOut, serverRoot, file);
            } else if (format == Format.ZSTD) {
                packIntoTar(tarOut, serverRoot, file);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            FTBBackups.LOGGER.debug("File disappeared during compression, skipping: {}", file);
        } catch (IOException e) {
            handleCompressionError(e, file);
        }
    }

    private static boolean shouldSkipFile(Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.equals("session.lock")) {
            return true;
        }
        if (!Files.exists(file) || !Files.isReadable(file)) {
            return true;
        }
        return false;
    }

    private static void handleCompressionError(IOException e, Path file) {
        String errorMessage = e.getMessage();
        if (errorMessage != null && errorMessage.contains("duplicate entry")) {
            // FTBBackups.LOGGER.debug("Skipped duplicate file during compression: {}", file);
        } else {
            FTBBackups.LOGGER.warn("Error compressing file {}: {}", file, errorMessage, e);
        }
    }

    private static void packIntoZip(ZipOutputStream zos, Path rootDir, Path file) throws IOException {
        ZipEntry zipEntry = new ZipEntry(rootDir.relativize(file).toString());
        zos.putNextEntry(zipEntry);
        updateZipEntry(zipEntry, file);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    public static void updateZipEntry(ZipEntry zipEntry, Path path) {
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(path, BasicFileAttributes.class);
            zipEntry.setLastModifiedTime(basicFileAttributes.lastModifiedTime());
            zipEntry.setCreationTime(basicFileAttributes.creationTime());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void packIntoTar(TarOutputStream taos, Path rootDir, Path file) throws IOException {
        TarEntry tarEntry = new TarEntry(file.toFile(), rootDir.relativize(file).toString());
        taos.putNextEntry(tarEntry);
        updateTarEntry(tarEntry, file);
        try {
            Files.copy(file, taos);
        } catch (Exception e) {
            FTBBackups.LOGGER.debug("Error copying file to TAR, skipping: {}", file);
        }
    }

    public static void updateTarEntry(TarEntry tarEntry, Path path) {
        try {
            BasicFileAttributes basicFileAttributes = Files.readAttributes(path, BasicFileAttributes.class);
            tarEntry.setModTime(basicFileAttributes.lastModifiedTime().toMillis());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isChildOf(Path path, Path parent) {
        if (path == null) {
            return false;
        }
        if (path.equals(parent)) {
            return true;
        }
        return isChildOf(path.getParent(), parent);
    }

    public static String getFileSha1(Path path) {
        try {
            HashCode sha1HashCode = com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha1());
            return sha1HashCode.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getDirectorySha1(Path directory) {
        try {
            Hasher hasher = Hashing.sha1().newHasher();
            try (Stream<Path> pathStream = Files.walk(directory)) {
                List<Path> paths = pathStream.toList();
                for (Path path : paths) {
                    if (Files.isDirectory(path)) {
                        continue;
                    }
                    HashCode hash = com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha1());
                    hasher.putBytes(hash.asBytes());
                }
            }
            return hasher.hash().toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

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
        try (Stream<Path> walk = Files.walk(folder)) {
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

    public static String getSizeString(Path path) {
        long size = getSize(path.toFile());
        return getSizeString((double) size);
    }

    public static String getSizeString(File file) {
        long size = getSize(file);
        return getSizeString((double) size);
    }

    public static String getSizeString(double b) {
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

    public static long getSize(File file) {
        if (!file.exists()) {
            return 0L;
        } else if (file.isFile()) {
            return file.length();
        } else if (file.isDirectory()) {
            long length = 0L;
            File[] f1 = file.listFiles();
            if (f1 != null && f1.length > 0) {
                for (File aF1 : f1) {
                    length += getSize(aF1);
                }
            }
            return length;
        }
        return 0L;
    }

    public static boolean matchesAny(Path relPath, List<String> filters) {
        for (String filter : filters) {
            filter = filter.replaceAll("\\\\", "/");

            boolean directory = filter.endsWith("/");

            boolean sw = filter.startsWith("*");
            if (sw) {
                filter = filter.substring(1);
            }

            boolean ew = filter.endsWith("*") || directory;
            if (ew) {
                filter = filter.substring(0, filter.length() - 1);
            }

            boolean wildCard = sw || ew;

            boolean path = filter.contains("/");
            if (filter.startsWith("/") && !sw) {
                filter = filter.substring(1);
            }

            if (!path && !wildCard) {
                if (relPath.getFileName().toString().equals(filter)) {
                    return true;
                }
            } else if (path && !wildCard) {
                if (relPath.toString().equals(filter)) {
                    return true;
                }
            } else if (sw && ew) {
                if (relPath.toString().contains(filter)) {
                    return true;
                }
            } else if (sw) {
                if (relPath.toString().endsWith(filter)) {
                    return true;
                }
            } else {
                if (relPath.toString().startsWith(filter)) {
                    return true;
                }
            }
        }
        return false;
    }
}