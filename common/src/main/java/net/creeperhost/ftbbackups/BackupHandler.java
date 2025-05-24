package net.creeperhost.ftbbackups;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.creeperhost.ftbbackups.config.Config;
import net.creeperhost.ftbbackups.config.Format;
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
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles backup creation, management, and cleanup for the FTB Backups mod.
 */
public class BackupHandler {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final LevelPreview PREVIEW = new LevelPreview(new MCNBTImpl());
    private static String lastPreview = "";

    private static Path serverRoot;
    private static Path backupFolderPath;
    private static Path worldFolder;
    public static final AtomicBoolean backupRunning = new AtomicBoolean(false);
    private static final AtomicBoolean backupFailed = new AtomicBoolean(false);
    private static AtomicReference<String> backupPreview = new AtomicReference<>("");
    private static boolean isSpaceConstrained = false;

    public static AtomicReference<Backups> backups = new AtomicReference<>(new Backups());
    private static final Object BACKUP_LOCK = new Object();

    private static String failReason = "";
    private static long lastAutoBackup = 0;

    public static CompletableFuture<Void> currentFuture;
    public static Path defaultBackupLocation;
    private static long expectedSize = 0;

    /**
     * Initializes the backup handler with the Minecraft server instance.
     *
     * @param minecraftServer The Minecraft server instance.
     */
    public static void init(MinecraftServer minecraftServer) {
        FTBBackups.LOGGER.info("Initializing BackupHandler...");
        serverRoot = minecraftServer.getServerDirectory().normalize().toAbsolutePath();
        defaultBackupLocation = serverRoot.resolve("backups");

        FTBBackups.LOGGER.debug("Server root set to: {}", serverRoot);
        FTBBackups.LOGGER.debug("Default backup location set to: {}", defaultBackupLocation);

        if (!Config.cached().backup_location.equalsIgnoreCase(".")) {
            try {
                Path configPath = Path.of(Config.cached().backup_location);
                if (Files.exists(configPath)) {
                    FTBBackups.LOGGER.info("Using configured backups directory at {}", configPath.toAbsolutePath());
                    backupFolderPath = configPath;
                } else {
                    FTBBackups.LOGGER.error("Backup directory {} does not exist. Defaulting to {}",
                            configPath.toAbsolutePath(), defaultBackupLocation);
                    backupFolderPath = defaultBackupLocation;
                }
            } catch (Exception e) {
                FTBBackups.LOGGER.error("Error accessing backup directory from config: {}",
                        Config.cached().backup_location, e);
                backupFolderPath = defaultBackupLocation;
            }
        } else {
            FTBBackups.LOGGER.info("No custom backup location specified, using default: {}", defaultBackupLocation);
            backupFolderPath = defaultBackupLocation;
        }

        // createBackupFolder(defaultBackupLocation);
        createBackupFolder(backupFolderPath);
        loadJson();
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
     * Returns an empty string if 'enable_preview' is false, 'preview_dimension' is "none", or generation fails.
     *
     * @param minecraftServer The Minecraft server instance.
     * @return A base64-encoded string of the preview image or an empty string if disabled or failed.
     */
    public static String createPreview(MinecraftServer minecraftServer) {
        if (!Config.cached().enable_preview) {
            FTBBackups.LOGGER.info("Backup preview disabled in configuration.");
            return "";
        }

        String currentWorldHash = calculateWorldHash(minecraftServer);
        String storedWorldHash = backups.get().getWorldHash();

        if (currentWorldHash.equals(storedWorldHash) && !lastPreview.isEmpty()) {
            FTBBackups.LOGGER.info("World state unchanged, reusing cached preview.");
            return lastPreview;
        }

        FTBBackups.LOGGER.info("Starting backup preview generation...");
        long startTime = System.currentTimeMillis();
        try {
            String previewDim = Config.cached().preview_dimension;
            FTBBackups.LOGGER.debug("Preview dimension: {}", previewDim);

            Path worldPath = minecraftServer.getWorldPath(LevelResource.ROOT).toAbsolutePath();
            FTBBackups.LOGGER.debug("Loading world from path: {}", worldPath);
            PREVIEW.loadWorld(worldPath);
            LevelIO levelIO = PREVIEW.getLevelIO();
            FTBBackups.LOGGER.debug("LevelIO initialized.");

            CaptureArea area;
            if ("all".equals(previewDim)) {
                FTBBackups.LOGGER.debug("Scanning dimensions for activity clusters...");
                List<ActivityScanner> scanners = new ArrayList<>();
                List<String> dimensionsToScan = Config.cached().preview_dimensions_list;
                if (dimensionsToScan.isEmpty()) {
                    FTBBackups.LOGGER.debug("No specific dimensions listed, scanning all available dimensions.");
                    for (Level level : levelIO.getLevels()) {
                        ActivityScanner scanner = new ActivityScanner(levelIO, level, 1);
                        if (scanner.findActivityClusters(512, 512, 1)) {
                            scanners.add(scanner);
                        }
                    }
                } else {
                    for (String dim : dimensionsToScan) {
                        Level level = levelIO.getLevel(dim);
                        if (level != null) {
                            ActivityScanner scanner = new ActivityScanner(levelIO, level, 1);
                            if (scanner.findActivityClusters(512, 512, 1)) {
                                scanners.add(scanner);
                            }
                        } else {
                            FTBBackups.LOGGER.warn("Dimension {} not found, skipping.", dim);
                        }
                    }
                }

                if (scanners.isEmpty()) {
                    FTBBackups.LOGGER.warn("No activity clusters found for preview.");
                    return "";
                }

                scanners.sort(Comparator.comparingDouble(ActivityScanner::getTotalHabitationFactor).reversed());
                ActivityScanner scanner = scanners.get(0);
                area = scanner.getResults().get(0);
                FTBBackups.LOGGER.debug("Selected highest habitation factor cluster from dimension.");
            } else {
                Level level = levelIO.getLevel(previewDim);
                if (level == null) {
                    FTBBackups.LOGGER.error("Specified dimension {} not found, falling back to overworld.", previewDim);
                    level = levelIO.getLevel("minecraft:overworld");
                    if (level == null) {
                        FTBBackups.LOGGER.warn("Overworld dimension not found, skipping preview.");
                        return "";
                    }
                }
                ActivityScanner scanner = new ActivityScanner(levelIO, level, 1);
                if (!scanner.findActivityClusters(512, 512, 1)) {
                    FTBBackups.LOGGER.warn("No activity clusters found in dimension {}.", previewDim);
                    return "";
                }
                area = scanner.getResults().get(0);
                FTBBackups.LOGGER.debug("Activity cluster found in dimension: {}", previewDim);
            }

            long captureStart = System.currentTimeMillis();
            SimplePNG.SimpleImg capture = PREVIEW.newCapture()
                    .captureArea(area)
                    .doCapture()
                    .getImage();
            FTBBackups.LOGGER.debug("Capture completed.");

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            SimplePNG.writePNG(os, capture);
            byte[] image = os.toByteArray();

            String newPreview = "data:image/png;base64, " + Base64.getEncoder().encodeToString(image);
            FTBBackups.LOGGER.info("Backup preview created. Scan took {}ms, Capture took {}ms",
                    captureStart - startTime, System.currentTimeMillis() - captureStart);

            lastPreview = newPreview;
            backups.get().setWorldHash(currentWorldHash);
            return newPreview;
        } catch (Exception ex) {
            FTBBackups.LOGGER.error("Error generating backup preview", ex);
            return "";
        } finally {
            try {
                PREVIEW.close();
                FTBBackups.LOGGER.debug("LevelPreview closed successfully.");
            } catch (Exception e) {
                FTBBackups.LOGGER.error("Error closing LevelPreview", e);
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
        String previewDim = Config.cached().preview_dimension;
        List<ResourceLocation> dimensionsToHash = new ArrayList<>();

        // Determine which dimensions to hash
        if (!"all".equals(previewDim)) {
            ResourceLocation dimLocation = parseDimensionString(previewDim);
            if (dimLocation != null) {
                dimensionsToHash.add(dimLocation);
            } else {
                FTBBackups.LOGGER.warn("Invalid preview_dimension: {}, falling back to overworld", previewDim);
                dimensionsToHash.add(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
            }
        } else {
            for (ServerLevel level : minecraftServer.getAllLevels()) {
                dimensionsToHash.add(level.dimension().location());
            }
            dimensionsToHash.sort(Comparator.comparing(ResourceLocation::toString)); // Ensure consistent order
        }

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
                            String fileInfo = dim.toString() + ":" + file.getFileName().toString() + ":" + size + ":"
                                    + lastModified;
                            digest.update(fileInfo.getBytes());
                        }
                    }
                }
            }
            byte[] hashBytes = digest.digest();
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException | IOException e) {
            FTBBackups.LOGGER.warn("Error calculating world hash", e);
            return "";
        }
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
        FTBBackups.LOGGER.info("Starting backup process for '{}'", name);
        if (shouldSkipBackup(minecraftServer)) {
            FTBBackups.LOGGER.info("Backup skipped due to conditions.");
            return;
        }

