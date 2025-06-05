package net.creeperhost.ftbbackups.config;

import blue.endless.jankson.Comment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ConfigData {

  public enum NotificationMode {
      NONE,
      OPS_ONLY,
      ALL_PLAYERS
  }

  public enum RetentionMode {
      MAX_BACKUPS,
      TIERED
  }

  public enum Format {
      ZIP,
      ZSTD,
      DIRECTORY
  }

  public enum LoggingLevel {
      DEBUG,
      INFO,
      WARN,
      ERROR
  }

  // General Backup Settings
  // -----------------------
  // These settings control the overall behavior of the backup system.

  @Comment("\n" +
          " * Enables or disables automatic backups. When true, backups occur based on 'backup_cron'. When false, use /backup manually if permitted.\n")
  public boolean enabled = true;

  @Comment("\n" +
          " * Sets the permission level needed for the /backup command:\n" +
          " * - 0: Everyone\n" +
          " * - 1: Moderators\n" +
          " * - 2: Game masters\n" +
          " * - 3: Admins (default)\n" +
          " * - 4: Server owners\n")
  public int command_permission_level = 3;

  @Comment("\n" +
          " * Sets the notification mode for backups:\n" +
          " * - NONE: No player notifications.\n" +
          " * - OPS_ONLY: Notifications for operators only.\n" +
          " * - ALL_PLAYERS: Notifications for all players.\n")
  public NotificationMode notification_mode = NotificationMode.OPS_ONLY;

  @Comment("\n" +
          " * Enables or disables backup progress updates in the console.\n")
  public boolean enable_console_progress = true;

  @Comment("\n" +
      " * Sets the logging level for the mod. Options: DEBUG, INFO, WARN, ERROR. DEBUG provides detailed logs in debug.log for troubleshooting.\n")
  public LoggingLevel logging_level = LoggingLevel.INFO;

  @Comment("\n" +
          " * Enables verbose logging for backup operations, providing detailed information about the backup process (e.g., files included).\n"
          +
          " * Requires logging_level to be set to DEBUG to see these messages.\n")
  public boolean verbose_logging = false;

  // Backup Retention Settings
  // -------------------------
  // These settings determine how many backups are kept and for how long.

  @Comment("\n" +
      " * Defines how backups are retained:\n" +
      " * - MAX_BACKUPS: Limits to a set number, removing the oldest when full.\n" +
      " * - TIERED: Keeps backups by time intervals.\n" +
      " *   - This is quite technical to explain, but understand that the tiered categories overlap!\n" +
      " *   - With the default configuration, and the server being idle for 4 out of 24 hours, this would retain around 10-14 backups.\n")
  public RetentionMode retention_mode = RetentionMode.MAX_BACKUPS;

  @Comment("\n" +
      " * Sets the maximum number of backups in MAX_BACKUPS mode. Oldest backups are deleted when this limit is reached.\n")
  public int max_backups = 10;

  // The following settings are only used when retention_mode is set to TIERED.
  @Comment("\n" +
      " * For TIERED mode: Number of most recent backups to always retain, regardless of age.\n")
  public int keep_latest = 3;

  @Comment("\n" +
      " * For TIERED mode: Number of hourly backups to retain.\n")
  public int keep_hourly = 3;

  @Comment("\n" +
      " * For TIERED mode: Number of daily backups to retain.\n")
  public int keep_daily = 7;

  @Comment("\n" +
      " * For TIERED mode: Number of weekly backups to retain.\n")
  public int keep_weekly = 4;

  @Comment("\n" +
      " * For TIERED mode: Number of monthly backups to retain.\n")
  public int keep_monthly = 3;

  // Backup Scheduling
  // -----------------
  // These settings control when backups are performed.

  @Comment("\n" +
      " * Cron expression for backup timing. Default '0 30 * * * ?' runs every 30 minutes. Customize at http://www.cronmaker.com.\n"
     )
  public String backup_cron = "0 30 * * * ?";

  @Comment("\n" +
      " * Sets the minimum time (in minutes) between manual backups via /backup. Set to 0 for no cooldown.\n")
  public int manual_backups_time = 0;

  @Comment("\n" +
      " * If true, backups only occur if a player has been online since the last backup. Prevents unnecessary backups on idle servers.\n"
     )
  public boolean only_if_players_been_online = true;

  // Backup Content
  // --------------
  // These settings specify what files and directories are included or excluded from backups.

  @Comment("\n" +
      " * Adds files or directories to backups beyond the default world files (e.g., 'world/'). Paths are relative to the server root and can include specific files, folders, or wildcards.\n"
      +
      " * How it works:\n" +
      " * - Use specific paths like 'server.properties' to include a single file.\n" +
      " * - Use folder paths like 'config/' to include an entire directory and its contents.\n" +
      " * - Use wildcards like 'logs/*.log' to include all files matching a pattern (e.g., all .log files in 'logs/').\n" +
      " * Special case: To back up the entire server, use '/'—but ensure 'backup_location' points to an external directory (e.g., '/path/to/external/backups') to avoid including backup files in backups.\n")
  public List<String> additional_paths = new ArrayList<>();

  @Comment("\n" +
      " * Excludes files or directories from backups. Uses the same syntax as 'additional_files'.\n" +
      " * Examples:\n" +
      " * - 'crash-reports/' (entire folder)\n" +
      " * - 'session.lock' (specific file)\n" +
      " * - 'logs/*.gz' (wildcard for compressed logs)\n")
  public List<String> excluded_paths = new ArrayList<>();

  // Backup Location and Format
  // --------------------------
  // These settings determine where backups are stored and in what format.

  @Comment("\n" +
      " * Sets the backup storage location. Use '.' for the default 'backups' folder. For full server backups, specify an external path.\n"
     )
  public String backup_location = ".";

  @Comment("\n" +
      " * Sets the backup format:\n" +
      " * - ZIP: Compressed archive\n" +
      " * - ZSTD: Faster compression\n" +
      " * - DIRECTORY: Uncompressed folder\n")
  public Format backup_format = Format.ZIP;

  // Disk Space Management
  // ---------------------
  // These settings manage disk space requirements and handling of incomplete backups.

  @Comment("\n" +
      " * Sets the minimum free disk space (in MB) required to start a backup. Skips backup if below this threshold.\n"
     )
  public long minimum_free_space = 500;

  @Comment("\n" +
      " * If true, deletes the oldest backup to free space and retries if a backup fails due to insufficient space. Use cautiously.\n"
     )
  public boolean free_space_if_needed = false;

  @Comment("\n" +
      " * If true, removes incomplete backups during cleanup. Set to false to keep them for debugging or recovery.\n")
  public boolean remove_incomplete_backups = true;

  // Backup Preview Options
  // ----------------------
  // These settings control the generation of preview images for backups.

  @Comment("\n" +
      " * Enables backup preview generation. Set to false to disable.\n")
  public boolean enable_preview = true;

  @Comment("\n" +
          " * List of dimensions to consider for generating the backup preview image.\n" +
          " * If multiple dimensions are specified, the one with the highest activity will be selected.\n" +
          " * Default: ['minecraft:overworld']\n")
  public List<String> preview_dimensions_list = Arrays.asList("minecraft:overworld");
}