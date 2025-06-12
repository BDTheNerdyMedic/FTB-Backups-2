package net.creeperhost.ftbbackups;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.platform.Platform;
import net.creeperhost.ftbbackups.commands.BackupCommand;
import net.creeperhost.ftbbackups.config.Config;
import net.creeperhost.ftbbackups.config.ConfigData.LoggingLevel;
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
import java.text.ParseException;
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
    public static final Logger LOGGER = LogManager.getLogger("FTBBackups");
    public static final Logger backupCleanerLogger = LogManager.getLogger("FTBBackups.BackupCleaner");
    public static final Logger backupExecutorLogger = LogManager.getLogger("FTBBackups.BackupExecutor");
    public static final Logger statusMonitorLogger = LogManager.getLogger("FTBBackups.StatusMonitor");
    public static Path configFile = Platform.getConfigFolder().resolve(MOD_ID + ".json");

    public static final ScheduledExecutorService backupCleanerExecutorService = createScheduledExecutor(
            "Backup Cleaner", backupCleanerLogger);
    public static final ScheduledExecutorService statusMonitorExecutorService = createScheduledExecutor(
            "Status Monitor", statusMonitorLogger);
    public static final ExecutorService backupExecutor = createExecutor("Backup Executor", backupExecutorLogger);

    public static volatile MinecraftServer minecraftServer;
    public static volatile boolean isShutdown = false;
    public static Scheduler scheduler;

    /**
     * Initializes the FTB Backups mod, setting up configuration, logging, and scheduling tasks.
     */
    public static void init() {
        LOGGER.info("Starting FTB Backups initialization...");
        try {
            Config.init(configFile.toFile());
            LOGGER.debug("Configuration loaded from {}", configFile.toString());
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration from {}. Using default values.", configFile.toString(), e);
        }

        setLoggerLevel(LOGGER, Config.getConfigData().logging_level);
        setLoggerLevel(backupCleanerLogger, Config.getConfigData().logging_level);
        setLoggerLevel(backupExecutorLogger, Config.getConfigData().logging_level);
        setLoggerLevel(statusMonitorLogger, Config.getConfigData().logging_level);


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

        if (!CronExpression.isValidExpression(Config.getConfigData().backup_cron)) {
            LOGGER.error("Invalid backup_cron expression: {}. Restoring default value: '0 */30 * * * ?'.",
                    Config.getConfigData().backup_cron);
            Config.getConfigData().backup_cron = "0 */30 * * * ?";
            Config.save();
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
                    .withSchedule(CronScheduleBuilder.cronSchedule(Config.getConfigData().backup_cron))
                    .build();
            scheduler.start();
            scheduler.scheduleJob(jobDetail, trigger);
            LOGGER.info("Backup scheduler started with cron expression: {}", Config.getConfigData().backup_cron);
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
     * @param logger       The logger to configure.
     * @param loggingLevel The desired logging level as a LoggingLevel enum.
     */
    public static void setLoggerLevel(Logger logger, LoggingLevel loggingLevel) {
        try {
            Level level = Level.toLevel(loggingLevel.name());
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig loggerConfig = config.getLoggerConfig(logger.getName());
            loggerConfig.setLevel(level);
            ctx.updateLoggers();
            LOGGER.debug("Logging level set to: {} for logger: {}", level, logger.getName());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid logging level: {}. Defaulting to INFO for logger: {}", loggingLevel.name(), logger.getName());
            setLoggerLevel(logger, LoggingLevel.INFO);
        }
    }

    /**
    * Updates the backup schedule with a new cron expression.
    *
    * @param newCron The new cron expression to apply.
    * @throws SchedulerException If the scheduler fails to reschedule or the cron expression is invalid.
    */
    public static void updateBackupSchedule(String newCron) throws SchedulerException {
        if (scheduler == null || !scheduler.isStarted()) {
            LOGGER.warn("Scheduler is not running, cannot update backup schedule.");
            return;
        }
        TriggerKey triggerKey = TriggerKey.triggerKey(MOD_ID);
        try {
            CronTrigger newTrigger = TriggerBuilder.newTrigger()
                    .withIdentity(MOD_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(newCron))
                    .build();
            scheduler.rescheduleJob(triggerKey, newTrigger);
            LOGGER.info("Backup schedule updated to new cron: {}", newCron);
        } catch (ParseException e) {
            LOGGER.error("Failed to parse cron expression: {}", newCron, e);
            throw new SchedulerException("Invalid cron expression: " + newCron, e);
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

        // Shut down the Quartz scheduler
        if (scheduler != null) {
            try {
                scheduler.shutdown(true);
                LOGGER.debug("Quartz scheduler shutdown initiated.");
            } catch (SchedulerException e) {
                LOGGER.error("Error shutting down Quartz scheduler", e);
                try {
                    scheduler.shutdown(false);
                    LOGGER.debug("Quartz scheduler forcibly shut down.");
                } catch (SchedulerException ex) {
                    LOGGER.error("Failed to force shutdown Quartz scheduler", ex);
                }
            }
        }

        // Shut down all executors
        shutdownExecutor(backupCleanerExecutorService, "Backup Cleaner Executor");
        shutdownExecutor(backupExecutor, "Backup Executor");
        shutdownExecutor(statusMonitorExecutorService, "Status Monitor Executor");

        // Finalize shutdown tasks
        try {
            BackupHandler.backupRunning.set(false);
            BackupHandler.updateJson();
            LOGGER.info("Shutdown completed successfully.");
        } catch (Exception e) {
            LOGGER.error("Error finalizing shutdown tasks", e);
        }

        // Log active threads for debugging
        if (Config.getConfigData().verbose_logging) {
            LOGGER.debug("Checking for remaining active threads...");
            Thread.getAllStackTraces().keySet().forEach(
                    thread -> LOGGER.debug("Thread still active: {} (Daemon: {})", thread.getName(), thread.isDaemon()));
        }
    }

    /**
    * Shuts down an executor service gracefully, forcing termination if it doesn't stop within 5 seconds.
    *
    * @param executor The executor service to shut down.
    * @param name     The name of the executor for logging purposes.
    */
    private static void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("{} did not terminate within 5 seconds, forcing shutdown.", name);
                    executor.shutdownNow();
                } else {
                    LOGGER.debug("{} shut down successfully.", name);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                LOGGER.error("Interrupted while shutting down {}", name, e);
            }
        }
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
                        .setNameFormat(name + " %d")
                        .build());
    }
}