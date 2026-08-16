package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;

// 负责隔离可选 Refined Storage API，未安装时不触碰集成实现类。
final class RefinedStorageProvider {
    private RefinedStorageProvider() {
    }

    // 判断当前服务器是否可以使用 Refined Storage 集成。
    static boolean isAvailable() {
        return Config.enableRefinedStorage && ModList.get().isLoaded("refinedstorage");
    }

    // Grid 菜单打开时绑定网络来源。
    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            RefinedStorageIntegration.onContainerOpen(player, menu);
        }
    }

    // 推进 RS 初始缓存扫描。
    static void tick(MinecraftServer server) {
        if (isAvailable()) {
            RefinedStorageIntegration.tick(server);
        }
    }

    // 返回已完成扫描的 RS 缓存快照。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        return isAvailable() ? RefinedStorageIntegration.getCachedStacks(player) : List.of();
    }

    // 从已绑定 RS 存储实际扣除任务物品。
    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        return isAvailable() ? RefinedStorageIntegration.consumeMatching(task, player, remaining) : 0L;
    }

    // 删除指定玩家的 RS 绑定。
    static void removePlayer(ServerPlayer player) {
        if (isAvailable()) {
            RefinedStorageIntegration.removePlayer(player);
        }
    }

    // 清理所有 RS 绑定。
    static void clear() {
        if (isAvailable()) {
            RefinedStorageIntegration.clear();
        }
    }
}
