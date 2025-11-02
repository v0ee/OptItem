package com.optitem.cache;

import de.tr7zw.changeme.nbtapi.NBTContainer;
import de.tr7zw.changeme.nbtapi.NBTItem;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.Location;
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
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

@SuppressWarnings("deprecation")
public class NBTCacheManager {

    private static final String CACHE_KEY = "CacheRef";
    private final JavaPlugin plugin;
    private final long cleanupIntervalSeconds;
    private final int maxShulkerNbtSizeBytes;
    private final boolean debugLogging;

    private final ConcurrentHashMap<Integer, CachedEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> entityIndex = new ConcurrentHashMap<>();

    private BukkitTask cleanupTask;

    public NBTCacheManager(JavaPlugin plugin, long cleanupIntervalSeconds, int maxShulkerNbtSizeBytes,
            boolean debugLogging) {
        this.plugin = plugin;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        this.maxShulkerNbtSizeBytes = Math.max(1024, maxShulkerNbtSizeBytes);
        this.debugLogging = debugLogging;
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

        ItemStack sanitized = sanitizeShulkerItem(stack);
        if (sanitized != stack) {
            item.setItemStack(sanitized);
            stack = sanitized;
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

    public void restoreItemsNear(Location location, double radius) {
        if (location == null || radius <= 0) {
            return;
        }

        double radiusSquared = radius * radius;
        List<UUID> tracked = List.copyOf(entityIndex.keySet());
        for (UUID uuid : tracked) {
            Entity entity = Bukkit.getEntity(uuid);
            if (!(entity instanceof Item item)) {
                continue;
            }
            if (!item.getWorld().equals(location.getWorld())) {
                continue;
            }
            if (item.getLocation().distanceSquared(location) > radiusSquared) {
                continue;
            }
            if (restoreItem(item, "player-quit")) {
                debug("Restored cached item near player quit at %s", location);
            }
        }
    }

    public ItemStack sanitizeShulkerItem(ItemStack original) {
        if (original == null || original.getType() == Material.AIR) {
            return original;
        }

        Material type = original.getType();
        if (!Tag.SHULKER_BOXES.isTagged(type)) {
            return original;
        }

        try {
            ItemStack working = original.clone();
            NBTItem nbtItem = new NBTItem(working);
            String serialized = nbtItem.toString();
            if (serialized == null) {
                return original;
            }

            int size = serialized.getBytes(StandardCharsets.UTF_8).length;
            if (size <= maxShulkerNbtSizeBytes) {
                return original;
            }

            ItemMeta meta = working.getItemMeta();
            if (!(meta instanceof BlockStateMeta blockStateMeta)) {
                return original;
            }

            if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulker)) {
                return original;
            }

            boolean modified = sanitizeShulkerContents(shulker);
            if (!modified) {
                return original;
            }

            blockStateMeta.setBlockState(shulker);
            working.setItemMeta(blockStateMeta);
            working.setAmount(original.getAmount());
            debug("Trimmed shulker NBT (%d bytes) for item %s", size, describeItem(original));
            return working;
        } catch (Exception ex) {
            return original;
        }
    }

    private boolean sanitizeShulkerContents(ShulkerBox shulker) {
        boolean modified = false;
        Inventory inventory = shulker.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack content = contents[slot];
            ItemStack trimmed = trimLargestTag(content);
            if (trimmed != content) {
                contents[slot] = trimmed;
                modified = true;
            }
        }

        if (modified) {
            inventory.setContents(contents);
            debug("Sanitized contents of shulker box; %d slots adjusted", contents.length);
        }
        return modified;
    }

    private ItemStack trimLargestTag(ItemStack content) {
        if (content == null || content.getType() == Material.AIR) {
            return content;
        }

        try {
            ItemStack base = content.clone();
            NBTItem nbtItem = new NBTItem(base);
            if (!nbtItem.hasNBTData()) {
                return content;
            }

            Set<String> keys = nbtItem.getKeys();
            if (keys.isEmpty()) {
                return content;
            }

            int originalSize = estimateSizeBytes(nbtItem);
            String targetKey = null;
            int largestDelta = 0;

            for (String key : keys) {
                int delta;
                if ("display".equals(key)) {
                    ItemStack copyStack = content.clone();
                    NBTItem copyNbt = new NBTItem(copyStack);
                    var display = copyNbt.getCompound("display");
                    if (display != null) {
                        display.removeKey("Lore");
                        display.removeKey("Name");
                        if (display.getKeys().isEmpty()) {
                            copyNbt.removeKey("display");
                        }
                        delta = originalSize - estimateSizeBytes(copyNbt);
                    } else {
                        delta = 0;
                    }
                } else {
                    ItemStack copyStack = content.clone();
                    NBTItem copyNbt = new NBTItem(copyStack);
                    if (!copyNbt.hasKey(key)) {
                        continue;
                    }
                    copyNbt.removeKey(key);
                    delta = originalSize - estimateSizeBytes(copyNbt);
                }

                if (delta > largestDelta) {
                    largestDelta = delta;
                    targetKey = key;
                }
            }

            if (targetKey == null || largestDelta <= 0) {
                return content;
            }

            NBTItem editable = new NBTItem(base);
            if ("display".equals(targetKey)) {
                var display = editable.getCompound("display");
                if (display != null) {
                    display.removeKey("Lore");
                    display.removeKey("Name");
                    if (display.getKeys().isEmpty()) {
                        editable.removeKey("display");
                    }
                }
            } else {
                editable.removeKey(targetKey);
            }

            ItemStack cleaned = editable.getItem();
            cleaned.setAmount(content.getAmount());
            debug("Removed NBT tag '%s' from shulker content (%d bytes saved)", targetKey, largestDelta);
            return cleaned;
        } catch (Exception ex) {
            return content;
        }
    }

    private int estimateSizeBytes(NBTItem item) {
        String serialized = item.toString();
        if (serialized == null) {
            return 0;
        }
        return serialized.getBytes(StandardCharsets.UTF_8).length;
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
        debug("Applied cached NBT to item %s (%s) for %s", item.getUniqueId(), describeItem(restored.get()), reason);

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
                if (restoreItem(item, reason)) {
                    debug("Restored tracked item %s for %s", uuid, reason);
                }
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

    public Optional<ItemStack> createClientViewStack(ItemStack placeholder) {
        if (placeholder == null || placeholder.getType() == Material.AIR) {
            return Optional.empty();
        }

        try {
            ItemStack template = placeholder.clone();
            NBTItem nbtItem = new NBTItem(template);
            if (!nbtItem.hasKey(CACHE_KEY)) {
                return Optional.empty();
            }

            int hashKey = nbtItem.getInteger(CACHE_KEY);
            CachedEntry entry = cache.get(hashKey);
            if (entry == null) {
                return Optional.empty();
            }

            return entry.createItemStack(template);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public void debug(String message, Object... args) {
        if (!debugLogging) {
            return;
        }
        String formatted = args.length == 0 ? message : String.format(message, args);
        plugin.getLogger().info("[Debug] " + formatted);
    }

    private String describeItem(ItemStack stack) {
        if (stack == null) {
            return "null";
        }
        String base = stack.getType().name() + "x" + stack.getAmount();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return base + " (" + meta.getDisplayName() + ")";
        }
        return base;
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
