package net.creeperhost.ftbbackups;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.platform.Platform;
import net.creeperhost.ftbbackups.commands.BackupCommand;
import net.creeperhost.ftbbackups.config.Config;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main class for FTB Backups mod. Handles initialization, event registration, and scheduling of backup tasks.
 */
public class FTBBackups {
    public static final String MOD_ID = "ftbbackups2";
    public static final Logger LOGGER = LogManager.getLogger(FTBBackups.class);
    private static final Logger configWatcherLogger = LogManager.getLogger("FTBBackups.ConfigWatcher");
    private static final Logger backupCleanerLogger = LogManager.getLogger("FTBBackups.BackupCleaner");
    private static final Logger backupExecutorLogger = LogManager.getLogger("FTBBackups.BackupExecutor");
    public static final Logger statusMonitorLogger = LogManager.getLogger("FTBBackups.StatusMonitor");
    public static Path configFile = Platform.getConfigFolder().resolve(MOD_ID + ".json");

    public static final ScheduledExecutorService configWatcherExecutorService = createScheduledExecutor(
            "Config Watcher", configWatcherLogger);
    public static final ScheduledExecutorService backupCleanerExecutorService = createScheduledExecutor(
            "Backup Cleaner", backupCleanerLogger);
    public static final ScheduledExecutorService statusMonitorExecutorService = createScheduledExecutor(
            "Status Monitor", statusMonitorLogger);
    public static final ExecutorService backupExecutor = createExecutor("Backup Executor", backupExecutorLogger);

    public static MinecraftServer minecraftServer;
    public static Scheduler scheduler;
    public static boolean isShutdown = false;

    /**
     * Initializes the FTB Backups mod, setting up configuration, logging, and scheduling tasks.
     */
    public static void init() {
        LOGGER.info("Starting FTB Backups initialization...");
        Config.init(configFile.toFile());

        setLoggerLevel(LOGGER, Config.cached().logging_level);
        setLoggerLevel(configWatcherLogger, Config.cached().logging_level);
        setLoggerLevel(backupCleanerLogger, Config.cached().logging_level);
        setLoggerLevel(backupExecutorLogger, Config.cached().logging_level);
        setLoggerLevel(statusMonitorLogger, Config.cached().logging_level);

        LOGGER.debug("Configuration loaded from {}", configFile.toString());


        LOGGER.debug("Registering commands and events...");
        CommandRegistrationEvent.EVENT
                .register((dispatcher, registry, selection) -> dispatcher.register(BackupCommand.register()));
        LifecycleEvent.SERVER_STARTED.register(FTBBackups::serverStartedEvent);
        LifecycleEvent.SERVER_STOPPING.register(instance -> onShutdown());
        registerEvents();

        LOGGER.debug("Scheduling backup cleanup task...");
        FTBBackups.backupCleanerExecutorService.scheduleAtFixedRate(() -> {
            BackupHandler.clean();
        }, 30, 300, TimeUnit.SECONDS);

        if (!CronExpression.isValidExpression(Config.cached().backup_cron)) {
            LOGGER.error("Invalid backup_cron expression: {}. Restoring default value: '0 */30 * * * ?'.",
                    Config.cached().backup_cron);
            Config.cached().backup_cron = "0 */30 * * * ?";
            Config.saveConfig();
        }

        try {
            JobDetail jobDetail = JobBuilder.newJob(BackupJob.class).withIdentity(MOD_ID).build();
            Properties properties = new Properties();
            properties.put(StdSchedulerFactory.PROP_SCHED_INSTANCE_NAME, MOD_ID);
            properties.put("org.quartz.threadPool.threadCount", "1");
            properties.put("org.quartz.threadPool.makeThreadsDaemons", "true");
            properties.put(StdSchedulerFactory.PROP_SCHED_MAKE_SCHEDULER_THREAD_DAEMON, "true");
            SchedulerFactory schedulerFactory = new StdSchedulerFactory(properties);
            scheduler = schedulerFactory.getScheduler();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(MOD_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(Config.cached().backup_cron))
                    .build();
            scheduler.start();
            scheduler.scheduleJob(jobDetail, trigger);
            LOGGER.info("Backup scheduler started with cron expression: {}", Config.cached().backup_cron);
        } catch (Exception e) {
            LOGGER.error("Failed to start backup scheduler", e);
        }

        LOGGER.info("FTB Backups initialization completed.");
    }

