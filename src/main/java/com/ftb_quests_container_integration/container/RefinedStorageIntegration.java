package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import com.refinedmods.refinedstorage.blockentity.grid.WirelessGrid;
import com.refinedmods.refinedstorage.blockentity.grid.portable.IPortableGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 负责绑定 Refined Storage Grid，并维护玩家当前绑定存储的物品缓存。
final class RefinedStorageIntegration {
    private static final String MOD_ID = "refinedstorage";
    private static final Map<UUID, Binding> BINDINGS = new HashMap<>();

    private RefinedStorageIntegration() {
    }

    // 判断当前环境是否可以使用 Refined Storage API。
    static boolean isAvailable() {
        return Config.enableRefinedStorage && ModList.get().isLoaded(MOD_ID);
    }

    // 玩家打开 Grid 菜单时绑定网络或便携存储，已有同一来源的扫描不会重复创建。
    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (!isAvailable() || !(menu instanceof GridContainerMenu gridMenu)) {
            return;
        }

        Binding newBinding = createBinding(player, gridMenu.getGrid());
        if (newBinding == null) {
            return;
        }

        Binding oldBinding = BINDINGS.get(player.getUUID());
        if (oldBinding != null && oldBinding.isSameSource(newBinding)) {
            newBinding.close();
            // 任务可能在缓存建立后才开始，重新打开同一 Grid 时补发一次检测请求。
            ContainerScanManager.requestDetection(player);
            return;
        }

