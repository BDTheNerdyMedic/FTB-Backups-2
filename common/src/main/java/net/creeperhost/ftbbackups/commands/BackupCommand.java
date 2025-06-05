package net.creeperhost.ftbbackups.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.creeperhost.ftbbackups.BackupHandler;
import net.creeperhost.ftbbackups.FTBBackups;
import net.creeperhost.ftbbackups.config.Config;
import net.creeperhost.ftbbackups.config.ConfigData;
import net.creeperhost.ftbbackups.config.ConfigData.Format;
import net.creeperhost.ftbbackups.config.ConfigData.LoggingLevel;
import net.creeperhost.ftbbackups.config.ConfigData.NotificationMode;
import net.creeperhost.ftbbackups.config.ConfigData.RetentionMode;
import net.creeperhost.ftbbackups.data.Backup;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class BackupCommand {
    public static long lastManualBackupTime = 0;

    public static final SuggestionProvider<CommandSourceStack> SUGGESTIONS = (commandContext, suggestionsBuilder) -> {
        String[] strings = new String[]{"start", "snapshot", "status", "config"};
        return SharedSuggestionProvider.suggest(strings, suggestionsBuilder);
    };

    private static final Map<String, ConfigOption<?>> CONFIG_OPTIONS = new HashMap<>();
    private static final ConcurrentHashMap<UUID, String> pendingDeletions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<Backup>> userSnapshots = new ConcurrentHashMap<>();

    static {
        CONFIG_OPTIONS.put("enabled", new ConfigOption<>("enabled", boolean.class, config -> config.enabled, (config, value) -> config.enabled = Boolean.parseBoolean(value)));
        CONFIG_OPTIONS.put("command_permission_level", new ConfigOption<>("command_permission_level", int.class, config -> config.command_permission_level, (config, value) -> config.command_permission_level = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("notification_mode", new ConfigOption<>("notification_mode", NotificationMode.class, config -> config.notification_mode, (config, value) -> config.notification_mode = NotificationMode.valueOf(value.toUpperCase())));
        CONFIG_OPTIONS.put("enable_console_progress", new ConfigOption<>("enable_console_progress", boolean.class, config -> config.enable_console_progress, (config, value) -> config.enable_console_progress = Boolean.parseBoolean(value)));
        CONFIG_OPTIONS.put("logging_level", new ConfigOption<>("logging_level", LoggingLevel.class, config -> config.logging_level, (config, value) -> config.logging_level = LoggingLevel.valueOf(value.toUpperCase())));
        CONFIG_OPTIONS.put("verbose_logging", new ConfigOption<>("verbose_logging", boolean.class, config -> config.verbose_logging, (config, value) -> config.verbose_logging = Boolean.parseBoolean(value)));
        CONFIG_OPTIONS.put("retention_mode", new ConfigOption<>("retention_mode", RetentionMode.class, config -> config.retention_mode, (config, value) -> config.retention_mode = RetentionMode.valueOf(value.toUpperCase())));
        CONFIG_OPTIONS.put("max_backups", new ConfigOption<>("max_backups", int.class, config -> config.max_backups, (config, value) -> config.max_backups = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("keep_latest", new ConfigOption<>("keep_latest", int.class, config -> config.keep_latest, (config, value) -> config.keep_latest = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("keep_hourly", new ConfigOption<>("keep_hourly", int.class, config -> config.keep_hourly, (config, value) -> config.keep_hourly = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("keep_daily", new ConfigOption<>("keep_daily", int.class, config -> config.keep_daily, (config, value) -> config.keep_daily = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("keep_weekly", new ConfigOption<>("keep_weekly", int.class, config -> config.keep_weekly, (config, value) -> config.keep_weekly = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("keep_monthly", new ConfigOption<>("keep_monthly", int.class, config -> config.keep_monthly, (config, value) -> config.keep_monthly = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("backup_cron", new ConfigOption<>("backup_cron", String.class, config -> config.backup_cron, (config, value) -> config.backup_cron = value));
        CONFIG_OPTIONS.put("manual_backups_time", new ConfigOption<>("manual_backups_time", int.class, config -> config.manual_backups_time, (config, value) -> config.manual_backups_time = Integer.parseInt(value)));
        CONFIG_OPTIONS.put("only_if_players_been_online", new ConfigOption<>("only_if_players_been_online", boolean.class, config -> config.only_if_players_been_online, (config, value) -> config.only_if_players_been_online = Boolean.parseBoolean(value)));
        CONFIG_OPTIONS.put("additional_paths", new ConfigOption<>("additional_paths", List.class, config -> config.additional_paths, (config, value) -> config.additional_paths = Arrays.asList(value.split(","))));
        CONFIG_OPTIONS.put("excluded_paths", new ConfigOption<>("excluded_paths", List.class, config -> config.excluded_paths, (config, value) -> config.excluded_paths = Arrays.asList(value.split(","))));
        CONFIG_OPTIONS.put("backup_location", new ConfigOption<>("backup_location", String.class, config -> config.backup_location, (config, value) -> config.backup_location = value));
        CONFIG_OPTIONS.put("backup_format", new ConfigOption<>("backup_format", Format.class, config -> config.backup_format, (config, value) -> config.backup_format = Format.valueOf(value.toUpperCase())));
        CONFIG_OPTIONS.put("minimum_free_space", new ConfigOption<>("minimum_free_space", long.class, config -> config.minimum_free_space, (config, value) -> config.minimum_free_space = Long.parseLong(value)));
        CONFIG_OPTIONS.put("free_space_if_needed", new ConfigOption<>("free_space_if_needed", boolean.class, config -> config.free_space_if_needed, (config, value) -> config.free_space_if_needed = Boolean.parseBoolean(value)));
        CONFIG_OPTIONS.put("remove_incomplete_backups", new ConfigOption<>("remove_incomplete_backups", boolean.class, config -> config.remove_incomplete_backups, (config, value) -> config.remove_incomplete_backups = Boolean.parseBoolean(value)));
        CONFIG_OPTIONS.put("enable_preview", new ConfigOption<>("enable_preview", boolean.class, config -> config.enable_preview, (config, value) -> config.enable_preview = Boolean.parseBoolean(value)));
        CONFIG_OPTIONS.put("preview_dimensions_list", new ConfigOption<>("preview_dimensions_list", List.class, config -> config.preview_dimensions_list, (config, value) -> config.preview_dimensions_list = Arrays.asList(value.split(","))));
    }

    public static final SuggestionProvider<CommandSourceStack> CONFIG_VALUE_SUGGESTIONS = (context, builder) -> {
        String option = context.getArgument("option", String.class);
        ConfigOption<?> configOption = CONFIG_OPTIONS.get(option);
        if (configOption != null) {
            if (configOption.type == boolean.class) {
                return SharedSuggestionProvider.suggest(new String[]{"true", "false"}, builder);
            } else if (configOption.type.isEnum()) {
                return SharedSuggestionProvider.suggest(Arrays.stream(configOption.type.getEnumConstants()).map(Object::toString), builder);
            } else if (configOption.type == List.class) {
                ConfigData config = Config.getConfigData();
                List<String> currentList = (List<String>) configOption.getter.apply(config);
                List<String> suggestions = new ArrayList<>();
                suggestions.add("add ");
                for (String item : currentList) {
                    suggestions.add("remove " + item);
                }
                return SharedSuggestionProvider.suggest(suggestions, builder);
            }
        }
        return SharedSuggestionProvider.suggest(new String[0], builder);
    };

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("backup")
                .requires(cs -> cs.hasPermission(hasPerm(cs.getServer())))
                .then(Commands.argument("command", StringArgumentType.string()).suggests(SUGGESTIONS)
                        .executes(cs -> execute(cs, StringArgumentType.getString(cs, "command"), ""))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(cs -> execute(cs, StringArgumentType.getString(cs, "command"), StringArgumentType.getString(cs, "name")))
                        )
                )
                .then(Commands.literal("status")
                        .executes(BackupCommand::status))
                .then(Commands.literal("config")
                        .then(Commands.literal("get")
                                .then(Commands.argument("option", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(CONFIG_OPTIONS.keySet(), builder))
                                        .executes(context -> getConfig(context, StringArgumentType.getString(context, "option")))
                                )
                        )
                        .then(Commands.literal("set")
                                .then(Commands.argument("option", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(CONFIG_OPTIONS.keySet(), builder))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .suggests(CONFIG_VALUE_SUGGESTIONS)
                                                .executes(context -> setConfig(context, StringArgumentType.getString(context, "option"), StringArgumentType.getString(context, "value")))
                                        )
                                )
                        )
                        .then(Commands.literal("reload")
                                .executes(BackupCommand::reloadConfig))
                        .then(Commands.literal("help")
                                .executes(BackupCommand::configHelp))
                )
                .then(Commands.literal("list")
                        .executes(BackupCommand::listBackups))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(context -> removeBackup(context, IntegerArgumentType.getInteger(context, "index")))))
                        .then(Commands.literal("confirm")
                                .executes(BackupCommand::confirmRemove));
    }

    public static int hasPerm(MinecraftServer server) {
        if (server.isDedicatedServer() || (server.isSingleplayer() && server.isPublished())) {
            return Config.getConfigData().command_permission_level;
        }
        return 0;
    }

    private static int execute(CommandContext<CommandSourceStack> cs, String command, String name) {
        boolean isProtected = command.toLowerCase(Locale.ROOT).equals("snapshot");
        int manualBackupsTime = Config.getConfigData().manual_backups_time;

        if (manualBackupsTime > 0) {
            long configTimeFromMinutes = ((long) manualBackupsTime) * 60_000;
            long lastBackupWithConfig = lastManualBackupTime + configTimeFromMinutes;
            if (System.currentTimeMillis() <= lastBackupWithConfig) {
                cs.getSource().sendFailure(
                        Component.literal("Unable to create backup, last manual backup was taken less than "
                                + manualBackupsTime + " minutes ago"));
                return 0;
            }
        }

        if (manualBackupsTime > 0) {
            lastManualBackupTime = System.currentTimeMillis();
        }

        BackupHandler.setDirty(true);
        BackupHandler.createBackup(cs.getSource().getServer(), isProtected, name);
        return 0;
    }
    
    private static int listBackups(CommandContext<CommandSourceStack> context) {
        List<Backup> backupList = new ArrayList<>(BackupHandler.backups.get().getBackups());
        if (backupList.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No backups available."), false);
            return 1;
        }

        backupList.sort(Comparator.comparingLong(Backup::getCreateTime).reversed());

        // Safely determine UUID: use player UUID if available, otherwise use a default for console
        UUID playerUUID = (context.getSource().getPlayer() != null) 
            ? context.getSource().getPlayer().getUUID() 
            : new UUID(0, 0);
        userSnapshots.put(playerUUID, new ArrayList<>(backupList));

        // Header with colors
        String header = "§fIndex. §eName §b(Created, §aSize, §dComplete, §6Protected§b)";
        context.getSource().sendSuccess(() -> Component.literal("§6Backups:"), false);
        context.getSource().sendSuccess(() -> Component.literal(header), false);
        context.getSource().sendSuccess(() -> Component.literal("§7----------------------------------------"), false);

        for (int i = 0; i < backupList.size(); i++) {
            Backup backup = backupList.get(i);
            String index = "§f" + (i + 1) + "§r";
        String name = backup.getBackupName() != null && !backup.getBackupName().isEmpty() 
            ? "§e" + truncate(backup.getBackupName(), 15) + "§r" 
            : "§eUnnamed§r";
            String created = "§b" + formatTime(backup.getCreateTime()) + "§r";
            String size = "§a" + formatSize(backup.getSize()) + "§r";
            String complete = backup.isComplete() ? "§dYes§r" : "§dNo§r";
            String snapshot = backup.isProtected() ? "§6Yes§r" : "§6No§r";

        String message = String.format("%s. %-15s (%s, %s, %s, %s)", index, name, created, size, complete, snapshot);
            context.getSource().sendSuccess(() -> Component.literal(message), false);
        }
        return 1;
    }

    private static String truncate(String str, int length) {
        if (str.length() <= length) {
            return str;
        } else {
            return str.substring(0, length - 3) + "...";
        }
    }

    private static String formatTime(long time) {
        Instant instant = Instant.ofEpochMilli(time);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        return zdt.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH));
    }

    private static String formatSize(long size) {
        if (size >= 1_000_000_000) {
            return String.format("%5.1fG", (double) size / 1_000_000_000.0); // e.g., " 1.5G"
        } else if (size >= 1_000_000) {
            return String.format("%3dM", size / 1_000_000); // e.g., "150M"
        } else if (size >= 1_000) {
            return String.format("%3dK", size / 1_000); // e.g., " 50K"
        } else {
            return String.format("%3dB", size); // e.g., "500B"
        }
    }

    private static int removeBackup(CommandContext<CommandSourceStack> context, int index) {
        UUID playerUUID = (context.getSource().getPlayer() != null) 
            ? context.getSource().getPlayer().getUUID() 
            : new UUID(0, 0);
        List<Backup> snapshot = userSnapshots.get(playerUUID);
        if (snapshot == null || index < 1 || index > snapshot.size()) {
            context.getSource().sendFailure(Component.literal("Invalid backup index: " + index + ". Please run '/backup list' first."));
            return 0;
        }

        Backup backup = snapshot.get(index - 1);
        String uniqueId = generateUniqueId(backup);
        pendingDeletions.put(playerUUID, uniqueId);

        // Schedule timeout for pending deletion
        FTBBackups.statusMonitorExecutorService.schedule(() -> {
            pendingDeletions.remove(playerUUID);
        }, 60, TimeUnit.SECONDS);

        // Send confirmation message
        String message = String.format(
                "§cYou are about to remove backup §f%d§r: §e%s§r\n" +
                        "§bCreated: %s§r\n" +
                        "§aSize: %s§r\n" +
                        "§dComplete: %s§r\n" +
                        "§6Protected: %s§r\n" +
                        "§cRun §e/backup confirm §cto confirm deletion.§r",
                index, backup.getBackupName(), formatTime(backup.getCreateTime()), formatSize(backup.getSize()),
                backup.isComplete() ? "Yes" : "No", backup.isProtected() ? "Yes" : "No");
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
    
    private static int confirmRemove(CommandContext<CommandSourceStack> context) {
        UUID playerUUID = (context.getSource().getPlayer() != null) 
            ? context.getSource().getPlayer().getUUID() 
            : new UUID(0, 0);
        String uniqueId = pendingDeletions.remove(playerUUID);
        if (uniqueId == null) {
            context.getSource().sendFailure(Component.literal("No pending deletion to confirm."));
            return 0;
        }
    
        List<Backup> backupList = BackupHandler.backups.get().getBackups();
        Optional<Backup> backupOpt = backupList.stream()
            .filter(b -> generateUniqueId(b).equals(uniqueId))
            .findFirst();
    
        if (backupOpt.isPresent()) {
            BackupHandler.deleteBackup(backupOpt.get());
            context.getSource().sendSuccess(() -> Component.literal("Backup removed successfully."), false);
            FTBBackups.LOGGER.info("Player {} removed backup with ID: {}", 
                context.getSource().getPlayer() != null ? context.getSource().getPlayer().getName().getString() : "Console", 
                uniqueId);
        } else {
            context.getSource().sendFailure(Component.literal("Backup not found."));
        }
        return 1;
    }

    private static String generateUniqueId(Backup backup) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");

            String data = backup.getCreateTime() + backup.getBackupLocation() +
                    backup.getSize() + backup.getSha1();

            byte[] hashBytes = digest.digest(data.getBytes());

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not found", e);
        }
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ConfigData config = Config.getConfigData();
        StringBuilder messageBuilder = new StringBuilder();
    
        messageBuilder.append(config.enabled ? "§aBackups Enabled§r\n" : "§cBackups Disabled§r\n");
    
        // Last backup time
        Backup latestBackup = BackupHandler.getLatestBackup();
        if (latestBackup != null) {
            long createTime = latestBackup.getCreateTime();
            Instant instant = Instant.ofEpochMilli(createTime);
            ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
            String formattedTime = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long timeSince = System.currentTimeMillis() - createTime;
            String timeSinceStr = formatDuration(timeSince);
            messageBuilder.append("§eLast backup:§r ").append(formattedTime).append(" §7(").append(timeSinceStr).append(" ago)§r\n");
        } else {
            messageBuilder.append("§cNo backups available§r\n");
        }
    
        // Next backup time (if enabled)
        if (config.enabled) {
            try {
                Date nextFireTime = FTBBackups.scheduler.getTrigger(TriggerKey.triggerKey(FTBBackups.MOD_ID)).getNextFireTime();
                if (nextFireTime != null) {
                    long timeUntil = nextFireTime.getTime() - System.currentTimeMillis();
                    String timeUntilStr = timeUntil > 0 ? "in " + formatDuration(timeUntil) : "now";
                    messageBuilder.append("§eNext backup:§r ").append(timeUntilStr).append("\n");
                } else {
                    messageBuilder.append("§cNo scheduled backups§r\n");
                }
            } catch (SchedulerException e) {
                messageBuilder.append("§cError retrieving next backup time§r\n");
            }
            // Dirty status
            if (config.only_if_players_been_online) {
                boolean isDirty = BackupHandler.isDirty();
                messageBuilder.append("§eServer marked for backup:§r ").append(isDirty ? "§aYes§r" : "§cNo§r").append("\n");
            }
        }
    
        context.getSource().sendSuccess(() -> Component.literal(messageBuilder.toString()), false);
        return 0;
    }

    private static String formatDuration(long millis) {
        if (millis <= 0) return "now";
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) return days + " days";
        else if (hours > 0) return hours + " hours";
        else if (minutes > 0) return minutes + " minutes";
        else return seconds + " seconds";
    }

    private static int getConfig(CommandContext<CommandSourceStack> context, String optionName) {
        ConfigOption<?> option = CONFIG_OPTIONS.get(optionName);
        if (option == null) {
            context.getSource().sendFailure(Component.literal("Unknown config option: " + optionName));
            return 0;
        }
        ConfigData config = Config.getConfigData();
        Object value = option.getter.apply(config);
        final String valueStr;
        if (option.type == List.class) {
            @SuppressWarnings("unchecked")
            List<String> listValue = (List<String>) value;
            valueStr = String.join(", ", listValue);
        } else {
            valueStr = value.toString();
        }
        context.getSource().sendSuccess(() -> Component.literal(optionName + ": " + valueStr), false);
        return 1;
    }

    private static int setConfig(CommandContext<CommandSourceStack> context, String optionName, String valueStr) {
        ConfigOption<?> option = CONFIG_OPTIONS.get(optionName);
        if (option == null) {
            context.getSource().sendFailure(Component.literal("Unknown config option: " + optionName));
            return 0;
        }

        ConfigData config = Config.getConfigData();
        try {
            if (option.type == List.class) {
                // Get the current list
                @SuppressWarnings("unchecked")
                List<String> currentList = new ArrayList<>((List<String>) option.getter.apply(config));

                // Split the input into action and value parts
                String[] parts = valueStr.split(" ", 2);
                String action = parts[0].toLowerCase();
                String value = parts.length > 1 ? parts[1] : "";

                if (action.equals("add")) {
                    if (value.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("No value provided to add."));
                        return 0;
                    }
                    currentList.add(value.trim());
                    option.setter.accept(config, String.join(",", currentList));
                    context.getSource().sendSuccess(() -> Component.literal("Added " + value + " to " + optionName), false);
                } else if (action.equals("remove")) {
                    if (value.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("No value provided to remove."));
                        return 0;
                    }
                    if (!currentList.remove(value.trim())) {
                        context.getSource().sendFailure(Component.literal("Value not found in " + optionName + ": " + value));
                        return 0;
                    }
                    option.setter.accept(config, String.join(",", currentList));
                    context.getSource().sendSuccess(() -> Component.literal("Removed " + value + " from " + optionName), false);
                } else {
                    // Treat the entire valueStr as a comma-separated list
                    String[] items = valueStr.split(",");
                    List<String> newList = new ArrayList<>();
                    for (String item : items) {
                        String trimmed = item.trim();
                        if (!trimmed.isEmpty()) {
                            newList.add(trimmed);
                        }
                    }
                    option.setter.accept(config, String.join(",", newList));
                    context.getSource().sendSuccess(() -> Component.literal("Set " + optionName + " to " + String.join(", ", newList)), false);
                }
            } else {
                // Handle non-list options as before
                if (option.type == boolean.class) {
                    String lowerValue = valueStr.toLowerCase();
                    if (!lowerValue.equals("true") && !lowerValue.equals("false")) {
                        context.getSource().sendFailure(Component.literal("Invalid boolean value: " + valueStr + ". Use 'true' or 'false'."));
                        return 0;
                    }
                    option.setter.accept(config, lowerValue);
                } else if (option.type == int.class) {
                    int intValue = Integer.parseInt(valueStr);
                    option.setter.accept(config, String.valueOf(intValue));
                } else if (option.type == long.class) {
                    long longValue = Long.parseLong(valueStr);
                    option.setter.accept(config, String.valueOf(longValue));
                } else if (option.type == String.class) {
                    option.setter.accept(config, valueStr);
                } else if (option.type == LoggingLevel.class) {
                    LoggingLevel logging = LoggingLevel.valueOf(valueStr.toUpperCase());
                    option.setter.accept(config, logging.name());
                } else if (option.type == NotificationMode.class) {
                    NotificationMode notify = NotificationMode.valueOf(valueStr.toUpperCase());
                    option.setter.accept(config, notify.name());
                } else if (option.type == RetentionMode.class) {
                    RetentionMode mode = RetentionMode.valueOf(valueStr.toUpperCase());
                    option.setter.accept(config, mode.name());
                } else if (option.type == Format.class) {
                    Format format = Format.valueOf(valueStr.toUpperCase());
                    option.setter.accept(config, format.name());
                } else {
                    context.getSource().sendFailure(Component.literal("Unsupported config type for " + optionName));
                    return 0;
                }
                context.getSource().sendSuccess(() -> Component.literal("Set " + optionName + " to " + valueStr), false);
            }

            // Save the updated configuration
            Config.update(config);
            Config.save();

            // Handle special cases
            if (optionName.equals("backup_cron")) {
                FTBBackups.updateBackupSchedule(valueStr);
            }
            if (optionName.equals("logging_level")) {
                FTBBackups.setLoggerLevel(FTBBackups.LOGGER, config.logging_level);
                FTBBackups.setLoggerLevel(FTBBackups.backupCleanerLogger, config.logging_level);
                FTBBackups.setLoggerLevel(FTBBackups.backupExecutorLogger, config.logging_level);
                FTBBackups.setLoggerLevel(FTBBackups.statusMonitorLogger, config.logging_level);
            }
        } catch (NumberFormatException e) {
            context.getSource().sendFailure(Component.literal("Invalid number format for " + optionName + ": " + valueStr));
            return 0;
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal("Invalid value for " + optionName + ": " + valueStr));
            return 0;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Failed to set " + optionName + ": " + e.getMessage()));
            return 0;
        }
        return 1;
    }
    
    private static int configHelp(CommandContext<CommandSourceStack> context) {
        String helpMessage = "To set a configuration option, use: /backup config set <option> <value>\n" +
                "For list-type options like additional_paths and excluded_paths:\n" +
                "- Use 'add <item>' to add an item to the list.\n" +
                "- Use 'remove <item>' to remove an item from the list.\n" +
                "- Use '<item1>,<item2>,...' to set the entire list.\n" +
                "For other options, simply provide the new value.";
        context.getSource().sendSuccess(() -> Component.literal(helpMessage), false);
        return 1;
    }

    /**
    * Handles the reload command for the configuration.
    * Provides feedback to the command source based on the result of the reload operation.
    * 
    * @param context The command context.
    * @return An integer indicating the success (1) or failure (0) of the command.
    */
    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        Config.LoadResult result = Config.reload();
        switch (result) {
            case UPDATED:
                context.getSource().sendSuccess(() -> Component.literal("Configuration reloaded and updated."), false);
                return 1;
            case UNCHANGED:
                context.getSource().sendSuccess(() -> Component.literal("Configuration reloaded, no changes detected."), false);
                return 1;
            case FAILED:
                context.getSource().sendFailure(Component.literal("Failed to reload configuration."));
                return 0;
            default:
                context.getSource().sendFailure(Component.literal("Unknown reload result."));
                return 0;
        }
    }

    private static class ConfigOption<T> {
        final String name;
        final Class<T> type;
        final Function<ConfigData, T> getter;
        final BiConsumer<ConfigData, String> setter;

        ConfigOption(String name, Class<T> type, Function<ConfigData, T> getter, BiConsumer<ConfigData, String> setter) {
            this.name = name;
            this.type = type;
            this.getter = getter;
            this.setter = setter;
        }
    }
}