package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;

// 负责隔离可选 Beyond Dimensions API，未安装时不触碰集成实现类。
final class BeyondDimensionsProvider {
    private BeyondDimensionsProvider() {
    }

    // 判断当前环境是否可以使用 Beyond Dimensions 集成。
    static boolean isAvailable() {
        return Config.enableBeyondDimensions && ModList.get().isLoaded("beyonddimensions");
    }

    // Beyond Dimensions 网络菜单打开时绑定其统一存储。
    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.onContainerOpen(player, menu);
        }
    }

    // Beyond Dimensions 网络菜单关闭时解除统一存储绑定。
    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.onContainerClose(player, menu);
        }
    }

    // 推进 Beyond Dimensions 的初始分批缓存扫描。
    static void tick(MinecraftServer server) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.tick(server);
        }
    }

    // 返回已完成扫描的 Beyond Dimensions 物品缓存快照。
    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        return isAvailable() ? BeyondDimensionsIntegration.getCachedStacks(player) : List.of();
    }

    // 从已绑定的 Beyond Dimensions 存储实际扣除任务物品。
    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        return isAvailable() ? BeyondDimensionsIntegration.consumeMatching(task, player, remaining) : 0L;
    }

    // 删除指定玩家的 Beyond Dimensions 绑定。
    static void removePlayer(ServerPlayer player) {
        if (isAvailable()) {
            BeyondDimensionsIntegration.removePlayer(player);
        }
    }

    // 清理所有 Beyond Dimensions 绑定和监听器。
    static void clear() {
        if (isAvailable()) {
            BeyondDimensionsIntegration.clear();
        }
    }
}
