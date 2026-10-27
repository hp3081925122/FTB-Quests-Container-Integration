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
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 负责把打开的 Beyond Dimensions 网络转换为 FTB Quests 可读取的物品缓存。
final class BeyondDimensionsIntegration {
    private static final String MOD_ID = "beyonddimensions";
    private static final Map<UUID, Binding> BINDINGS = new HashMap<>();

    private BeyondDimensionsIntegration() {
    }

    // 判断当前环境是否可以使用 Beyond Dimensions API。
    static boolean isAvailable() {
        return Config.enableBeyondDimensions && ModList.get().isLoaded(MOD_ID);
    }

    // 玩家打开 Beyond Dimensions 网络菜单时绑定菜单持有的统一存储。
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

    // 玩家关闭当前绑定的 Beyond Dimensions 网络菜单时解除监听器。
    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        Binding binding = BINDINGS.get(player.getUUID());
        if (binding != null && binding.menu == menu) {
            BINDINGS.remove(player.getUUID());
            binding.close();
            ContainerScanManager.requestDetection(player);
        }
    }

    // 每个服务端 tick 检查网络状态并推进初始缓存扫描。
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

    // 返回已完成初始扫描的物品缓存，并把长数量拆成合法的 ItemStack 数量。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        Binding binding = BINDINGS.get(player.getUUID());
        return binding == null ? List.of() : binding.getCachedStacks();
    }

    // 从当前绑定的 Beyond Dimensions 存储实际提取消耗型任务物品。
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

    // 删除指定玩家的绑定和对应的 Beyond Dimensions 存储监听器。
    static void removePlayer(ServerPlayer player) {
        Binding binding = BINDINGS.remove(player.getUUID());
        if (binding != null) {
            binding.close();
        }
    }

    // 清理服务端停止时残留的所有绑定和监听器。
    static void clear() {
        BINDINGS.values().forEach(Binding::close);
        BINDINGS.clear();
    }

    // 保存一个玩家的菜单来源、统一存储和增量缓存状态。
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

        // 创建绑定并注册统一存储的单键变化和整体变化监听。
        Binding(ServerPlayer player, AbstractContainerMenu menu, UnifiedStorage storage) {
            this.player = player;
            this.menu = menu;
            this.storage = storage;
            this.deltaSubscription = storage.subscribeDelta(this, this::onDelta);
            this.anySubscription = storage.subscribeAny(this, this::onAnyChange);
        }

        // 建立当前统一存储的初始物品键队列，具体内容按 tick 分批读取。
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

        // 按配置预算处理初始物品键，完成后才将缓存提供给 FTB Quests。
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

        // 处理统一存储的单个键变化并更新对应数量。
        void onDelta(IStackKey<?> key, long size, boolean insert) {
            if (closed || !(key instanceof ItemStackKey itemKey)) {
                return;
            }
            if (scanning) {
                changedDuringScan.add(itemKey);
            }
            updateKey(itemKey, ready);
        }

        // 处理统一存储整体变化，重新建立分批扫描队列以覆盖清空操作。
        void onAnyChange() {
            if (!closed) {
                startScan();
            }
        }

        // 从统一存储读取单个键的当前数量并同步本地缓存。
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

        // 判断绑定的维度网络是否仍然存在且没有被删除。
        boolean isUsable() {
            DimensionsNet net = storage.getNet();
            return !closed && net != null && !net.deleted;
        }

        // 返回缓存物品副本，避免 FTB Quests 持有统一存储内部对象。
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

        // 取消统一存储监听并释放绑定缓存。
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

        // 安全关闭统一存储监听器，避免 AutoCloseable 的异常影响服务端 tick。
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