        BackupSetup setup = setupBackupEnvironment(minecraftServer, name);
        if (setup.backupLocation == null) {
            FTBBackups.LOGGER.warn("Backup environment setup failed.");
            return;
        }

        Format format = Config.cached().backup_format;
        // Create a new Backup object with the necessary details
        Backup backup = new Backup(
                worldFolder.normalize().getFileName().toString(), // World folder name
                lastAutoBackup,                                   // Timestamp of the last backup
                setup.backupLocation.toString(),                  // Backup location path
                0, 1, "",                         // Default values for size, count, and description
                backupPreview.get(),                              // Backup preview setting
                protect,                                          // Protection flag
                name,                                             // Backup name
                format,                                           // Backup format
                false                                    // Not a manual backup
        );

        // Log the addition of the new backup
        FTBBackups.LOGGER.debug("Adding new backup: {} at {}",
                Path.of(backup.getBackupLocation()).getFileName(),
                new Date(backup.getCreateTime()));
        synchronized (BACKUP_LOCK) {
            addBackup(backup);
            updateJson();
            FTBBackups.LOGGER.info("Backup added and JSON updated for: {}",
                    Path.of(backup.getBackupLocation()).getFileName());
        }

        AtomicLong startTime = new AtomicLong(System.nanoTime());

