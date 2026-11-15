package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 负责把 Sophisticated Backpacks 内容转换为事件驱动的玩家缓存。
final class SophisticatedBackpacksIntegration {
    private static final Map<UUID, PlayerCache> PLAYERS = new HashMap<>();

    private SophisticatedBackpacksIntegration() {
    }

    // 每个服务端 tick 推进精妙背包的首次扫描和新增分支扫描。
    static void tick(MinecraftServer server) {
        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PLAYERS.computeIfAbsent(player.getUUID(), uuid -> new PlayerCache(player));
        }
        PLAYERS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entry.getValue().close();
                return true;
            }
            entry.getValue().tick(gameTime);
            return false;
        });
    }

    // 返回玩家已经建立的精妙背包缓存快照。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        PlayerCache cache = PLAYERS.get(player.getUUID());
        return cache == null ? List.of() : cache.getCachedStacks();
    }

    // 从精妙背包缓存找到候选槽位，并通过真实 InventoryHandler 扣除物品。
    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        if (!Config.consumeFromContainers || remaining <= 0L) {
            return 0L;
        }

        PlayerCache cache = PLAYERS.get(player.getUUID());
        if (cache == null || !cache.ready) {
            return 0L;
        }

        long consumed = 0L;
        for (Candidate candidate : cache.getCandidates(task)) {
            if (consumed >= remaining) {
                break;
            }

            BackpackBinding binding = candidate.binding;
            if (!binding.active || binding.handler.getSlots() <= candidate.slot) {
                continue;
            }
            // 缓存可能与实际槽位不同步，扣除前以真实槽位内容重新匹配任务。
            ItemStack current = binding.handler.getStackInSlot(candidate.slot);
            if (current.isEmpty() || !task.test(current)) {
                continue;
            }

            long needed = remaining - consumed;
            int amount = (int) Math.min((long) current.getCount(), needed);
            if (amount <= 0) {
                continue;
            }

            ItemStack extracted = binding.handler.extractItem(candidate.slot, amount, false);
            if (!extracted.isEmpty()) {
                consumed += extracted.getCount();
            }
        }
        return consumed;
    }

    // 删除玩家缓存并停止相关来源的刷新监听。
    static void removePlayer(ServerPlayer player) {
        PlayerCache cache = PLAYERS.remove(player.getUUID());
        if (cache != null) {
            cache.close();
        }
    }

    // 清理服务端停止时注册的所有精妙背包监听。
    static void clear() {
        PLAYERS.values().forEach(PlayerCache::close);
        PLAYERS.clear();
    }

    // 保存一个可变槽位扫描任务。
    private record ScanEntry(BackpackBinding binding, int slot) {
    }

    // 保存一个用于真实消耗的缓存候选槽位。
    private static final class Candidate {
        final BackpackBinding binding;
        final int slot;
        final ItemStack stack;

        // 创建候选槽位副本，避免抽取回调修改遍历对象。
        Candidate(BackpackBinding binding, int slot, ItemStack stack) {
            this.binding = binding;
            this.slot = slot;
            this.stack = stack.copy();
        }
    }

    // 保存一个玩家所有顶层和嵌套背包的缓存状态。
    private static final class PlayerCache {
        final ServerPlayer player;
        final Map<String, BackpackBinding> roots = new HashMap<>();
        final Set<BackpackBinding> bindings = Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<ScanEntry> scanQueue = new ArrayDeque<>();
        long nextRootCheck;
        boolean ready;
        boolean scanning;
        boolean closed;

        // 创建玩家缓存并等待第一次顶层来源检查。
        PlayerCache(ServerPlayer player) {
            this.player = player;
        }

        // 按配置周期检查顶层背包替换，同时推进固定预算的槽位扫描。
        void tick(long gameTime) {
            if (closed) {
                return;
            }

            // 每 tick 验证已有顶层来源，及时移除已经离开玩家物品栏的旧绑定。
            Map<String, ItemStack> currentRoots = null;
            if (!roots.isEmpty() || gameTime >= nextRootCheck) {
                currentRoots = SophisticatedBackpacksProvider.collectTopLevel(player);
                for (Map.Entry<String, BackpackBinding> entry : new ArrayList<>(roots.entrySet())) {
                    ItemStack current = currentRoots.get(entry.getKey());
                    if (current == null || !entry.getValue().isSameSource(current)) {
                        roots.remove(entry.getKey());
                        removeBinding(entry.getValue());
                    }
                }
            }

            if (gameTime >= nextRootCheck) {
                reconcileRoots(currentRoots);
                nextRootCheck = gameTime + Math.max(1L, (long) Config.scanIntervalSeconds * 20L);
            }
            processBatch();
        }

        // 对比顶层背包来源，处理背包替换和被移除的来源。
        private void reconcileRoots(Map<String, ItemStack> currentRoots) {
            Set<String> removedKeys = new HashSet<>(roots.keySet());
            currentRoots.forEach((key, stack) -> {
                removedKeys.remove(key);
                BackpackBinding existing = roots.get(key);
                if (existing != null && existing.isSameSource(stack)) {
                    return;
                }
                if (existing != null) {
                    removeBinding(existing);
                }
                BackpackBinding created = createBinding(stack, 0, null, -1);
                if (created != null) {
                    roots.put(key, created);
                    addBinding(created);
                } else {
                    roots.remove(key);
                }
            });
            removedKeys.forEach(key -> {
                BackpackBinding removed = roots.remove(key);
                if (removed != null) {
                    removeBinding(removed);
                }
            });
            finishEmptyScan();
        }

        // 注册背包来源并把其全部槽位放入分批扫描队列。
        private void addBinding(BackpackBinding binding) {
            bindings.add(binding);
            ready = false;
            enqueue(binding);
        }

        // 创建一个嵌套背包并替换该父背包槽位上的旧来源。
        private void updateNestedBinding(BackpackBinding parent, int slot, ItemStack stack) {
            BackpackBinding existing = parent.children.get(slot);
            if (stack.isEmpty() || parent.depth >= Config.nestedBackpackDepth) {
                if (existing != null) {
                    parent.children.remove(slot);
                    removeBinding(existing);
                }
                return;
            }

            if (existing != null && existing.isSameSource(stack)) {
                return;
            }
            if (existing != null) {
                parent.children.remove(slot);
                removeBinding(existing);
            }

            BackpackBinding created = createBinding(stack, parent.depth + 1, parent, slot);
            if (created != null) {
                parent.children.put(slot, created);
                addBinding(created);
            }
        }

        // 处理一个槽位变化事件并即时刷新该槽位及其嵌套来源。
        private void slotChanged(BackpackBinding binding, int slot) {
            if (closed || !binding.active || binding.handler.getSlots() <= slot) {
                return;
            }
            if (scanning) {
                binding.changedDuringScan.add(slot);
            }
            ItemStack stack = binding.handler.getStackInSlot(slot);
            updateSlot(binding, slot, stack);
            if (ready) {
                ContainerScanManager.requestDetection(player);
            }
        }

        // 处理背包 handler 刷新并重新排入该背包的完整槽位扫描。
        private void handlerRefreshed(BackpackBinding binding) {
            if (closed || !binding.active) {
                return;
            }
            InventoryHandler refreshed = binding.wrapper.getInventoryHandler();
            if (refreshed == null || refreshed == binding.handler) {
                return;
            }
            new ArrayList<>(binding.children.values()).forEach(this::removeBinding);
            binding.children.clear();
            binding.handler = refreshed;
            binding.slots.clear();
            binding.changedDuringScan.clear();
            ready = false;
            binding.registerHandlerListener();
            enqueue(binding);
        }

        // 从一个背包槽位缓存物品并维护嵌套背包关系。
        private void updateSlot(BackpackBinding binding, int slot, ItemStack stack) {
            if (stack.isEmpty()) {
                binding.slots.remove(slot);
            } else {
                binding.slots.put(slot, stack.copy());
            }
            updateNestedBinding(binding, slot, stack);
        }

        // 把背包所有槽位加入队列，扫描预算由统一配置控制。
        private void enqueue(BackpackBinding binding) {
            if (!binding.active) {
                return;
            }
            scanning = true;
            scanQueue.add(new ScanEntry(binding, 0));
        }

        // 在单个服务端 tick 内读取固定数量的背包槽位。
        private void processBatch() {
            int budget = Math.max(1, Config.maxSlotsPerPlayerPerTick);
            while (budget-- > 0 && !scanQueue.isEmpty()) {
                ScanEntry entry = scanQueue.removeFirst();
                BackpackBinding binding = entry.binding();
                int slot = entry.slot();
                if (!binding.active || binding.handler.getSlots() <= slot) {
                    continue;
                }
                if (!binding.changedDuringScan.remove(slot)) {
                    updateSlot(binding, slot, binding.handler.getStackInSlot(slot));
                }
                if (slot + 1 < binding.handler.getSlots()) {
                    scanQueue.addLast(new ScanEntry(binding, slot + 1));
                }
            }
            finishEmptyScan();
        }

        // 扫描队列清空后标记缓存可供 FTB Quests 使用。
        private void finishEmptyScan() {
            if (scanning && scanQueue.isEmpty()) {
                scanning = false;
                ready = true;
                ContainerScanManager.requestDetection(player);
            }
        }

        // 获取全部已缓存非空物品的副本。
        List<ItemStack> getCachedStacks() {
            if (!ready) {
                return List.of();
            }
            List<ItemStack> result = new ArrayList<>();
            bindings.forEach(binding -> binding.slots.values().forEach(stack -> {
                if (!stack.isEmpty()) {
                    result.add(stack.copy());
                }
            }));
            return result;
        }

        // 获取任务匹配的缓存槽位副本。
        List<Candidate> getCandidates(ItemTask task) {
            List<Candidate> result = new ArrayList<>();
            bindings.forEach(binding -> binding.slots.forEach((slot, stack) -> {
                if (!stack.isEmpty() && task.test(stack)) {
                    result.add(new Candidate(binding, slot, stack));
                }
            }));
            return result;
        }

        // 递归移除一个背包及其所有嵌套来源。
        private void removeBinding(BackpackBinding binding) {
            if (!binding.active) {
                return;
            }
            new ArrayList<>(binding.children.values()).forEach(this::removeBinding);
            binding.children.clear();
            binding.active = false;
            binding.wrapper.unregisterOnInventoryHandlerRefreshListener();
            bindings.remove(binding);
            if (!closed) {
                ContainerScanManager.requestDetection(player);
            }
        }

        // 创建一个拥有完整 InventoryHandler 监听的背包绑定。
        private BackpackBinding createBinding(ItemStack stack, int depth, BackpackBinding parent, int parentSlot) {
            LazyOptional<IBackpackWrapper> optional = stack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance());
            return optional.resolve().map(wrapper -> new BackpackBinding(this, wrapper, stack, depth, parent, parentSlot)).orElse(null);
        }

        // 释放玩家缓存和所有 wrapper 刷新监听。
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            new ArrayList<>(roots.values()).forEach(this::removeBinding);
            roots.clear();
            bindings.clear();
            scanQueue.clear();
        }
    }

    // 保存一个背包 InventoryHandler、监听器和嵌套来源。
    private static final class BackpackBinding {
        final PlayerCache owner;
        final IBackpackWrapper wrapper;
        final ItemStack sourceStackReference;
        final UUID contentsUuid;
        final int depth;
        final BackpackBinding parent;
        final int parentSlot;
        final Map<Integer, ItemStack> slots = new HashMap<>();
        final Map<Integer, BackpackBinding> children = new HashMap<>();
        final Set<Integer> changedDuringScan = new HashSet<>();
        InventoryHandler handler;
        boolean active = true;

        // 创建绑定并注册完整 InventoryHandler 的槽位变化监听。
        BackpackBinding(PlayerCache owner, IBackpackWrapper wrapper, ItemStack sourceStack, int depth, BackpackBinding parent, int parentSlot) {
            this.owner = owner;
            this.wrapper = wrapper;
            this.sourceStackReference = sourceStack;
            this.contentsUuid = wrapper.getContentsUuid().orElse(null);
            this.depth = depth;
            this.parent = parent;
            this.parentSlot = parentSlot;
            this.handler = wrapper.getInventoryHandler();
            registerHandlerListener();
            WeakReference<BackpackBinding> reference = new WeakReference<>(this);
            wrapper.registerOnInventoryHandlerRefreshListener(() -> {
                BackpackBinding binding = reference.get();
                if (binding != null && binding.active) {
                    binding.owner.handlerRefreshed(binding);
                }
            });
        }

        // 注册当前 InventoryHandler 的槽位变化监听，旧 handler 的监听只保留弱引用绑定。
        void registerHandlerListener() {
            WeakReference<BackpackBinding> reference = new WeakReference<>(this);
            InventoryHandler observedHandler = handler;
            observedHandler.addListener(slot -> {
                BackpackBinding binding = reference.get();
                if (binding != null && binding.active && binding.handler == observedHandler) {
                    binding.owner.slotChanged(binding, slot);
                }
            });
        }

        // 优先使用 Sophisticated Core 为背包内容分配的稳定 UUID，避免内容 NBT 变化造成重复重建。
        boolean isSameSource(ItemStack stack) {
            UUID currentContentsUuid = stack.getCapability(CapabilityBackpackWrapper.getCapabilityInstance())
                    .resolve()
                    .flatMap(IBackpackWrapper::getContentsUuid)
                    .orElse(null);
            if (contentsUuid != null || currentContentsUuid != null) {
                return contentsUuid != null && contentsUuid.equals(currentContentsUuid);
            }
            return sourceStackReference == stack;
        }
    }
}
