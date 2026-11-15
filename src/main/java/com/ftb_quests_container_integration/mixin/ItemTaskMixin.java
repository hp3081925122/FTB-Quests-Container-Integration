package com.ftb_quests_container_integration.mixin;

import com.ftb_quests_container_integration.container.ContainerScanManager;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemTask.class, remap = false)
public abstract class ItemTaskMixin {
    @Inject(method = "submitTask", at = @At("TAIL"))
    private void ftb_quests_container_integration$consumeFromContainers(TeamData teamData, ServerPlayer player, ItemStack craftedItem, CallbackInfo callbackInfo) {
        ContainerScanManager.consumeMatching((ItemTask) (Object) this, teamData, player, craftedItem);
    }
}
