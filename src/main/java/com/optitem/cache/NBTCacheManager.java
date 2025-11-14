package com.optitem.cache;

import de.tr7zw.changeme.nbtapi.NBTContainer;
import de.tr7zw.changeme.nbtapi.NBTItem;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBTList;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

@SuppressWarnings("deprecation")
public class NBTCacheManager {

    private static final String CACHE_KEY = "CacheRef";
    private static final List<String> PRIORITY_TRIM_KEYS = List.of(
            "SkullOwner",
            "EntityTag",
            "BlockEntityTag",
            "Enchantments",
            "StoredEnchantments",
            "AttributeModifiers",
            "CustomModelData");
    private final JavaPlugin plugin;
    private final long cleanupIntervalSeconds;
    private final int maxShulkerNbtSizeBytes;
    private final boolean debugLogging;

    private final ConcurrentMap<Integer, CachedEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> entityIndex = new ConcurrentHashMap<>();
    private final Queue<ItemSpawnRequest> pendingSpawnQueue = new ConcurrentLinkedQueue<>();
    private final int asyncProcessBaseLimit = 25;
    private final int asyncProcessBurstLimit = 150;
    private final long asyncProcessTimeBudgetNanos = TimeUnit.MILLISECONDS.toNanos(8);
    private final AtomicInteger pendingSpawnCount = new AtomicInteger();
    private final AtomicBoolean acceptingWork = new AtomicBoolean(true);
    private final int maxPendingSpawn = 512;
    private final long cleanupGracePeriodNanos = TimeUnit.MILLISECONDS.toNanos(1500);

    private BukkitTask cleanupTask;
    private BukkitTask asyncWorkerTask;

    public NBTCacheManager(JavaPlugin plugin, long cleanupIntervalSeconds, int maxShulkerNbtSizeBytes,
            boolean debugLogging) {
        this.plugin = plugin;
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
        this.maxShulkerNbtSizeBytes = Math.max(1024, maxShulkerNbtSizeBytes);
        this.debugLogging = debugLogging;
    }

