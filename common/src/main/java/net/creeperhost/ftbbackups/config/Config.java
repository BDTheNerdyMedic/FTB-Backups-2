package net.creeperhost.ftbbackups.config;

import blue.endless.jankson.api.SyntaxError;
import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonGrammar;
import blue.endless.jankson.JsonObject;
import net.creeperhost.ftbbackups.config.ConfigData.NotificationMode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the configuration for the mod, providing thread-safe methods to load, save,
 * and access configuration data. Uses Jankson for JSON serialization and deserialization.
 */
public class Config {
    private static final Logger LOGGER = LogManager.getLogger(Config.class);
    private static final AtomicReference<ConfigData> DATA = new AtomicReference<>();
    private static File lastFile;
    private static boolean configLoaded;
    private static final Jankson GSON = Jankson.builder().build();
    private static final ReentrantLock CONFIG_LOCK = new ReentrantLock();

    public enum LoadResult {
        UPDATED,
        UNCHANGED,
        FAILED
    }

    /**
     * Initializes the configuration by loading it from the specified file or creating a default
     * configuration if the file does not exist.
     *
     * @param file The configuration file to load or create.
     */
    public static void init(File file) {
        LOGGER.debug("Initializing configuration with file: {}", file.getAbsolutePath());
        lastFile = file;
        if (!file.exists()) {
            createDefaultConfig(file);
        } else {
            loadFromFile(file);
        }
    }

