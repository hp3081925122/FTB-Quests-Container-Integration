package com.ftb_quests_container_integration.mixin;

import com.ftb_quests_container_integration.container.ContainerScanManager;
import dev.ftb.mods.ftbquests.util.PlayerInventorySummary;
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

@Mixin(value = PlayerInventorySummary.class, remap = false)
public abstract class PlayerInventorySummaryMixin {
    @Shadow
    @Final
    private static Map<Item, List<ItemStack>> stacksByItem;

    @Shadow
    @Final
    private static List<ItemStack> nonEmptyStacks;

    @Inject(method = "build", at = @At("TAIL"))
    private static void ftb_quests_container_integration$appendContainerSnapshot(ServerPlayer player, CallbackInfo callbackInfo) {
        for (ItemStack stack : ContainerScanManager.getActiveSnapshot(player)) {
            if (!stack.isEmpty()) {
                stacksByItem.computeIfAbsent(stack.getItem(), item -> new ArrayList<>()).add(stack);
                nonEmptyStacks.add(stack);
            }
        }
    }
}
