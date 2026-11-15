package com.ftb_quests_container_integration.container;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.Map;

final class CuriosSupport {
    private CuriosSupport() {
    }

    static void collect(ServerPlayer player, Map<String, ItemStack> stacks) {
        CuriosApi.getCuriosInventory(player).ifPresent(curiosHandler -> {
            for (int slot = 0; slot < curiosHandler.getEquippedCurios().getSlots(); slot++) {
                ItemStack stack = curiosHandler.getEquippedCurios().getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    stacks.put("curios:" + slot, stack);
                }
            }
        });
    }

    static boolean contains(ServerPlayer player, ItemStack target) {
        final boolean[] found = {false};
        CuriosApi.getCuriosInventory(player).ifPresent(curiosHandler -> {
            for (int slot = 0; slot < curiosHandler.getEquippedCurios().getSlots(); slot++) {
                if (curiosHandler.getEquippedCurios().getStackInSlot(slot) == target) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }
}
