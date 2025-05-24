package net.creeperhost.ftbbackups.config;

import blue.endless.jankson.Comment;

import java.util.ArrayList;
import java.util.List;

public class ConfigData {

        // General Backup Settings
        @Comment("Enable automatic backups. Set to false to disable all backup functionality.")
        public boolean enabled = true;

        @Comment("Permission level required to use the /backup command. Default is 3 (operator level).")
        public int command_permission_level = 3;

        @Comment("Notify only server operators about backup status. If false, all players receive notifications.")
        public boolean notify_op_only = true;

        @Comment("Disable all backup notifications to players and operators.")
        public boolean do_not_notify = false;

        @Comment("Include backup file size in status messages.")
        public boolean display_file_size = false;

        @Comment("Enable status monitoring messages during backups. Set to false to disable.")
        public boolean enable_status_monitoring = true;

        @Comment("Logging level for FTB Backups. Valid options: DEBUG, INFO, WARN, ERROR. DEBUG adds detailed logs to debug.log.")
        public String logging_level = "INFO";

        // Backup Retention Settings
        @Comment("Retention mode for backups. Options: MAX_BACKUPS (keep a fixed number), TIERED (experimental, retains backups by time tiers).")
        public RetentionMode retention_mode = RetentionMode.MAX_BACKUPS;

        @Comment("For MAX_BACKUPS mode: Maximum number of backups to keep. Older backups are deleted when this limit is exceeded.")
        public int max_backups = 5;

        @Comment("For TIERED mode: Number of most recent backups to always retain, regardless of age.")
        public int keep_latest = 5;

        @Comment("For TIERED mode: Number of hourly backups to retain.")
        public int keep_hourly = 1;

        @Comment("For TIERED mode: Number of daily backups to retain.")
        public int keep_daily = 1;

        @Comment("For TIERED mode: Number of weekly backups to retain.")
        public int keep_weekly = 1;

        @Comment("For TIERED mode: Number of monthly backups to retain.")
        public int keep_monthly = 1;

        // Backup Scheduling
        @Comment("Cron expression for scheduling automatic backups. Default runs every 30 minutes. Generate expressions at http://www.cronmaker.com.")
        public String backup_cron = "0 */30 * * * ?";

        @Comment("Minimum time (in minutes) between manual backups via /backup. Set to 0 for no cooldown.")
        public int manual_backups_time = 0;

        @Comment("Only create backups if a player has been online since the last backup. Prevents unnecessary backups on idle servers.")
        public boolean only_if_players_been_online = true;

        // Backup Content
        @Comment("""
                        Additional files or directories to include in backups. Supports file names, paths, and wildcards (relative to server root).
                        Examples:
                          fileName.txt         - Any file named 'fileName.txt'
                          folder/file.txt      - Specific file path
                          folder/              - Entire folder contents
                          path/starts/with*    - Files with paths starting with 'path/starts/with'
                          *ends/with.txt       - Files ending with 'ends/with.txt'
                        Note: To backup the entire server, set this and ensure 'backup_location' is outside the server directory.""")
        public List<String> additional_files = new ArrayList<>();

        @Comment("""
                        Files or directories to exclude from backups. Supports file names, paths, and wildcards (relative to server root).
                        Examples:
                          fileName.txt         - Any file named 'fileName.txt'
                          folder/file.txt      - Specific file path
                          folder/              - Entire folder contents
                          path/starts/with*    - Files with paths starting with 'path/starts/with'
                          *ends/with.txt       - Files ending with 'ends/with.txt'""")
        public List<String> excluded = new ArrayList<>();

        // Backup Location and Format
        @Comment("Backup storage location. Use '.' for the default 'backups' folder, or specify a full path. Must be outside server root if backing up everything.")
        public String backup_location = ".";

        @Comment("Backup format. Options: ZIP (compressed archive), ZSTD (faster compression), DIRECTORY (uncompressed folder).")
        public Format backup_format = Format.ZIP;

        // Disk Space Management
        @Comment("Minimum free disk space (in MB) required to start a backup. Skips backup if this threshold isn’t met.")
        public long minimum_free_space = 500;

        @Comment("If a backup fails due to insufficient space, delete the oldest backup to free space and retry. Use cautiously.")
        public boolean free_space_if_needed = false;

        // Backup Preview Options
        @Comment("Enable backup preview generation. Set to false to disable.")
        public boolean enable_preview = true;

        @Comment("The dimension used for generating the backup preview image.\n" +
            "- Set to a specific dimension (e.g., \"minecraft:overworld\") to always use that dimension.\n" +
            "- Set to \"all\" to automatically detect the primary dimension based on activity (can be slow).\n" +
            "Note: The \"none\" option is deprecated; use 'enable_preview' set to false to disable previews.")
        public String preview_dimension = "minecraft:overworld";

        @Comment("List of dimensions to consider when 'preview_dimension' is set to 'all'. If empty, all dimensions are scanned.")
        public List<String> preview_dimensions_list = new ArrayList<>();

        // Deprecated Options
        @Deprecated(forRemoval = true)
        @Comment("Deprecated: Use 'additional_files' instead. This option will be removed in a future version.")
        public List<String> additional_directories = new ArrayList<>();
}