package com.optitem.cache;

import de.tr7zw.changeme.nbtapi.NBTContainer;
import de.tr7zw.changeme.nbtapi.NBTItem;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public class NBTCacheManager {

    private static final String CACHE_KEY = "CacheRef";

    private final JavaPlugin plugin;
    private final long cleanupIntervalSeconds;

    private final ConcurrentHashMap<Integer, CachedEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> entityIndex = new ConcurrentHashMap<>();

    private BukkitTask cleanupTask;

    public NBTCacheManager(JavaPlugin plugin, long cleanupIntervalSeconds) {
        this.plugin = plugin;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
    }

    public void start() {
        long intervalTicks = Math.max(20L, cleanupIntervalSeconds * 20L);
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> cleanup("scheduled"), intervalTicks,
                intervalTicks);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        restoreTrackedItems("shutdown");
        cleanup("shutdown");
        cache.clear();
        entityIndex.clear();
    }

    public void handleItemSpawn(Item item) {
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }

        ItemMeta meta = stack.getItemMeta();
        boolean hasLore = meta != null && meta.hasLore();

        NBTItem nbtItem = new NBTItem(stack);
        if (!hasLore && !nbtItem.hasNBTData()) {
            return;
        }

        if (nbtItem.hasKey(CACHE_KEY)) {
            return;
        }

        String serialized = nbtItem.toString();
        if (serialized == null || serialized.isEmpty()) {
            return;
        }

        int hashKey = serialized.hashCode();

        CachedEntry entry = cache.compute(hashKey,
                (key, existing) -> existing == null ? new CachedEntry(serialized) : existing);

        if (entry == null) {
            return;
        }

        entry.addReference(item.getUniqueId());
        entityIndex.put(item.getUniqueId(), hashKey);

        ItemStack placeholder = createPlaceholderStack(stack, hashKey);
        item.setItemStack(placeholder);

    }

    public Optional<ItemStack> handleAttemptPickup(Item item) {
        if (restoreItem(item, "pickup")) {
            return Optional.ofNullable(item.getItemStack());
        }
        return Optional.empty();
    }

    public void handleChunkUnload(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item) {
                if (!restoreItem(item, "chunk-unload")) {
                    removeReference(item.getUniqueId());
                }
            }
        }
    }

    public void handleEntityRemoval(Item item) {
        removeReference(item.getUniqueId());
    }

    public void ensureEntityItemsRestored(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (entity instanceof HumanEntity human && !(human instanceof Player)) {
                restoreInventoryContents(human.getInventory());
            }
            EntityEquipment equipment = entity.getEquipment();
            if (equipment != null) {
                restoreEquipmentContents(equipment);
            }
        });
    }

    public void ensureInventoryRestored(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> restoreInventoryContents(inventory));
    }

    private boolean restoreItem(Item item, String reason) {
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }

        NBTItem nbtItem = new NBTItem(stack);
        if (!nbtItem.hasKey(CACHE_KEY)) {
            return false;
        }

        int hashKey = nbtItem.getInteger(CACHE_KEY);
        CachedEntry entry = cache.get(hashKey);
        if (entry == null) {
            nbtItem.removeKey(CACHE_KEY);
            item.setItemStack(nbtItem.getItem());
            removeReference(item.getUniqueId());
            return false;
        }

        Optional<ItemStack> restored = entry.createItemStack(stack);
        if (restored.isEmpty()) {
            removeReference(item.getUniqueId());
            return false;
        }

        item.setItemStack(restored.get());
        entry.removeReference(item.getUniqueId());
        entityIndex.remove(item.getUniqueId());

        return true;
    }

    private void removeReference(UUID entityId) {
        Integer key = entityIndex.remove(entityId);
        if (key == null) {
            return;
        }
        CachedEntry entry = cache.get(key);
        if (entry != null) {
            entry.removeReference(entityId);
        }
    }

    private ItemStack createPlaceholderStack(ItemStack original, int hashKey) {
        ItemStack placeholder = new ItemStack(original.getType(), original.getAmount());
        ItemMeta baseMeta = Bukkit.getItemFactory().getItemMeta(original.getType());
        ItemMeta originalMeta = original.getItemMeta();
        if (baseMeta != null && originalMeta instanceof Damageable originalDamage
                && baseMeta instanceof Damageable baseDamage) {
            baseDamage.setDamage(originalDamage.getDamage());
            placeholder.setItemMeta(baseMeta);
        }

        NBTItem nbtItem = new NBTItem(placeholder);
        nbtItem.setInteger(CACHE_KEY, hashKey);
        return nbtItem.getItem();
    }

    private void cleanup(String reason) {
        cache.entrySet().removeIf(entry -> entry.getValue().isOrphaned());
    }

    public void restoreTrackedItems(String reason) {
        List<UUID> tracked = List.copyOf(entityIndex.keySet());
        for (UUID uuid : tracked) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity instanceof Item item) {
                restoreItem(item, reason);
            }
        }
    }

    private void restoreEquipmentContents(EntityEquipment equipment) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack slotStack = equipment.getItem(slot);
            restoreStack(slotStack).ifPresent(restored -> equipment.setItem(slot, restored));
        }
    }

    private void restoreInventoryContents(Inventory inventory) {
        int size = inventory.getSize();
        for (int index = 0; index < size; index++) {
            int slotIndex = index;
            ItemStack slotStack = inventory.getItem(slotIndex);
            restoreStack(slotStack).ifPresent(restored -> inventory.setItem(slotIndex, restored));
        }
    }

    private Optional<ItemStack> restoreStack(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return Optional.empty();
        }
        try {
            NBTItem nbtItem = new NBTItem(stack);
            if (!nbtItem.hasKey(CACHE_KEY)) {
                return Optional.empty();
            }

            int hashKey = nbtItem.getInteger(CACHE_KEY);
            CachedEntry entry = cache.get(hashKey);
            if (entry == null) {
                nbtItem.removeKey(CACHE_KEY);
                return Optional.of(nbtItem.getItem());
            }

            return entry.createItemStack(stack);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static final class CachedEntry {
        private final String nbtSnapshot;
        private final Set<UUID> references = ConcurrentHashMap.newKeySet();

        private CachedEntry(String nbtSnapshot) {
            this.nbtSnapshot = nbtSnapshot;
        }

        private void addReference(UUID uuid) {
            references.add(uuid);
        }

        private void removeReference(UUID uuid) {
            references.remove(uuid);
        }

        private Optional<ItemStack> createItemStack(ItemStack baseTemplate) {
            try {
                NBTContainer container = new NBTContainer(nbtSnapshot);
                ItemStack base = baseTemplate.clone();
                NBTItem reconstructed = new NBTItem(base);
                reconstructed.mergeCompound(container);
                reconstructed.removeKey(CACHE_KEY);
                ItemStack stack = reconstructed.getItem();
                stack.setType(baseTemplate.getType());
                stack.setAmount(baseTemplate.getAmount());
                return Optional.of(stack);
            } catch (Exception ex) {
                return Optional.empty();
            }
        }

        private boolean isOrphaned() {
            return references.isEmpty();
        }
    }
}
