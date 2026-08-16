package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import appeng.api.config.Actionable;
import appeng.api.implementations.menuobjects.IPortableTerminal;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.common.MEStorageMenu;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// 负责把 Applied Energistics 2 的 ME 存储转换为 FTB Quests 可读取的玩家缓存。
final class AppliedEnergistics2Integration {
    private static final String MOD_ID = "ae2";
    private static final Map<UUID, Binding> BINDINGS = new HashMap<>();
    private static final Map<IGrid, NetworkCache> NETWORK_CACHES = new IdentityHashMap<>();

    private AppliedEnergistics2Integration() {
    }

    // 判断当前环境是否可以使用 Applied Energistics 2 集成。
    static boolean isAvailable() {
        return Config.enableAppliedEnergistics2 && ModList.get().isLoaded(MOD_ID);
    }

    // 玩家打开 AE2 存储菜单时绑定对应的网络或便携存储。
    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (!isAvailable() || !(menu instanceof MEStorageMenu storageMenu)) {
            return;
        }

        ITerminalHost host = storageMenu.getHost();
        MEStorage storage = host.getInventory();
        if (storage == null) {
            return;
        }

        IGridNode networkNode = storageMenu.getNetworkNode();
        if (networkNode == null && host instanceof IActionHost actionHost) {
            networkNode = actionHost.getActionableNode();
        }
        IGrid grid = networkNode == null ? null : networkNode.getGrid();
        if (grid == null && (!(host instanceof IPortableTerminal) || host instanceof IActionHost)) {
            return;
        }

        ItemStack sourceStack = null;
        Integer sourceSlot = null;
        if (host instanceof ItemMenuHost itemMenuHost) {
            sourceStack = itemMenuHost.getItemStack();
            sourceSlot = itemMenuHost.getSlot();
        }

        IEnergySource energySource = host instanceof IEnergySource portableEnergy
                ? portableEnergy
                : grid == null ? null : grid.getEnergyService();
        if (energySource == null) {
            return;
        }

        NetworkCache networkCache = grid == null ? null : NETWORK_CACHES.computeIfAbsent(grid, NetworkCache::new);
        Binding newBinding = new Binding(player, menu, grid, networkNode, storage, energySource, sourceStack, sourceSlot,
                networkCache);
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

