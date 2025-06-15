package net.creeperhost.ftbbackups.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages a collection of Backup objects, providing thread-safe methods to add, remove, and query backups.
 */
public class Backups {
    private final List<Backup> backups = Collections.synchronizedList(new ArrayList<>());
    private boolean isDirty = true;
    private String worldHash = "";
    private String lastPreview = "";

    /** 
     * Adds a backup to the collection.
     * @param backup The Backup object to add.
     */
    public void add(Backup backup) { backups.add(backup); }

    /** @return True if the collection is empty, false otherwise. */
    public boolean isEmpty() { return backups.isEmpty(); }

    /** @return The total number of backups in the collection. */
    public int size() { return backups.size(); }

    /** 
     * Returns the number of unprotected backups in the collection.
     * @return The count of unprotected backups.
     */
    public int getUnprotectedBackupCount() {
        synchronized (backups) {
            return (int) backups.stream().filter(backup -> !backup.isProtected()).count();
        }
    }

    /** 
     * Checks if the collection contains a specific backup.
     * @param backup The Backup object to check for.
     * @return True if the backup is present, false otherwise.
     */
    public boolean contains(Backup backup) { return backups.contains(backup); }

    /** 
     * Removes a backup from the collection.
     * @param backup The Backup object to remove.
     */
    public void remove(Backup backup) { backups.remove(backup); }

    /** @return A synchronized list of all backups. */
    public List<Backup> getBackups() { return backups; }

    /** @return True if the backups need to be saved or updated, false otherwise. */
    public boolean isDirty() { return isDirty; }

    /** 
     * Sets the dirty flag for the backups.
     * @param isDirty The new dirty state.
     */
    public void setIsDirty(boolean isDirty) { this.isDirty = isDirty; }

    /** @return The hash of the world associated with these backups. */
    public String getWorldHash() { return worldHash; }

    /** 
     * Sets the hash of the world.
     * @param worldHash The new world hash.
     */
    public void setWorldHash(String worldHash) { this.worldHash = worldHash; }

    /** @return The last preview data. */
    public String getLastPreview() { return lastPreview; }

    /** 
     * Sets the last preview data.
     * @param lastPreview The new last preview.
     */
    public void setLastPreview(String lastPreview) { this.lastPreview = lastPreview; }
}