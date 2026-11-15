package com.ftb_quests_container_integration.container;

import appeng.api.config.Actionable;
import appeng.api.implementations.menuobjects.IPortableTerminal;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.me.common.MEStorageMenu;
import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class AppliedEnergistics2Integration {
    private static final String MOD_ID = "ae2";
    private static final Map<UUID, Binding> BINDINGS = new HashMap<>();
    private static final Map<IGrid, NetworkCache> NETWORK_CACHES = new IdentityHashMap<>();

    private AppliedEnergistics2Integration() {
    }

    static boolean isAvailable() {
        return Config.enableAppliedEnergistics2 && ModList.get().isLoaded(MOD_ID);
    }

    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (!isAvailable() || !(menu instanceof MEStorageMenu storageMenu)) {
            return;
        }

        ITerminalHost host = storageMenu.getHost();
        boolean portableHost = host instanceof IPortableTerminal && !(host instanceof IActionHost);
        boolean wirelessHost = host instanceof IPortableTerminal && host instanceof IActionHost;
        if (!portableHost && !wirelessHost) {
            return;
        }

        MEStorage storage = host.getInventory();
        if (storage == null) {
            return;
        }

        IGridNode networkNode = storageMenu.getGridNode();
        if (wirelessHost && networkNode == null && host instanceof IActionHost actionHost) {
            networkNode = actionHost.getActionableNode();
        }
        IGrid grid = networkNode == null ? null : networkNode.getGrid();
        if (portableHost && grid != null || wirelessHost && grid == null) {
            return;
        }

        ItemStack sourceStack = null;
        Integer sourceSlot = null;
        ItemMenuHostLocator sourceLocator = null;
        if (host instanceof ItemMenuHost<?> itemMenuHost) {
            sourceStack = itemMenuHost.getItemStack();
            sourceSlot = itemMenuHost.getPlayerInventorySlot();
            sourceLocator = itemMenuHost.getLocator();
        }

        IEnergySource energySource = host instanceof IEnergySource portableEnergy
                ? portableEnergy
                : grid == null ? null : grid.getEnergyService();
        if (energySource == null) {
            return;
        }

        NetworkCache networkCache = grid == null ? null : NETWORK_CACHES.computeIfAbsent(grid, NetworkCache::new);
        Binding newBinding = new Binding(player, menu, grid, networkNode, storage, energySource, sourceStack,
                sourceSlot, sourceLocator, networkCache);
        Binding oldBinding = BINDINGS.get(player.getUUID());
        if (oldBinding != null && oldBinding.isSameSource(newBinding)) {
            oldBinding.menu = menu;
            ContainerScanManager.requestDetection(player);
            return;
        }

        if (oldBinding != null) {
            oldBinding.close();
        }
        BINDINGS.put(player.getUUID(), newBinding);
        long gameTime = player.serverLevel().getGameTime();
        if (networkCache == null) {
            newBinding.refresh(gameTime);
        } else {
            networkCache.addBinding(newBinding);
            if (!networkCache.ready) {
                networkCache.refresh(gameTime);
            }
            ContainerScanManager.requestDetection(player);
        }
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
        long gameTime = server.overworld().getGameTime();
        BINDINGS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entry.getValue().close();
                return true;
            }

            Binding binding = entry.getValue();
            if (!binding.isSourcePresent() || binding.grid != null && !binding.isUsable()) {
                binding.close();
                ContainerScanManager.requestDetection(player);
                return true;
            }

            return false;
        });
        NETWORK_CACHES.values().forEach(cache -> cache.tick(gameTime));
        BINDINGS.values().forEach(binding -> {
            if (binding.networkCache == null) {
                binding.tick(gameTime);
            }
        });
    }

    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        Binding binding = BINDINGS.get(player.getUUID());
        return binding == null ? List.of() : binding.getCachedStacks();
    }

    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        if (!Config.consumeFromAppliedEnergistics2 || remaining <= 0L) {
            return 0L;
        }

        Binding binding = BINDINGS.get(player.getUUID());
        if (binding == null || !binding.isReady() || !binding.isUsable()) {
            return 0L;
        }

        long consumed = 0L;
        Map<AEItemKey, Long> counts = binding.cachedCounts();
        List<Map.Entry<AEItemKey, Long>> candidates = new ArrayList<>();
        for (Map.Entry<AEItemKey, Long> entry : counts.entrySet()) {
            if (task.test(entry.getKey().toStack(1))) {
                candidates.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }

        boolean changed = false;
        for (Map.Entry<AEItemKey, Long> entry : candidates) {
            if (consumed >= remaining) {
                break;
            }

            long amount = Math.min(entry.getValue(), remaining - consumed);
            long extracted = StorageHelper.poweredExtraction(
                    binding.energySource,
                    binding.storage,
                    entry.getKey(),
                    amount,
                    IActionSource.ofPlayer(player),
                    Actionable.MODULATE);
            if (extracted <= 0L) {
                continue;
            }

            consumed += extracted;
            changed = true;
            binding.updateKey(entry.getKey(), entry.getValue() - extracted);
        }

        if (changed && binding.networkCache == null) {
            binding.rebuildCachedStacks();
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
        NETWORK_CACHES.clear();
    }

    private static final class NetworkCache {
        final IGrid grid;
        final Set<Binding> bindings = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<AEItemKey, Long> cachedCounts = new HashMap<>();
        Map<AEItemKey, List<ItemStack>> cachedStacks = new HashMap<>();
        long nextRefreshGameTime;
        boolean ready;

        NetworkCache(IGrid grid) {
            this.grid = grid;
        }

        void addBinding(Binding binding) {
            if (bindings.add(binding)) {
                NETWORK_CACHES.putIfAbsent(grid, this);
            }
        }

        void removeBinding(Binding binding) {
            bindings.remove(binding);
            if (bindings.isEmpty()) {
                ready = false;
                cachedCounts.clear();
                cachedStacks.clear();
                nextRefreshGameTime = 0L;
                if (NETWORK_CACHES.get(grid) == this) {
                    NETWORK_CACHES.remove(grid);
                }
            }
        }

        void tick(long gameTime) {
            if (bindings.isEmpty()) {
                return;
            }

            boolean usable = false;
            for (Binding binding : bindings) {
                if (!binding.closed && binding.isUsable()) {
                    usable = true;
                    break;
                }
            }
            if (!usable) {
                if (ready) {
                    ready = false;
                    cachedCounts.clear();
                    cachedStacks.clear();
                    bindings.forEach(binding -> ContainerScanManager.requestDetection(binding.player));
                }
                nextRefreshGameTime = gameTime + 1L;
                return;
            }

            if (gameTime >= nextRefreshGameTime) {
                refresh(gameTime);
            }
        }

        void refresh(long gameTime) {
            KeyCounter available = grid.getStorageService().getCachedInventory();
            Map<AEItemKey, Long> refreshed = new HashMap<>();
            for (Object2LongMap.Entry<AEKey> entry : available) {
                if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0L) {
                    refreshed.put(itemKey, entry.getLongValue());
                }
            }

            boolean changed = !ready;
            Map<AEItemKey, List<ItemStack>> stacks = new HashMap<>(refreshed.size());
            for (Map.Entry<AEItemKey, Long> entry : refreshed.entrySet()) {
                Long oldAmount = cachedCounts.get(entry.getKey());
                List<ItemStack> oldStacks = cachedStacks.get(entry.getKey());
                if (oldAmount != null && oldAmount.longValue() == entry.getValue() && oldStacks != null) {
                    stacks.put(entry.getKey(), oldStacks);
                } else {
                    stacks.put(entry.getKey(), createStackParts(entry.getKey(), entry.getValue()));
                    changed = true;
                }
            }
            if (refreshed.size() != cachedCounts.size()) {
                changed = true;
            }

            cachedCounts = refreshed;
            cachedStacks = stacks;
            ready = true;
            nextRefreshGameTime = gameTime + Math.max(1L, (long) Config.appliedEnergistics2RefreshIntervalSeconds * 20L);
            if (changed) {
                bindings.forEach(binding -> ContainerScanManager.requestDetection(binding.player));
            }
        }

        void updateKey(AEItemKey key, long amount) {
            if (amount <= 0L) {
                cachedCounts.remove(key);
                cachedStacks.remove(key);
            } else {
                cachedCounts.put(key, amount);
                cachedStacks.put(key, createStackParts(key, amount));
            }
        }

        List<ItemStack> getCachedStacks() {
            if (!ready) {
                return List.of();
            }
            List<ItemStack> result = new ArrayList<>(cachedCounts.size());
            cachedStacks.values().forEach(stacks -> stacks.forEach(stack -> result.add(stack.copy())));
            return result;
        }
    }

    private static final class Binding {
        final ServerPlayer player;
        AbstractContainerMenu menu;
        final IGrid grid;
        final IGridNode networkNode;
        final MEStorage storage;
        final IEnergySource energySource;
        final ItemStack sourceStackReference;
        final Integer sourceSlot;
        final ItemMenuHostLocator sourceLocator;
        final NetworkCache networkCache;
        Map<AEItemKey, Long> cachedCounts = new HashMap<>();
        List<ItemStack> cachedStacks = List.of();
        long nextRefreshGameTime;
        boolean ready;
        boolean closed;

        Binding(ServerPlayer player, AbstractContainerMenu menu, IGrid grid, IGridNode networkNode, MEStorage storage,
                IEnergySource energySource, ItemStack sourceStackReference, Integer sourceSlot,
                ItemMenuHostLocator sourceLocator, NetworkCache networkCache) {
            this.player = player;
            this.menu = menu;
            this.grid = grid;
            this.networkNode = networkNode;
            this.storage = storage;
            this.energySource = energySource;
            this.sourceStackReference = sourceStackReference;
            this.sourceSlot = sourceSlot;
            this.sourceLocator = sourceLocator;
            this.networkCache = networkCache;
        }

        void tick(long gameTime) {
            if (closed || networkCache != null || gameTime < nextRefreshGameTime) {
                return;
            }
            if (!isUsable()) {
                if (ready) {
                    ready = false;
                    cachedCounts.clear();
                    cachedStacks = List.of();
                    ContainerScanManager.requestDetection(player);
                }
                nextRefreshGameTime = gameTime + 1L;
                return;
            }
            refresh(gameTime);
        }

        void refresh(long gameTime) {
            KeyCounter available = storage.getAvailableStacks();
            Map<AEItemKey, Long> refreshed = new HashMap<>();
            for (Object2LongMap.Entry<AEKey> entry : available) {
                if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0L) {
                    refreshed.put(itemKey, entry.getLongValue());
                }
            }

            boolean changed = !ready || !cachedCounts.equals(refreshed);
            if (changed) {
                cachedCounts = refreshed;
                cachedStacks = createStacks(refreshed);
            }
            ready = true;
            nextRefreshGameTime = gameTime + Math.max(1L, (long) Config.appliedEnergistics2RefreshIntervalSeconds * 20L);
            if (changed) {
                ContainerScanManager.requestDetection(player);
            }
        }

        boolean isUsable() {
            return grid == null || networkNode != null && networkNode.isActive() && networkNode.getGrid() == grid;
        }

        boolean isReady() {
            return networkCache == null ? ready : networkCache.ready;
        }

        Map<AEItemKey, Long> cachedCounts() {
            return networkCache == null ? cachedCounts : networkCache.cachedCounts;
        }

        void updateKey(AEItemKey key, long amount) {
            if (networkCache != null) {
                networkCache.updateKey(key, amount);
            } else if (amount <= 0L) {
                cachedCounts.remove(key);
            } else {
                cachedCounts.put(key, amount);
            }
        }

        void rebuildCachedStacks() {
            cachedStacks = createStacks(cachedCounts);
        }

        boolean isSourcePresent() {
            if (sourceStackReference == null) {
                return true;
            }
            if (sourceLocator != null && sourceLocator.locateItem(player) == sourceStackReference) {
                return true;
            }
            if (sourceSlot != null && sourceSlot >= 0 && sourceSlot < player.getInventory().getContainerSize()
                    && player.getInventory().getItem(sourceSlot) == sourceStackReference) {
                return true;
            }
            for (ItemStack stack : player.getInventory().items) {
                if (stack == sourceStackReference) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().armor) {
                if (stack == sourceStackReference) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (stack == sourceStackReference) {
                    return true;
                }
            }
            return ModList.get().isLoaded("curios") && CuriosSupport.contains(player, sourceStackReference);
        }

        boolean isSameSource(Binding other) {
            if (grid != null || other.grid != null) {
                boolean sameNetwork = grid != null && grid == other.grid;
                boolean sameItemSource = sourceStackReference == null && other.sourceStackReference == null
                        || sourceStackReference != null && sourceStackReference == other.sourceStackReference
                        && Objects.equals(sourceSlot, other.sourceSlot);
                return sameNetwork && sameItemSource;
            }
            return sourceStackReference != null && sourceStackReference == other.sourceStackReference
                    && Objects.equals(sourceSlot, other.sourceSlot);
        }

        List<ItemStack> getCachedStacks() {
            if (networkCache != null) {
                return networkCache.getCachedStacks();
            }

            if (!ready) {
                return List.of();
            }

            List<ItemStack> result = new ArrayList<>(cachedStacks.size());
            cachedStacks.forEach(stack -> result.add(stack.copy()));
            return result;
        }

        void close() {
            if (!closed) {
                closed = true;
                if (networkCache != null) {
                    networkCache.removeBinding(this);
                } else {
                    ready = false;
                    cachedCounts.clear();
                    cachedStacks = List.of();
                }
            }
        }
    }

    private static Map<AEItemKey, List<ItemStack>> createStackParts(Map<AEItemKey, Long> counts) {
        Map<AEItemKey, List<ItemStack>> result = new HashMap<>(counts.size());
        counts.forEach((key, amount) -> result.put(key, createStackParts(key, amount)));
        return result;
    }

    private static List<ItemStack> createStackParts(AEItemKey key, long amount) {
        List<ItemStack> result = new ArrayList<>();
        long remaining = amount;
        while (remaining > 0L) {
            int stackAmount = (int) Math.min((long) Integer.MAX_VALUE, remaining);
            result.add(key.toStack(stackAmount));
            remaining -= stackAmount;
        }
        return result;
    }

    private static List<ItemStack> createStacks(Map<AEItemKey, Long> counts) {
        List<ItemStack> result = new ArrayList<>(counts.size());
        createStackParts(counts).values().forEach(result::addAll);
        return result;
    }
}
