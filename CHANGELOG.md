### Significant changes that have been made to this Minecraft Mod

Below is a comprehensive summary of the significant changes and improvements made to this Minecraft mod. Each change is described with its purpose and impact on the mod’s functionality, maintainability, or user experience.

- **Added Logging Level Configuration**
  - **Description**: Introduced a `logging_level` field in `ConfigData.java` to allow users to set log verbosity (e.g., DEBUG, INFO, WARN, ERROR). Updated `Config.java` to load this setting and `FTBBackups.java` to apply it dynamically to all loggers.
  - **Impact**: Enhances configurability and debugging capabilities by providing tailored log output.

- **Enhanced Logging Throughout the Mod**
  - **Description**: Added detailed, structured logging across `FTBBackups.java`, `BackupHandler.java`, `FileUtils.java`, and `Config.java` to provide insights into operations and errors.
  - **Impact**: Increases transparency and simplifies issue diagnosis.

- **Added Backup Progress Monitoring**
  - **Description**: Implemented a new feature in `BackupHandler.java` to log backup progress periodically using a dedicated `statusMonitorLogger` and `statusMonitorExecutorService` in `FTBBackups.java`.
  - **Impact**: Provides real-time feedback during backups, improving user experience.

- **Improved Backup Logic and Utilities**
  - **Description**: Optimized `BackupHandler.java` to filter out duplicate files during compression. Enhanced `FileUtils.java` with `getSizeString(double)` for readable size outputs and updated `getFolderSize` for better exception handling.
  - **Impact**: Boosts backup efficiency and reliability while improving log readability.

- **Refactored `createBackup` for Better Readability**
  - **Description**: Split `createBackup` in `BackupHandler.java` into smaller helper methods (`shouldSkipBackup`, `setupBackupEnvironment`, `performBackup`, `finalizeBackup`).
  - **Impact**: Enhances code maintainability.

- **Added Persistent `isDirty` Logic for Backed-Up State**
  - **Description**: Added an `isDirty` flag in `Backups.java` with persistent storage via JSON, ensuring accurate tracking across server restarts.
  - **Impact**: Prevents unnecessary or missed backups, improving efficiency and data protection.

- **Optimized `isDirty` Flag Setting**
  - **Description**: Improved the method for setting the `isDirty` flag by replacing the `onServerTickPre` method with `PlayerEvent.PLAYER_JOIN`, reducing unnecessary computations during server ticks.
  - **Impact**: Enhances server performance by minimizing the overhead of checking or setting the `isDirty` flag.

- **Refactored Executor Initialization**
  - **Description**: Consolidated executor creation in `FTBBackups.java` with reusable methods (`createScheduledExecutor`, `createExecutor`).
  - **Impact**: Reduces code duplication and improves threading consistency.

- **Improved Configuration File Handling**
  - **Description**: Added a `pauseWatcher` flag in `Config.java` to prevent redundant reloads during writes and implemented content-based comparison in `loadFromFile`.
  - **Impact**: Minimizes unnecessary reloads, improving efficiency.

- **Enhanced Shutdown Logic**
  - **Description**: Improved `onShutdown` in `FTBBackups.java` to gracefully terminate the Quartz scheduler and executor services with timeouts.
  - **Impact**: Ensures smooth shutdowns without delays.

- **Fixed Temp File Handling During Backups**
  - **Description**: Enhanced `BackupHandler.java` and `FileUtils.java` to manage temporary files properly, preventing premature removal or modification.
  - **Impact**: Increases backup success rates and stability.

- **Improved Backup File Name Generation**
  - **Description**: Updated `genBackupFileName` in `BackupHandler.java` to use `LocalDateTime` for a consistent `yyyy-MM-dd_HH-mm-ss` format.
  - **Impact**: Provides a more reliable and readable backup naming convention.

- **Added Detection of Unmanaged Backup Files**
  - **Description**: Added logic in `BackupHandler.java`’s `clean` method to identify and log unmanaged backup files not tracked in `backups.json`.
  - **Impact**: Enhances maintenance by identifying orphaned files.

- **Performance Improvements**
  - **Description**: Fixed processes running multiple times per second (e.g., increased config watcher polling to 90s) and removed redundant calls (e.g., reused `getLatestBackup` results in `canCreateBackup`).
  - **Impact**: Reduces server load and improves mod efficiency.

- **Optimized Backup Compression**
  - **Description**: `BackupHandler.java` skips duplicate subpaths during compression, reducing backup time.
  - **Impact**: Improves efficiency, especially for large or nested directories.

- **Added Backup Failure Recovery**
  - **Description**: `BackupHandler.java` cleans up after compression failures, preventing partial backups from lingering.
  - **Impact**: Enhances reliability by ensuring failed backups don’t disrupt the system.

- **Enhanced Configuration Validation**
  - **Description**: `FTBBackups.java` validates `backup_cron`, resetting to default if invalid.
  - **Impact**: Ensures a reliable backup schedule.

- **Improved ConfigData Comments and Ordering**
  - **Description**: Updated `ConfigData.java` with clearer, more detailed comments and a more logical ordering of configuration options.
  - **Impact**: Enhances the user experience by making the configuration easier to understand and navigate.

- **Enhanced Backup Deletion Logic**
  - **Description**: Improved the `deleteBackup` method to correctly handle backup removal and update the backup list without relying on `verifyOldBackups`. Enhanced error checking and logging for better reliability.
  - **Impact**: Ensures backups are properly removed and the backup list remains accurate, preventing issues with backup management.

- **Optimized Backup Verification Process**
  - **Description**: Refactored the `verifyOldBackups` method to remove unnecessary list copies, ensure thread safety, and add proper error handling and logging.
  - **Impact**: Improves performance and reliability of the backup verification process, ensuring it runs efficiently and safely in a multi-threaded environment.