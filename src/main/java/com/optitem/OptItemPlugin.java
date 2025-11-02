package com.optitem;

import com.optitem.cache.NBTCacheManager;
import com.optitem.listener.ItemListener;
import com.optitem.protocol.ItemEntityPacketInterceptor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class OptItemPlugin extends JavaPlugin {

    private NBTCacheManager cacheManager;
    private ItemListener itemListener;
    private ItemEntityPacketInterceptor packetInterceptor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        long cleanupInterval = Math.max(10L, config.getLong("cache-cleanup-interval-seconds", 60L));
        int maxShulkerNbtBytes = Math.max(1024,
                config.getInt("max-shulker-nbt-bytes", 200 * 1024));
        double playerQuitRadius = Math.max(0D, config.getDouble("player-quit-restore-radius", 32.0D));
        boolean debugLogging = config.getBoolean("debug-logging", false);

        cacheManager = new NBTCacheManager(this, cleanupInterval, maxShulkerNbtBytes, debugLogging);
        cacheManager.start();

        itemListener = new ItemListener(cacheManager, playerQuitRadius);
        getServer().getPluginManager().registerEvents(itemListener, this);

        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            packetInterceptor = new ItemEntityPacketInterceptor(this, cacheManager, debugLogging);
            packetInterceptor.register();
            getLogger().info("ProtocolLib integration enabled; clients will see full item data.");
        } else {
            getLogger().warning("ProtocolLib not found. Clients will see placeholder item data.");
        }

        getLogger().info(() -> String.format(
                "OptItem ready. Cleanup interval: %d seconds, max shulker NBT: %d bytes, quit radius: %.1f, debug: %s",
                cleanupInterval, maxShulkerNbtBytes, playerQuitRadius, debugLogging));
    }

    @Override
    public void onDisable() {
        if (packetInterceptor != null) {
            packetInterceptor.unregister();
        }
        if (cacheManager != null) {
            cacheManager.restoreTrackedItems("shutdown");
            cacheManager.shutdown();
        }
    }
}
