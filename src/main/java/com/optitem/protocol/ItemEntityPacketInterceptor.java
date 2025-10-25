package com.optitem.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.EquivalentConverter;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.optitem.cache.NBTCacheManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ItemEntityPacketInterceptor {

    private static final int ITEM_STACK_DATA_INDEX = 8;

    private final Plugin plugin;
    private final NBTCacheManager cacheManager;
    private final EquivalentConverter<ItemStack> itemStackConverter = BukkitConverters.getItemStackConverter();
    private PacketAdapter listener;

    public ItemEntityPacketInterceptor(Plugin plugin, NBTCacheManager cacheManager) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
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

                    Object nmsStack = convertToNmsStack(clientStack.get());
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
                }

                if (modified) {
                    event.getPacket().getDataValueCollectionModifier().write(0, updated);
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
}
