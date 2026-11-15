package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import com.mojang.logging.LogUtils;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
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
import org.slf4j.Logger;

// 负责统一推进便携容器、Refined Storage、Applied Energistics 2 和 Beyond Dimensions 缓存。
public final class ContainerScanManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<UUID> PENDING_DETECTIONS = new ObjectOpenHashSet<>();
    private static final ThreadLocal<ActiveSnapshot> ACTIVE_SNAPSHOT = new ThreadLocal<>();

    private ContainerScanManager() {
    }

    // 任务完成后可能解锁新任务，为在线成员补发一次带容器快照的检测。
    public static void init() {
        ObjectCompletedEvent.QUEST.register(event -> {
            event.getOnlineMembers().forEach(ContainerScanManager::requestDetection);
            return EventResult.pass();
        });
    }

    // 推进所有可选存储集成，并在服务端 tick 末尾合并处理检测请求。
    public static void tick(MinecraftServer server) {
        if (SophisticatedBackpacksProvider.isAvailable()) {
            SophisticatedBackpacksProvider.tick(server);
        }

        RefinedStorageProvider.tick(server);

        AppliedEnergistics2Provider.tick(server);

        BeyondDimensionsProvider.tick(server);

        processPendingDetections(server);
    }

    // 容器菜单打开时绑定 RS、AE2 和 Beyond Dimensions 的主动存储来源。
    public static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        RefinedStorageProvider.onContainerOpen(player, menu);
        AppliedEnergistics2Provider.onContainerOpen(player, menu);
        BeyondDimensionsProvider.onContainerOpen(player, menu);
    }

    // 转发容器关闭事件，解除 RS、AE2 和 Beyond Dimensions 当前存储绑定。
    public static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        RefinedStorageProvider.onContainerClose(player, menu);
        AppliedEnergistics2Provider.onContainerClose(player, menu);
        BeyondDimensionsProvider.onContainerClose(player, menu);
    }

    // 请求在服务端 tick 末尾统一重新检测玩家任务，检测期间产生的请求顺延到下一 tick。
    static void requestDetection(ServerPlayer player) {
        if (player != null) {
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

    // 合并便携容器、RS、AE2 和 Beyond Dimensions 缓存，作为本次 FTB Quests 库存汇总的附加物品。
    private static List<ItemStack> buildContainerSnapshot(ServerPlayer player) {
        List<ItemStack> snapshot = new ArrayList<>();
        snapshot.addAll(SophisticatedBackpacksProvider.getCachedStacks(player));
        snapshot.addAll(RefinedStorageProvider.getCachedStacks(player));
        snapshot.addAll(AppliedEnergistics2Provider.getCachedStacks(player));
        List<ItemStack> beyondDimensionsStacks = BeyondDimensionsProvider.getCachedStacks(player);
        snapshot.addAll(beyondDimensionsStacks);
        LOGGER.debug("Built FTB Quests snapshot with {} Beyond Dimensions stacks for player {}",
                beyondDimensionsStacks.size(), player.getGameProfile().getName());
        return snapshot;
    }

    // 从真实精妙背包、RS 或 AE2 存储 API 中按实际数量消耗物品。
    public static void consumeMatching(ItemTask task, TeamData teamData, ServerPlayer player, ItemStack craftedItem) {
        if ((!Config.consumeFromContainers && !Config.consumeFromRefinedStorage
                && !Config.consumeFromAppliedEnergistics2 && !Config.consumeFromBeyondDimensions)
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

        if (remaining > 0L) {
            consumed = BeyondDimensionsProvider.consumeMatching(task, player, remaining);
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

    // 在检测期间安装一次性快照。
    private static void detectWithSnapshot(ServerPlayer player, List<ItemStack> snapshot) {
        ACTIVE_SNAPSHOT.set(new ActiveSnapshot(player, snapshot));
        try {
            FTBQuestsInventoryListener.detect(player, ItemStack.EMPTY, 0L);
        } finally {
            ACTIVE_SNAPSHOT.remove();
        }
    }

    // 删除指定玩家的容器、RS、AE2 和 Beyond Dimensions 缓存及待处理检测请求。
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
            BeyondDimensionsProvider.removePlayer(serverPlayer);
        }
    }

    // 清理服务端停止时残留的缓存和监听器。
    public static void clear() {
        PENDING_DETECTIONS.clear();
        SophisticatedBackpacksProvider.clear();
        RefinedStorageProvider.clear();
        AppliedEnergistics2Provider.clear();
        BeyondDimensionsProvider.clear();
        ACTIVE_SNAPSHOT.remove();
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
