package net.creeperhost.ftbbackups.config;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonGrammar;
import blue.endless.jankson.JsonObject;
import net.creeperhost.ftbbackups.FTBBackups;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the configuration file for FTB Backups, including loading, saving,
 * and watching for changes.
 */
public class Config {
    private static final Logger LOGGER = LogManager.getLogger(Config.class);
    private static AtomicReference<ConfigData> data = new AtomicReference<>();
    private static File lastFile;
    private static boolean configLoaded;
    private static volatile boolean pauseWatcher = false;
    private static Jankson gson = Jankson.builder().build();
    
    // Add the ReentrantLock
    private static final ReentrantLock configLock = new ReentrantLock();

    /**
     * Loads the configuration from the specified file, handling deprecated options.
     *
     * @param file The configuration file to load.
     * @return True if the configuration was loaded or updated, false if unchanged.
     */
    public static boolean loadFromFile(File file) {
        configLock.lock(); // Acquire the lock
        try {
            lastFile = file;
            try {
                LOGGER.debug("Attempting to load configuration from: {}", file.getAbsolutePath());
                JsonObject jObject = gson.load(file);
                ConfigData newData = gson.fromJson(jObject, ConfigData.class);

                // Handle deprecated "none" value for preview_dimension
                if ("none".equalsIgnoreCase(newData.preview_dimension)) {
                    newData.enable_preview = false;
                    newData.preview_dimension = "minecraft:overworld";
                    LOGGER.debug("Detected deprecated 'preview_dimension = none', setting 'enable_preview = false' and resetting to 'minecraft:overworld'");
                }

                // Serialize current data to JSON without comments for comparison
                String currentJson = data.get() != null ? gson.toJson(data.get()).toJson(JsonGrammar.COMPACT) : null;
                String newJson = gson.toJson(newData).toJson(JsonGrammar.COMPACT);

                if (currentJson == null || !currentJson.equals(newJson)) {
                    data.set(newData);
                    if (!isConfigLoaded()) {
                        saveConfigToFile(file); // Already within lock, just for consistency
                        LOGGER.info("Configuration file updated with defaults and comments at: {}", file.getAbsolutePath());
                    }
                    configLoaded = true;
                    LOGGER.debug("Configuration loaded successfully from: {}", file.getAbsolutePath());
                    return true;
                } else {
                    LOGGER.debug("Configuration unchanged, no reload needed.");
                    return false;
                }
            } catch (Exception e) {
                LOGGER.error("Error occurred while reading config file, loading defaults", e);
                data.set(new ConfigData());
                return true;
            }
        } finally {
            configLock.unlock(); // Release the lock
        }
    }

    /**
     * Checks if the configuration has been loaded.
     *
     * @return True if the configuration is loaded, false otherwise.
     */
    public static boolean isConfigLoaded() {
        return configLoaded;
    }

    /**
     * Saves the current configuration to the specified file.
     *
     * @param file The file to save the configuration to.
     */
    public static void saveConfigToFile(File file) {
        configLock.lock();
        try {
            try (FileOutputStream configOut = new FileOutputStream(file)) {
                IOUtils.write(Config.saveConfig(), configOut, Charset.defaultCharset());
                configOut.flush();
                LOGGER.debug("Configuration saved to: {}", file.getAbsolutePath());
            }
        } catch (Throwable e) {
            LOGGER.error("Error saving configuration to file", e);
        } finally {
            configLock.unlock();
        }
    }

    /**
     * Returns the currently loaded configuration data.
     *
     * @return The ConfigData instance.
     */
    public static ConfigData cached() {
        return data.get();
    }

    /**
     * Updates the configuration data.
     *
     * @param _data The new ConfigData to set.
     * @return The updated ConfigData.
     */
    public static synchronized ConfigData update(ConfigData _data) {
        data.set(_data);
        return data.get();
    }

    /**
     * Reloads the configuration from the last loaded file.
     *
     * @return True if the reload was successful, false otherwise.
     */
    public static synchronized boolean reload() {
        if (lastFile != null) {
            return loadFromFile(lastFile);
        }
        return false;
    }

    /**
     * Converts the current configuration data to a JSON string, ensuring deprecated values are not saved.
     *
     * @return The JSON representation of the configuration.
     */
    public static String saveConfig() {
        ConfigData conf = data.get();
        // Prevent saving "none" for preview_dimension; use enable_preview instead
        if (!conf.enable_preview && "none".equalsIgnoreCase(conf.preview_dimension)) {
            conf.preview_dimension = "minecraft:overworld";
            LOGGER.debug("Adjusted 'preview_dimension' to 'minecraft:overworld' since 'enable_preview' is false");
        }
        JsonElement elem = gson.toJson(conf);
        return elem.toJson(true, true);
    }

    /**
     * Saves the current configuration to the last loaded file, pausing the watcher to prevent reload loops.
     */
    public static void saveConfigWithPause() {
        configLock.lock();
        try {
            pauseWatcher = true;
            saveConfigToFile(lastFile);
        } finally {
            pauseWatcher = false;
            configLock.unlock();
        }
    }

    public static AtomicReference<WatchService> watcher = new AtomicReference<>();

    /**
     * Initializes the configuration, setting up file watching for automatic reloads.
     *
     * @param file The configuration file to use.
     */
    public static void init(File file) {
        if (lastFile == null)
            lastFile = file;
        try {
            // Define the config watcher task
            Runnable configWatcher = () -> {
                try {
                    if (watcher.get() == null) {
                        watcher.set(FileSystems.getDefault().newWatchService());
                        lastFile.toPath().getParent().register(watcher.get(), StandardWatchEventKinds.ENTRY_MODIFY);
                        LOGGER.info("Configuration file watcher initialized for: {}", lastFile.getParent());
                    }
                    WatchKey checker = watcher.get().take();
                    for (WatchEvent<?> event : checker.pollEvents()) {
                        if (FTBBackups.isShutdown) {
                            return;
                        }
                        Path changed = (Path) event.context();
                        if (changed.endsWith(lastFile.getName()) && isConfigLoaded()) {
                            if (pauseWatcher) {
                                continue;
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                LOGGER.debug("Config watcher sleep interrupted", e);
                            }
                            if (reload()) {
                                LOGGER.info("Config at {} has changed, reloaded!", lastFile.getAbsolutePath());
                            }
                        }
                    }
                    checker.reset();
                } catch (InterruptedException e) {
                    LOGGER.debug("Config watcher interrupted, exiting.");
                } catch (ClosedWatchServiceException e) {
                    LOGGER.debug("WatchService closed, exiting.");
                } catch (Exception e) {
                    LOGGER.error("Error in configuration file watcher", e);
                }
            };

            // Schedule the watcher with a 10-second initial delay, then every 90 seconds
            FTBBackups.configWatcherExecutorService.scheduleAtFixedRate(configWatcher, 10, 90, TimeUnit.SECONDS);
            LOGGER.debug("Configuration watcher scheduled with initial delay of 10 seconds and 90-second intervals.");

            // Load or create the config file as usual
            if (!file.exists()) {
                ConfigData configData = new ConfigData();
                data.set(configData);
                saveConfigToFile(file);
                LOGGER.info("New configuration file created with defaults at: {}", file.getAbsolutePath());
            } else {
                Config.loadFromFile(file);
            }
        } catch (Exception e) {
            LOGGER.error("Error during configuration initialization", e);
        }
    }
}