        if (oldBinding != null) {
            oldBinding.close();
        }
        BINDINGS.put(player.getUUID(), newBinding);
        newBinding.startScan();
    }

    // 每个服务端 tick 固定推进每位玩家的 RS 初始缓存扫描。
    static void tick(MinecraftServer server) {
        BINDINGS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entry.getValue().close();
                return true;
            }

            // 便携 Grid 被移走后立即撤销绑定，避免继续读取脱离玩家物品栏的旧存储。
            if (!entry.getValue().isSourcePresent()) {
                entry.getValue().close();
                ContainerScanManager.requestDetection(player);
                return true;
            }

            entry.getValue().processBatch();
            return false;
        });
    }

    // 返回已完成初始扫描的绑定存储快照，供 FTB Quests 的库存汇总使用。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        Binding binding = BINDINGS.get(player.getUUID());
        return binding == null ? List.of() : binding.getCachedStacks();
    }

    // 删除指定玩家的 RS 绑定和缓存监听器。
    static void removePlayer(ServerPlayer player) {
        Binding binding = BINDINGS.remove(player.getUUID());
        if (binding != null) {
            binding.close();
        }
    }

    // 从玩家绑定的 RS 存储实际抽取匹配物品并返回实际抽取数量。
    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        if (!Config.consumeFromRefinedStorage || remaining <= 0L) {
            return 0L;
        }

        Binding binding = BINDINGS.get(player.getUUID());
        if (binding == null || !binding.scanComplete) {
            return 0L;
        }

        long consumed = 0L;
        for (ItemStack candidate : binding.getMatchingStacks(task)) {
            if (consumed >= remaining) {
                break;
            }

            long needed = remaining - consumed;
            int amount = (int) Math.min((long) candidate.getCount(), needed);
            if (amount <= 0) {
                continue;
            }

            ItemStack extracted = binding.extract(player, candidate, amount);
            if (!extracted.isEmpty()) {
                consumed += extracted.getCount();
            }
        }
        return consumed;
    }

    // 清理所有玩家的监听器和绑定状态。
    static void clear() {
        BINDINGS.values().forEach(Binding::close);
        BINDINGS.clear();
    }

    // 创建普通网络、无线网络或便携 Grid 的绑定对象。
    private static Binding createBinding(ServerPlayer player, IGrid grid) {
        if (grid instanceof IPortableGrid portableGrid) {
            IStorageCache<ItemStack> cache = portableGrid.getItemCache();
            IStorageDisk<ItemStack> storage = portableGrid.getItemStorage();
            if (cache == null || storage == null) {
                return null;
            }
            return Binding.forPortable(player, grid, portableGrid, cache, storage);
        }

        if (grid instanceof INetworkAwareGrid networkAwareGrid) {
            INetwork network = networkAwareGrid.getNetwork();
            if (network == null) {
                return null;
            }

            IStorageCache<ItemStack> cache = network.getItemStorageCache();
            if (cache == null) {
                return null;
            }
            return Binding.forNetwork(player, grid, network, cache);
        }

        return null;
    }

    // 保存玩家绑定来源、增量监听器和分批扫描状态。
    private static final class Binding {
        final ServerPlayer player;
        final IGrid grid;
        final IStorageCache<ItemStack> cache;
        final INetwork network;
        final IPortableGrid portableGrid;
        final IStorageDisk<ItemStack> storage;
        final int portableSlot;
        final ItemStack portableStackReference;
        final IStorageCacheListener<ItemStack> listener;
        final Map<UUID, ItemStack> cached = new HashMap<>();
        final Set<UUID> changedDuringScan = new HashSet<>();
        List<UUID> initialEntryIds = List.of();
        int initialIndex;
        boolean scanComplete;
        boolean scanning;
        boolean closed;

        // 创建普通网络或无线网络绑定。
        static Binding forNetwork(ServerPlayer player, IGrid grid, INetwork network, IStorageCache<ItemStack> cache) {
            return new Binding(player, grid, cache, network, null, null, -1, null);
        }

        // 创建便携 Grid 绑定。
        static Binding forPortable(ServerPlayer player, IGrid grid, IPortableGrid portableGrid, IStorageCache<ItemStack> cache, IStorageDisk<ItemStack> storage) {
            return new Binding(player, grid, cache, null, portableGrid, storage, grid.getSlotId(), portableGridStack(portableGrid));
        }

        // 初始化绑定字段并注册 RS 缓存监听器。
        private Binding(ServerPlayer player, IGrid grid, IStorageCache<ItemStack> cache, INetwork network, IPortableGrid portableGrid, IStorageDisk<ItemStack> storage, int portableSlot, ItemStack portableStackReference) {
            this.player = player;
            this.grid = grid;
            this.cache = cache;
            this.network = network;
            this.portableGrid = portableGrid;
            this.storage = storage;
            this.portableSlot = portableSlot;
            this.portableStackReference = portableStackReference;
            this.listener = new CacheListener(this);
            this.cache.addListener(this.listener);
        }

        // 读取扫描开始时的 RS 条目列表并准备分批处理。
        void startScan() {
            cached.clear();
            changedDuringScan.clear();
            // 只缓存稳定 UUID，物品副本在每个 tick 的预算内按需读取。
            Collection<StackListEntry<ItemStack>> entries = cache.getList().getStacks();
            List<UUID> ids = new ArrayList<>(entries.size());
            for (StackListEntry<ItemStack> entry : entries) {
                if (entry.getId() != null) {
                    ids.add(entry.getId());
                }
            }
            initialEntryIds = ids;
            initialIndex = 0;
            scanComplete = false;
            scanning = true;
        }

        // 每 tick 读取固定数量的条目，避免按网络总量集中处理造成卡顿。
        void processBatch() {
            if (closed || !scanning) {
                return;
            }

            int entriesThisTick = Math.max(1, Config.maxRefinedStorageEntriesPerPlayerPerTick);
            int processed = 0;
            while (processed < entriesThisTick && initialIndex < initialEntryIds.size()) {
                UUID id = initialEntryIds.get(initialIndex++);
                if (!changedDuringScan.remove(id)) {
                    ItemStack current = cache.getList().get(id);
                    if (current == null || current.isEmpty()) {
                        cached.remove(id);
                    } else {
                        cached.put(id, current.copy());
                    }
                }
                processed++;
            }

            if (initialIndex >= initialEntryIds.size()) {
                scanning = false;
                scanComplete = true;
                ContainerScanManager.requestDetection(player);
            }
        }

        // 判断两个绑定是否指向同一个网络或同一个玩家物品栏槽位中的便携 Grid。
        boolean isSameSource(Binding other) {
            if (network != null || other.network != null) {
                return network != null && network == other.network;
            }
            return portableGrid != null
                    && other.portableGrid != null
                    && ((portableStackReference != null && portableStackReference == other.portableStackReference
                    && portableSlot == other.portableSlot)
                    || (portableStackReference == null && other.portableStackReference == null && portableGrid == other.portableGrid));
        }

        // 判断便携 Grid 的原始物品栈是否仍在玩家身上。
        boolean isSourcePresent() {
            if (portableGrid == null) {
                return true;
            }
            if (portableStackReference == null) {
                return false;
            }
            for (ItemStack stack : player.getInventory().items) {
                if (stack == portableStackReference) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().armor) {
                if (stack == portableStackReference) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (stack == portableStackReference) {
                    return true;
                }
            }
            return ModList.get().isLoaded("curios") && CuriosSupport.contains(player, portableStackReference);
        }

        // 返回缓存物品副本，防止 FTB 汇总阶段持有 RS 内部可变对象。
        List<ItemStack> getCachedStacks() {
            if (!scanComplete) {
                return List.of();
            }

            List<ItemStack> stacks = new ArrayList<>(cached.size());
            cached.values().forEach(stack -> {
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
            });
            return stacks;
        }

        // 使用任务匹配规则从缓存中复制候选物品，抽取过程中不遍历可变映射。
        List<ItemStack> getMatchingStacks(ItemTask task) {
            List<ItemStack> matches = new ArrayList<>();
            cached.values().forEach(stack -> {
                if (!stack.isEmpty() && task.test(stack)) {
                    matches.add(stack.copy());
                }
            });
            return matches;
        }

        // 通过 RS 实际存储 API 执行一次安全抽取。
        ItemStack extract(ServerPlayer player, ItemStack stack, int amount) {
            if (network != null) {
                return extractFromNetwork(player, stack, amount);
            }
            return extractFromPortableGrid(player, stack, amount);
        }

        // 从普通或无线网络抽取，并同步无线 Grid 的能源和存储追踪。
        private ItemStack extractFromNetwork(ServerPlayer player, ItemStack stack, int amount) {
            if (!network.canRun() || !network.getSecurityManager().hasPermission(Permission.EXTRACT, player)) {
                return ItemStack.EMPTY;
            }

            ItemStack simulated = network.extractItem(stack, amount, Action.SIMULATE);
            if (simulated.isEmpty()) {
                return ItemStack.EMPTY;
            }

            int actualAmount = Math.min(amount, simulated.getCount());
            IStorageTracker<ItemStack> tracker = network.getItemStorageTracker();
            if (tracker != null) {
                tracker.changed(player, stack.copy());
            }

            ItemStack extracted = network.extractItem(stack, actualAmount, Action.PERFORM);
            if (!extracted.isEmpty() && grid instanceof WirelessGrid) {
                network.getNetworkItemManager().drainEnergy(player, RS.SERVER_CONFIG.getWirelessGrid().getExtractUsage());
            }
            return extracted;
        }

        // 从便携 Grid 存储盘抽取，并同步 RS 的缓存、追踪器和能源消耗。
        private ItemStack extractFromPortableGrid(ServerPlayer player, ItemStack stack, int amount) {
            if (!grid.isGridActive() || storage == null) {
                return ItemStack.EMPTY;
            }

            ItemStack simulated = storage.extract(stack, amount, 1, Action.SIMULATE);
            if (simulated.isEmpty()) {
                return ItemStack.EMPTY;
            }

            int actualAmount = Math.min(amount, simulated.getCount());
            IStorageTracker<ItemStack> tracker = portableGrid.getItemStorageTracker();
            if (tracker != null) {
                tracker.changed(player, stack.copy());
            }

            ItemStack extracted = storage.extract(stack, actualAmount, 1, Action.PERFORM);
            if (!extracted.isEmpty()) {
                portableGrid.drainEnergy(RS.SERVER_CONFIG.getPortableGrid().getExtractUsage());
            }
            return extracted;
        }

        // 移除缓存监听器并释放绑定状态。
        void close() {
            if (!closed) {
                closed = true;
                cache.removeListener(listener);
            }
        }

        // 读取便携 Grid 绑定时使用的玩家物品栈对象，用于识别槽位替换。
        private static ItemStack portableGridStack(IPortableGrid portableGrid) {
            if (portableGrid instanceof com.refinedmods.refinedstorage.blockentity.grid.portable.PortableGrid portable) {
                return portable.getStack();
            }
            return null;
        }
    }

    // 把 RS 缓存变化转换为玩家缓存更新，并合并同 tick 的 FTB 检测请求。
    private static final class CacheListener implements IStorageCacheListener<ItemStack> {
        private final Binding binding;

        // 创建一个绑定专属的 RS 缓存监听器。
        CacheListener(Binding binding) {
            this.binding = binding;
        }

        // RS 重新挂接监听器时不直接扫描，保持固定批量预算。
        @Override
        public void onAttached() {
        }

        // RS 缓存失效时清空本地缓存，避免任务看见过期物品。
        @Override
        public void onInvalidated() {
            // 重新建立分批扫描队列，避免缓存重建后永久停留在未完成状态。
            binding.startScan();
            ContainerScanManager.requestDetection(binding.player);
        }

        // 处理一个 RS 物品条目的增量变化。
        @Override
        public void onChanged(StackListResult<ItemStack> result) {
            updateEntry(result);
        }

        // 处理 RS 在一个批次中发出的多个物品变化。
        @Override
        public void onChangedBulk(List<StackListResult<ItemStack>> results) {
            results.forEach(this::updateEntry);
        }

        // 从 RS 当前列表读取变化后的条目，避免依赖负数变化的旧栈数量语义。
        private void updateEntry(StackListResult<ItemStack> result) {
            if (binding.closed || result == null || result.getId() == null) {
                return;
            }

            UUID id = result.getId();
            if (binding.scanning) {
                binding.changedDuringScan.add(id);
            }

            ItemStack current = binding.cache.getList().get(id);
            if (current == null || current.isEmpty()) {
                binding.cached.remove(id);
            } else {
                binding.cached.put(id, current.copy());
            }

            if (binding.scanComplete) {
                ContainerScanManager.requestDetection(binding.player);
            }
        }
    }
}
