package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.dimensionnet.UnifiedStorage;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BeyondDimensionsIntegration {
    private static final String MOD_ID = "beyonddimensions";
    private static final Map<UUID, Binding> BINDINGS = new HashMap<>();

    private BeyondDimensionsIntegration() {
    }

    static boolean isAvailable() {
        return Config.enableBeyondDimensions && ModList.get().isLoaded(MOD_ID);
    }

    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (!isAvailable() || !(menu instanceof DimensionsNetMenu netMenu)
                || !(netMenu.storage instanceof UnifiedStorage storage)
                || storage.getNet() == null) {
            return;
        }

        Binding oldBinding = BINDINGS.get(player.getUUID());
        if (oldBinding != null && oldBinding.storage == storage) {
            oldBinding.menu = menu;
            ContainerScanManager.requestDetection(player);
            return;
        }

        if (oldBinding != null) {
            oldBinding.close();
        }

        Binding binding = new Binding(player, menu, storage);
        BINDINGS.put(player.getUUID(), binding);
        binding.startScan();
    }

    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        Binding binding = BINDINGS.get(player.getUUID());
        if (binding != null && binding.menu == menu) {
            BINDINGS.remove(player.getUUID());
            binding.close();
            ContainerScanManager.requestDetection(player);
        }
    }

    static void tick(MinecraftServer server) {
        BINDINGS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Binding binding = entry.getValue();
            if (player == null || !binding.isUsable()) {
                binding.close();
                if (player != null) {
                    ContainerScanManager.requestDetection(player);
                }
                return true;
            }

            binding.processBatch();
            return false;
        });
    }

    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        Binding binding = BINDINGS.get(player.getUUID());
        return binding == null ? List.of() : binding.getCachedStacks();
    }

    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        if (!Config.consumeFromBeyondDimensions || remaining <= 0L) {
            return 0L;
        }

        Binding binding = BINDINGS.get(player.getUUID());
        if (binding == null || !binding.ready || !binding.isUsable()) {
            return 0L;
        }

        Map<ItemStackKey, Long> candidates = new HashMap<>();
        binding.cached.forEach((key, amount) -> {
            if (amount > 0L && task.test(key.copyStack())) {
                candidates.put(key, amount);
            }
        });

        long consumed = 0L;
        for (Map.Entry<ItemStackKey, Long> entry : candidates.entrySet()) {
            if (consumed >= remaining) {
                break;
            }

            long requested = Math.min(entry.getValue(), remaining - consumed);
            KeyAmount extracted = binding.storage.extract(entry.getKey(), requested, false, false);
            long actual = Math.min(requested, Math.max(0L, extracted.amount()));
            if (actual > 0L) {
                consumed += actual;
                binding.updateKey(entry.getKey(), false);
            }
        }
        return consumed;
    }

    static void removePlayer(ServerPlayer player) {
        Binding binding = BINDINGS.remove(player.getUUID());
        if (binding != null) {
            binding.close();
        }
    }

    static void clear() {
        BINDINGS.values().forEach(Binding::close);
        BINDINGS.clear();
    }

    private static final class Binding {
        final ServerPlayer player;
        AbstractContainerMenu menu;
        final UnifiedStorage storage;
        final Map<ItemStackKey, Long> cached = new HashMap<>();
        final Set<ItemStackKey> changedDuringScan = new HashSet<>();
        AutoCloseable deltaSubscription;
        AutoCloseable anySubscription;
        List<ItemStackKey> initialKeys = List.of();
        int initialIndex;
        boolean scanning;
        boolean ready;
        boolean closed;

        Binding(ServerPlayer player, AbstractContainerMenu menu, UnifiedStorage storage) {
            this.player = player;
            this.menu = menu;
            this.storage = storage;
            this.deltaSubscription = storage.subscribeDelta(this, this::onDelta);
            this.anySubscription = storage.subscribeAny(this, this::onAnyChange);
        }

        void startScan() {
            cached.clear();
            changedDuringScan.clear();
            List<ItemStackKey> keys = new ArrayList<>();
            for (KeyAmount entry : storage.getStorage()) {
                if (entry.amount() > 0L && entry.key() instanceof ItemStackKey itemKey) {
                    keys.add(itemKey);
                }
            }
            initialKeys = keys;
            initialIndex = 0;
            scanning = true;
            ready = false;
        }

        void processBatch() {
            if (closed || !scanning) {
                return;
            }

            int budget = Math.max(1, Config.maxBeyondDimensionsEntriesPerPlayerPerTick);
            while (budget-- > 0 && initialIndex < initialKeys.size()) {
                ItemStackKey key = initialKeys.get(initialIndex++);
                if (!changedDuringScan.remove(key)) {
                    updateKey(key, false);
                }
            }

            if (initialIndex >= initialKeys.size()) {
                scanning = false;
                ready = true;
                ContainerScanManager.requestDetection(player);
            }
        }

        void onDelta(IStackKey<?> key, long size, boolean insert) {
            if (closed || !(key instanceof ItemStackKey itemKey)) {
                return;
            }
            if (scanning) {
                changedDuringScan.add(itemKey);
            }
            updateKey(itemKey, ready);
        }

        void onAnyChange() {
            if (!closed) {
                startScan();
            }
        }

        void updateKey(ItemStackKey key, boolean requestDetection) {
            long amount = storage.getStackByKey(key).amount();
            if (amount > 0L) {
                cached.put(key, amount);
            } else {
                cached.remove(key);
            }
            if (requestDetection) {
                ContainerScanManager.requestDetection(player);
            }
        }

        boolean isUsable() {
            DimensionsNet net = storage.getNet();
            return !closed && net != null && !net.deleted;
        }

        List<ItemStack> getCachedStacks() {
            if (!ready) {
                return List.of();
            }

            List<ItemStack> result = new ArrayList<>(cached.size());
            cached.forEach((key, amount) -> {
                long remaining = amount;
                while (remaining > 0L) {
                    long stackAmount = Math.min((long) Integer.MAX_VALUE, remaining);
                    result.add(key.copyStackWithCount(stackAmount));
                    remaining -= stackAmount;
                }
            });
            return result;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeSubscription(deltaSubscription);
            closeSubscription(anySubscription);
            deltaSubscription = null;
            anySubscription = null;
            cached.clear();
            changedDuringScan.clear();
            initialKeys = List.of();
            scanning = false;
            ready = false;
        }

        private static void closeSubscription(AutoCloseable subscription) {
            if (subscription == null) {
                return;
            }
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        }
    }
}