    // 玩家关闭 AE2 存储菜单时解除当前菜单对应的绑定。
    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        Binding binding = BINDINGS.get(player.getUUID());
        if (binding != null && binding.menu == menu) {
            BINDINGS.remove(player.getUUID());
            binding.close();
            ContainerScanManager.requestDetection(player);
        }
    }

    // 每个服务端 tick 检查绑定网络状态，并推进 watcher 失败时的轮询兜底。
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

    // 返回当前绑定 AE2 存储的缓存快照。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        Binding binding = BINDINGS.get(player.getUUID());
        return binding == null ? List.of() : binding.getCachedStacks();
    }

    // 从当前绑定的 AE2 存储中实际提取消耗型任务物品。
    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        if (!Config.consumeFromAppliedEnergistics2 || remaining <= 0L) {
            return 0L;
        }

        Binding binding = BINDINGS.get(player.getUUID());
        if (binding == null || !binding.isReady() || !binding.isUsable()) {
            return 0L;
        }

        long consumed = 0L;
        Iterator<Map.Entry<AEItemKey, Long>> iterator = binding.cachedCounts().entrySet().iterator();
        while (iterator.hasNext() && consumed < remaining) {
            Map.Entry<AEItemKey, Long> entry = iterator.next();
            if (!task.test(entry.getKey().toStack(1))) {
                continue;
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
            long left = entry.getValue() - extracted;
            if (left <= 0L) {
                iterator.remove();
            } else {
                entry.setValue(left);
            }
        }

        if (consumed > 0L) {
            binding.rebuildCachedStacks();
        }
        return consumed;
    }

    // 删除指定玩家的 AE2 绑定和缓存。
    static void removePlayer(ServerPlayer player) {
        Binding binding = BINDINGS.remove(player.getUUID());
        if (binding != null) {
            binding.close();
        }
    }

    // 清理服务端停止时残留的所有 AE2 绑定。
    static void clear() {
        BINDINGS.values().forEach(Binding::close);
        BINDINGS.clear();
        NETWORK_CACHES.clear();
    }

    // 保存同一个 AE2 网络的共享缓存，避免多个玩家重复读取和构造物品栈。
    private static final class NetworkCache {
        final IGrid grid;
        final Set<Binding> bindings = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<AEItemKey, Long> cachedCounts = new HashMap<>();
        Map<AEItemKey, List<ItemStack>> cachedStacks = new HashMap<>();
        long nextRefreshGameTime;
        boolean ready;
        IManagedGridNode watcherNode;
        IGridConnection watcherConnection;
        NetworkWatcher watcher;

        // 创建一个网络级缓存对象。
        NetworkCache(IGrid grid) {
            this.grid = grid;
        }

        // 记录玩家绑定，并在第一个玩家进入时建立网络 watcher。
        void addBinding(Binding binding) {
            if (!bindings.add(binding)) {
                return;
            }
            NETWORK_CACHES.putIfAbsent(grid, this);
            if (bindings.size() == 1) {
                startWatcher(grid.getPivot());
            }
        }

        // 删除玩家绑定，并在网络无人使用时销毁 watcher 和缓存。
        void removeBinding(Binding binding) {
            bindings.remove(binding);
            if (bindings.isEmpty()) {
                stopWatcher();
                ready = false;
                cachedCounts.clear();
                cachedStacks.clear();
                nextRefreshGameTime = 0L;
                if (NETWORK_CACHES.get(grid) == this) {
                    NETWORK_CACHES.remove(grid);
                }
            }
        }

        // 网络失效时清理缓存；watcher 创建失败时保留三秒轮询作为兜底。
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
                stopWatcher();
                nextRefreshGameTime = gameTime + 1L;
                return;
            }

            if (watcher == null && gameTime >= nextRefreshGameTime) {
                refresh(gameTime);
            }
        }

        // 建立一个不占用世界方块的 AE2 网格节点，并注册全量物品 watcher。
        private void startWatcher(IGridNode targetNode) {
            if (watcher != null || targetNode == null) {
                return;
            }

            NetworkWatcher networkWatcher = new NetworkWatcher(this);
            IManagedGridNode managedNode = GridHelper.createManagedNode(networkWatcher, (owner, node) -> {
            });
            managedNode.setInWorldNode(false)
                    .setFlags(GridFlags.CANNOT_CARRY)
                    .setIdlePowerUsage(0)
                    .addService(IStorageWatcherNode.class, networkWatcher);
            try {
                managedNode.create(targetNode.getLevel(), BlockPos.ZERO);
                IGridNode watcherGridNode = managedNode.getNode();
                if (watcherGridNode == null) {
                    managedNode.destroy();
                    return;
                }

                watcherNode = managedNode;
                watcher = networkWatcher;
                watcherConnection = GridHelper.createConnection(watcherGridNode, targetNode);
            } catch (RuntimeException exception) {
                watcher = null;
                watcherNode = null;
                managedNode.destroy();
            }
        }

        // 销毁虚拟节点和连接，避免网络在终端关闭后继续保留 watcher。
        private void stopWatcher() {
            if (watcherConnection != null) {
                watcherConnection.destroy();
                watcherConnection = null;
            }
            if (watcherNode != null) {
                watcherNode.destroy();
                watcherNode = null;
            }
            watcher = null;
        }

        // 首次建立 watcher 或 watcher 失败时读取一次 AE2 网络完整快照。
        void refresh(long gameTime) {
            KeyCounter available = grid.getStorageService().getCachedInventory();
            Map<AEItemKey, Long> refreshed = new HashMap<>();
            for (Object2LongMap.Entry<AEKey> entry : available) {
                if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0L) {
                    refreshed.put(itemKey, entry.getLongValue());
                }
            }

            boolean changed = !ready || !cachedCounts.equals(refreshed);
            if (changed) {
                cachedCounts = refreshed;
                cachedStacks = createStackParts(refreshed);
            }
            ready = true;
            nextRefreshGameTime = gameTime + Math.max(1L, (long) Config.appliedEnergistics2RefreshIntervalSeconds * 20L);
            if (changed) {
                bindings.forEach(binding -> ContainerScanManager.requestDetection(binding.player));
            }
        }

        // 根据消耗结果重建共享物品栈快照。
        void rebuildCachedStacks() {
            cachedStacks = createStackParts(cachedCounts);
        }

        // 接收 AE2 单个物品键的最新数量，并只重建该物品的物品栈。
        void onStackChange(AEKey key, long amount) {
            if (!(key instanceof AEItemKey itemKey)) {
                return;
            }

            Long oldAmount = cachedCounts.get(itemKey);
            if (amount <= 0L) {
                if (oldAmount == null) {
                    return;
                }
                cachedCounts.remove(itemKey);
                cachedStacks.remove(itemKey);
            } else {
                if (oldAmount != null && oldAmount == amount) {
                    return;
                }
                cachedCounts.put(itemKey, amount);
                cachedStacks.put(itemKey, createStackParts(itemKey, amount));
            }

            ready = true;
            bindings.forEach(binding -> ContainerScanManager.requestDetection(binding.player));
        }

        // 返回共享缓存的副本，避免 FTB Quests 持有内部可变对象。
        List<ItemStack> getCachedStacks() {
            if (!ready) {
                return List.of();
            }
            List<ItemStack> result = new ArrayList<>(cachedCounts.size());
            cachedStacks.values().forEach(stacks -> stacks.forEach(stack -> result.add(stack.copy())));
            return result;
        }
    }

    // 把 AE2 watcher 回调转发到对应网络的增量缓存。
    private static final class NetworkWatcher implements IStorageWatcherNode {
        private final NetworkCache cache;

        // 创建一个绑定到网络缓存的 watcher 服务。
        NetworkWatcher(NetworkCache cache) {
            this.cache = cache;
        }

        // 监听所有物品键，使网络变动可以立即进入增量回调。
        @Override
        public void updateWatcher(IStackWatcher watcher) {
            watcher.setWatchAll(true);
        }

        // 把 AE2 的单键数量变化交给网络缓存处理。
        @Override
        public void onStackChange(AEKey key, long amount) {
            cache.onStackChange(key, amount);
        }
    }

    // 保存玩家终端来源和便携存储的独立缓存，网络缓存由 NetworkCache 统一维护。
    private static final class Binding {
        final ServerPlayer player;
        AbstractContainerMenu menu;
        final IGrid grid;
        final IGridNode networkNode;
        final MEStorage storage;
        final IEnergySource energySource;
        final ItemStack sourceStackReference;
        final Integer sourceSlot;
        final NetworkCache networkCache;
        Map<AEItemKey, Long> cachedCounts = new HashMap<>();
        List<ItemStack> cachedStacks = List.of();
        long nextRefreshGameTime;
        boolean ready;
        boolean closed;

        // 创建一个绑定并记录其来源身份。
        Binding(ServerPlayer player, AbstractContainerMenu menu, IGrid grid, IGridNode networkNode, MEStorage storage,
                IEnergySource energySource, ItemStack sourceStackReference, Integer sourceSlot,
                NetworkCache networkCache) {
            this.player = player;
            this.menu = menu;
            this.grid = grid;
            this.networkNode = networkNode;
            this.storage = storage;
            this.energySource = energySource;
            this.sourceStackReference = sourceStackReference;
            this.sourceSlot = sourceSlot;
            this.networkCache = networkCache;
        }

        // 按周期读取便携 AE2 存储的完整物品快照。
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

        // 读取一次便携 AE2 存储的完整物品快照。
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

        // 判断当前绑定是否仍然可以进行读取和提取。
        boolean isUsable() {
            return grid == null || networkNode != null && networkNode.isActive() && networkNode.getGrid() == grid;
        }

        // 判断当前缓存是否已经完成初始读取。
        boolean isReady() {
            return networkCache == null ? ready : networkCache.ready;
        }

        // 返回当前绑定使用的数量映射。
        Map<AEItemKey, Long> cachedCounts() {
            return networkCache == null ? cachedCounts : networkCache.cachedCounts;
        }

        // 根据消耗结果重建当前绑定对应的物品栈快照。
        void rebuildCachedStacks() {
            if (networkCache == null) {
                cachedStacks = createStacks(cachedCounts);
            } else {
                networkCache.rebuildCachedStacks();
            }
        }

        // 判断便携 AE2 物品来源是否仍在玩家物品栏中。
        boolean isSourcePresent() {
            if (sourceStackReference == null) {
                return true;
            }
            if (sourceSlot != null && player.getInventory().getItem(sourceSlot) == sourceStackReference) {
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
            return false;
        }

        // 判断两个绑定是否指向同一个 ME 网络或同一个便携物品。
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

        // 返回缓存物品副本，避免 FTB Quests 持有 AE2 内部对象。
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

        // 关闭绑定并释放便携缓存或共享网络缓存引用。
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

    // 把 AE2 的长数量条目转换为 FTB Quests 可读取的物品栈。
    private static Map<AEItemKey, List<ItemStack>> createStackParts(Map<AEItemKey, Long> counts) {
        Map<AEItemKey, List<ItemStack>> result = new HashMap<>(counts.size());
        counts.forEach((key, amount) -> result.put(key, createStackParts(key, amount)));
        return result;
    }

    // 把单个 AE2 物品键的数量拆分成 FTB Quests 可读取的物品栈。
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

    // 把 AE2 的长数量条目转换为 FTB Quests 可读取的扁平物品栈列表。
    private static List<ItemStack> createStacks(Map<AEItemKey, Long> counts) {
        List<ItemStack> result = new ArrayList<>(counts.size());
        createStackParts(counts).values().forEach(result::addAll);
        return result;
    }
}
