package com.ftb_quests_container_integration;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

// 负责声明并缓存服务端容器扫描配置。
@Mod.EventBusSubscriber(modid = Ftb_quests_container_integration.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // 负责控制 Sophisticated Backpacks 集成是否启用。
    private static final ForgeConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACKS = BUILDER
            .comment("启用 Sophisticated Backpacks 内容扫描。\nEnable Sophisticated Backpacks content scanning.")
            .define("enable_sophisticated_backpacks", true);

    // 负责控制 Refined Storage 便携 Grid 和主动远程网络缓存是否启用。
    private static final ForgeConfigSpec.BooleanValue ENABLE_REFINED_STORAGE = BUILDER
            .comment("启用 Refined Storage 便携 Grid 和主动远程网络内容缓存。\nEnable Refined Storage portable Grid and actively opened remote network content caching.")
            .define("enable_refined_storage", true);

    // 负责控制 Applied Energistics 2 网络内容缓存是否启用。
    private static final ForgeConfigSpec.BooleanValue ENABLE_APPLIED_ENERGISTICS_2 = BUILDER
            .comment("启用 Applied Energistics 2 网络内容缓存。\nEnable Applied Energistics 2 network content caching.")
            .define("enable_applied_energistics_2", true);

    // 负责控制两轮完整容器扫描之间的目标间隔。
    private static final ForgeConfigSpec.IntValue SCAN_INTERVAL_SECONDS = BUILDER
            .comment("完整扫描之间的目标间隔，单位为秒。\nTarget interval between complete scans, in seconds.")
            .defineInRange("scan_interval_seconds", 3, 1, 3600);

    // 负责限制单个玩家在一个服务端 tick 中读取的最大槽位数量。
    private static final ForgeConfigSpec.IntValue MAX_SLOTS_PER_PLAYER_PER_TICK = BUILDER
            .comment("单个玩家每个服务端 tick 的最大槽位读取量。\nMaximum slots read for one player per server tick.")
            .defineInRange("max_slots_per_player_per_tick", 32, 1, 4096);

    // 负责限制单个玩家每个服务端 tick 的 RS 物品条目读取量。
    private static final ForgeConfigSpec.IntValue MAX_REFINED_STORAGE_ENTRIES_PER_PLAYER_PER_TICK = BUILDER
            .comment("单个玩家每个服务端 tick 的最大 RS 物品条目读取量。\nMaximum Refined Storage entries read for one player per server tick.")
            .defineInRange("max_refined_storage_entries_per_player_per_tick", 16, 1, 4096);

    // 负责控制 Applied Energistics 2 缓存的完整刷新间隔。
    private static final ForgeConfigSpec.IntValue APPLIED_ENERGISTICS_2_REFRESH_INTERVAL_SECONDS = BUILDER
            .comment("Applied Energistics 2 缓存完整刷新间隔，单位为秒。\nFull Applied Energistics 2 cache refresh interval, in seconds.")
            .defineInRange("applied_energistics_2_refresh_interval_seconds", 3, 1, 3600);

    // 负责控制玩家盔甲栏和副手中的便携容器是否参与扫描。
    private static final ForgeConfigSpec.BooleanValue SCAN_ARMOR_AND_OFFHAND = BUILDER
            .comment("扫描盔甲栏和副手中的便携容器。\nScan portable containers in armor and offhand slots.")
            .define("scan_armor_and_offhand", true);

    // 负责控制 Curios 槽位中的便携容器是否参与扫描。
    private static final ForgeConfigSpec.BooleanValue SCAN_CURIOS = BUILDER
            .comment("扫描 Curios 槽位中的便携容器。\nScan portable containers in Curios slots.")
            .define("scan_curios", true);

    // 负责控制背包嵌套扫描的最大深度，零表示不扫描嵌套背包。
    private static final ForgeConfigSpec.IntValue NESTED_BACKPACK_DEPTH = BUILDER
            .comment("背包嵌套扫描最大深度，零表示关闭。\nMaximum nested backpack scan depth, zero disables it.")
            .defineInRange("nested_backpack_depth", 0, 0, 8);

    // 负责控制消耗型任务是否可以从便携容器中扣除物品。
    private static final ForgeConfigSpec.BooleanValue CONSUME_FROM_CONTAINERS = BUILDER
            .comment("允许消耗型任务从便携容器扣除物品。\nAllow consuming tasks to remove items from portable containers.")
            .define("consume_from_containers", true);

    // 负责控制消耗型任务是否可以从绑定的 RS 存储中扣除物品。
    private static final ForgeConfigSpec.BooleanValue CONSUME_FROM_REFINED_STORAGE = BUILDER
            .comment("允许消耗型任务从绑定的 Refined Storage 存储扣除物品。\nAllow consuming tasks to remove items from bound Refined Storage storage.")
            .define("consume_from_refined_storage", true);

    // 负责控制消耗型任务是否可以从绑定的 Applied Energistics 2 存储中扣除物品。
    private static final ForgeConfigSpec.BooleanValue CONSUME_FROM_APPLIED_ENERGISTICS_2 = BUILDER
            .comment("允许消耗型任务从绑定的 Applied Energistics 2 存储扣除物品。\nAllow consuming tasks to remove items from bound Applied Energistics 2 storage.")
            .define("consume_from_applied_energistics_2", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enableSophisticatedBackpacks = true;
    public static boolean enableRefinedStorage = true;
    public static boolean enableAppliedEnergistics2 = true;
    public static int scanIntervalSeconds = 3;
    public static int maxSlotsPerPlayerPerTick = 32;
    public static int maxRefinedStorageEntriesPerPlayerPerTick = 16;
    public static int appliedEnergistics2RefreshIntervalSeconds = 3;
    public static boolean scanArmorAndOffhand = true;
    public static boolean scanCurios = true;
    public static int nestedBackpackDepth = 0;
    public static boolean consumeFromContainers = true;
    public static boolean consumeFromRefinedStorage = true;
    public static boolean consumeFromAppliedEnergistics2 = true;

    private Config() {
    }

    // 负责在 Forge 读取配置后同步运行时缓存。
    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }

        enableSophisticatedBackpacks = ENABLE_SOPHISTICATED_BACKPACKS.get();
        enableRefinedStorage = ENABLE_REFINED_STORAGE.get();
        enableAppliedEnergistics2 = ENABLE_APPLIED_ENERGISTICS_2.get();
        scanIntervalSeconds = SCAN_INTERVAL_SECONDS.get();
        maxSlotsPerPlayerPerTick = MAX_SLOTS_PER_PLAYER_PER_TICK.get();
        maxRefinedStorageEntriesPerPlayerPerTick = MAX_REFINED_STORAGE_ENTRIES_PER_PLAYER_PER_TICK.get();
        appliedEnergistics2RefreshIntervalSeconds = APPLIED_ENERGISTICS_2_REFRESH_INTERVAL_SECONDS.get();
        scanArmorAndOffhand = SCAN_ARMOR_AND_OFFHAND.get();
        scanCurios = SCAN_CURIOS.get();
        nestedBackpackDepth = NESTED_BACKPACK_DEPTH.get();
        consumeFromContainers = CONSUME_FROM_CONTAINERS.get();
        consumeFromRefinedStorage = CONSUME_FROM_REFINED_STORAGE.get();
        consumeFromAppliedEnergistics2 = CONSUME_FROM_APPLIED_ENERGISTICS_2.get();
    }
}
