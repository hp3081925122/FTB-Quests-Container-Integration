package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;

// 负责隔离可选 Applied Energistics 2 API，未安装时不触碰集成实现类。
final class AppliedEnergistics2Provider {
    private AppliedEnergistics2Provider() {
    }

    // 判断当前服务器是否可以使用 Applied Energistics 2 集成。
    static boolean isAvailable() {
        return Config.enableAppliedEnergistics2 && ModList.get().isLoaded("ae2");
    }

    // AE2 菜单打开时绑定便携存储来源。
    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.onContainerOpen(player, menu);
        }
    }

    // AE2 菜单关闭时解除便携存储绑定。
    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.onContainerClose(player, menu);
        }
    }

    // 推进 AE2 缓存刷新。
    static void tick(MinecraftServer server) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.tick(server);
        }
    }

    // 返回已完成刷新的 AE2 缓存快照。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        return isAvailable() ? AppliedEnergistics2Integration.getCachedStacks(player) : List.of();
    }

    // 从已绑定 AE2 存储实际扣除任务物品。
    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        return isAvailable() ? AppliedEnergistics2Integration.consumeMatching(task, player, remaining) : 0L;
    }

    // 删除指定玩家的 AE2 绑定。
    static void removePlayer(ServerPlayer player) {
        if (isAvailable()) {
            AppliedEnergistics2Integration.removePlayer(player);
        }
    }

    // 清理所有 AE2 绑定。
    static void clear() {
        if (isAvailable()) {
            AppliedEnergistics2Integration.clear();
        }
    }
}
