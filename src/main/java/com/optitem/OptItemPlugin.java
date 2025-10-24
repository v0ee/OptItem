package com.optitem;

import com.optitem.cache.NBTCacheManager;
import com.optitem.listener.ItemListener;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class OptItemPlugin extends JavaPlugin {

    private NBTCacheManager cacheManager;
    private ItemListener itemListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        long cleanupInterval = Math.max(10L, config.getLong("cache-cleanup-interval-seconds", 60L));

        cacheManager = new NBTCacheManager(this, cleanupInterval);
        cacheManager.start();

        itemListener = new ItemListener(cacheManager);
        getServer().getPluginManager().registerEvents(itemListener, this);

        getLogger().info(() -> String.format("OptItem ready. Cleanup interval: %d seconds", cleanupInterval));
    }

    @Override
    public void onDisable() {
        if (cacheManager != null) {
            cacheManager.restoreTrackedItems("shutdown");
            cacheManager.shutdown();
        }
    }
}
