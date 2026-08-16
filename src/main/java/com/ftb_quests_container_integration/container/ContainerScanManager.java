package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// 负责统一推进精妙背包和 Refined Storage 的缓存，并把变化合并为 FTB 检测请求。
public final class ContainerScanManager {
    private static final Set<UUID> PENDING_DETECTIONS = new ObjectOpenHashSet<>();
    private static final ThreadLocal<ActiveSnapshot> ACTIVE_SNAPSHOT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DETECTING = ThreadLocal.withInitial(() -> false);

    private ContainerScanManager() {
    }

    // 推进三个可选存储集成，并在服务端 tick 末尾合并处理检测请求。
    public static void tick(MinecraftServer server) {
        if (SophisticatedBackpacksProvider.isAvailable()) {
            SophisticatedBackpacksProvider.tick(server);
        }

        RefinedStorageProvider.tick(server);

        AppliedEnergistics2Provider.tick(server);

        processPendingDetections(server);
    }

    // Grid 菜单打开时绑定对应 RS 或 AE2 网络及便携存储来源。
    public static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        RefinedStorageProvider.onContainerOpen(player, menu);
        AppliedEnergistics2Provider.onContainerOpen(player, menu);
    }

    // 转发容器关闭事件，停止 AE2 网络 watcher。
    public static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        AppliedEnergistics2Provider.onContainerClose(player, menu);
    }

    // 请求在当前服务端 tick 统一重新检测玩家任务，避免事件风暴。
    static void requestDetection(ServerPlayer player) {
        if (player != null && !DETECTING.get()) {
            PENDING_DETECTIONS.add(player.getUUID());
        }
    }

    // 在同一 tick 内最多为每位玩家调用一次 FTB Quests 检测。
    private static void processPendingDetections(MinecraftServer server) {
        if (PENDING_DETECTIONS.isEmpty()) {
            return;
        }

        Set<UUID> requests = new HashSet<>(PENDING_DETECTIONS);
        PENDING_DETECTIONS.clear();
        for (UUID uuid : requests) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                continue;
            }
            detectWithSnapshot(player, buildContainerSnapshot(player));
        }
    }

    // 合并便携容器、RS 和 AE2 缓存，作为本次 FTB Quests 库存汇总的附加物品。
    private static List<ItemStack> buildContainerSnapshot(ServerPlayer player) {
        List<ItemStack> snapshot = new ArrayList<>();
        snapshot.addAll(SophisticatedBackpacksProvider.getCachedStacks(player));
        snapshot.addAll(RefinedStorageProvider.getCachedStacks(player));
        snapshot.addAll(AppliedEnergistics2Provider.getCachedStacks(player));
        return snapshot;
    }

    // 从真实精妙背包、RS 或 AE2 存储 API 中按实际数量消耗物品。
    public static void consumeMatching(ItemTask task, TeamData teamData, ServerPlayer player, ItemStack craftedItem) {
        if ((!Config.consumeFromContainers && !Config.consumeFromRefinedStorage
                && !Config.consumeFromAppliedEnergistics2)
                || !craftedItem.isEmpty()
                || !task.consumesResources()
                || teamData.isCompleted(task)) {
            return;
        }

        long remaining = task.getMaxProgress() - teamData.getProgress(task);
        if (remaining <= 0L) {
            return;
        }

        boolean changed = false;
        long consumed = SophisticatedBackpacksProvider.consumeMatching(task, player, remaining);
        if (consumed > 0L) {
            changed = true;
            teamData.addProgress(task, consumed);
            remaining -= consumed;
        }

        if (remaining > 0L) {
            consumed = RefinedStorageProvider.consumeMatching(task, player, remaining);
            if (consumed > 0L) {
                changed = true;
                teamData.addProgress(task, consumed);
                remaining -= consumed;
            }
        }

        if (remaining > 0L) {
            consumed = AppliedEnergistics2Provider.consumeMatching(task, player, remaining);
            if (consumed > 0L) {
                changed = true;
                teamData.addProgress(task, consumed);
                remaining -= consumed;
            }
        }

        if (changed) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
    }

    // 提供给 FTB Quests Mixin 的当前检测附加快照，普通 FTB 事件不读取旧缓存。
    public static List<ItemStack> getActiveSnapshot(ServerPlayer player) {
        ActiveSnapshot active = ACTIVE_SNAPSHOT.get();
        return active != null && active.player == player ? active.items : List.of();
    }

    // 在检测期间安装一次性快照，并阻止容器事件立即递归触发检测。
    private static void detectWithSnapshot(ServerPlayer player, List<ItemStack> snapshot) {
        ACTIVE_SNAPSHOT.set(new ActiveSnapshot(player, snapshot));
        DETECTING.set(true);
        try {
            FTBQuestsInventoryListener.detect(player, ItemStack.EMPTY, 0L);
        } finally {
            DETECTING.remove();
            ACTIVE_SNAPSHOT.remove();
        }
    }

    // 删除指定玩家的容器、RS 和 AE2 缓存及待处理检测请求。
    public static void removePlayer(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        PENDING_DETECTIONS.remove(uuid);
        if (player instanceof ServerPlayer serverPlayer) {
            SophisticatedBackpacksProvider.removePlayer(serverPlayer);
            RefinedStorageProvider.removePlayer(serverPlayer);
            AppliedEnergistics2Provider.removePlayer(serverPlayer);
        }
    }

    // 清理服务端停止时残留的缓存和监听器。
    public static void clear() {
        PENDING_DETECTIONS.clear();
        SophisticatedBackpacksProvider.clear();
        RefinedStorageProvider.clear();
        AppliedEnergistics2Provider.clear();
        ACTIVE_SNAPSHOT.remove();
        DETECTING.remove();
    }

    // 标记当前线程正在向 FTB Quests 提供哪位玩家的容器快照。
    private static final class ActiveSnapshot {
        final ServerPlayer player;
        final List<ItemStack> items;

        // 创建一次性检测上下文。
        ActiveSnapshot(ServerPlayer player, List<ItemStack> items) {
            this.player = player;
            this.items = items;
        }
    }
}
