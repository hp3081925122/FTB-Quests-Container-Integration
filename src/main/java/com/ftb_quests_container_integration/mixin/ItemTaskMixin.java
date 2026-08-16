package com.ftb_quests_container_integration.mixin;

import com.ftb_quests_container_integration.container.ContainerScanManager;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 负责让 FTB Quests 消耗型物品任务在原版物品栏之后继续消耗便携容器内容。
@Mixin(value = ItemTask.class, remap = false)
public abstract class ItemTaskMixin {
    // 在 FTB Quests 原有消耗流程结束后补充容器消耗。
    @Inject(method = "submitTask", at = @At("TAIL"))
    private void ftb_quests_container_integration$consumeFromContainers(TeamData teamData, ServerPlayer player, ItemStack craftedItem, CallbackInfo callbackInfo) {
        ContainerScanManager.consumeMatching((ItemTask) (Object) this, teamData, player, craftedItem);
    }
}
