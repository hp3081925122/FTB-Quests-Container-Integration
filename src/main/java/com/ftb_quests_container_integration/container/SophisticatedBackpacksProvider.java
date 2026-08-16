package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

// 负责发现玩家身上由 Forge 物品能力提供的 Sophisticated Backpacks 容器。
final class SophisticatedBackpacksProvider {
    private SophisticatedBackpacksProvider() {
    }

    // 判断当前服务器是否可以执行 Sophisticated Backpacks 扫描。
    static boolean isAvailable() {
        return Config.enableSophisticatedBackpacks && ModList.get().isLoaded("sophisticatedbackpacks");
    }

    // 推进可选的精妙背包缓存集成。
    static void tick(net.minecraft.server.MinecraftServer server) {
        if (isAvailable()) {
            SophisticatedBackpacksIntegration.tick(server);
        }
    }

    // 从精妙背包缓存中按任务匹配规则实际扣除物品。
    static long consumeMatching(dev.ftb.mods.ftbquests.quest.task.ItemTask task, ServerPlayer player, long remaining) {
        return isAvailable() ? SophisticatedBackpacksIntegration.consumeMatching(task, player, remaining) : 0L;
    }

    // 返回精妙背包缓存快照，未安装或未启用时不加载可选 API 类。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        return isAvailable() ? SophisticatedBackpacksIntegration.getCachedStacks(player) : List.of();
    }

    // 删除精妙背包玩家缓存，未安装或未启用时保持空操作。
    static void removePlayer(ServerPlayer player) {
        if (isAvailable()) {
            SophisticatedBackpacksIntegration.removePlayer(player);
        }
    }

    // 清理精妙背包所有缓存，未安装或未启用时保持空操作。
    static void clear() {
        if (isAvailable()) {
            SophisticatedBackpacksIntegration.clear();
        }
    }

    // 收集玩家主背包、盔甲栏、副手和可选 Curios 槽中的顶层物品。
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