    public void start() {
        long intervalTicks = Math.max(20L, cleanupIntervalSeconds * 20L);
        acceptingWork.set(true);
        pendingSpawnCount.set(0);
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> cleanup("scheduled"), intervalTicks,
                intervalTicks);
        asyncWorkerTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::drainSpawnQueue,
                1L, 1L);
    }

    public void shutdown() {
        acceptingWork.set(false);
        pendingSpawnQueue.clear();
        pendingSpawnCount.set(0);
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (asyncWorkerTask != null) {
            asyncWorkerTask.cancel();
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

        if (!acceptingWork.get()) {
            return;
        }

        ItemMeta meta = stack.getItemMeta();
        boolean hasDisplayName = meta != null && meta.hasDisplayName();
        boolean hasLore = meta != null && meta.hasLore();
        boolean hasEnchantments = !stack.getEnchantments().isEmpty();
        boolean isShulker = Tag.SHULKER_BOXES.isTagged(stack.getType());

        if (!hasDisplayName && !hasLore && !hasEnchantments && !isShulker) {
            return;
        }

        if (pendingSpawnCount.incrementAndGet() > maxPendingSpawn) {
            pendingSpawnCount.decrementAndGet();
            return;
        }

        ItemStack snapshot = stack.clone();
        ItemSpawnRequest request = new ItemSpawnRequest(item.getUniqueId(), snapshot,
                hasLore, isShulker, describeItem(stack));
        pendingSpawnQueue.offer(request);
    }

    private void drainSpawnQueue() {
        if (!acceptingWork.get()) {
            return;
        }

        long start = System.nanoTime();
        int queueSize = pendingSpawnCount.get();
        int dynamicLimit = Math.min(asyncProcessBurstLimit,
                Math.max(asyncProcessBaseLimit, queueSize / 2));

        int processed = 0;
        ItemSpawnRequest request;
        while (acceptingWork.get() && processed < dynamicLimit && (request = pendingSpawnQueue.poll()) != null) {
            releasePendingSlot();
            processSpawnRequestAsync(request);
            processed++;
            if ((processed & 7) == 0 && System.nanoTime() - start >= asyncProcessTimeBudgetNanos) {
                break;
            }
        }

        if (!pendingSpawnQueue.isEmpty() && acceptingWork.get()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::drainSpawnQueue);
        }
    }

    private void releasePendingSlot() {
        int current = pendingSpawnCount.decrementAndGet();
        if (current < 0) {
            pendingSpawnCount.set(0);
        }
    }

    private void processSpawnRequestAsync(ItemSpawnRequest request) {
        if (!acceptingWork.get()) {
            return;
        }
        Optional<ProcessedItem> processed = buildProcessedItem(request);
        if (processed.isPresent()) {
            ProcessedItem result = processed.get();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    applyProcessedItem(result);
                } catch (Exception ex) {
                    debug("Failed to apply cached item %s: %s", request.entityId, ex.getMessage());
                    handleProcessingFailure(request);
                }
            });
        } else {
            handleProcessingFailure(request);
        }
    }

    private void handleProcessingFailure(ItemSpawnRequest request) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Entity entity = Bukkit.getEntity(request.entityId);
            if (!(entity instanceof Item item)) {
                return;
            }
            ItemStack fallback = request.snapshot == null ? null : request.snapshot.clone();
            if (fallback != null) {
                item.setItemStack(fallback);
            }
            removeReference(item.getUniqueId());
        });
    }

    private Optional<ProcessedItem> buildProcessedItem(ItemSpawnRequest request) {
        try {
            NBTItem nbtItem = new NBTItem(request.snapshot);
            if (nbtItem.hasKey(CACHE_KEY)) {
                return Optional.empty();
            }

            String initialSerialized = nbtItem.toString();
            if (initialSerialized == null || initialSerialized.isEmpty()) {
                return Optional.empty();
            }

            int initialSizeBytes = estimateSizeBytes(initialSerialized);

            List<String> debugMessages = new ArrayList<>();
            boolean sanitized = false;

            if (request.shulker) {
                if (initialSizeBytes > maxShulkerNbtSizeBytes) {
                    sanitized = sanitizeShulkerItem(nbtItem, debugMessages, initialSizeBytes, request.description);
                }
            }

            if (!request.hasLore && !nbtItem.hasNBTData()) {
                return Optional.empty();
            }

            String serialized = sanitized ? nbtItem.toString() : initialSerialized;
            if (serialized == null || serialized.isEmpty()) {
                return Optional.empty();
            }

            int signature = sum(serialized);
            return Optional.of(new ProcessedItem(request, serialized, signature, sanitized, debugMessages));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void applyProcessedItem(ProcessedItem processed) {
        Entity entity = Bukkit.getEntity(processed.request.entityId);
        if (!(entity instanceof Item item)) {
            return;
        }

        ItemStack base = item.getItemStack();
        if (base == null || base.getType() == Material.AIR) {
            return;
        }

        ItemStack sanitized = applySnapshotToTemplate(base, processed.serializedSnapshot);

        CachedEntry entry = cache.compute(processed.signature,
                (key, existing) -> existing == null ? new CachedEntry(processed.serializedSnapshot, sanitized)
                        : existing);
        if (entry == null) {
            return;
        }

        entry.addReference(item.getUniqueId());
        entityIndex.put(item.getUniqueId(), processed.signature);

        if (processed.sanitized) {
            item.setItemStack(sanitized.clone());
        }

        ItemStack placeholder = createPlaceholderStack(sanitized, processed.signature);
        item.setItemStack(placeholder);

        if (!processed.debugMessages.isEmpty()) {
            for (String message : processed.debugMessages) {
                debug(message);
            }
        }
    }

    private boolean sanitizeShulkerItem(NBTItem nbtItem, List<String> debugMessages, int originalSize,
            String description) {
        boolean modified = sanitizeShulkerContents(nbtItem, debugMessages);
        if (modified && description != null) {
            debugMessages.add(String.format("Trimmed shulker NBT (%d bytes) for item %s", originalSize, description));
        }
        return modified;
    }

    private boolean sanitizeShulkerContents(NBTItem nbtItem, List<String> debugMessages) {
        ReadWriteNBT blockEntity = nbtItem.getCompound("BlockEntityTag");
        if (blockEntity == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        ReadWriteNBTList<ReadWriteNBT> items = (ReadWriteNBTList<ReadWriteNBT>) blockEntity.getCompoundList("Items");
        if (items == null || items.isEmpty()) {
            return false;
        }

        boolean modified = false;
        int adjusted = 0;
        for (ReadWriteNBT itemCompound : items) {
            if (itemCompound == null) {
                continue;
            }
            if (trimLargestTag(itemCompound, debugMessages)) {
                modified = true;
                adjusted++;
            }
        }

        if (modified) {
            debugMessages.add(String.format("Sanitized contents of shulker box; %d slots adjusted", adjusted));
        }
        return modified;
    }

    private boolean trimLargestTag(ReadWriteNBT itemCompound, List<String> debugMessages) {
        ReadWriteNBT tag = itemCompound.getCompound("tag");
        if (tag == null) {
            return false;
        }
        Set<String> keys = tag.getKeys();
        if (keys.isEmpty()) {
            return false;
        }

        int originalSize = estimateSizeBytes(itemCompound.toString());
        String targetKey = null;
        int largestDelta = 0;

        List<String> orderedCandidates = new ArrayList<>();
        for (String priority : PRIORITY_TRIM_KEYS) {
            if (keys.contains(priority)) {
                orderedCandidates.add(priority);
            }
        }
        if (orderedCandidates.isEmpty()) {
            int inspected = 0;
            for (String key : keys) {
                orderedCandidates.add(key);
                if (++inspected >= 3) {
                    break;
                }
            }
        }

        for (String key : orderedCandidates) {
            int delta = computeTagRemovalDelta(itemCompound, key, originalSize);
            if (delta > largestDelta) {
                largestDelta = delta;
                targetKey = key;
            }
        }

        if (targetKey == null || largestDelta <= 0) {
            return false;
        }

        if ("display".equals(targetKey)) {
            ReadWriteNBT display = tag.getCompound("display");
            if (display != null) {
                display.removeKey("Lore");
                display.removeKey("Name");
                if (display.getKeys().isEmpty()) {
                    tag.removeKey("display");
                }
            }
        } else {
            tag.removeKey(targetKey);
        }

        if (tag.getKeys().isEmpty()) {
            itemCompound.removeKey("tag");
        }

        debugMessages.add(String.format("Removed NBT tag '%s' from shulker content (%d bytes saved)", targetKey,
                largestDelta));
        return true;
    }

    private int computeTagRemovalDelta(ReadWriteNBT itemCompound, String key, int originalSize) {
        try {
            NBTContainer copy = new NBTContainer(itemCompound.toString());
            ReadWriteNBT copyTag = copy.getCompound("tag");
            if (copyTag == null) {
                return 0;
            }
            if ("display".equals(key)) {
                ReadWriteNBT display = copyTag.getCompound("display");
                if (display == null) {
                    return 0;
                }
                display.removeKey("Lore");
                display.removeKey("Name");
                if (display.getKeys().isEmpty()) {
                    copyTag.removeKey("display");
                }
            } else {
                if (!copyTag.getKeys().contains(key)) {
                    return 0;
                }
                copyTag.removeKey(key);
            }
            if (copyTag.getKeys().isEmpty()) {
                copy.removeKey("tag");
            }
            int newSize = estimateSizeBytes(copy.toString());
            return originalSize - newSize;
        } catch (Exception ex) {
            return 0;
        }
    }

    private ItemStack applySnapshotToTemplate(ItemStack template, String serialized) {
        if (template == null) {
            return null;
        }
        ItemStack merged = template.clone();
        try {
            NBTContainer container = new NBTContainer(serialized);
            stripIdentityKeys(container);
            NBTItem nbtItem = new NBTItem(merged);
            nbtItem.mergeCompound(container);
            nbtItem.removeKey(CACHE_KEY);
            return nbtItem.getItem();
        } catch (Exception ex) {
            return merged;
        }
    }

    private int estimateSizeBytes(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return 0;
        }
        return serialized.getBytes(StandardCharsets.UTF_8).length;
    }

    public Optional<ItemStack> handleAttemptPickup(Item item) {
        if (restoreItem(item, "pickup")) {
            return Optional.ofNullable(item.getItemStack());
        }
        return Optional.empty();
    }

    public void handleChunkUnload(Chunk chunk) {
        List<Item> items = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item) {
                items.add(item);
            }
        }
        restoreItemsBulk(items, "chunk-unload");
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

            int size = estimateSizeBytes(serialized);
            if (size <= maxShulkerNbtSizeBytes) {
                return original;
            }

            List<String> debugMessages = new ArrayList<>();
            if (!sanitizeShulkerItem(nbtItem, debugMessages, size, describeItem(original))) {
                return original;
            }

            ItemStack sanitized = nbtItem.getItem();
            if (!debugMessages.isEmpty()) {
                for (String message : debugMessages) {
                    debug(message);
                }
            }
            return sanitized;
        } catch (Exception ex) {
            return original;
        }
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

        int signature = nbtItem.getInteger(CACHE_KEY);
        CachedEntry entry = cache.get(signature);
        if (entry == null) {
            item.setItemStack(removeCacheKey(stack));
            removeReference(item.getUniqueId());
            return false;
        }

        Optional<ItemStack> restored = entry.createItemStack(stack);
        if (restored.isEmpty()) {
            item.setItemStack(removeCacheKey(stack));
            removeReference(item.getUniqueId(), signature, entry);
            return false;
        }

        ItemStack restoredStack = restored.get();
        item.setItemStack(restoredStack);
        removeReference(item.getUniqueId(), signature, entry);
        debug("Applied cached NBT to item %s (%s) for %s", item.getUniqueId(), describeItem(restoredStack), reason);

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

    private void removeReference(UUID entityId, int signature, CachedEntry entry) {
        entityIndex.remove(entityId, signature);
        if (entry != null) {
            entry.removeReference(entityId);
        }
    }

    private ItemStack createPlaceholderStack(ItemStack original, int signature) {
        ItemStack placeholder = original == null ? null : original.clone();
        if (placeholder == null) {
            return null;
        }

        NBTItem nbtItem = new NBTItem(placeholder);
        nbtItem.setInteger(CACHE_KEY, signature);
        return nbtItem.getItem();
    }

    private void cleanup(String reason) {
        long cutoff = System.nanoTime() - cleanupGracePeriodNanos;
        cache.entrySet().removeIf(entry -> entry.getValue().readyForCleanup(cutoff));
    }

    public void restoreTrackedItems(String reason) {
        List<Item> items = new ArrayList<>();
        List<UUID> tracked = List.copyOf(entityIndex.keySet());
        for (UUID uuid : tracked) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity instanceof Item item) {
                items.add(item);
            }
        }
        restoreItemsBulk(items, reason);
    }

    private void restoreItemsBulk(List<Item> items, String reason) {
        if (items == null || items.isEmpty()) {
            return;
        }

        HashMap<Integer, List<Item>> buckets = new HashMap<>();
        for (Item item : items) {
            if (item == null) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            try {
                NBTItem nbtItem = new NBTItem(stack);
                if (!nbtItem.hasKey(CACHE_KEY)) {
                    continue;
                }
                int signature = nbtItem.getInteger(CACHE_KEY);
                buckets.computeIfAbsent(signature, key -> new ArrayList<>()).add(item);
            } catch (Exception ex) {
                item.setItemStack(removeCacheKey(stack));
                removeReference(item.getUniqueId());
            }
        }

        for (Map.Entry<Integer, List<Item>> entry : buckets.entrySet()) {
            int signature = entry.getKey();
            CachedEntry cached = cache.get(signature);
            if (cached == null) {
                for (Item item : entry.getValue()) {
                    ItemStack stack = item.getItemStack();
                    item.setItemStack(removeCacheKey(stack));
                    removeReference(item.getUniqueId(), signature, null);
                }
                continue;
            }

            for (Item item : entry.getValue()) {
                ItemStack stack = item.getItemStack();
                if (stack == null || stack.getType() == Material.AIR) {
                    removeReference(item.getUniqueId(), signature, cached);
                    continue;
                }

                Optional<ItemStack> restored = cached.createItemStack(stack);
                if (restored.isEmpty()) {
                    item.setItemStack(removeCacheKey(stack));
                    removeReference(item.getUniqueId(), signature, cached);
                    continue;
                }

                ItemStack restoredStack = restored.get();
                item.setItemStack(restoredStack);
                removeReference(item.getUniqueId(), signature, cached);
                debug("Applied cached NBT to item %s (%s) for %s", item.getUniqueId(), describeItem(restoredStack),
                        reason);
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

            int signature = nbtItem.getInteger(CACHE_KEY);
            CachedEntry entry = cache.get(signature);
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

            int signature = nbtItem.getInteger(CACHE_KEY);
            CachedEntry entry = cache.get(signature);
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

    private static int sum(String s) {
        int cursor = 0;
        for (int i = 0; i < s.length(); i++) {
            int ch = s.charAt(i);
            int shifted = cursor << 5;
            int mixed = shifted - cursor;
            cursor = mixed + ch;
        }
        return cursor;
    }

    private static void stripIdentityKeys(NBTContainer container) {
        if (container == null) {
            return;
        }
        container.removeKey("id");
        container.removeKey("Id");
        container.removeKey("ID");
        container.removeKey("Count");
    }

    private ItemStack removeCacheKey(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            NBTItem nbtItem = new NBTItem(stack.clone());
            nbtItem.removeKey(CACHE_KEY);
            return nbtItem.getItem();
        } catch (Exception ex) {
            return stack;
        }
    }

    private static <T> Set<T> createConcurrentSet() {
        ConcurrentMap<T, Boolean> backing = new ConcurrentHashMap<>();
        return Collections.newSetFromMap(backing);
    }

    private static final class ItemSpawnRequest {
        private final UUID entityId;
        private final ItemStack snapshot;
        private final boolean hasLore;
        private final boolean shulker;
        private final String description;

        private ItemSpawnRequest(UUID entityId, ItemStack snapshot, boolean hasLore, boolean shulker,
                String description) {
            this.entityId = entityId;
            this.snapshot = snapshot;
            this.hasLore = hasLore;
            this.shulker = shulker;
            this.description = description;
        }
    }

    private static final class ProcessedItem {
        private final ItemSpawnRequest request;
        private final String serializedSnapshot;
        private final int signature;
        private final boolean sanitized;
        private final List<String> debugMessages;

        private ProcessedItem(ItemSpawnRequest request, String serializedSnapshot, int signature, boolean sanitized,
                List<String> debugMessages) {
            this.request = request;
            this.serializedSnapshot = serializedSnapshot;
            this.signature = signature;
            this.sanitized = sanitized;
            this.debugMessages = debugMessages;
        }
    }

    private static final class CachedEntry {
        private final String nbtSnapshot;
        private final Set<UUID> references = createConcurrentSet();
        private final AtomicLong lastTouched = new AtomicLong(System.nanoTime());

        private CachedEntry(String nbtSnapshot, ItemStack prototype) {
            this.nbtSnapshot = nbtSnapshot;
        }

        private void addReference(UUID uuid) {
            references.add(uuid);
            markAccess();
        }

        private void removeReference(UUID uuid) {
            references.remove(uuid);
            markAccess();
        }

        private Optional<ItemStack> createItemStack(ItemStack baseTemplate) {
            if (baseTemplate == null) {
                return Optional.empty();
            }
            try {
                NBTContainer container = new NBTContainer(nbtSnapshot);
                stripIdentityKeys(container);
                ItemStack base = baseTemplate.clone();
                NBTItem reconstructed = new NBTItem(base);
                reconstructed.mergeCompound(container);
                reconstructed.removeKey(CACHE_KEY);
                ItemStack stack = reconstructed.getItem();
                markAccess();
                return Optional.of(stack);
            } catch (Exception ex) {
                return Optional.empty();
            }
        }

        private void markAccess() {
            lastTouched.set(System.nanoTime());
        }

        private boolean readyForCleanup(long cutoffNanos) {
            return references.isEmpty() && lastTouched.get() < cutoffNanos;
        }
    }
}
