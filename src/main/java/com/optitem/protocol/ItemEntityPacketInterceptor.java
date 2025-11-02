package com.optitem.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.EquivalentConverter;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.optitem.cache.NBTCacheManager;
import de.tr7zw.changeme.nbtapi.NBTItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SuppressWarnings("deprecation")
public final class ItemEntityPacketInterceptor {

    private static final int ITEM_STACK_DATA_INDEX = 8;

    private final Plugin plugin;
    private final NBTCacheManager cacheManager;
    private final boolean debugLogging;
    private final EquivalentConverter<ItemStack> itemStackConverter = BukkitConverters.getItemStackConverter();
    private PacketAdapter listener;

    public ItemEntityPacketInterceptor(Plugin plugin, NBTCacheManager cacheManager, boolean debugLogging) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
        this.debugLogging = debugLogging;
    }

    public void register() {
        if (listener != null) {
            return;
        }

        listener = new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }

                List<WrappedDataValue> dataValues;
                try {
                    dataValues = event.getPacket().getDataValueCollectionModifier().read(0);
                } catch (Exception ex) {
                    return;
                }

                if (dataValues == null || dataValues.isEmpty()) {
                    return;
                }

                List<WrappedDataValue> updated = new ArrayList<>(dataValues.size());
                boolean modified = false;
                int sanitizedEntries = 0;

                for (WrappedDataValue dataValue : dataValues) {
                    if (dataValue == null || dataValue.getIndex() != ITEM_STACK_DATA_INDEX) {
                        updated.add(dataValue);
                        continue;
                    }

                    ItemStack placeholder = extractItemStack(dataValue.getValue());
                    if (placeholder == null) {
                        updated.add(dataValue);
                        continue;
                    }

                    Optional<ItemStack> clientStack = cacheManager.createClientViewStack(placeholder);
                    if (clientStack.isEmpty()) {
                        updated.add(dataValue);
                        continue;
                    }

                    ItemStack sanitized = sanitizeForClient(clientStack.get());
                    Object nmsStack = convertToNmsStack(sanitized);
                    if (nmsStack == null) {
                        updated.add(dataValue);
                        continue;
                    }

                    WrappedDataValue replacement = new WrappedDataValue(
                            dataValue.getIndex(),
                            dataValue.getSerializer(),
                            nmsStack);
                    updated.add(replacement);
                    modified = true;
                    sanitizedEntries++;
                }

                if (modified) {
                    event.getPacket().getDataValueCollectionModifier().write(0, updated);
                    String target = event.getPlayer() != null ? event.getPlayer().getName() : "unknown-player";
                    ItemEntityPacketInterceptor.this.debug("Sanitized %d metadata entries for %s", sanitizedEntries,
                            target);
                }
            }
        };

        ProtocolLibrary.getProtocolManager().addPacketListener(listener);
    }

    public void unregister() {
        if (listener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
            listener = null;
        }
    }

    private Object convertToNmsStack(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            return itemStackConverter.getGeneric(stack);
        } catch (Exception ex) {
            return null;
        }
    }

    private ItemStack sanitizeForClient(ItemStack original) {
        if (original == null || original.getType() == Material.AIR) {
            return original;
        }

        ItemStack working = original.clone();
        working = cacheManager.sanitizeShulkerItem(working);
        stripLore(working);
        working = removeBlockEntityTag(working);
        return working;
    }

    private void stripLore(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return;
        }
        meta.setLore(null);
        stack.setItemMeta(meta);
    }

    private ItemStack removeBlockEntityTag(ItemStack stack) {
        try {
            NBTItem nbtItem = new NBTItem(stack.clone());
            if (!nbtItem.hasKey("BlockEntityTag")) {
                return stack;
            }
            nbtItem.removeKey("BlockEntityTag");
            ItemStack sanitized = nbtItem.getItem();
            sanitized.setAmount(stack.getAmount());
            return sanitized;
        } catch (Exception ex) {
            return stack;
        }
    }

    private ItemStack extractItemStack(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ItemStack stack) {
            return stack;
        }
        try {
            return itemStackConverter.getSpecific(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private void debug(String message, Object... args) {
        if (!debugLogging) {
            return;
        }
        String formatted = args.length == 0 ? message : String.format(message, args);
        plugin.getLogger().info("[Debug] " + formatted);
    }
}
