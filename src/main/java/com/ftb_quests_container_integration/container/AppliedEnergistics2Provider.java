package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.List;

final class AppliedEnergistics2Provider {
    private AppliedEnergistics2Provider() {
    }

    static boolean isAvailable() {
        return Config.enableAppliedEnergistics2 && ModList.get().isLoaded("ae2");
    }

    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.onContainerOpen(player, menu);
        }
    }

    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.onContainerClose(player, menu);
        }
    }

    static void tick(MinecraftServer server) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.tick(server);
        }
    }

    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        return isAvailable() ? AppliedEnergistics2Integration.getCachedStacks(player) : List.of();
    }

    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        return isAvailable() ? AppliedEnergistics2Integration.consumeMatching(task, player, remaining) : 0L;
    }

    static void removePlayer(ServerPlayer player) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.removePlayer(player);
        }
    }

    static void clear() {
        if (isAvailable()) {
            AppliedEnergistics2Integration.clear();
        }
    }
}
