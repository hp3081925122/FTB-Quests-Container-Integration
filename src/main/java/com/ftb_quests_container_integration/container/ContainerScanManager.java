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
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ContainerScanManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<UUID> PENDING_DETECTIONS = new ObjectOpenHashSet<>();
    private static final ThreadLocal<ActiveSnapshot> ACTIVE_SNAPSHOT = new ThreadLocal<>();

    private ContainerScanManager() {
    }

    public static void init() {
        ObjectCompletedEvent.QUEST.register(event -> {
            event.getOnlineMembers().forEach(ContainerScanManager::requestDetection);
            return EventResult.pass();
        });
    }

    public static void tick(MinecraftServer server) {
        if (SophisticatedBackpacksProvider.isAvailable()) {
            SophisticatedBackpacksProvider.tick(server);
        }

        RefinedStorageProvider.tick(server);

        AppliedEnergistics2Provider.tick(server);

        BeyondDimensionsProvider.tick(server);

        processPendingDetections(server);
    }

    public static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        RefinedStorageProvider.onContainerOpen(player, menu);
        AppliedEnergistics2Provider.onContainerOpen(player, menu);
        BeyondDimensionsProvider.onContainerOpen(player, menu);
    }

    public static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        RefinedStorageProvider.onContainerClose(player, menu);
        AppliedEnergistics2Provider.onContainerClose(player, menu);
        BeyondDimensionsProvider.onContainerClose(player, menu);
    }

    static void requestDetection(ServerPlayer player) {
        if (player != null) {
            PENDING_DETECTIONS.add(player.getUUID());
        }
    }

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

    public static List<ItemStack> getActiveSnapshot(ServerPlayer player) {
        ActiveSnapshot active = ACTIVE_SNAPSHOT.get();
        return active != null && active.player == player ? active.items : List.of();
    }

    private static void detectWithSnapshot(ServerPlayer player, List<ItemStack> snapshot) {
        ACTIVE_SNAPSHOT.set(new ActiveSnapshot(player, snapshot));
        try {
            FTBQuestsInventoryListener.detect(player, ItemStack.EMPTY, 0L);
        } finally {
            ACTIVE_SNAPSHOT.remove();
        }
    }

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

    public static void clear() {
        PENDING_DETECTIONS.clear();
        SophisticatedBackpacksProvider.clear();
        RefinedStorageProvider.clear();
        AppliedEnergistics2Provider.clear();
        BeyondDimensionsProvider.clear();
        ACTIVE_SNAPSHOT.remove();
    }

    private static final class ActiveSnapshot {
        final ServerPlayer player;
        final List<ItemStack> items;

        ActiveSnapshot(ServerPlayer player, List<ItemStack> items) {
            this.player = player;
            this.items = items;
        }
    }
}
