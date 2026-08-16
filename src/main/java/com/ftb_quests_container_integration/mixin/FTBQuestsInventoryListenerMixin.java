package com.ftb_quests_container_integration.mixin;

import com.ftb_quests_container_integration.container.ContainerScanManager;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 负责把本模组完成的一轮容器快照追加到 FTB Quests 的临时库存汇总。
@Mixin(value = FTBQuestsInventoryListener.class, remap = false)
public abstract class FTBQuestsInventoryListenerMixin {
    @Shadow
    @Final
    private static Map<Item, List<ItemStack>> inventorySummaryCache;

    @Shadow
    @Final
    private static List<ItemStack> nonEmptyStacks;

    // 只在本模组主动检测时追加快照，避免普通 FTB 事件使用过期容器数据。
    @Inject(method = "buildInventorySummary", at = @At("TAIL"))
    private static void ftb_quests_container_integration$appendContainerSnapshot(ServerPlayer player, CallbackInfo callbackInfo) {
        for (ItemStack stack : ContainerScanManager.getActiveSnapshot(player)) {
            inventorySummaryCache.computeIfAbsent(stack.getItem(), item -> new ArrayList<>()).add(stack);
            nonEmptyStacks.add(stack);
        }
    }
}
