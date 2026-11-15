package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.List;

final class BeyondDimensionsProvider {
    private BeyondDimensionsProvider() {
    }

    static boolean isAvailable() {
        return Config.enableBeyondDimensions && ModList.get().isLoaded("beyonddimensions");
    }

    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.onContainerOpen(player, menu);
        }
    }

    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.onContainerClose(player, menu);
        }
    }

    static void tick(MinecraftServer server) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.tick(server);
        }
    }

    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        return isAvailable() ? BeyondDimensionsIntegration.getCachedStacks(player) : List.of();
    }

    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        return isAvailable() ? BeyondDimensionsIntegration.consumeMatching(task, player, remaining) : 0L;
    }

    static void removePlayer(ServerPlayer player) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.removePlayer(player);
        }
    }

    static void clear() {
        if (isAvailable()) {
            BeyondDimensionsIntegration.clear();
        }
    }
}
