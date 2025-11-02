package com.optitem.listener;

import com.optitem.cache.NBTCacheManager;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Chunk;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ItemListener implements Listener {

    private final NBTCacheManager cacheManager;
    private final double playerQuitRestoreRadius;

    public ItemListener(NBTCacheManager cacheManager, double playerQuitRestoreRadius) {
        this.cacheManager = cacheManager;
        this.playerQuitRestoreRadius = Math.max(0D, playerQuitRestoreRadius);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        cacheManager.handleItemSpawn(event.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        var restored = cacheManager.handleAttemptPickup(event.getItem());
        if (restored.isPresent()) {
            cacheManager.ensureEntityItemsRestored(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        var restored = cacheManager.handleAttemptPickup(event.getItem());
        if (restored.isPresent()) {
            cacheManager.ensureInventoryRestored(event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Item item) {
            cacheManager.handleEntityRemoval(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        cacheManager.handleChunkUnload(chunk);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        cacheManager.debug("Player %s quit; restoring items within %.1f blocks", event.getPlayer().getName(),
                playerQuitRestoreRadius);
        cacheManager.restoreItemsNear(event.getPlayer().getLocation(), playerQuitRestoreRadius);
    }

}