    /**
     * Loads the configuration from the specified file, or the last used file if none is provided.
     * Handles deprecated options and updates the configuration if changed.
     * 
     * @param file The file to load from, or null to use the last loaded file.
     * @return A LoadResult indicating whether the configuration was updated, unchanged, or failed to load.
     */
    public static LoadResult loadFromFile(File file) {
        CONFIG_LOCK.lock();
        try {
            File targetFile = (file != null) ? file : lastFile;
            if (targetFile == null) {
                LOGGER.error("No configuration file specified for loading.");
                return LoadResult.FAILED;
            }
            lastFile = targetFile;
            LOGGER.debug("Loading configuration from: {}", targetFile.getAbsolutePath());
    
            JsonObject jObject = GSON.load(targetFile);
            ConfigData newData = GSON.fromJson(jObject, ConfigData.class);
            adjustDeprecatedOptions(newData);
    
            String currentJson = (DATA.get() != null) ? GSON.toJson(DATA.get()).toJson(JsonGrammar.COMPACT) : null;
            String newJson = GSON.toJson(newData).toJson(JsonGrammar.COMPACT);
    
            if (currentJson == null || !currentJson.equals(newJson)) {
                DATA.set(newData);
                if (!configLoaded) {
                    saveToFile(targetFile);
                    LOGGER.info("Configuration updated with defaults at: {}", targetFile.getAbsolutePath());
                }
                configLoaded = true;
                LOGGER.info("Configuration loaded successfully from: {}", targetFile.getAbsolutePath());
                return LoadResult.UPDATED;
            } else {
                LOGGER.debug("Configuration unchanged, no reload needed.");
                return LoadResult.UNCHANGED;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load configuration from file: {}", file != null ? file.getAbsolutePath() : "null", e);
            DATA.set(new ConfigData());
            configLoaded = true;
            return LoadResult.UPDATED;
        } catch (Exception e) {
            LOGGER.error("Unexpected error loading configuration", e);
            return LoadResult.FAILED;
        } finally {
            CONFIG_LOCK.unlock();
        }
    }

    /**
     * Checks if the configuration has been successfully loaded.
     *
     * @return True if the configuration is loaded, false otherwise.
     */
    public static boolean isConfigLoaded() {
        return configLoaded;
    }

    /**
     * Saves the current configuration to the specified file using atomic file replacement.
     *
     * @param file The file to save the configuration to.
     */
    public static void saveToFile(File file) {
        CONFIG_LOCK.lock();
        try {
            LOGGER.debug("Saving configuration to: {}", file.getAbsolutePath());
            Path tempFile = Files.createTempFile(file.toPath().getParent(), "config_temp", ".json");
            try (FileOutputStream out = new FileOutputStream(tempFile.toFile())) {
                IOUtils.write(serializeToJson(), out, Charset.defaultCharset());
                out.flush();
            }
            Files.move(tempFile, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("Configuration saved successfully to: {}", file.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration to file: {}", file.getAbsolutePath(), e);
        } finally {
            CONFIG_LOCK.unlock();
        }
    }

    /**
     * Saves the current configuration to the last loaded file, if available.
     *
     * @return True if the save was successful, false if no file was previously loaded.
     */
    public static boolean save() {
        if (lastFile == null) {
            LOGGER.error("Cannot save configuration: no file has been loaded.");
            return false;
        }
        saveToFile(lastFile);
        return true;
    }

    /**
     * Retrieves the current configuration data.
     *
     * @return The current ConfigData instance, or null if not loaded.
     */
    public static ConfigData getConfigData() {
        return DATA.get();
    }

    /**
     * Updates the current configuration data with a new instance.
     *
     * @param newData The new configuration data to set.
     * @return The updated ConfigData instance.
     */
    public static ConfigData update(ConfigData newData) {
        CONFIG_LOCK.lock();
        try {
            DATA.set(newData);
            LOGGER.debug("Configuration data updated.");
            return DATA.get();
        } finally {
            CONFIG_LOCK.unlock();
        }
    }

    /**
     * Reloads the configuration from the last loaded file.
     * 
     * @return A LoadResult indicating the outcome of the reload operation.
     */
    public static LoadResult reload() {
        return loadFromFile(null);
    }

    /**
     * Serializes the current configuration data to a JSON string, adjusting for deprecated options.
     *
     * @return The JSON string representation of the configuration.
     */
    public static String serializeToJson() {
        CONFIG_LOCK.lock();
        try {
            ConfigData config = DATA.get();
            if (config == null) {
                LOGGER.warn("No configuration data to serialize.");
                return "{}";
            }
            adjustDeprecatedOptions(config);
            JsonElement elem = GSON.toJson(config);
            return elem.toJson(true, true);
        } finally {
            CONFIG_LOCK.unlock();
        }
    }

    // Private helper methods

    /**
     * Creates a default configuration and saves it to the specified file.
     *
     * @param file The file to create and save the default configuration to.
     */
    private static void createDefaultConfig(File file) {
        CONFIG_LOCK.lock();
        try {
            LOGGER.debug("Creating default configuration at: {}", file.getAbsolutePath());
            DATA.set(new ConfigData());
            saveToFile(file);
            configLoaded = true;
            LOGGER.info("New configuration file created with defaults at: {}", file.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Failed to create default configuration", e);
        } finally {
            CONFIG_LOCK.unlock();
        }
    }

    /**
     * Adjusts deprecated configuration options to maintain compatibility.
     *
     * @param config The ConfigData instance to adjust.
     */
    private static void adjustDeprecatedOptions(ConfigData config) {
        try {
            JsonObject jObject = GSON.load(lastFile);
    
            // Map old notification options to notification_mode
            if (jObject.containsKey("do_not_notify")) {
                boolean doNotNotify = jObject.getBoolean("do_not_notify", false);
                if (doNotNotify) {
                    config.notification_mode = NotificationMode.NONE;
                } else if (jObject.containsKey("notify_op_only")) {
                    boolean notifyOpOnly = jObject.getBoolean("notify_op_only", true);
                    config.notification_mode = notifyOpOnly ? NotificationMode.OPS_ONLY : NotificationMode.ALL_PLAYERS;
                } else {
                    config.notification_mode = NotificationMode.ALL_PLAYERS;
                }
                LOGGER.debug("Mapped old notification options to notification_mode: {}", config.notification_mode);
            }
    
            // Existing preview_dimension adjustment
            if ("none".equalsIgnoreCase(config.preview_dimension)) {
                config.enable_preview = false;
                config.preview_dimension = "minecraft:overworld";
                LOGGER.debug(
                        "Adjusted deprecated 'preview_dimension' from 'none' to 'minecraft:overworld' and disabled 'enable_preview'.");
            }

            // Migrate additional_files to additional_paths
            if (jObject.containsKey("additional_files")) {
                @SuppressWarnings("unchecked")
                List<String> files = (List<String>) jObject.get(List.class, "additional_files");
                if (files != null) {
                    config.additional_paths.addAll(files);
                    LOGGER.info("Migrated 'additional_files' to 'additional_paths'");
                }
                jObject.remove("additional_files");
            }
    
            // Migrate additional_directories to additional_paths
            if (jObject.containsKey("additional_directories")) {
                @SuppressWarnings("unchecked")
                List<String> directories = (List<String>) jObject.get(List.class, "additional_directories");
                if (directories != null) {
                    config.additional_paths.addAll(directories);
                    LOGGER.info("Migrated 'additional_directories' to 'additional_paths'");
                }
                jObject.remove("additional_directories");
            }
    
            // Migrate excluded to excluded_paths
            if (jObject.containsKey("excluded")) {
                @SuppressWarnings("unchecked")
                List<String> excluded = (List<String>) jObject.get(List.class, "excluded");
                if (excluded != null) {
                    config.excluded_paths.addAll(excluded);
                    LOGGER.info("Migrated 'excluded' to 'excluded_paths'");
                }
                jObject.remove("excluded");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load configuration file for deprecated option adjustment: {}", lastFile.getAbsolutePath(), e);
            // Optionally, set default values or proceed without adjustments
        } catch (SyntaxError e) {
            LOGGER.error("Syntax error in configuration file: {}", lastFile.getAbsolutePath(), e);
            // Optionally, set default values or proceed without adjustments
        }
    }
}