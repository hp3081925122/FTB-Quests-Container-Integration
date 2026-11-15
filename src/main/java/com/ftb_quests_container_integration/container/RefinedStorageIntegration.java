package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import com.mojang.logging.LogUtils;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.api.network.impl.node.grid.GridWatcherManager;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.security.PlatformSecurityNetworkComponent;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.security.BuiltinPermission;
import com.refinedmods.refinedstorage.common.storage.portablegrid.AbstractPortableGridContainerMenu;
import com.refinedmods.refinedstorage.common.support.AbstractBaseContainerMenu;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class RefinedStorageIntegration {
    private static final String MOD_ID = "refinedstorage";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Binding> BINDINGS = new HashMap<>();

    private static final Field MENU_GRID_FIELD = findField(AbstractGridContainerMenu.class, "grid");
    private static final Field MENU_DISABLED_SLOT_FIELD = findField(AbstractBaseContainerMenu.class, "disabledSlot");
    private static final Class<?> WIRELESS_GRID_CLASS = findClass("com.refinedmods.refinedstorage.common.grid.WirelessGrid");
    private static final Field WIRELESS_CONTEXT_FIELD = findField(WIRELESS_GRID_CLASS, "context");
    private static final Field WIRELESS_WATCHERS_FIELD = findField(WIRELESS_GRID_CLASS, "watchers");
    private static final Class<?> PORTABLE_GRID_CLASS = findClass("com.refinedmods.refinedstorage.common.storage.portablegrid.PortableGrid");
    private static final Field PORTABLE_ENERGY_FIELD = findField(PORTABLE_GRID_CLASS, "energyStorage");

    private RefinedStorageIntegration() {
    }

    static boolean isAvailable() {
        return Config.enableRefinedStorage && ModList.get().isLoaded(MOD_ID);
    }

    static void onContainerOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (!isAvailable() || !(menu instanceof AbstractGridContainerMenu gridMenu)) {
            return;
        }

        Binding newBinding = createBinding(player, gridMenu);
        if (newBinding == null) {
            return;
        }
        newBinding.menu = menu;

        Binding oldBinding = BINDINGS.get(player.getUUID());
        if (oldBinding != null && oldBinding.isSameSource(newBinding)) {
            oldBinding.menu = menu;
            newBinding.close();
            ContainerScanManager.requestDetection(player);
            return;
        }

        if (oldBinding != null) {
            oldBinding.close();
        }
        BINDINGS.put(player.getUUID(), newBinding);
        newBinding.syncRootStorage(false);
    }

    static void tick(MinecraftServer server) {
        BINDINGS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entry.getValue().close();
                return true;
            }

            Binding binding = entry.getValue();
            if (!binding.isSourcePresent()) {
                binding.close();
                ContainerScanManager.requestDetection(player);
                return true;
            }

            binding.syncRootStorage(true);
            binding.processBatch();
            return false;
        });
    }

    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        Binding binding = BINDINGS.get(player.getUUID());
        return binding == null ? List.of() : binding.getCachedStacks();
    }

    static void onContainerClose(ServerPlayer player, AbstractContainerMenu menu) {
        Binding binding = BINDINGS.get(player.getUUID());
        if (binding != null && binding.menu == menu) {
            BINDINGS.remove(player.getUUID());
            binding.close();
            ContainerScanManager.requestDetection(player);
        }
    }

    static void removePlayer(ServerPlayer player) {
        Binding binding = BINDINGS.remove(player.getUUID());
        if (binding != null) {
            binding.close();
        }
    }

    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        if (!Config.consumeFromRefinedStorage || remaining <= 0L) {
            return 0L;
        }

        Binding binding = BINDINGS.get(player.getUUID());
        if (binding == null || !binding.scanComplete) {
            return 0L;
        }

        long consumed = 0L;
        for (Map.Entry<ItemResource, Long> candidate : binding.getMatchingEntries(task)) {
            if (consumed >= remaining) {
                break;
            }

            long needed = remaining - consumed;
            long amount = Math.min(candidate.getValue(), needed);
            if (amount <= 0L) {
                continue;
            }

            long extracted = binding.extract(player, candidate.getKey(), amount);
            if (extracted > 0L) {
                consumed += extracted;
            }
        }
        return consumed;
    }

    static void clear() {
        BINDINGS.values().forEach(Binding::close);
        BINDINGS.clear();
    }

    private static Binding createBinding(ServerPlayer player, AbstractGridContainerMenu menu) {
        Grid grid = getFieldValue(MENU_GRID_FIELD, menu, Grid.class);
        if (grid == null) {
            return null;
        }

        SlotReference slotReference = getFieldValue(MENU_DISABLED_SLOT_FIELD, menu, SlotReference.class);
        boolean portableMenu = menu instanceof AbstractPortableGridContainerMenu;
        boolean wirelessMenu = !portableMenu && slotReference != null;
        if (!portableMenu && !wirelessMenu) {
            return null;
        }

        ItemStack sourceStack = slotReference == null ? null : slotReference.resolve(player).orElse(null);
        if (portableMenu) {
            EnergyStorage energyStorage = PORTABLE_GRID_CLASS != null && PORTABLE_GRID_CLASS.isInstance(grid)
                    ? getFieldValue(PORTABLE_ENERGY_FIELD, grid, EnergyStorage.class)
                    : null;
            return Binding.forPortable(player, grid, energyStorage, slotReference, sourceStack);
        }

        NetworkItemContext context = WIRELESS_GRID_CLASS != null && WIRELESS_GRID_CLASS.isInstance(grid)
                ? getFieldValue(WIRELESS_CONTEXT_FIELD, grid, NetworkItemContext.class)
                : null;
        GridWatcherManager watchers = WIRELESS_GRID_CLASS != null && WIRELESS_GRID_CLASS.isInstance(grid)
                ? getFieldValue(WIRELESS_WATCHERS_FIELD, grid, GridWatcherManager.class)
                : null;
        if (context != null && context.resolveNetwork().isEmpty()) {
            return null;
        }
        return Binding.forWireless(player, grid, context, watchers, slotReference, sourceStack);
    }

    private static Class<?> findClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            LOGGER.debug("Refined Storage class {} not found: {}", className, exception.getMessage());
            return null;
        }
    }

    private static Field findField(Class<?> owner, String fieldName) {
        if (owner == null) {
            return null;
        }
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            LOGGER.debug("Refined Storage field {}.{} not found: {}", owner.getName(), fieldName, exception.getMessage());
            return null;
        }
    }

    private static <T> T getFieldValue(Field field, Object target, Class<T> expectedType) {
        if (field == null || target == null) {
            return null;
        }
        try {
            Object value = field.get(target);
            return expectedType.isInstance(value) ? expectedType.cast(value) : null;
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static final class Binding {
        final ServerPlayer player;
        AbstractContainerMenu menu;
        final Grid grid;
        final boolean portable;
        final NetworkItemContext networkContext;
        final GridWatcherManager wirelessWatchers;
        final EnergyStorage portableEnergyStorage;
        final SlotReference slotReference;
        final ItemStack sourceStackReference;
        final RootStorageListener listener;
        final Map<ItemResource, Long> cached = new HashMap<>();
        final Set<ItemResource> changedDuringScan = new HashSet<>();
        RootStorage rootStorage;
        List<ItemResource> initialKeys = List.of();
        int initialIndex;
        boolean scanComplete;
        boolean scanning;
        boolean closed;

        static Binding forWireless(ServerPlayer player, Grid grid, NetworkItemContext networkContext,
                                   GridWatcherManager wirelessWatchers, SlotReference slotReference,
                                   ItemStack sourceStackReference) {
            return new Binding(player, grid, false, networkContext, wirelessWatchers, null, slotReference, sourceStackReference);
        }

        static Binding forPortable(ServerPlayer player, Grid grid, EnergyStorage portableEnergyStorage,
                                   SlotReference slotReference, ItemStack sourceStackReference) {
            return new Binding(player, grid, true, null, null, portableEnergyStorage, slotReference, sourceStackReference);
        }

        private Binding(ServerPlayer player, Grid grid, boolean portable, NetworkItemContext networkContext,
                        GridWatcherManager wirelessWatchers, EnergyStorage portableEnergyStorage,
                        SlotReference slotReference, ItemStack sourceStackReference) {
            this.player = player;
            this.grid = grid;
            this.portable = portable;
            this.networkContext = networkContext;
            this.wirelessWatchers = wirelessWatchers;
            this.portableEnergyStorage = portableEnergyStorage;
            this.slotReference = slotReference;
            this.sourceStackReference = sourceStackReference;
            this.listener = new CacheListener(this);
        }

        void syncRootStorage(boolean notifyWhenCleared) {
            if (closed) {
                return;
            }
            Storage currentStorage = grid.getItemStorage();
            RootStorage nextRoot = currentStorage instanceof RootStorage root ? root : null;
            if (nextRoot == rootStorage && (nextRoot != null || !scanComplete && cached.isEmpty())) {
                if (nextRoot != null && !scanning && !scanComplete) {
                    startScan();
                }
                return;
            }

            if (rootStorage != null) {
                rootStorage.removeListener(listener);
            }
            rootStorage = nextRoot;
            if (rootStorage != null) {
                rootStorage.addListener(listener);
                startScan();
                if (notifyWhenCleared) {
                    ContainerScanManager.requestDetection(player);
                }
            } else {
                boolean hadData = scanComplete || !cached.isEmpty();
                cached.clear();
                changedDuringScan.clear();
                initialKeys = List.of();
                initialIndex = 0;
                scanning = false;
                scanComplete = false;
                if (notifyWhenCleared && hadData) {
                    ContainerScanManager.requestDetection(player);
                }
            }
        }

        void startScan() {
            cached.clear();
            changedDuringScan.clear();
            if (rootStorage == null) {
                initialKeys = List.of();
                initialIndex = 0;
                scanComplete = false;
                scanning = false;
                return;
            }
            Collection<ResourceAmount> entries = rootStorage.getAll();
            List<ItemResource> keys = new ArrayList<>(entries.size());
            for (ResourceAmount entry : entries) {
                if (entry.amount() > 0L && entry.resource() instanceof ItemResource itemResource) {
                    keys.add(itemResource);
                }
            }
            initialKeys = keys;
            initialIndex = 0;
            scanComplete = false;
            scanning = true;
        }

        void processBatch() {
            if (closed || !scanning || rootStorage == null) {
                return;
            }

            int entriesThisTick = Math.max(1, Config.maxRefinedStorageEntriesPerPlayerPerTick);
            int processed = 0;
            while (processed < entriesThisTick && initialIndex < initialKeys.size()) {
                ItemResource key = initialKeys.get(initialIndex++);
                if (!changedDuringScan.remove(key)) {
                    long currentAmount = rootStorage.get(key);
                    if (currentAmount <= 0L) {
                        cached.remove(key);
                    } else {
                        cached.put(key, currentAmount);
                    }
                }
                processed++;
            }

            if (initialIndex >= initialKeys.size()) {
                scanning = false;
                scanComplete = true;
                ContainerScanManager.requestDetection(player);
            }
        }

        boolean isSameSource(Binding other) {
            if (portable != other.portable) {
                return false;
            }
            if (!portable) {
                Network thisNetwork = resolveNetwork();
                Network otherNetwork = other.resolveNetwork();
                if (thisNetwork != null || otherNetwork != null) {
                    return thisNetwork != null && thisNetwork == otherNetwork;
                }
                return grid == other.grid;
            }
            if (sourceStackReference != null || other.sourceStackReference != null) {
                return sourceStackReference != null && sourceStackReference == other.sourceStackReference;
            }
            return grid == other.grid;
        }

        private Network resolveNetwork() {
            return networkContext == null ? null : networkContext.resolveNetwork().orElse(null);
        }

        boolean isSourcePresent() {
            if (sourceStackReference == null) {
                return true;
            }
            if (slotReference != null) {
                Optional<ItemStack> resolved = slotReference.resolve(player);
                if (resolved.isPresent() && resolved.get() == sourceStackReference) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().items) {
                if (stack == sourceStackReference) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().armor) {
                if (stack == sourceStackReference) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (stack == sourceStackReference) {
                    return true;
                }
            }
            return ModList.get().isLoaded("curios") && CuriosSupport.contains(player, sourceStackReference);
        }

        List<ItemStack> getCachedStacks() {
            if (!scanComplete) {
                return List.of();
            }

            List<ItemStack> stacks = new ArrayList<>(cached.size());
            cached.forEach((resource, amount) -> {
                long remaining = amount;
                while (remaining > 0L) {
                    long part = Math.min((long) Integer.MAX_VALUE, remaining);
                    stacks.add(resource.toItemStack(part));
                    remaining -= part;
                }
            });
            return stacks;
        }

        List<Map.Entry<ItemResource, Long>> getMatchingEntries(ItemTask task) {
            List<Map.Entry<ItemResource, Long>> matches = new ArrayList<>();
            cached.forEach((resource, amount) -> {
                if (amount > 0L && task.test(resource.toItemStack())) {
                    matches.add(Map.entry(resource, amount));
                }
            });
            return matches;
        }

        long extract(ServerPlayer player, ItemResource resource, long amount) {
            if (!grid.isGridActive() || rootStorage == null || amount <= 0L) {
                return 0L;
            }
            if (!portable) {
                return extractFromWirelessNetwork(player, resource, amount);
            }
            return extractFromPortableGrid(player, resource, amount);
        }

        private long extractFromWirelessNetwork(ServerPlayer player, ItemResource resource, long amount) {
            if (networkContext != null) {
                Network network = networkContext.resolveNetwork().orElse(null);
                if (network == null) {
                    return 0L;
                }
                if (RefinedStorageApi.INSTANCE.isEnergyRequired()
                        && network.getComponent(EnergyNetworkComponent.class).getStored() <= 0L) {
                    return 0L;
                }
                PlatformSecurityNetworkComponent security = network.getComponent(PlatformSecurityNetworkComponent.class);
                if (security != null && !security.isAllowed(BuiltinPermission.EXTRACT, player)) {
                    return 0L;
                }
            }

            PlayerActor actor = new PlayerActor(player);
            long simulated = rootStorage.extract(resource, amount, Action.SIMULATE, actor);
            if (simulated <= 0L) {
                return 0L;
            }

            long actualAmount = Math.min(amount, simulated);
            long extracted = rootStorage.extract(resource, actualAmount, Action.EXECUTE, actor);
            if (extracted > 0L && networkContext != null) {
                boolean wasActive = networkContext.isActive();
                networkContext.drainEnergy(Platform.INSTANCE.getConfig().getWirelessGrid().getExtractEnergyUsage());
                boolean isActive = networkContext.isActive();
                if (wasActive != isActive && wirelessWatchers != null) {
                    wirelessWatchers.activeChanged(isActive);
                }
            }
            return extracted;
        }

        private long extractFromPortableGrid(ServerPlayer player, ItemResource resource, long amount) {
            PlayerActor actor = new PlayerActor(player);
            long simulated = rootStorage.extract(resource, amount, Action.SIMULATE, actor);
            if (simulated <= 0L) {
                return 0L;
            }

            long actualAmount = Math.min(amount, simulated);
            long extracted = rootStorage.extract(resource, actualAmount, Action.EXECUTE, actor);
            if (extracted > 0L && portableEnergyStorage != null) {
                portableEnergyStorage.extract(
                        Platform.INSTANCE.getConfig().getPortableGrid().getExtractEnergyUsage(),
                        Action.EXECUTE);
            }
            return extracted;
        }

        void close() {
            if (!closed) {
                closed = true;
                if (rootStorage != null) {
                    rootStorage.removeListener(listener);
                    rootStorage = null;
                }
                cached.clear();
                changedDuringScan.clear();
                initialKeys = List.of();
                scanning = false;
                scanComplete = false;
            }
        }
    }

    private static final class CacheListener implements RootStorageListener {
        private final Binding binding;

        CacheListener(Binding binding) {
            this.binding = binding;
        }

        @Override
        public void changed(MutableResourceList.OperationResult result) {
            if (binding.closed || binding.rootStorage == null || result == null
                    || !(result.resource() instanceof ItemResource itemResource)) {
                return;
            }

            if (binding.scanning) {
                binding.changedDuringScan.add(itemResource);
            }

            long currentAmount = binding.rootStorage.get(itemResource);
            if (currentAmount <= 0L) {
                binding.cached.remove(itemResource);
            } else {
                binding.cached.put(itemResource, currentAmount);
            }

            if (binding.scanComplete) {
                ContainerScanManager.requestDetection(binding.player);
            }
        }
    }
}
