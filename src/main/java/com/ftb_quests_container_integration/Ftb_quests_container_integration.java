package com.ftb_quests_container_integration;

import com.ftb_quests_container_integration.container.ContainerScanManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

// 负责注册配置并把容器扫描器接入 Forge 服务端事件总线。
    @Mod(Ftb_quests_container_integration.MODID)
public final class Ftb_quests_container_integration {
    public static final String MODID = "ftb_quests_container_integration";

    @SuppressWarnings("removal")
    public Ftb_quests_container_integration() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }

    // 负责在服务端 tick 末尾分批推进所有在线玩家的容器扫描。
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ContainerScanManager.tick(event.getServer());
        }
    }

    // 监听玩家打开容器事件，用于绑定 Refined Storage Grid 来源。
    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ContainerScanManager.onContainerOpen(player, event.getContainer());
        }
    }

    // 负责清理退出玩家的扫描状态，避免跨玩家复用快照。
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ContainerScanManager.removePlayer(event.getEntity());
    }

    // 负责在服务端停止时释放所有扫描快照和调度状态。
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ContainerScanManager.clear();
    }
}