    /**
     * Registers player-related events, such as marking backups as dirty when a player joins.
     */
    public static void registerEvents() {
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (!BackupHandler.isDirty()) {
                BackupHandler.setDirty(true);
                LOGGER.debug("Player {} joined, setting backup dirty flag.", player.getName().getString());
            }
        });
    }

    /**
     * Sets the logging level for a given logger.
     *
     * @param logger    The logger to configure.
     * @param levelName The desired logging level (e.g., "DEBUG", "INFO").
     */
    private static void setLoggerLevel(Logger logger, String levelName) {
        try {
            Level level = Level.toLevel(levelName);
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig loggerConfig = config.getLoggerConfig(logger.getName());
            loggerConfig.setLevel(level);
            ctx.updateLoggers();
            LOGGER.debug("Logging level set to: {} for logger: {}", level, logger.getName());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid logging level: {}. Defaulting to INFO for logger: {}", levelName, logger.getName());
            setLoggerLevel(logger, "INFO");
        }
    }

    /**
     * Handles server start events, initializing the backup handler and setting logger levels.
     *
     * @param minecraftServer The Minecraft server instance.
     */
    private static void serverStartedEvent(MinecraftServer minecraftServer) {
        FTBBackups.minecraftServer = minecraftServer;
        BackupHandler.init(minecraftServer);
        isShutdown = false;

        // setLoggerLevel(LOGGER, Config.cached().logging_level);
        // setLoggerLevel(configWatcherLogger, Config.cached().logging_level);
        // setLoggerLevel(backupCleanerLogger, Config.cached().logging_level);
        // setLoggerLevel(backupExecutorLogger, Config.cached().logging_level);

        LOGGER.info("Server started, backup handler initialized.");
    }

    /**
     * Handles mod shutdown, ensuring backups are not running and cleaning up resources.
     */
    public static void onShutdown() {
        if (isShutdown) {
            LOGGER.debug("Shutdown already in progress, skipping.");
            return;
        }

        LOGGER.info("Starting shutdown process for FTB Backups...");
        isShutdown = true;

        // Step 1: Shut down the Quartz scheduler
        if (scheduler != null) {
            try {
                if (!scheduler.isShutdown()) {
                    scheduler.shutdown(true);
                    LOGGER.debug("Quartz scheduler shutdown initiated.");
                    long startTime = System.currentTimeMillis();
                    while (!scheduler.isShutdown() && (System.currentTimeMillis() - startTime) < 2000) {
                        Thread.sleep(100);
                    }
                    if (scheduler.isShutdown()) {
                        LOGGER.debug("Quartz scheduler shut down successfully.");
                    } else {
                        LOGGER.warn("Quartz scheduler did not shut down within 2 seconds, forcing termination.");
                        try {
                            scheduler.shutdown(false);
                            if (scheduler.isShutdown()) {
                                LOGGER.debug("Quartz scheduler forcibly shut down successfully.");
                            } else {
                                LOGGER.error("Quartz scheduler failed to shut down even after forcing.");
                            }
                        } catch (SchedulerException ex) {
                            LOGGER.error("Failed to force shutdown Quartz scheduler", ex);
                        }
                    }
                } else {
                    LOGGER.debug("Quartz scheduler is already shut down.");
                }
            } catch (SchedulerException e) {
                LOGGER.error("Error shutting down Quartz scheduler", e);
                try {
                    scheduler.shutdown(false);
                    if (scheduler.isShutdown()) {
                        LOGGER.debug("Quartz scheduler forcibly shut down after error.");
                    } else {
                        LOGGER.error("Quartz scheduler failed to shut down even after forcing.");
                    }
                } catch (SchedulerException ex) {
                    LOGGER.error("Failed to force shutdown Quartz scheduler", ex);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("Thread interrupted while waiting for Quartz scheduler shutdown", e);
            }
        }

        // Step 2: Shut down the backup cleaner executor
        if (backupCleanerExecutorService != null && !backupCleanerExecutorService.isShutdown()) {
            try {
                backupCleanerExecutorService.shutdown();
                if (!backupCleanerExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Backup cleaner executor did not terminate within 5 seconds, forcing shutdown.");
                    backupCleanerExecutorService.shutdownNow();
                } else {
                    LOGGER.debug("Backup cleaner executor shut down successfully.");
                }
            } catch (InterruptedException e) {
                backupCleanerExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
                LOGGER.error("Interrupted while shutting down backup cleaner executor", e);
            }
        }

        // Step 3: Shut down the backup executor
        if (backupExecutor != null && !backupExecutor.isShutdown()) {
            try {
                backupExecutor.shutdown();
                if (!backupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Backup executor did not terminate within 5 seconds, forcing shutdown.");
                    backupExecutor.shutdownNow();
                } else {
                    LOGGER.debug("Backup executor shut down successfully.");
                }
            } catch (InterruptedException e) {
                backupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                LOGGER.error("Interrupted while shutting down backup executor", e);
            }
        }

        // Step 4: Shut down the status monitor executor
        if (statusMonitorExecutorService != null && !statusMonitorExecutorService.isShutdown()) {
            try {
                statusMonitorExecutorService.shutdown();
                if (!statusMonitorExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Status monitor executor did not terminate within 5 seconds, forcing shutdown.");
                    statusMonitorExecutorService.shutdownNow();
                } else {
                    LOGGER.debug("Status monitor executor shut down successfully.");
                }
            } catch (InterruptedException e) {
                statusMonitorExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
                LOGGER.error("Interrupted while shutting down status monitor executor", e);
            }
        }

        // Step 5: Close the WatchService
        if (Config.watcher.get() != null) {
            try {
                Config.watcher.get().close();
                LOGGER.debug("Config watcher closed successfully.");
            } catch (Exception e) {
                LOGGER.error("Error closing config watcher", e);
            }
        }

        // Step 6: Shut down the config watcher executor
        if (configWatcherExecutorService != null && !configWatcherExecutorService.isShutdown()) {
            try {
                configWatcherExecutorService.shutdown();
                if (!configWatcherExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Config watcher executor did not terminate within 5 seconds, forcing shutdown.");
                    configWatcherExecutorService.shutdownNow();
                } else {
                    LOGGER.debug("Config watcher executor shut down successfully.");
                }
            } catch (InterruptedException e) {
                configWatcherExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
                LOGGER.error("Interrupted while shutting down config watcher executor", e);
            }
        }

        // Step 7: Finalize shutdown tasks
        try {
            BackupHandler.backupRunning.set(false);
            BackupHandler.updateJson();
            LOGGER.info("Shutdown completed successfully.");
        } catch (Exception e) {
            LOGGER.error("Error finalizing shutdown tasks", e);
        }

        // Log active threads for debugging
        LOGGER.debug("Checking for remaining active threads...");
        Thread.getAllStackTraces().keySet().forEach(
                thread -> LOGGER.debug("Thread still active: " + thread.getName() + ", Daemon: " + thread.isDaemon()));
    }

    /**
     * Quartz job for scheduled backups.
     */
    public static class BackupJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            if (FTBBackups.minecraftServer != null && !FTBBackups.isShutdown) {
                LOGGER.info("Cron-triggered automatic backup started.");
                try {
                    BackupHandler.createBackup(FTBBackups.minecraftServer);
                    LOGGER.info("Backup job completed successfully.");
                } catch (Exception e) {
                    LOGGER.error("Error during backup job execution", e);
                }
            } else {
                LOGGER.debug("Backup job skipped: server not running or shutting down.");
            }
        }
    }

    /**
     * Creates a scheduled executor service with a custom thread name and exception handler.
     *
     * @param name   The name prefix for the executor threads.
     * @param logger The logger to handle uncaught exceptions.
     * @return A configured ScheduledExecutorService.
     */
    private static ScheduledExecutorService createScheduledExecutor(String name, Logger logger) {
        return Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setUncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception in {} thread", name, e))
                        .setNameFormat("FTB Backups " + name + " %d")
                        .build());
    }

    /**
     * Creates a single-threaded executor service with a custom thread name and exception handler.
     *
     * @param name   The name prefix for the executor thread.
     * @param logger The logger to handle uncaught exceptions.
     * @return A configured ExecutorService.
     */
    private static ExecutorService createExecutor(String name, Logger logger) {
        return Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setUncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception in {} thread", name, e))
                        .setNameFormat("FTB Backups " + name + " %d")
                        .build());
    }
}