        // Chain the backup operation to the save future
        currentFuture = setup.saveFuture.thenRunAsync(() -> {
            performBackup(minecraftServer, setup.backupLocation, format);
        }, FTBBackups.backupExecutor).thenRun(() -> {
            finalizeBackup(minecraftServer, backup, setup.backupLocation, format, startTime);
            currentFuture = null;
        });

        FTBBackups.LOGGER.debug("Backup task scheduled on executor.");
    }

    /**
     * Determines if a backup should be skipped based on current conditions.
     *
     * @param minecraftServer The Minecraft server instance.
     * @return True if the backup should be skipped, false otherwise.
     */
    private static boolean shouldSkipBackup(MinecraftServer minecraftServer) {
        FTBBackups.LOGGER.debug("Checking if backup should be skipped...");
        if (FTBBackups.isShutdown || !Config.cached().enabled) {
            FTBBackups.LOGGER.info("Skipping backup: mod is shutting down or disabled.");
            return true;
        }

        if (Config.cached().only_if_players_been_online && !isDirty()) {
            FTBBackups.LOGGER.info("Skipping backup: no players have been online since last backup.");
            return true;
        }

        worldFolder = minecraftServer.getWorldPath(LevelResource.ROOT).toAbsolutePath();
        FTBBackups.LOGGER.info("World folder located at: {}", worldFolder);

        if (!canCreateBackup()) {
            if (!failReason.isEmpty()) {
                backupRunning.set(false);
                String failMessage = "Unable to create backup, Reason: " + failReason;
                alertPlayers(minecraftServer, Component.translatable(failMessage));
                FTBBackups.LOGGER.error(failMessage);
                failReason = "";
            }
            backupRunning.set(false);
            FTBBackups.LOGGER.debug("Backup skipped due to failure conditions.");
            return true;
        }

        FTBBackups.LOGGER.debug("No conditions met to skip backup.");
        return false;
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
        FTBBackups.LOGGER.info("Setting up backup environment for '{}'", name);
        String backupName = TieredBackupTest.getBackupName();
        Path backupLocation = backupFolderPath.resolve(backupName);
        lastAutoBackup = TieredBackupTest.getBackupTime();
        backupRunning.set(true);

        FTBBackups.LOGGER.debug("Backup location set to: {}", backupLocation);
        FTBBackups.LOGGER.debug("Last auto backup time updated to: {}", new Date(lastAutoBackup));

        // Submit the save operation and chain setNoSave to run after the save completes
        CompletableFuture<Void> saveFuture = minecraftServer.submit(() -> {
            if (!minecraftServer.isCurrentlySaving()) {
                FTBBackups.LOGGER.info("Saving world before backup...");
                minecraftServer.saveEverything(true, false, true);
                FTBBackups.LOGGER.info("World save completed.");
            } else {
                FTBBackups.LOGGER.debug("World is already saving, skipping save operation.");
            }
        }).thenRun(() -> setNoSave(minecraftServer, true));

        FTBBackups.LOGGER.info("Backup environment setup complete.");
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
    private static void performBackup(MinecraftServer minecraftServer, Path backupLocation, Format format) {
        FTBBackups.LOGGER.info("Performing backup to: {}", backupLocation);

        try {
            alertPlayers(minecraftServer, Component.translatable(FTBBackups.MOD_ID + ".backup.starting"));
            Path backupPath = backupLocation;
            List<Path> backupPaths = new LinkedList<>();
            backupPaths.add(worldFolder);
            FTBBackups.LOGGER.debug("Added world folder to backup paths: {}", worldFolder);

            try (Stream<Path> pathStream = Files.walk(serverRoot)) {
                List<Path> paths = pathStream.toList();
                for (Path path : paths) {
                    Path relFile = serverRoot.relativize(path);
                    if (!FileUtils.matchesAny(relFile, Config.cached().additional_files)) {
                        continue;
                    }

                    if (!FileUtils.isChildOf(path, serverRoot)) {
                        FTBBackups.LOGGER.warn("Ignoring path {}: not a child of server root.", relFile);
                        continue;
                    }

                    if (FileUtils.isChildOf(path, worldFolder)) {
                        FTBBackups.LOGGER.warn("Ignoring path {}: child of world folder.", relFile);
                        continue;
                    }

                    if (FileUtils.isChildOf(path, backupFolderPath)) {
                        FTBBackups.LOGGER.warn("Ignoring path {}: child of backups folder.", relFile);
                        continue;
                    }

                    if (Files.exists(path)) {
                        backupPaths.add(path);
                        FTBBackups.LOGGER.debug("Added additional path to backup: {}", path);
                    } else {
                        FTBBackups.LOGGER.debug("Path no longer exists, skipping: {}", relFile);
                    }
                }
            }

            List<Path> filteredBackupPaths = new ArrayList<>();
            for (Path path : backupPaths) {
                if (Files.isDirectory(path)) {
                    filteredBackupPaths.add(path);
                } else {
                    boolean isWithinDir = false;
                    for (Path dir : backupPaths) {
                        if (Files.isDirectory(dir) && path.startsWith(dir)) {
                            isWithinDir = true;
                            break;
                        }
                    }
                    if (!isWithinDir) {
                        filteredBackupPaths.add(path);
                    }
                }
            }
            backupPaths = filteredBackupPaths;
            FTBBackups.LOGGER.debug("Selected paths for backup (subpaths of directories excluded): {}", backupPaths);

            if (Config.cached().enable_status_monitoring) {
                scheduleStatusCheck(backupPath, format, expectedSize, 5, TimeUnit.SECONDS);
            } else {
                FTBBackups.LOGGER.debug("Status monitoring disabled in config.");
            }

            FTBBackups.LOGGER.debug("Generating backup preview before file operations...");
            backupPreview.set(createPreview(minecraftServer));

            FTBBackups.LOGGER.info("Starting backup operation with format: {}", format);
            if (format == Format.DIRECTORY) {
                FileUtils.copy(backupPath, serverRoot, backupPaths);
                FTBBackups.LOGGER.debug("Directory backup completed.");
            } else {
                FileUtils.compress(backupPath, serverRoot, backupPaths, format);
                FTBBackups.LOGGER.debug("Compressed backup completed.");
            }

            backupFailed.set(false);
            if (minecraftServer.getPlayerList().getPlayers().isEmpty()) {
                setDirty(false);
            } else {
                setDirty(true);
                FTBBackups.LOGGER.debug("Player still connected after backup, setting isDirty to true.");
            }
            FTBBackups.LOGGER.info("Backup performed successfully.");
        } catch (Exception e) {
            handleBackupException(minecraftServer, e);
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
        FTBBackups.LOGGER.debug("Finalizing backup at: {}", backupLocation);
        setNoSave(minecraftServer, false);
        if (backupFailed.get()) {
            backupFailed.set(false);
            backupRunning.set(false);
            FTBBackups.LOGGER.warn("Backup failed, resetting flags.");
            return;
        }

        String sha1;
        float ratio = 1;
        long backupSize = FileUtils.getSize(backupLocation.toFile());
        if (format != Format.DIRECTORY) {
            sha1 = FileUtils.getFileSha1(backupLocation);
            ratio = (float) backupSize / (float) FileUtils.getFolderSize(worldFolder);
            FTBBackups.LOGGER.debug("Calculated compression ratio: {}", ratio);
        } else {
            sha1 = FileUtils.getDirectorySha1(backupLocation);
        }

        FTBBackups.LOGGER.debug("Backup size: {}, World size: {}", FileUtils.getSizeString(backupSize),
                FileUtils.getSizeString(FileUtils.getFolderSize(worldFolder)));
        synchronized (BACKUP_LOCK) {
            backup.setRatio(ratio).setSha1(sha1).setComplete();
            backup.setSize(backupSize);
            updateJson();
            FTBBackups.LOGGER.debug("Backup finalized and JSON updated for: {}",
                    Path.of(backup.getBackupLocation()).getFileName());
        }

        long elapsedTime = System.nanoTime() - startTime.get();
        backupRunning.set(false);
        alertPlayers(minecraftServer, Component.translatable("Backup finished in " + format(elapsedTime)
                + (Config.cached().display_file_size ? " Size: " + FileUtils.getSizeString(backupSize) : "")));
        FTBBackups.LOGGER.info("New backup created at {} size: {} Took: {} Sha1: {}", backupLocation,
                FileUtils.getSizeString(backupSize), format(elapsedTime), sha1);

        TieredBackupTest.testBackupCount++;
    }

    /**
     * Handles exceptions that occur during the backup process.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param e               The exception thrown.
     */
    private static void handleBackupException(MinecraftServer minecraftServer, Exception e) {
        backupRunning.set(false);
        backupFailed.set(true);
        currentFuture = null;
        alertPlayers(minecraftServer, Component.translatable(FTBBackups.MOD_ID + ".backup.failed"));
        if (e instanceof TimeoutException) {
            FTBBackups.LOGGER.warn("Backup failed: World save took too long.");
        } else if (e instanceof FileAlreadyExistsException) {
            FTBBackups.LOGGER.error("Backup failed: File already exists at destination.", e);
            TieredBackupTest.testBackupCount++;
        } else {
            FTBBackups.LOGGER.error("Backup failed with exception", e);
        }
    }

    /**
     * Generates a timestamped backup file name based on the current date and time.
     *
     * @return The generated backup file name.
     */
    public static String genBackupFileName() {
        FTBBackups.LOGGER.debug("Generating backup filename...");
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        String backupName = now.format(formatter);
        switch (Config.cached().backup_format) {
            case ZIP:
                backupName += ".zip";
                FTBBackups.LOGGER.debug("Backup format: ZIP, extension: .zip");
                break;
            case ZSTD:
                backupName += ".tar.zst";
                FTBBackups.LOGGER.debug("Backup format: ZSTD, extension: .tar.zst");
                break;
            case DIRECTORY:
                FTBBackups.LOGGER.debug("Backup format: DIRECTORY, no extension");
                break;
        }
        FTBBackups.LOGGER.info("Generated backup filename: {}", backupName);
        return backupName;
    }

    /**
     * Adds a new backup to the list of managed backups.
     *
     * @param backup The backup to add.
     */
    public static void addBackup(Backup backup) {
        FTBBackups.LOGGER.debug("Adding backup: {}", backup.getBackupLocation());
        backups.getAndUpdate(backups1 -> {
            backups1.add(backup);
            FTBBackups.LOGGER.debug("Backup added to list.");
            return backups1;
        });
    }

    /**
     * Removes a backup from the list of managed backups.
     *
     * @param backup The backup to remove.
     */
    public static void removeBackup(Backup backup) {
        FTBBackups.LOGGER.debug("Removing backup: {}", backup.getBackupLocation());
        backups.getAndUpdate(backups1 -> {
            if (backups1.contains(backup)) {
                backups1.remove(backup);
                FTBBackups.LOGGER.debug("Backup removed from list.");
                return backups1;
            }
            FTBBackups.LOGGER.debug("Backup not found in list.");
            return backups1;
        });
    }

    /**
     * Retrieves the most recent complete backup.
     *
     * @return The latest complete backup, or null if none exist.
     */
    public static Backup getLatestBackup() {
        FTBBackups.LOGGER.debug("Retrieving latest complete backup...");
        if (backups == null) {
            FTBBackups.LOGGER.debug("Backups reference is null.");
            return null;
        }
        if (backups.get().isEmpty()) {
            FTBBackups.LOGGER.debug("No backups available.");
            return null;
        }
        Backup currentNewest = null;
        for (Backup backup : backups.get().getBackups()) {
            if (!backup.isComplete())
                continue;
            if (currentNewest == null)
                currentNewest = backup;
            if (backup.getCreateTime() > currentNewest.getCreateTime()) {
                currentNewest = backup;
            }
        }
        if (currentNewest != null) {
            FTBBackups.LOGGER.debug("Latest backup found: {}", currentNewest.getBackupLocation());
        } else {
            FTBBackups.LOGGER.debug("No complete backups found.");
        }
        return currentNewest;
    }

    /**
     * Retrieves the oldest unprotected backup.
     *
     * @return The oldest unprotected backup, or null if none exist.
     */
    public static Backup getOldestBackup() {
        FTBBackups.LOGGER.debug("Retrieving oldest unprotected backup...");
        if (backups.get().isEmpty()) {
            FTBBackups.LOGGER.debug("No backups available.");
            return null;
        }
        Backup currentOldest = null;
        for (Backup backup : backups.get().getBackups()) {
            if (backup.isProtected())
                continue;
            if (currentOldest == null)
                currentOldest = backup;
            if (backup.getCreateTime() < currentOldest.getCreateTime()) {
                currentOldest = backup;
            }
        }
        if (currentOldest != null) {
            FTBBackups.LOGGER.debug("Oldest backup found: {}", currentOldest.getBackupLocation());
        } else {
            FTBBackups.LOGGER.debug("No unprotected backups found.");
        }
        return currentOldest;
    }

    /**
     * Cleans up old backups based on retention policies.
     */
    public static void clean() {
        FTBBackups.LOGGER.debug("Starting backup cleanup...");
        if (FTBBackups.minecraftServer == null) {
            FTBBackups.LOGGER.debug("Minecraft server not available, skipping cleanup.");
            return;
        }

        synchronized (BACKUP_LOCK) {
            if (FTBBackups.isShutdown) {
                FTBBackups.LOGGER.debug("Mod is shutting down, skipping cleanup.");
                return;
            }
            if (backupRunning.get()) {
                FTBBackups.LOGGER.debug("Backup is running, skipping cleanup.");
                return;
            }
            if (backups == null) {
                FTBBackups.LOGGER.debug("Backups reference is null, skipping cleanup.");
                return;
            }

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
                    FTBBackups.LOGGER.info("Unmanaged backup files found: {}", unmanagedFiles);
                }
            } catch (IOException e) {
                FTBBackups.LOGGER.error("Error while checking for unmanaged backup files", e);
            }

            switch (Config.cached().retention_mode) {
                case MAX_BACKUPS:
                    FTBBackups.LOGGER.debug("Retention mode: MAX_BACKUPS");
                    cleanMax();
                    break;
                case TIERED:
                    FTBBackups.LOGGER.debug("Retention mode: TIERED");
                    cleanTiered();
                    break;
                default:
                    FTBBackups.LOGGER.error("Unknown retention mode: {}", Config.cached().retention_mode);
                    break;
            }

            verifyOldBackups();
            FTBBackups.LOGGER.debug("Backup cleanup completed.");
        }
    }

    /**
     * Cleans backups using the MAX_BACKUPS retention policy.
     */
    private static void cleanMax() {
        FTBBackups.LOGGER.debug("Cleaning backups with MAX_BACKUPS retention mode...");
        int backupsNeedRemoving = 0;
        if (backups.get().unprotectedSize() > Config.cached().max_backups) {
            FTBBackups.LOGGER.info("More backups than {} found, removing oldest backups.", Config.cached().max_backups);
            backupsNeedRemoving = (backups.get().unprotectedSize() - Config.cached().max_backups);
        } else if (isSpaceConstrained && Config.cached().free_space_if_needed) {
            FTBBackups.LOGGER.info("Insufficient space, removing oldest backup to free space.");
            isSpaceConstrained = false;
            backupsNeedRemoving = 1;
        }
        if (backupsNeedRemoving <= 0 || getOldestBackup() == null) {
            FTBBackups.LOGGER.debug("No backups need removing.");
            return;
        }

        for (int i = 0; i < backupsNeedRemoving; i++) {
            Backup incomplete = backups.get().getBackups().stream()
                    .filter(e -> !e.isComplete())
                    .findAny()
                    .orElse(null);

            if (incomplete != null) {
                FTBBackups.LOGGER.info("Removing incomplete backup: {}", incomplete.getBackupLocation());
                deleteBackup(incomplete);
                continue;
            }

            Backup oldest = getOldestBackup();
            if (oldest != null) {
                FTBBackups.LOGGER.info("Removing oldest backup: {}", oldest.getBackupLocation());
                deleteBackup(oldest);
            } else {
                FTBBackups.LOGGER.debug("No more backups to remove.");
                break;
            }
        }
        FTBBackups.LOGGER.debug("MAX_BACKUPS cleanup completed.");
    }

    /**
     * Cleans backups using the TIERED retention policy.
     */
    private static void cleanTiered() {
        FTBBackups.LOGGER.debug("Cleaning backups with TIERED retention mode...");
        List<Backup> backupsList = new ArrayList<>(backups.get().getBackups());
        backupsList.removeIf(Backup::isProtected);
        backupsList.sort(Comparator.comparingLong(Backup::getCreateTime).reversed());

        if (backupsList.size() <= Config.cached().keep_latest) {
            FTBBackups.LOGGER.debug("No need to remove backups; within keep_latest limit.");
            return;
        }

        Map<Backup, String> backupsToKeep = new LinkedHashMap<>();
        if (Config.cached().keep_latest > 0) {
            int kept = 0;
            for (Backup backup : backupsList) {
                if (backup.isComplete()) {
                    backupsToKeep.put(backup, "Latest");
                    kept++;
                }
                if (kept >= Config.cached().keep_latest) {
                    break;
                }
            }
        }

        computeRetained(backupsList, backupsToKeep, Config.cached().keep_hourly, Calendar.HOUR_OF_DAY);
        computeRetained(backupsList, backupsToKeep, Config.cached().keep_daily, Calendar.DAY_OF_YEAR);
        computeRetained(backupsList, backupsToKeep, Config.cached().keep_weekly, Calendar.WEEK_OF_YEAR);
        computeRetained(backupsList, backupsToKeep, Config.cached().keep_monthly, Calendar.MONTH);

        backupsList.removeAll(backupsToKeep.keySet());

        for (Backup backup : backupsList) {
            if (!TieredBackupTest.shouldRemoveBackup(backup)) {
                continue;
            }
            FTBBackups.LOGGER.debug("Removing backup: {} (not retained)", backup.getBackupLocation());
            deleteBackup(backup);
        }

        if (!backupsToKeep.isEmpty()) {
            backupsToKeep.forEach((backup, rule) -> FTBBackups.LOGGER.debug("Keeping backup: {}, Rule: {}",
                    backup.getBackupLocation(), rule));
        }

        TieredBackupTest.cycleComplete();
        FTBBackups.LOGGER.debug("TIERED cleanup completed.");
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
        FTBBackups.LOGGER.debug("Computing retained backups for {} (keep {})", unitName, keepNumber);
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
                    FTBBackups.LOGGER.debug("Retaining backup: {} for {}", latest.getBackupLocation(), unitName);
                }
            }
        } catch (Throwable e) {
            FTBBackups.LOGGER.error("Error computing retained backups for {}", unitName, e);
        }
    }

    /**
     * Deletes a backup from the file system.
     *
     * @param backup The backup to delete.
     */
    private static void deleteBackup(Backup backup) {
        FTBBackups.LOGGER.debug("Attempting to delete backup: {}", backup.getBackupLocation());
        Path backupFile = Path.of(backup.getBackupLocation());

        if (!Files.exists(backupFile)) {
            FTBBackups.LOGGER.info("Backup does not exist on disk: {}, removing from list.", backupFile);
            removeBackup(backup);
            updateJson();
            return;
        }

        boolean deletionSuccessful = false;
        try {
            if (backup.getBackupFormat() == Format.DIRECTORY) {
                org.apache.commons.io.FileUtils.deleteDirectory(backupFile.toFile());
                FTBBackups.LOGGER.info("Successfully deleted directory backup: {}", backupFile);
                deletionSuccessful = true;
            } else {
                if (Files.deleteIfExists(backupFile)) {
                    FTBBackups.LOGGER.info("Successfully deleted file backup: {}", backupFile);
                    deletionSuccessful = true;
                } else {
                    FTBBackups.LOGGER.warn("Failed to delete backup file: {}", backupFile);
                }
            }
        } catch (IOException e) {
            FTBBackups.LOGGER.error("IO error while deleting backup: {} - {}", backupFile, e.getMessage(), e);
        } catch (Exception e) {
            FTBBackups.LOGGER.error("Unexpected error while deleting backup: {} - {}", backupFile, e.getMessage(), e);
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
        FTBBackups.LOGGER.debug("Loading backups from JSON: {}", json);
        if (Files.exists(json)) {
            Gson gson = new Gson();
            try {
                FileReader fileReader = new FileReader(json.toFile());
                backups.getAndUpdate(backups1 -> gson.fromJson(fileReader, Backups.class));
                fileReader.close();
                FTBBackups.LOGGER.debug("Backups loaded successfully.");
            } catch (Exception e) {
                FTBBackups.LOGGER.error("Error loading backups from JSON", e);
                backups.getAndUpdate(backups1 -> new Backups());
            }
        } else {
            FTBBackups.LOGGER.debug("No backups.json found, initializing new backups.");
            backups.set(new Backups());
        }
    }

    /**
     * Updates the backups metadata in the JSON file.
     */
    public static void updateJson() {
        FTBBackups.LOGGER.debug("Updating backups.json...");
        try {
            String jsonString = GSON.toJson(backups.get(), Backups.class);
            writeToFile(jsonString);
            FTBBackups.LOGGER.debug("backups.json updated successfully.");
        } catch (Exception e) {
            FTBBackups.LOGGER.error("Error updating backups.json", e);
        }
    }

    /**
     * Writes the backups metadata to the JSON file.
     *
     * @param json The JSON string to write.
     */
    public static void writeToFile(String json) {
        Path backupsJsonPath = defaultBackupLocation.resolve("backups.json");
        FTBBackups.LOGGER.debug("Writing to backups.json: {}", backupsJsonPath);
        try (FileOutputStream fileOutputStream = new FileOutputStream(backupsJsonPath.toFile())) {
            byte[] jsonBytes = json.getBytes(Charset.defaultCharset());
            fileOutputStream.write(jsonBytes);
            FTBBackups.LOGGER.debug("Successfully wrote to backups.json.");
        } catch (IOException e) {
            FTBBackups.LOGGER.error("Error writing to backups.json", e);
        }
    }

    /**
     * Checks if a backup can be created based on current conditions (e.g., space, running state).
     *
     * @return True if a backup can be created, false otherwise.
     */
    public static boolean canCreateBackup() {
        FTBBackups.LOGGER.debug("Checking if backup can be created...");
        File worldFile = worldFolder.toFile();
        if (!worldFile.exists() || !worldFile.isDirectory()) {
            FTBBackups.LOGGER.warn("World folder does not exist or is not a directory: {}",
                    worldFile.getAbsolutePath());
            failReason = "Invalid world folder";
            return false;
        }

        if (backupFolderPath == null) {
            failReason = "backup folder path is null";
            FTBBackups.LOGGER.error("Backup folder path is null.");
            return false;
        }
        if (!backupFolderPath.toFile().exists()) {
            failReason = "backup folder does not exist";
            FTBBackups.LOGGER.error("Backup folder does not exist: {}", backupFolderPath);
            return false;
        }
        if (backupRunning.get()) {
            failReason = "Unable to start new backup as backup is already running";
            FTBBackups.LOGGER.info("Backup already running, cannot start new backup.");
            return false;
        }

        if (currentFuture != null) {
            failReason = "backup thread is somehow still running";
            FTBBackups.LOGGER.error("Backup thread is still running.");
            return false;
        }

        long minFreeSpace = Config.cached().minimum_free_space * 1000000L;
        long free = backupFolderPath.toFile().getUsableSpace() - minFreeSpace;
        long currentWorldSize = FileUtils.getFolderSize(worldFolder);
        for (String p : Config.cached().additional_files) {
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
                FTBBackups.LOGGER.error("Insufficient space for backup. World size: {}, Available: {}",
                        FileUtils.getSizeString(currentWorldSize), FileUtils.getSizeString(free));
                failReason = "not enough free space on device";
                isSpaceConstrained = true;
                return false;
            } else {
                expectedSize = currentWorldSize;
                FTBBackups.LOGGER.info("No previous backup. World size: {}, Available: {}",
                        FileUtils.getSizeString(currentWorldSize), FileUtils.getSizeString(free));
            }
        } else {
            long latestBackupSize = latestBackup.getSize();
            float ratio = latestBackup.getRatio();
            expectedSize = (long) (Math.ceil(currentWorldSize * ratio) * 1.05);
            FTBBackups.LOGGER.info("Last backup size: {}, World size: {}, Available: {}, Expected: {}",
                    FileUtils.getSizeString(latestBackupSize), FileUtils.getSizeString(currentWorldSize),
                    FileUtils.getSizeString(free), FileUtils.getSizeString(expectedSize));
            if (expectedSize > free) {
                failReason = "not enough free space on device";
                isSpaceConstrained = true;
                FTBBackups.LOGGER.error("Insufficient space for expected backup size.");
                return false;
            }
        }
        FTBBackups.LOGGER.debug("Backup can be created.");
        return true;
    }

    /**
     * Verifies and removes any backups that no longer exist on the file system.
     */
    public static void verifyOldBackups() {
        FTBBackups.LOGGER.debug("Verifying old backups...");
        if (backups == null) {
            FTBBackups.LOGGER.debug("Backups reference is null, nothing to verify.");
            return;
        }
        if (backups.get().isEmpty()) {
            FTBBackups.LOGGER.debug("No backups to verify.");
            return;
        }

        synchronized (BACKUP_LOCK) {
            List<Backup> toRemove = new ArrayList<>();
            for (Backup backup : backups.get().getBackups()) {
                try {
                    Path backupPath = Path.of(backup.getBackupLocation());
                    if (!Files.exists(backupPath)) {
                        FTBBackups.LOGGER.debug("Backup file missing: {}, marking for removal.", backupPath);
                        toRemove.add(backup);
                    }
                } catch (Exception e) {
                    FTBBackups.LOGGER.error("Error checking backup: {}", backup.getBackupLocation(), e);
                }
            }

            if (!toRemove.isEmpty()) {
                for (Backup backup : toRemove) {
                    removeBackup(backup);
                }
                updateJson();
                FTBBackups.LOGGER.info("Backups list updated: {} backups removed.", toRemove.size());
            } else {
                FTBBackups.LOGGER.debug("No changes needed after verification.");
            }
        }
    }

    /**
     * Creates the backup folder if it does not exist.
     *
     * @param path The path to the backup folder.
     */
    public static void createBackupFolder(Path path) {
        if (!Files.exists(path)) {
            boolean backupFolderCreated = path.toFile().mkdirs();
            if (backupFolderCreated) {
                FTBBackups.LOGGER.info("Created backup folder at: {}", path.toAbsolutePath());
            } else {
                FTBBackups.LOGGER.warn("Failed to create backup folder at: {}", path.toAbsolutePath());
            }
        } else {
            FTBBackups.LOGGER.debug("Backup folder already exists at: {}", path.toAbsolutePath());
        }
    }

    /**
     * Sets the noSave flag for all levels, controlling whether the world is saved.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param value           The value to set for noSave.
     */
    public static void setNoSave(MinecraftServer minecraftServer, boolean value) {
        FTBBackups.LOGGER.debug("Setting noSave flag to {} for all levels.", value);
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            if (level != null) {
                level.noSave = value;
                FTBBackups.LOGGER.debug("noSave set to {} for level: {}", value, level.dimension().location());
            }
        }
    }

    /**
     * Alerts players with a message, based on configuration settings.
     *
     * @param minecraftServer The Minecraft server instance.
     * @param message         The message to display.
     */
    public static void alertPlayers(MinecraftServer minecraftServer, Component message) {
        FTBBackups.LOGGER.debug("Alerting players with message: {}", message.getString());
        if (Config.cached().do_not_notify) {
            FTBBackups.LOGGER.debug("Notifications disabled, skipping alert.");
            return;
        }
        if (Config.cached().notify_op_only && minecraftServer instanceof DedicatedServer) {
            FTBBackups.LOGGER.debug("Notifying operators only.");
            for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
                if (player.hasPermissions(4)) {
                    player.displayClientMessage(message, false);
                    FTBBackups.LOGGER.debug("Notified operator: {}", player.getName().getString());
                }
            }
        } else {
            FTBBackups.LOGGER.debug("Notifying all players.");
            for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
                player.displayClientMessage(message, false);
                FTBBackups.LOGGER.debug("Notified player: {}", player.getName().getString());
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
        loadJson();
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
    private static void scheduleStatusCheck(Path backupPath, Format format, long expectedSize, long delay,
            TimeUnit unit) {
        FTBBackups.statusMonitorLogger.debug("Scheduling status check with delay: {} {}", delay, unit);
        FTBBackups.statusMonitorExecutorService.schedule(() -> {
            if (isRunning()) {
                long currentSize = getCurrentBackupSize(backupPath, format);
                int percentage = calculatePercentage(currentSize, expectedSize);
                FTBBackups.statusMonitorLogger.info("Backup in progress: {}% complete, Current size: {}", percentage,
                        FileUtils.getSizeString(currentSize));
                scheduleStatusCheck(backupPath, format, expectedSize, 30, TimeUnit.SECONDS);
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
        FTBBackups.LOGGER.debug("Calculating current backup size for: {}", backupPath);
        try {
            long size;
            if (format == Format.DIRECTORY) {
                size = FileUtils.getFolderSize(backupPath);
            } else {
                size = Files.size(backupPath);
            }
            FTBBackups.LOGGER.debug("Current backup size: {}", FileUtils.getSizeString(size));
            return size;
        } catch (IOException e) {
            FTBBackups.LOGGER.warn("Failed to get current backup size", e);
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

    private static class BackupSetup {
        final Path backupLocation;
        final CompletableFuture<Void> saveFuture;

        BackupSetup(Path backupLocation, CompletableFuture<Void> saveFuture) {
            this.backupLocation = backupLocation;
            this.saveFuture = saveFuture;
        }
    }
}