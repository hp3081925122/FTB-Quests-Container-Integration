package com.ftb_quests_container_integration.container;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.Map;

// 负责在 Curios 存在时读取玩家已装备的 Curios 物品。
final class CuriosSupport {
    private CuriosSupport() {
    }

    // 将 Curios 槽中的顶层物品加入当前玩家来源表。
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

    // 判断指定物品栈是否仍位于玩家的 Curios 槽位中。
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
