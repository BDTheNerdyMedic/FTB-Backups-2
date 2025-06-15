package net.creeperhost.ftbbackups.data;

import net.creeperhost.ftbbackups.config.ConfigData.Format;
import java.util.Objects;

/**
 * Represents a single backup of a Minecraft world, containing metadata such as creation time, size, and format.
 * This class is immutable where possible to ensure data integrity.
 */
public class Backup {
    private final String worldName;
    private final long createTime;
    private final String backupLocation;
    private long size;
    private float ratio;
    private String sha1;
    private String preview;
    private final boolean isProtected;
    private final String backupName;
    private final Format backupFormat;
    private boolean complete;

    /**
     * Default constructor for JSON deserialization. Initializes a backup with default values.
     * Sets {@code complete} to true for backward compatibility.
     */
    public Backup() {
        complete = true;
        this.worldName = "";
        this.createTime = 0;
        this.backupLocation = "";
        this.size = 0;
        this.ratio = 0.0f;
        this.sha1 = "";
        this.preview = "";
        this.isProtected = false;
        this.backupName = "";
        this.backupFormat = Format.ZIP;
    }

    /**
     * Constructs a new Backup instance with the provided parameters.
     * Validates critical fields to ensure data integrity.
     *
     * @param worldName      The name of the world being backed up.
     * @param createTime     The timestamp when the backup was created.
     * @param backupLocation The file path where the backup is stored.
     * @param size           The size of the backup in bytes.
     * @param ratio          The compression ratio of the backup.
     * @param sha1           The SHA-1 hash of the backup file.
     * @param preview        A preview image or data for the backup.
     * @param isProtected    Whether this backup is protected (e.g., a snapshot).
     * @param backupName     The name of the backup.
     * @param backupFormat   The format of the backup (e.g., ZIP, TAR).
     * @param complete       Whether the backup process completed successfully.
     * @throws IllegalArgumentException If validation fails.
     */
    public Backup(String worldName, long createTime, String backupLocation, long size, float ratio, String sha1,
                  String preview, boolean isProtected, String backupName, Format backupFormat, boolean complete) {
        if (createTime < 0) throw new IllegalArgumentException("createTime cannot be negative");
        if (size < 0) throw new IllegalArgumentException("size cannot be negative");

        this.worldName = worldName;
        this.createTime = createTime;
        this.backupLocation = backupLocation;
        this.size = size;
        this.ratio = ratio;
        this.sha1 = sha1;
        this.preview = preview;
        this.isProtected = isProtected;
        this.backupName = backupName;
        this.backupFormat = backupFormat;
        this.complete = complete;
    }

    /** @return The name of the world associated with this backup. */
    public String getWorldName() { return worldName; }

    /** @return The size of the backup in bytes. */
    public long getSize() { return size; }

    /** 
     * Sets the size of the backup.
     * @param size The new size in bytes.
     * @throws IllegalArgumentException If size is negative.
     */
    public void setSize(long size) {
        if (size < 0) throw new IllegalArgumentException("size cannot be negative");
        this.size = size;
    }

    /** @return The SHA-1 hash of the backup file. */
    public String getSha1() { return sha1; }

    /** @return Whether this backup is protected (e.g., a snapshot). */
    public boolean isProtected() { return isProtected; }

    /** @return The creation timestamp of the backup in milliseconds since epoch. */
    public long getCreateTime() { return createTime; }

    /** @return The compression ratio of the backup. */
    public float getRatio() { return ratio; }

    /** @return The file path where the backup is stored. */
    public String getBackupLocation() { return backupLocation; }

    /** @return The name of the backup. */
    public String getBackupName() { return backupName; }

    /** @return The format of the backup (e.g., ZIP, TAR). */
    public Format getBackupFormat() { return backupFormat; }

    /** 
     * Sets the compression ratio of the backup.
     * @param ratio The new compression ratio.
     * @return This Backup instance for method chaining.
     */
    public Backup setRatio(float ratio) {
        this.ratio = ratio;
        return this;
    }

    /** 
     * Sets the SHA-1 hash of the backup.
     * @param sha1 The new SHA-1 hash.
     * @return This Backup instance for method chaining.
     */
    public Backup setSha1(String sha1) {
        this.sha1 = sha1;
        return this;
    }

    /** 
     * Marks the backup as complete.
     * @return This Backup instance for method chaining.
     */
    public Backup setComplete() {
        complete = true;
        return this;
    }

    /** @return Whether the backup process completed successfully. */
    public boolean isComplete() { return complete; }

    /** @return The preview data for the backup. */
    public String getPreview() { return preview; }

    /** 
     * Sets the preview data for the backup.
     * @param preview The new preview data.
     */
    public void setPreview(String preview) { this.preview = preview; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Backup backup = (Backup) o;
        return createTime == backup.createTime && size == backup.size && Float.compare(ratio, backup.ratio) == 0
                && isProtected == backup.isProtected && complete == backup.complete
                && Objects.equals(worldName, backup.worldName) && Objects.equals(backupLocation, backup.backupLocation)
                && Objects.equals(sha1, backup.sha1) && Objects.equals(preview, backup.preview)
                && Objects.equals(backupName, backup.backupName) && backupFormat == backup.backupFormat;
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldName, createTime, backupLocation, size, ratio, sha1, preview, isProtected, backupName, backupFormat, complete);
    }
}