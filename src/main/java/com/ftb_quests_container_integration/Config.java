package com.ftb_quests_container_integration;

import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACKS = BUILDER
            .comment("启用 Sophisticated Backpacks 内容扫描。\nEnable Sophisticated Backpacks content scanning.")
            .define("enable_sophisticated_backpacks", true);

    private static final ModConfigSpec.BooleanValue ENABLE_REFINED_STORAGE = BUILDER
            .comment("启用 Refined Storage 便携 Grid 和主动远程网络内容缓存。\nEnable Refined Storage portable Grid and actively opened remote network content caching.")
            .define("enable_refined_storage", true);

    private static final ModConfigSpec.BooleanValue ENABLE_APPLIED_ENERGISTICS_2 = BUILDER
            .comment("启用 Applied Energistics 2 网络内容缓存。\nEnable Applied Energistics 2 network content caching.")
            .define("enable_applied_energistics_2", true);

    private static final ModConfigSpec.BooleanValue ENABLE_BEYOND_DIMENSIONS = BUILDER
            .comment("启用 Beyond Dimensions 网络内容缓存。\nEnable Beyond Dimensions network content caching.")
            .define("enable_beyond_dimensions", true);

    private static final ModConfigSpec.IntValue SCAN_INTERVAL_SECONDS = BUILDER
            .comment("完整扫描之间的目标间隔，单位为秒。\nTarget interval between complete scans, in seconds.")
            .defineInRange("scan_interval_seconds", 3, 1, 3600);

    private static final ModConfigSpec.IntValue MAX_SLOTS_PER_PLAYER_PER_TICK = BUILDER
            .comment("单个玩家每个服务端 tick 的最大槽位读取量。\nMaximum slots read for one player per server tick.")
            .defineInRange("max_slots_per_player_per_tick", 32, 1, 4096);

    private static final ModConfigSpec.IntValue MAX_REFINED_STORAGE_ENTRIES_PER_PLAYER_PER_TICK = BUILDER
            .comment("单个玩家每个服务端 tick 的最大 RS 物品条目读取量。\nMaximum Refined Storage entries read for one player per server tick.")
            .defineInRange("max_refined_storage_entries_per_player_per_tick", 16, 1, 4096);

    private static final ModConfigSpec.IntValue MAX_BEYOND_DIMENSIONS_ENTRIES_PER_PLAYER_PER_TICK = BUILDER
            .comment("单个玩家每个服务端 tick 的最大 Beyond Dimensions 物品条目读取量。\nMaximum Beyond Dimensions entries read for one player per server tick.")
            .defineInRange("max_beyond_dimensions_entries_per_player_per_tick", 16, 1, 4096);

    private static final ModConfigSpec.IntValue APPLIED_ENERGISTICS_2_REFRESH_INTERVAL_SECONDS = BUILDER
            .comment("Applied Energistics 2 缓存完整刷新间隔，单位为秒。\nFull Applied Energistics 2 cache refresh interval, in seconds.")
            .defineInRange("applied_energistics_2_refresh_interval_seconds", 3, 1, 3600);

    private static final ModConfigSpec.BooleanValue SCAN_ARMOR_AND_OFFHAND = BUILDER
            .comment("扫描盔甲栏和副手中的便携容器。\nScan portable containers in armor and offhand slots.")
            .define("scan_armor_and_offhand", true);

    private static final ModConfigSpec.BooleanValue SCAN_CURIOS = BUILDER
            .comment("扫描 Curios 槽位中的便携容器。\nScan portable containers in Curios slots.")
            .define("scan_curios", true);

    private static final ModConfigSpec.IntValue NESTED_BACKPACK_DEPTH = BUILDER
            .comment("背包嵌套扫描最大深度，零表示关闭。\nMaximum nested backpack scan depth, zero disables it.")
            .defineInRange("nested_backpack_depth", 0, 0, 8);

    private static final ModConfigSpec.BooleanValue CONSUME_FROM_CONTAINERS = BUILDER
            .comment("允许消耗型任务从便携容器扣除物品。\nAllow consuming tasks to remove items from portable containers.")
            .define("consume_from_containers", true);

    private static final ModConfigSpec.BooleanValue CONSUME_FROM_REFINED_STORAGE = BUILDER
            .comment("允许消耗型任务从绑定的 Refined Storage 存储扣除物品。\nAllow consuming tasks to remove items from bound Refined Storage storage.")
            .define("consume_from_refined_storage", true);

    private static final ModConfigSpec.BooleanValue CONSUME_FROM_APPLIED_ENERGISTICS_2 = BUILDER
            .comment("允许消耗型任务从绑定的 Applied Energistics 2 存储扣除物品。\nAllow consuming tasks to remove items from bound Applied Energistics 2 storage.")
            .define("consume_from_applied_energistics_2", true);

    private static final ModConfigSpec.BooleanValue CONSUME_FROM_BEYOND_DIMENSIONS = BUILDER
            .comment("允许消耗型任务从绑定的 Beyond Dimensions 存储扣除物品。\nAllow consuming tasks to remove items from bound Beyond Dimensions storage.")
            .define("consume_from_beyond_dimensions", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enableSophisticatedBackpacks = true;
    public static boolean enableRefinedStorage = true;
    public static boolean enableAppliedEnergistics2 = true;
    public static boolean enableBeyondDimensions = true;
    public static int scanIntervalSeconds = 3;
    public static int maxSlotsPerPlayerPerTick = 32;
    public static int maxRefinedStorageEntriesPerPlayerPerTick = 16;
    public static int maxBeyondDimensionsEntriesPerPlayerPerTick = 16;
    public static int appliedEnergistics2RefreshIntervalSeconds = 3;
    public static boolean scanArmorAndOffhand = true;
    public static boolean scanCurios = true;
    public static int nestedBackpackDepth = 0;
    public static boolean consumeFromContainers = true;
    public static boolean consumeFromRefinedStorage = true;
    public static boolean consumeFromAppliedEnergistics2 = true;
    public static boolean consumeFromBeyondDimensions = true;

    private Config() {
    }

    static void onLoading(final ModConfigEvent.Loading event) {
        sync(event.getConfig().getSpec());
    }

    static void onReloading(final ModConfigEvent.Reloading event) {
        sync(event.getConfig().getSpec());
    }

    private static void sync(IConfigSpec spec) {
        if (spec != SPEC) {
            return;
        }

        enableSophisticatedBackpacks = ENABLE_SOPHISTICATED_BACKPACKS.get();
        enableRefinedStorage = ENABLE_REFINED_STORAGE.get();
        enableAppliedEnergistics2 = ENABLE_APPLIED_ENERGISTICS_2.get();
        enableBeyondDimensions = ENABLE_BEYOND_DIMENSIONS.get();
        scanIntervalSeconds = SCAN_INTERVAL_SECONDS.get();
        maxSlotsPerPlayerPerTick = MAX_SLOTS_PER_PLAYER_PER_TICK.get();
        maxRefinedStorageEntriesPerPlayerPerTick = MAX_REFINED_STORAGE_ENTRIES_PER_PLAYER_PER_TICK.get();
        maxBeyondDimensionsEntriesPerPlayerPerTick = MAX_BEYOND_DIMENSIONS_ENTRIES_PER_PLAYER_PER_TICK.get();
        appliedEnergistics2RefreshIntervalSeconds = APPLIED_ENERGISTICS_2_REFRESH_INTERVAL_SECONDS.get();
        scanArmorAndOffhand = SCAN_ARMOR_AND_OFFHAND.get();
        scanCurios = SCAN_CURIOS.get();
        nestedBackpackDepth = NESTED_BACKPACK_DEPTH.get();
        consumeFromContainers = CONSUME_FROM_CONTAINERS.get();
        consumeFromRefinedStorage = CONSUME_FROM_REFINED_STORAGE.get();
        consumeFromAppliedEnergistics2 = CONSUME_FROM_APPLIED_ENERGISTICS_2.get();
        consumeFromBeyondDimensions = CONSUME_FROM_BEYOND_DIMENSIONS.get();
    }
}
