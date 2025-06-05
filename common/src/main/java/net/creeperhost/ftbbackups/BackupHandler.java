package net.creeperhost.ftbbackups;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.creeperhost.ftbbackups.config.Config;
import net.creeperhost.ftbbackups.config.ConfigData;
import net.creeperhost.ftbbackups.config.ConfigData.Format;
import net.creeperhost.ftbbackups.data.Backup;
import net.creeperhost.ftbbackups.data.Backups;
import net.creeperhost.ftbbackups.utils.FileUtils;
import net.creeperhost.ftbbackups.utils.TieredBackupTest;
import net.creeperhost.levelio.LevelIO;
import net.creeperhost.levelio.data.Level;
import net.creeperhost.levelpreview.ActivityScanner;
import net.creeperhost.levelpreview.CaptureHandler;
import net.creeperhost.levelpreview.ColourMap;
import net.creeperhost.levelpreview.LevelPreview;
import net.creeperhost.levelpreview.lib.CaptureArea;
import net.creeperhost.levelpreview.lib.SimplePNG;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles backup creation, management, and cleanup for the FTB Backups mod.
 */
public class BackupHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final LevelPreview PREVIEW = new LevelPreview(new MCNBTImpl());
    public static String lastPreview = "";
    public static String currentBackupId;

    private static Path serverRoot;
    private static Path backupFolderPath;
    private static Path worldFolder;
    public static final AtomicBoolean backupRunning = new AtomicBoolean(false);
    private static final AtomicBoolean backupFailed = new AtomicBoolean(false);
    private static AtomicReference<String> backupPreview = new AtomicReference<>("");
    private static boolean isSpaceConstrained = false;

    public static AtomicReference<Backups> backups = new AtomicReference<>(new Backups());
    private static final Object BACKUP_LOCK = new Object();

    private static long lastAutoBackup = 0;

    public static CompletableFuture<Void> currentFuture;
    public static Path defaultBackupLocation;
    private static long expectedSize = 0;

    private static Logger backupLogger = null;

    /**
     * Initializes the backup handler with the Minecraft server instance.
     *
     * @param minecraftServer The Minecraft server instance.
     */
    public static void init(MinecraftServer minecraftServer) {
        FTBBackups.LOGGER.info("[BackupHandler] Initializing backup system...");
        serverRoot = minecraftServer.getServerDirectory().normalize().toAbsolutePath();
        defaultBackupLocation = serverRoot.resolve("backups");

        FTBBackups.LOGGER.debug("[BackupHandler] Setting server root to: {}", serverRoot);
        FTBBackups.LOGGER.debug("Default backup location set to: {}", defaultBackupLocation);

        if (!Config.getConfigData().backup_location.equalsIgnoreCase(".")) {
            try {
                Path configPath = Path.of(Config.getConfigData().backup_location);
                if (Files.exists(configPath)) {
                    FTBBackups.LOGGER.info("Using configured backups directory at {}", configPath.toAbsolutePath());
                    backupFolderPath = configPath;
                } else {
                    FTBBackups.LOGGER.error("[BackupHandler] Backup directory {} not found, falling back to default: {}", configPath.toAbsolutePath(), defaultBackupLocation);
                    backupFolderPath = defaultBackupLocation;
                }
            } catch (Exception e) {
                FTBBackups.LOGGER.error("Error accessing backup directory from config: {}",
                        Config.getConfigData().backup_location, e);
                backupFolderPath = defaultBackupLocation;
            }
        } else {
            FTBBackups.LOGGER.info("No custom backup location specified, using default: {}", defaultBackupLocation);
            backupFolderPath = defaultBackupLocation;
        }

        createBackupFolder(backupFolderPath);
        loadJson();
        lastPreview = backups.get().getLastPreview();
        initPreview();
        FTBBackups.LOGGER.debug("BackupHandler initialized successfully.");
    }

    /**
     * Initializes the preview system for generating world previews.
     */
    private static void initPreview() {
        FTBBackups.LOGGER.debug("Initializing preview...");
        ColourMap colourMap = PREVIEW.getColourMap();
        CaptureHandler.init(1);
        int blockCount = 0;
        for (ResourceLocation regName : BuiltInRegistries.BLOCK.keySet()) {
            Block block = BuiltInRegistries.BLOCK.get(regName);
            var mapColor = block.defaultMapColor();
            if (mapColor == null)
                continue;
            int colour = mapColor.col;
            colourMap.addBlockMapping(regName.toString(), colour);
            blockCount++;
        }
        FTBBackups.LOGGER.debug("Preview initialized with {} block mappings.", blockCount);
    }

    /**
     * Generates a preview image for the backup if enabled in the configuration.
     * Returns an empty string if 'enable_preview' is false or generation fails.
     *
     * @param minecraftServer The Minecraft server instance.
     * @return A base64-encoded string of the preview image or an empty string if disabled or failed.
     */
    public static String createPreview(MinecraftServer minecraftServer) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        if (!Config.getConfigData().enable_preview) {
            logger.info("Backup preview disabled in configuration.");
            backups.get().setLastPreview("");
            return "";
        }

        String currentWorldHash = calculateWorldHash(minecraftServer);
        String storedWorldHash = backups.get().getWorldHash();

        if (currentWorldHash.equals(storedWorldHash) && !lastPreview.isEmpty()) {
            logger.info("World state unchanged, reusing cached preview.");
            return lastPreview;
        }

        logger.info("Starting backup preview generation...");
        long startTime = System.currentTimeMillis();
        try {
            Path worldPath = minecraftServer.getWorldPath(LevelResource.ROOT).toAbsolutePath();
            logger.debug("Loading world from path: {}", worldPath);
            PREVIEW.loadWorld(worldPath);
            LevelIO levelIO = PREVIEW.getLevelIO();
            logger.debug("LevelIO initialized.");

            // Get dimensions to scan
            List<String> dimensionsToScan = getDimensionsToScan();
            logger.debug("Dimensions to scan: {}", dimensionsToScan);

            // Scan each dimension for activity clusters
            List<ActivityScanner> scanners = new ArrayList<>();
            for (String dim : dimensionsToScan) {
                Level level = levelIO.getLevel(dim);
                if (level != null) {
                    ActivityScanner scanner = new ActivityScanner(levelIO, level, 1);
                    if (scanner.findActivityClusters(512, 512, 1)) {
                        scanners.add(scanner);
                    }
                } else {
                    logger.warn("Dimension {} not found, skipping.", dim);
                }
            }

            if (scanners.isEmpty()) {
                logger.warn("No activity clusters found for preview.");
                return "";
            }

            // Select the dimension with the highest habitation factor
            scanners.sort(Comparator.comparingDouble(ActivityScanner::getTotalHabitationFactor).reversed());
            ActivityScanner scanner = scanners.get(0);
            CaptureArea area = scanner.getResults().get(0);
            logger.debug("Selected highest habitation factor cluster.");

            long captureStart = System.currentTimeMillis();
            SimplePNG.SimpleImg capture = PREVIEW.newCapture()
                    .captureArea(area)
                    .doCapture()
                    .getImage();
            logger.debug("Capture completed.");

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            SimplePNG.writePNG(os, capture);
            byte[] image = os.toByteArray();

            String newPreview = "data:image/png;base64, " + Base64.getEncoder().encodeToString(image);
            logger.info("Backup preview created. Scan took {}ms, Capture took {}ms",
                    captureStart - startTime, System.currentTimeMillis() - captureStart);

            lastPreview = newPreview;
            backups.get().setWorldHash(currentWorldHash);
            backups.get().setLastPreview(newPreview);
            return newPreview;
        } catch (Exception ex) {
            logger.error("Error generating backup preview", ex);
            return "";
        } finally {
            try {
                PREVIEW.close();
                logger.debug("LevelPreview closed successfully.");
            } catch (Exception e) {
                logger.error("Error closing LevelPreview", e);
            }
        }
    }

    /**
     * Calculates a hash representing the state of region files for specified dimensions.
     *
     * @param minecraftServer The server instance to access world data.
     * @return A hexadecimal string of the SHA-256 hash, or an empty string if an error occurs.
     */
    private static String calculateWorldHash(MinecraftServer minecraftServer) {
        // Get dimensions to scan
        List<String> dimensionsToScan = getDimensionsToScan();
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Dimensions to hash: {}", dimensionsToScan);

        // Parse dimensions into ResourceLocation and sort for consistency
        List<ResourceLocation> dimensionsToHash = new ArrayList<>();
        for (String dim : dimensionsToScan) {
            ResourceLocation loc = parseDimensionString(dim);
            if (loc != null) {
                dimensionsToHash.add(loc);
            } else {
                logger.warn("Invalid dimension name: {}, skipping", dim);
            }
        }
        dimensionsToHash.sort(Comparator.comparing(ResourceLocation::toString));
        logger.debug("Sorted dimensions to hash: {}", dimensionsToHash);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ResourceLocation dim : dimensionsToHash) {
                Path regionDir = getRegionDirPath(minecraftServer, dim);
                if (Files.exists(regionDir)) {
                    try (Stream<Path> walk = Files.walk(regionDir, 1)) {
                        List<Path> regionFiles = walk
                                .filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().matches("r\\.\\d+\\.\\d+\\.mca"))
                                .sorted() // Ensure consistent file order
                                .collect(Collectors.toList());
                        for (Path file : regionFiles) {
                            long size = Files.size(file);
                            long lastModified = Files.getLastModifiedTime(file).toMillis();
                            String fileInfo = dim.toString() + ":" + file.getFileName().toString() + ":" + size + ":" + lastModified;
                            digest.update(fileInfo.getBytes());
                        }
                    }
                }
            }
            byte[] hashBytes = digest.digest();
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException | IOException e) {
            logger.warn("Error calculating world hash", e);
            return "";
        }
    }

    /**
    * Retrieves the list of dimensions to scan from the configuration.
    * If the list is empty, defaults to ["minecraft:overworld"] and logs a warning.
    *
    * @return A list of dimension strings to scan.
    */
    private static List<String> getDimensionsToScan() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        List<String> dimensions = Config.getConfigData().preview_dimensions_list;
        if (dimensions.isEmpty()) {
            logger.warn("preview_dimensions_list is empty, defaulting to minecraft:overworld");
            return Arrays.asList("minecraft:overworld");
        }
        return dimensions;
    }

    /**
     * Parses a dimension string into a ResourceLocation.
     * 
     * @param dimString The dimension string, e.g., "minecraft:overworld".
     * @return A ResourceLocation object, or null if the string is invalid.
     */
    private static ResourceLocation parseDimensionString(String dimString) {
        String[] parts = dimString.split(":");
        if (parts.length == 2) {
            return ResourceLocation.fromNamespaceAndPath(parts[0], parts[1]);
        }
        return null;
    }

    /**
     * Gets the region directory path for a given dimension.
     * 
     * @param server            The server instance.
     * @param dimensionLocation The dimension's ResourceLocation.
     * @return The Path to the region directory.
     */
    private static Path getRegionDirPath(MinecraftServer server, ResourceLocation dimensionLocation) {
        Path worldPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath();
        if (dimensionLocation.getNamespace().equals("minecraft") && dimensionLocation.getPath().equals("overworld")) {
            return worldPath.resolve("region");
        } else {
            String dimensionPath = "dimensions/" + dimensionLocation.getNamespace() + "/" + dimensionLocation.getPath();
            return worldPath.resolve(dimensionPath).resolve("region");
        }
    }

    /**
     * Converts a byte array to a hexadecimal string.
     * 
     * @param bytes The byte array to convert.
     * @return The hexadecimal representation.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Checks if a backup is currently in progress.
     *
     * @return True if a backup is running, false otherwise.
     */
    public static boolean isRunning() {
        return backupRunning.get();
    }

    /**
     * Starts a backup process with default parameters.
     *
     * @param minecraftServer The Minecraft server instance.
     */
    public static void createBackup(MinecraftServer minecraftServer) {
        createBackup(minecraftServer, false, "automated");
    }

    /**
     * Starts a backup process with custom parameters.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param protect         Whether to protect the backup from deletion.
     * @param name            The name of the backup.
     */
    public static void createBackup(MinecraftServer minecraftServer, boolean protect, String name) {
        currentBackupId = "Backup-" + System.currentTimeMillis();
        backupLogger = LogManager.getLogger("FTBBackups." + currentBackupId);

        backupLogger.info("Backup initiated. Type: {}", protect ? "Manual" : name.equals("automated") ? "Cron" : "Command-line");
        worldFolder = minecraftServer.getWorldPath(LevelResource.ROOT).toAbsolutePath();

        // Step 1: Check if backup should be skipped due to non-resource conditions
        BackupFeasibility skipFeasibility = shouldSkipBackup();
        if (!skipFeasibility.canCreate) {
            String fullFailMessage = "Backup skipped, Reason: " + skipFeasibility.failMessage;
            alertPlayers(minecraftServer, Component.translatable(fullFailMessage));
            logFeasibilityMessage(skipFeasibility, fullFailMessage);
            backupRunning.set(false);
            return;
        }

        // Step 2: Check if backup can be created based on resource availability
        BackupFeasibility createFeasibility = canCreateBackup();
        if (!createFeasibility.canCreate) {
            String fullFailMessage = "Unable to create backup, Reason: " + createFeasibility.failMessage;
            alertPlayers(minecraftServer, Component.translatable(fullFailMessage));
            logFeasibilityMessage(createFeasibility, fullFailMessage);
            return;
        }

        // Step 3: Proceed with backup setup and execution
        BackupSetup setup = setupBackupEnvironment(minecraftServer, name);
        if (setup == null || setup.backupLocation == null) {
            backupLogger.error("[{}] Failed to set up backup environment", currentBackupId);
            return;
        }

        Format format = Config.getConfigData().backup_format;
        // Create a new Backup object with the necessary details
        Backup backup = new Backup(
                worldFolder.normalize().getFileName().toString(),
                lastAutoBackup,
                setup.backupLocation.toString(),
                0, 1, "",
                backupPreview.get(),
                protect,
                name,
                format,
                false);

        synchronized (BACKUP_LOCK) {
            addBackup(backup);
            updateJson();
        }

        AtomicLong startTime = new AtomicLong(System.nanoTime());

        // Chain the backup operation to the save future
        currentFuture = setup.saveFuture.thenRunAsync(() -> {
            performBackup(minecraftServer, setup.backupLocation, format, backup);
        }, FTBBackups.backupExecutor).thenRun(() -> {
            finalizeBackup(minecraftServer, backup, setup.backupLocation, format, startTime);
            currentFuture = null;
            clean();
            backupLogger = null;
        });
    }

    /**
    * Determines if a backup should be skipped based on non-resource conditions.
    *
    * @return A BackupFeasibility object indicating if the backup should be skipped, with reason and log level.
    */
    private static BackupFeasibility shouldSkipBackup() {
        if (FTBBackups.isShutdown) {
            return new BackupFeasibility(false, "Mod is shutting down", ConfigData.LoggingLevel.INFO);
        }
        if (!Config.getConfigData().enabled) {
            return new BackupFeasibility(false, "Backups are disabled in config", ConfigData.LoggingLevel.INFO);
        }
        if (!backups.get().getBackups().isEmpty() && Config.getConfigData().only_if_players_been_online && !isDirty()) {
            return new BackupFeasibility(false, "No player activity since last backup", ConfigData.LoggingLevel.INFO);
        }

        return new BackupFeasibility(true, "", ConfigData.LoggingLevel.INFO);
    }

    /**
    * Checks if a backup can be created based on resource availability.
    *
    * @return A BackupFeasibility object containing the feasibility status, failure message, and logging level.
    */
    public static BackupFeasibility canCreateBackup() {
        String failMessage;
        ConfigData.LoggingLevel logLevel;

        // Check world folder
        File worldFile = worldFolder.toFile();
        if (!worldFile.exists() || !worldFile.isDirectory()) {
            failMessage = "Invalid world folder";
            logLevel = ConfigData.LoggingLevel.ERROR;
            return new BackupFeasibility(false, failMessage, logLevel);
        }
        if (!worldFile.canRead()) {
            failMessage = "World folder is not readable";
            logLevel = ConfigData.LoggingLevel.ERROR;
            return new BackupFeasibility(false, failMessage, logLevel);
        }
        if (!worldFile.canWrite()) {
            failMessage = "World folder is not writable";
            logLevel = ConfigData.LoggingLevel.ERROR;
            return new BackupFeasibility(false, failMessage, logLevel);
        }

        // Check backup folder
        if (backupFolderPath == null || !backupFolderPath.toFile().exists()) {
            failMessage = "Invalid backup folder";
            logLevel = ConfigData.LoggingLevel.ERROR;
            return new BackupFeasibility(false, failMessage, logLevel);
        }
        if (!backupFolderPath.toFile().canWrite()) {
            failMessage = "Backup folder is not writable";
            logLevel = ConfigData.LoggingLevel.ERROR;
            return new BackupFeasibility(false, failMessage, logLevel);
        }

        // Check backup or thread status
        if (backupRunning.get()) {
            failMessage = "Backup already running";
            logLevel = ConfigData.LoggingLevel.WARN;
            return new BackupFeasibility(false, failMessage, logLevel);
        }
        if (currentFuture != null) {
            failMessage = "Backup thread is still running";
            logLevel = ConfigData.LoggingLevel.ERROR;
            return new BackupFeasibility(false, failMessage, logLevel);
        }

        // Check disk space
        long minFreeSpace = Config.getConfigData().minimum_free_space * 1000000L;
        long free = backupFolderPath.toFile().getUsableSpace() - minFreeSpace;
        long currentWorldSize = FileUtils.getFolderSize(worldFolder);
        for (String p : Config.getConfigData().additional_paths) {
            try {
                Path path = worldFolder.getParent().resolve(p);
                if (Files.exists(path)) {
                    currentWorldSize += FileUtils.getFolderSize(path);
                }
            } catch (Exception ignored) {
            }
        }

        Backup latestBackup = getLatestBackup();
        if (latestBackup == null) {
            if (currentWorldSize > free) {
                failMessage = "Insufficient space for initial backup";
                logLevel = ConfigData.LoggingLevel.ERROR;
                return new BackupFeasibility(false, failMessage, logLevel);
            }
        } else {
            float ratio = latestBackup.getRatio();
            long expectedSize = (long) (Math.ceil(currentWorldSize * ratio) * 1.05);
            if (expectedSize > free) {
                failMessage = "Insufficient space for expected backup size";
                logLevel = ConfigData.LoggingLevel.ERROR;
                return new BackupFeasibility(false, failMessage, logLevel);
            }
        }

        // All checks passed
        return new BackupFeasibility(true, "", ConfigData.LoggingLevel.INFO);
    }

    /**
     * Sets up the environment for a backup, including saving the world and
     * preparing the backup location.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param name            The name of the backup.
     * @return A BackupSetup object containing the backup location and save future, or null if setup fails.
     */
    private static BackupSetup setupBackupEnvironment(MinecraftServer minecraftServer, String name) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.info("Setting up backup environment for '{}'", name);
        String backupName = TieredBackupTest.getBackupName();
        Path backupLocation = backupFolderPath.resolve(backupName);
        lastAutoBackup = TieredBackupTest.getBackupTime();
        backupRunning.set(true);

        logger.debug("Backup location set to: {}", backupLocation);
        logger.debug("Last auto backup time updated to: {}", new Date(lastAutoBackup));

        // Submit the save operation and chain setNoSave to run after the save completes
        CompletableFuture<Void> saveFuture = minecraftServer.submit(() -> {
            if (!minecraftServer.isCurrentlySaving()) {
                logger.info("Saving world before backup...");
                minecraftServer.saveEverything(true, false, true);
                logger.info("World save completed.");
            } else {
                logger.debug("World is already saving, skipping save operation.");
            }
        }).thenRun(() -> setNoSave(minecraftServer, true));

        logger.info("Backup environment setup complete.");
        return new BackupSetup(backupLocation, saveFuture);
    }

    /**
     * Performs the actual backup operation, including saving the world and copying
     * files.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param backupLocation  The location to save the backup.
     * @param format          The format of the backup (e.g., ZIP, DIRECTORY).
     */
    private static void performBackup(MinecraftServer minecraftServer, Path backupLocation, Format format, Backup backup) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.info("Performing backup to: {}", backupLocation);

        try {
            alertPlayers(minecraftServer, Component.translatable(FTBBackups.MOD_ID + ".backup.starting"));
            List<Path> backupPaths = collectBackupPaths();
            // backupLogger.debug("Collected backup paths: {}", backupPaths);

            // Calculate expectedSize based on total size of backup paths
            long totalSize = 0;
            for (Path path : backupPaths) {
                totalSize += FileUtils.getFolderSize(path);
            }

            // Adjust for compression if applicable
            if (format != Format.DIRECTORY) {
                Backup latestBackup = getLatestBackup();
                if (latestBackup != null) {
                    float ratio = latestBackup.getRatio(); // Compression ratio from previous backup
                    expectedSize = (long) (totalSize * ratio);
                } else {
                    // No previous backup; assume a default compression ratio (e.g., 70%)
                    expectedSize = (long) (totalSize * 0.7);
                }
            } else {
                expectedSize = totalSize; // No compression for DIRECTORY format
            }

            if (Config.getConfigData().enable_console_progress) {
                scheduleStatusCheck(backupLocation, format, expectedSize, 5, TimeUnit.SECONDS, logger);
            } else {
                backupLogger.debug("Console progress disabled.");
            }

            String preview = createPreview(minecraftServer);
            backup.setPreview(preview);
            backupPreview.set(preview);

            backupLogger.debug("Executing backup operation.");
            try {
                executeBackupOperation(backupLocation, format, backupPaths);
            } catch (IOException e) {
                backupLogger.error("Failed to create backup file at {}: {}", backupLocation, e.getMessage(), e);
                throw e;
            }

            backupFailed.set(false);
            if (minecraftServer.getPlayerList().getPlayers().isEmpty()) {
                setDirty(false);
            } else {
                setDirty(true);
                backupLogger.debug("Player still connected after backup, marking server as needing backed up.");
            }
        } catch (Exception e) {
            handleBackupException(minecraftServer, e);
        }
    }

    /**
     * Collects and returns a list of file and directory paths to be included in the backup.
     * This method validates the world folder and adds it to the backup paths, then processes
     * additional paths specified in the configuration, applying inclusion and exclusion filters.
     * 
     * <p>The method ensures that both files and directories are included in the backup paths,
     * and handles errors gracefully by skipping missing files and logging warnings for other issues.
     * 
     * <p>If the world folder does not exist, is not a directory, or is not readable, this method
     * throws an {@link IllegalStateException}.
     * 
     * @return a list of {@link Path} objects representing the files and directories to be backed up
     * @throws IOException if an I/O error occurs during the collection of paths
     */
    private static List<Path> collectBackupPaths() throws IOException {
        List<Path> backupPaths = new LinkedList<>();
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;

        // Validate and add the world folder
        if (!Files.exists(worldFolder)) {
            logger.error("World folder does not exist: {}", worldFolder);
            throw new IllegalStateException("World folder does not exist");
        }
        if (!Files.isDirectory(worldFolder)) {
            logger.error("World folder is not a directory: {}", worldFolder);
            throw new IllegalStateException("World folder is not a directory");
        }
        if (!Files.isReadable(worldFolder)) {
            logger.error("World folder is not readable: {}", worldFolder);
            throw new IllegalStateException("World folder is not readable");
        }
        backupPaths.add(worldFolder);
        logger.debug("Added world folder to backup paths: {}", worldFolder);

        // Process additional paths
        List<String> additionalPaths = Config.getConfigData().additional_paths;
        if (!additionalPaths.isEmpty()) {
            Files.walkFileTree(serverRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relDir = serverRoot.relativize(dir);
                    if (shouldInclude(dir, relDir)) {
                        backupPaths.add(dir);
                        if (Config.getConfigData().verbose_logging) {
                            logger.debug("Added additional directory to backup: {}", dir);}
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relFile = serverRoot.relativize(file);
                    if (shouldInclude(file, relFile) && !isChildOfAny(file, backupPaths)) {
                        backupPaths.add(file);
                        if (Config.getConfigData().verbose_logging) {
                            logger.debug("Added additional file to backup: {}", file);}                        
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException exc) {
                    if (exc instanceof NoSuchFileException) {
                        logger.debug("Skipping missing file during traversal: {}", path);
                    } else {
                        logger.warn("Error accessing path {}: {}", path, exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        logger.debug("Collected {} paths for backup", backupPaths.size());
        if (backupPaths.isEmpty()) {
            logger.error("No paths collected for backup. Aborting.");
            throw new IllegalStateException("No paths collected for backup.");
        }

        return backupPaths;
    }

    // Helper method to determine if a path should be included in the backup
    private static boolean shouldInclude(Path path, Path relPath) {
        // Exclude paths within worldFolder or backupFolderPath
        if (FileUtils.isSubPathOf(path, worldFolder))
            return false;
        if (FileUtils.isSubPathOf(path, backupFolderPath))
            return false;
        // Check inclusion and exclusion filters
        return FileUtils.matchesAnyFilter(relPath, Config.getConfigData().additional_paths) &&
                !FileUtils.matchesAnyFilter(relPath, Config.getConfigData().excluded_paths);
    }

    /**
    * Executes the backup operation by copying files to a directory or compressing them into an archive,
    * based on the specified format. If a file is missing during the process, it will be skipped with a warning.
    * The method verifies that the backup file or directory was created successfully.
    *
    * @param backupPath The path where the backup will be stored.
    * @param format The format of the backup (e.g., DIRECTORY, ZIP, ZSTD).
    * @param backupPaths The list of paths to be included in the backup.
    * @throws IOException If an I/O error occurs during the backup process, or if the backup file or directory fails to be created.
    */
    private static void executeBackupOperation(Path backupPath, Format format, List<Path> backupPaths)
            throws IOException {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.info("Starting backup operation with format: {}", format);
        try {
            if (format == Format.DIRECTORY) {
                FileUtils.copySourcePathsToDirectory(backupPath, serverRoot, backupPaths);
                if (!Files.exists(backupPath)) {
                    logger.error("Backup directory was not created: {}", backupPath);
                    throw new IOException("Backup directory was not created");
                } else {
                    logger.debug("Directory backup completed.");
                }
            } else {
                FileUtils.compressSourcePathsToArchive(backupPath, serverRoot, backupPaths, format);
                if (!Files.exists(backupPath)) {
                    logger.error("Backup file was not created: {}", backupPath);
                    throw new IOException("Backup file was not created");
                } else {
                    logger.debug("Compressed backup completed.");
                }
            }
        } catch (UncheckedIOException uioe) {
            if (uioe.getCause() instanceof NoSuchFileException) {
                logger.warn("[executeBackupOperation] Skipping missing file during backup: {}", uioe.getCause().getMessage());
            } else {
                throw uioe;
            }
        }
    }

    /**
     * Finalizes the backup process, including calculating checksums and updating
     * metadata.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param backup          The backup object.
     * @param backupLocation  The location of the backup.
     * @param format          The format of the backup.
     * @param startTime       The start time of the backup for timing purposes.
     */
    private static void finalizeBackup(MinecraftServer minecraftServer, Backup backup, Path backupLocation,
            Format format, AtomicLong startTime) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Finalizing backup at: {}", backupLocation);
        setNoSave(minecraftServer, false);
        if (backupFailed.get()) {
            backupFailed.set(false);
            backupRunning.set(false);
            logger.warn("Backup failed, resetting flags.");
            return;
        }

        long backupSize = 0;
        String sha1 = "";
        float ratio = 1;

        if (Files.exists(backupLocation)) {
            backupSize = FileUtils.getFolderSize(backupLocation);
            if (format != Format.DIRECTORY) {
                sha1 = FileUtils.generateFileSha1(backupLocation);
                ratio = (float) backupSize / (float) FileUtils.getFolderSize(worldFolder);
                logger.debug("Calculated compression ratio: {}", ratio);
            } else {
                sha1 = FileUtils.generateDirectorySha1(backupLocation);
            }
        } else {
            logger.error("Backup file does not exist: {}", backupLocation);
            backupFailed.set(true);
            backupRunning.set(false);
            alertPlayers(minecraftServer, Component.translatable(FTBBackups.MOD_ID + ".backup.failed"));
            return;
        }

        logger.debug("Backup size: {}, World size: {}", FileUtils.convertSizeToReadableString((double) backupSize),
                FileUtils.convertSizeToReadableString((double) FileUtils.getFolderSize(worldFolder)));
        synchronized (BACKUP_LOCK) {
            backup.setRatio(ratio).setSha1(sha1).setComplete();
            backup.setSize(backupSize);
            updateJson();
            logger.debug("Backup finalized and JSON updated for: {}",
                    Path.of(backup.getBackupLocation()).getFileName());
        }

        long elapsedTime = System.nanoTime() - startTime.get();
        backupRunning.set(false);

        if (Config.getConfigData().notification_mode != ConfigData.NotificationMode.NONE) {
            String msg = "Backup finished in " + format(elapsedTime) + " Size: " + FileUtils.convertSizeToReadableString((double) backupSize);
            alertPlayers(minecraftServer, Component.translatable(msg));
        }
        logger.info("New backup created at {} size: {} Took: {} Sha1: {}", backupLocation,
                FileUtils.convertSizeToReadableString((double) backupSize), format(elapsedTime), sha1);

        TieredBackupTest.testBackupCount++;
    }

    /**
     * Handles exceptions that occur during the backup process.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param e               The exception thrown.
     */
    private static void handleBackupException(MinecraftServer minecraftServer, Exception e) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        boolean shouldFailBackup = true;

        switch (e) {
            case TimeoutException timeoutException -> {
                logger.error("Backup failed: World save took too long.");
            }
            case FileAlreadyExistsException fileExistsException -> {
                logger.warn("Backup error: File already exists at destination.", e);
                shouldFailBackup = false;
            }
            case UncheckedIOException uncheckedIOException when uncheckedIOException
                    .getCause() instanceof NoSuchFileException -> {
                logger.warn("Unchecked exception kicked to handleBackupException: {}",
                        uncheckedIOException.getCause().getMessage());
                shouldFailBackup = false;
            }
            default -> {
                logger.error("Backup failed with unhandled exception", e);
            }
        }

        if (shouldFailBackup) {
            backupRunning.set(false);
            backupFailed.set(true);
            currentFuture = null;
            alertPlayers(minecraftServer, Component.translatable(FTBBackups.MOD_ID + ".backup.failed"));
        }
    }

    /**
     * Generates a timestamped backup file name based on the current date and time.
     *
     * @return The generated backup file name.
     */
    public static String genBackupFileName() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Generating backup filename...");
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        String backupName = now.format(formatter);
        switch (Config.getConfigData().backup_format) {
            case ZIP:
                backupName += ".zip";
                logger.debug("Backup format: ZIP, extension: .zip");
                break;
            case ZSTD:
                backupName += ".tar.zst";
                logger.debug("Backup format: ZSTD, extension: .tar.zst");
                break;
            case DIRECTORY:
                logger.debug("Backup format: DIRECTORY, no extension");
                break;
        }
        logger.info("Generated backup filename: {}", backupName);
        return backupName;
    }

    /**
     * Adds a new backup to the list of managed backups.
     *
     * @param backup The backup to add.
     */
    public static void addBackup(Backup backup) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Adding backup: {}", backup.getBackupLocation());
        backups.getAndUpdate(backups1 -> {
            backups1.add(backup);
            logger.debug("Backup added to list.");
            return backups1;
        });
    }

    /**
     * Removes a backup from the list of managed backups.
     *
     * @param backup The backup to remove.
     */
    public static void removeBackup(Backup backup) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Removing backup from list: {}", backup.getBackupLocation());
        backups.getAndUpdate(backups1 -> {
            if (backups1.contains(backup)) {
                backups1.remove(backup);
                logger.debug("Backup removed from list.");
                return backups1;
            }
            logger.debug("Backup not found in list.");
            return backups1;
        });
    }

    /**
     * Retrieves the most recent complete backup.
     *
     * @return The latest complete backup, or null if none exist.
     */
    public static Backup getLatestBackup() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Retrieving latest complete backup...");
        if (backups == null) {
            logger.debug("Backups reference is null.");
            return null;
        }
        if (backups.get().isEmpty()) {
            logger.debug("No backups available.");
            return null;
        }
        Optional<Backup> latestBackup = backups.get().getBackups().stream()
                .filter(Backup::isComplete)
                .max(Comparator.comparingLong(Backup::getCreateTime));
        if (latestBackup.isPresent()) {
            Backup backup = latestBackup.get();
            logger.debug("Latest backup found: {}", backup.getBackupLocation());
            return backup;
        } else {
            logger.debug("No complete backups found.");
            return null;
        }
    }

    /**
     * Cleans up old backups based on retention policies.
     */
    public static void clean() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Starting backup cleanup...");
        if (FTBBackups.minecraftServer == null) {
            logger.debug("Minecraft server not available, skipping cleanup.");
            return;
        }
    
        synchronized (BACKUP_LOCK) {
            if (FTBBackups.isShutdown) {
                logger.debug("Mod is shutting down, skipping cleanup.");
                return;
            }
            if (backupRunning.get()) {
                logger.debug("Backup is running, skipping cleanup.");
                return;
            }
    
            // Refresh the backups list from backups.json
            loadJson();
            if (backups == null || backups.get() == null) {
                logger.debug("Backups reference is null after loading, skipping cleanup.");
                return;
            }
    
            // Remove incomplete backups if configured
            if (Config.getConfigData().remove_incomplete_backups) {
                List<Backup> incompleteBackups = backups.get().getBackups().stream()
                        .filter(backup -> !backup.isComplete())
                        .collect(Collectors.toList());
                for (Backup backup : incompleteBackups) {
                    logger.info("Removing incomplete backup: {}", backup.getBackupLocation());
                    deleteBackup(backup);
                }
            }
    
            // Check for unmanaged files
            try {
                Set<String> managedFileNames = backups.get().getBackups().stream()
                        .map(backup -> Path.of(backup.getBackupLocation()).getFileName().toString())
                        .collect(Collectors.toSet());
    
                List<String> directoryFileNames = Files.list(backupFolderPath)
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toList());
    
                List<String> unmanagedFiles = directoryFileNames.stream()
                        .filter(fileName -> !managedFileNames.contains(fileName) && !"backups.json".equals(fileName))
                        .collect(Collectors.toList());
    
                if (!unmanagedFiles.isEmpty()) {
                    logger.info("Unmanaged backup files found: {}", unmanagedFiles);
                }
            } catch (IOException e) {
                logger.error("Error while checking for unmanaged backup files", e);
            }
    
            // Apply retention policies
            switch (Config.getConfigData().retention_mode) {
                case MAX_BACKUPS:
                    logger.debug("Retention mode: MAX_BACKUPS");
                    cleanMax();
                    break;
                case TIERED:
                    logger.debug("Retention mode: TIERED");
                    cleanTiered();
                    break;
                default:
                    logger.error("Unknown retention mode: {}", Config.getConfigData().retention_mode);
                    break;
            }
            verifyOldBackups();
            logger.debug("Backup cleanup completed.");
        }
    }

    /**
     * Cleans backups using the MAX_BACKUPS retention policy.
     */
    private static void cleanMax() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Cleaning backups with MAX_BACKUPS retention mode...");
        List<Backup> completeBackups = backups.get().getBackups().stream()
                .filter(Backup::isComplete)
                .filter(backup -> !backup.isProtected())
                .sorted(Comparator.comparingLong(Backup::getCreateTime))
                .collect(Collectors.toList());

        int backupsNeedRemoving = 0;
        if (completeBackups.size() > Config.getConfigData().max_backups) {
            logger.info("More backups than {} found, removing oldest backups.", Config.getConfigData().max_backups);
            backupsNeedRemoving = completeBackups.size() - Config.getConfigData().max_backups;
        } else if (isSpaceConstrained && Config.getConfigData().free_space_if_needed) {
            logger.info("Insufficient space, removing oldest backup to free space.");
            isSpaceConstrained = false;
            backupsNeedRemoving = 1;
        }

        if (backupsNeedRemoving <= 0 || completeBackups.isEmpty()) {
            logger.debug("No backups need removing.");
            return;
        }

        // Remove the oldest backups directly from the sorted list
        for (int i = 0; i < backupsNeedRemoving && i < completeBackups.size(); i++) {
            Backup backupToRemove = completeBackups.get(i);
            logger.info("Removing oldest backup: {}", backupToRemove.getBackupLocation());
            deleteBackup(backupToRemove);
        }

        logger.debug("MAX_BACKUPS cleanup completed.");
    }

    /**
     * Cleans backups using the TIERED retention policy.
     */
    private static void cleanTiered() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Cleaning backups with TIERED retention mode...");
        List<Backup> backupsList = backups.get().getBackups().stream()
                .filter(Backup::isComplete)
                .filter(backup -> !backup.isProtected())
                .sorted(Comparator.comparingLong(Backup::getCreateTime).reversed())
                .collect(Collectors.toList());

        if (backupsList.size() <= Config.getConfigData().keep_latest) {
            logger.debug("No need to remove backups; within keep_latest limit.");
            return;
        }

        Map<Backup, String> backupsToKeep = new LinkedHashMap<>();
        if (Config.getConfigData().keep_latest > 0) {
            int kept = 0;
            for (Backup backup : backupsList) {
                backupsToKeep.put(backup, "Latest");
                kept++;
                if (kept >= Config.getConfigData().keep_latest) {
                    break;
                }
            }
        }

        computeRetained(backupsList, backupsToKeep, Config.getConfigData().keep_hourly, Calendar.HOUR_OF_DAY);
        computeRetained(backupsList, backupsToKeep, Config.getConfigData().keep_daily, Calendar.DAY_OF_YEAR);
        computeRetained(backupsList, backupsToKeep, Config.getConfigData().keep_weekly, Calendar.WEEK_OF_YEAR);
        computeRetained(backupsList, backupsToKeep, Config.getConfigData().keep_monthly, Calendar.MONTH);

        backupsList.removeAll(backupsToKeep.keySet());

        for (Backup backup : backupsList) {
            if (!TieredBackupTest.shouldRemoveBackup(backup)) {
                continue;
            }
            logger.debug("Removing backup from list: {} (not retained)", backup.getBackupLocation());
            deleteBackup(backup);
        }

        if (!backupsToKeep.isEmpty()) {
            backupsToKeep.forEach((backup, rule) -> logger.debug("Keeping backup: {}, Rule: {}",
                    backup.getBackupLocation(), rule));
        }

        TieredBackupTest.cycleComplete();
        logger.debug("TIERED cleanup completed.");
    }

    /**
     * Computes which backups to retain based on the specified time unit and
     * retention count.
     *
     * @param backups    The list of backups to evaluate.
     * @param retained   A map of backups to retain with their retention reasons.
     * @param keepNumber The number of backups to keep for the time unit.
     * @param timeUnit   The time unit (e.g., Calendar.HOUR_OF_DAY).
     */
    private static void computeRetained(List<Backup> backups, Map<Backup, String> retained, int keepNumber,
            int timeUnit) {
        String unitName = timeUnit == Calendar.HOUR_OF_DAY ? "Hour"
                : timeUnit == Calendar.DAY_OF_YEAR ? "Day" : timeUnit == Calendar.WEEK_OF_YEAR ? "Week" : "Month";
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Computing retained backups for {} (keep {})", unitName, keepNumber);
        try {
            Calendar now = Calendar.getInstance();
            now.set(Calendar.MILLISECOND, 0);
            now.set(Calendar.SECOND, 0);
            now.set(Calendar.MINUTE, 0);
            if (timeUnit == Calendar.DAY_OF_YEAR) {
                now.set(Calendar.HOUR_OF_DAY, 0);
            }
            if (timeUnit == Calendar.WEEK_OF_YEAR) {
                now.set(Calendar.HOUR_OF_DAY, 0);
                now.set(Calendar.DAY_OF_WEEK, 0);
            }
            if (timeUnit == Calendar.MONTH) {
                now.set(Calendar.HOUR_OF_DAY, 0);
                now.set(Calendar.DAY_OF_MONTH, 0);
            }

            for (int i = 0; i < keepNumber; i++) {
                Calendar past = (Calendar) now.clone();
                past.add(timeUnit, -i);
                long end = past.getTimeInMillis();
                if (i == 0) {
                    end = Calendar.getInstance().getTimeInMillis();
                }
                past.add(timeUnit, -1);
                long start = past.getTimeInMillis();

                Backup latest = null;
                for (Backup backup : backups) {
                    long time = backup.getCreateTime();
                    if (time >= start && time < end && (latest == null || time > latest.getCreateTime())) {
                        latest = backup;
                    }
                }
                if (latest != null) {
                    TieredBackupTest.willKeep(unitName, latest);
                    String info = (retained.containsKey(latest) ? retained.get(latest) + "&" : "") + unitName;
                    retained.put(latest, info);
                    logger.debug("Retaining backup: {} for {}", latest.getBackupLocation(), unitName);
                }
            }
        } catch (Throwable e) {
            logger.error("Error computing retained backups for {}", unitName, e);
        }
    }

    /**
     * Deletes a backup from the file system.
     *
     * @param backup The backup to delete.
     */
    public static void deleteBackup(Backup backup) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Attempting to delete backup: {}", backup.getBackupLocation());
        Path backupFile = Path.of(backup.getBackupLocation());

        if (!Files.exists(backupFile)) {
            logger.info("Backup does not exist on disk: {}, removing from list.", backupFile);
            removeBackup(backup);
            updateJson();
            return;
        }

        boolean deletionSuccessful = false;
        try {
            if (backup.getBackupFormat() == Format.DIRECTORY) {
                org.apache.commons.io.FileUtils.deleteDirectory(backupFile.toFile());
                logger.info("Successfully deleted directory backup: {}", backupFile);
                deletionSuccessful = true;
            } else {
                if (Files.deleteIfExists(backupFile)) {
                    logger.info("Successfully deleted file backup: {}", backupFile);
                    deletionSuccessful = true;
                } else {
                    logger.warn("Failed to delete backup file: {}", backupFile);
                }
            }
        } catch (IOException e) {
            logger.error("IO error while deleting backup: {} - {}", backupFile, e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error while deleting backup: {} - {}", backupFile, e.getMessage(), e);
        }

        if (deletionSuccessful) {
            removeBackup(backup);
            updateJson();
        }
    }

    /**
     * Loads the backups metadata from the JSON file.
     */
    public static void loadJson() {
        Path json = defaultBackupLocation.resolve("backups.json");
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Loading backups from JSON: {}", json);
        if (Files.exists(json)) {
            Gson gson = new Gson();
            try {
                FileReader fileReader = new FileReader(json.toFile());
                backups.getAndUpdate(backups1 -> gson.fromJson(fileReader, Backups.class));
                fileReader.close();
                logger.debug("Backups loaded successfully.");
            } catch (Exception e) {
                logger.error("Error loading backups from JSON", e);
                backups.getAndUpdate(backups1 -> new Backups());
            }
        } else {
            logger.debug("No backups.json found, initializing new backups.");
            backups.set(new Backups());
        }
    }

    /**
     * Updates the backups metadata in the JSON file.
     */
    public static void updateJson() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Updating backups.json...");
        try {
            String jsonString = GSON.toJson(backups.get(), Backups.class);
            writeToFile(jsonString);
            logger.debug("backups.json updated successfully.");
        } catch (Exception e) {
            logger.error("Error updating backups.json", e);
        }
    }

    /**
     * Writes the backups metadata to the JSON file.
     *
     * @param json The JSON string to write.
     */
    public static void writeToFile(String json) {
        Path backupsJsonPath = defaultBackupLocation.resolve("backups.json");
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Writing to backups.json: {}", backupsJsonPath);
        try (FileOutputStream fileOutputStream = new FileOutputStream(backupsJsonPath.toFile())) {
            byte[] jsonBytes = json.getBytes(Charset.defaultCharset());
            fileOutputStream.write(jsonBytes);
            logger.debug("Successfully wrote to backups.json.");
        } catch (IOException e) {
            logger.error("Error writing to backups.json", e);
        }
    }

    /**
     * Represents the feasibility of creating a backup, including the status, failure message, and logging level.
     */
    public static class BackupFeasibility {
        public final boolean canCreate;
        public final String failMessage;
        public final ConfigData.LoggingLevel logLevel;

        public BackupFeasibility(boolean canCreate, String failMessage, ConfigData.LoggingLevel logLevel) {
            this.canCreate = canCreate;
            this.failMessage = failMessage;
            this.logLevel = logLevel;
        }
    }

    /**
     * Verifies and removes any backups that no longer exist on the file system.
     */
    public static void verifyOldBackups() {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Verifying old backups...");
        if (backups == null) {
            logger.debug("Backups reference is null, nothing to verify.");
            return;
        }
        if (backups.get().isEmpty()) {
            logger.debug("No backups to verify.");
            return;
        }

        synchronized (BACKUP_LOCK) {
            List<Backup> toRemove = new ArrayList<>();
            for (Backup backup : backups.get().getBackups()) {
                try {
                    Path backupPath = Path.of(backup.getBackupLocation());
                    if (!Files.exists(backupPath)) {
                        logger.debug("Backup file missing: {}, marking for removal.", backupPath);
                        toRemove.add(backup);
                    }
                } catch (Exception e) {
                    logger.error("Error checking backup: {}", backup.getBackupLocation(), e);
                }
            }

            if (!toRemove.isEmpty()) {
                for (Backup backup : toRemove) {
                    removeBackup(backup);
                }
                updateJson();
                logger.info("Backups list updated: {} backups removed.", toRemove.size());
            } else {
                logger.debug("No changes needed after verification.");
            }
        }
    }

    /**
     * Creates the backup folder if it does not exist.
     *
     * @param path The path to the backup folder.
     */
    public static void createBackupFolder(Path path) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        if (!Files.exists(path)) {
            boolean backupFolderCreated = path.toFile().mkdirs();
            if (backupFolderCreated) {
                logger.info("Created backup folder at: {}", path.toAbsolutePath());
            } else {
                logger.warn("Failed to create backup folder at: {}", path.toAbsolutePath());
            }
        } else {
            logger.debug("Backup folder already exists at: {}", path.toAbsolutePath());
        }
    }

    /**
     * Sets the noSave flag for all levels, controlling whether the world is saved.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param value           The value to set for noSave.
     */
    public static void setNoSave(MinecraftServer minecraftServer, boolean value) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Setting noSave flag to {} for all levels.", value);
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            if (level != null) {
                level.noSave = value;
                logger.debug("noSave set to {} for level: {}", value, level.dimension().location());
            }
        }
    }

    /**
    * Logs the feasibility message at the specified log level.
    *
    * @param feasibility The BackupFeasibility object.
    * @param message     The message to log.
    */
    private static void logFeasibilityMessage(BackupFeasibility feasibility, String message) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        switch (feasibility.logLevel) {
            case DEBUG:
                logger.debug("[{}] {}", currentBackupId, message);
                break;
            case INFO:
                logger.info("[{}] {}", currentBackupId, message);
                break;
            case WARN:
                logger.warn("[{}] {}", currentBackupId, message);
                break;
            case ERROR:
                logger.error("[{}] {}", currentBackupId, message);
                break;
        }
    }

    /**
     * Alerts players with a message, based on configuration settings.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param message         The message to display.
     */
    public static void alertPlayers(MinecraftServer minecraftServer, Component message) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        logger.debug("Alerting players with message: {}", message.getString());
        ConfigData.NotificationMode mode = Config.getConfigData().notification_mode;
        if (mode == ConfigData.NotificationMode.NONE) {
            logger.debug("Player notifications disabled, skipping alert.");
            return;
        }
        if (mode == ConfigData.NotificationMode.OPS_ONLY && minecraftServer instanceof DedicatedServer) {
            logger.debug("Notifying operators only.");
            for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
                if (player.hasPermissions(4)) {
                    player.displayClientMessage(message, false);
                    logger.debug("Notified operator: {}", player.getName().getString());
                }
            }
        } else if (mode == ConfigData.NotificationMode.ALL_PLAYERS) {
            logger.debug("Notifying all players.");
            for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
                player.displayClientMessage(message, false);
                logger.debug("Notified player: {}", player.getName().getString());
            }
        }
    }

    /**
     * Formats a duration in nanoseconds into a human-readable string.
     *
     * @param nano The duration in nanoseconds.
     * @return A formatted string representing the duration.
     */
    public static String format(long nano) {
        Duration duration = Duration.ofNanos(nano);
        long mins = duration.toMinutes();
        long seconds = duration.minusMinutes(mins).toSeconds();
        long mili = duration.minusMinutes(mins).minusSeconds(seconds).toMillis();
        return mins + "m, " + seconds + "s, " + mili + "ms";
    }

    /**
     * Checks if the backup state is dirty (i.e., needs to be saved).
     *
     * @return True if the backup state is dirty, false otherwise.
     */
    public static boolean isDirty() {
        return backups.get().isDirty();
    }

    /**
     * Sets the dirty state of the backup.
     *
     * @param value The value to set for dirty state.
     */
    public static void setDirty(boolean value) {
        backups.get().setIsDirty(value);
        updateJson();
    }

    /**
     * Schedules periodic status checks for an ongoing backup.
     *
     * @param backupPath   The path to the backup.
     * @param format       The format of the backup.
     * @param expectedSize The expected size of the backup.
     * @param delay        The delay before the next check.
     * @param unit         The time unit for the delay.
     */
    private static void scheduleStatusCheck(Path backupPath, Format format, long expectedSize, long delay, TimeUnit unit, Logger logger) {
        // FTBBackups.statusMonitorLogger.debug("Scheduling status check with delay: {} {}", delay, unit);
        FTBBackups.statusMonitorExecutorService.schedule(() -> {
            if (isRunning()) {
                long currentSize = getCurrentBackupSize(backupPath, format);
                int percentage = calculatePercentage(currentSize, expectedSize);
                logger.info("Backup in progress: {}% complete, Current size: {}", percentage,
                        FileUtils.convertSizeToReadableString((double) currentSize));
                scheduleStatusCheck(backupPath, format, expectedSize, 30, TimeUnit.SECONDS, logger);
            } else {
                FTBBackups.statusMonitorLogger.debug("Backup not running, status check ended.");
            }
        }, delay, unit);
    }

    /**
     * Calculates the current size of the backup.
     *
     * @param backupPath The path to the backup.
     * @param format     The format of the backup.
     * @return The current size of the backup.
     */
    private static long getCurrentBackupSize(Path backupPath, Format format) {
        Logger logger = (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
        try {
            return FileUtils.getFolderSize(backupPath);
        } catch (Exception e) {
            logger.warn("Failed to get current backup size", e);
            return 0;
        }
    }

    /**
     * Calculates the percentage completion of the backup based on current and expected sizes.
     *
     * @param currentSize  The current size of the backup.
     * @param expectedSize The expected size of the backup.
     * @return The percentage completion.
     */
    private static int calculatePercentage(long currentSize, long expectedSize) {
        if (expectedSize == 0)
            return 0;
        double exactPercentage = ((double) currentSize / expectedSize) * 100;
        double roundedPercentage = Math.round(exactPercentage / 5.0) * 5;
        if (roundedPercentage >= 100) {
            return 99;
        } else {
            return (int) roundedPercentage;
        }
    }

    // Helper method to check if a path is a child of any path in a list
    private static boolean isChildOfAny(Path path, List<Path> parents) {
        for (Path parent : parents) {
            if (FileUtils.isSubPathOf(path, parent)) {
                return true;
            }
        }
        return false;
    }

    private static class BackupSetup {
        final Path backupLocation;
        final CompletableFuture<Void> saveFuture;

        BackupSetup(Path backupLocation, CompletableFuture<Void> saveFuture) {
            this.backupLocation = backupLocation;
            this.saveFuture = saveFuture;
        }
    }

    /**
    * Retrieves the current logger, returning the backup-specific logger if set, or the default logger otherwise.
    *
    * @return The current Logger instance.
    */
    public static Logger getCurrentLogger() {
        return (backupLogger != null) ? backupLogger : FTBBackups.LOGGER;
    }
}