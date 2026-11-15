package com.ftb_quests_container_integration;

import com.ftb_quests_container_integration.container.ContainerScanManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(Ftb_quests_container_integration.MODID)
public final class Ftb_quests_container_integration {
    public static final String MODID = "ftb_quests_container_integration";

    public Ftb_quests_container_integration(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(Config::onLoading);
        modEventBus.addListener(Config::onReloading);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
        ContainerScanManager.init();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ContainerScanManager.tick(event.getServer());
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ContainerScanManager.onContainerOpen(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ContainerScanManager.onContainerClose(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ContainerScanManager.removePlayer(event.getEntity());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ContainerScanManager.clear();
    }
}
