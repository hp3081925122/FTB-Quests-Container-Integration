package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SophisticatedBackpacksProvider {
    private SophisticatedBackpacksProvider() {
    }

    static boolean isAvailable() {
        return Config.enableSophisticatedBackpacks && ModList.get().isLoaded("sophisticatedbackpacks");
    }

    static void tick(MinecraftServer server) {
        if (isAvailable()) {
            SophisticatedBackpacksIntegration.tick(server);
        }
    }

    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        return isAvailable() ? SophisticatedBackpacksIntegration.consumeMatching(task, player, remaining) : 0L;
    }

    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        return isAvailable() ? SophisticatedBackpacksIntegration.getCachedStacks(player) : List.of();
    }

    static void removePlayer(ServerPlayer player) {
        if (isAvailable()) {
            SophisticatedBackpacksIntegration.removePlayer(player);
        }
    }

    static void clear() {
        if (isAvailable()) {
            SophisticatedBackpacksIntegration.clear();
        }
    }

    static Map<String, ItemStack> collectTopLevel(ServerPlayer player) {
        Map<String, ItemStack> stacks = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!stack.isEmpty()) {
                stacks.put("inventory:" + slot, stack);
            }
        }

        if (Config.scanArmorAndOffhand) {
            for (int slot = 0; slot < player.getInventory().armor.size(); slot++) {
                ItemStack stack = player.getInventory().armor.get(slot);
                if (!stack.isEmpty()) {
                    stacks.put("armor:" + slot, stack);
                }
            }
            for (int slot = 0; slot < player.getInventory().offhand.size(); slot++) {
                ItemStack stack = player.getInventory().offhand.get(slot);
                if (!stack.isEmpty()) {
                    stacks.put("offhand:" + slot, stack);
                }
            }
        }

        if (Config.scanCurios && ModList.get().isLoaded("curios")) {
            CuriosSupport.collect(player, stacks);
        }
        return stacks;
    }
}
