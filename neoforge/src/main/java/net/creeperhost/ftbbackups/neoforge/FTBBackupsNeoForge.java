package net.creeperhost.ftbbackups.neoforge;

import net.creeperhost.ftbbackups.FTBBackups;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(FTBBackups.MOD_ID)
public class FTBBackupsNeoForge {
    public FTBBackupsNeoForge(IEventBus iEventBus) {
        // Initialize the mod
        FTBBackups.init();
        // Register gameplay events (e.g., player join) using Architectury
        FTBBackups.registerEvents();
    }
}