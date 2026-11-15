package com.ftb_quests_container_integration.container;

import com.ftb_quests_container_integration.Config;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SophisticatedBackpacksIntegration {
    private static final Map<UUID, PlayerCache> PLAYERS = new HashMap<>();

    private SophisticatedBackpacksIntegration() {
    }

    static void tick(MinecraftServer server) {
        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PLAYERS.computeIfAbsent(player.getUUID(), uuid -> new PlayerCache(player));
        }
        PLAYERS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entry.getValue().close();
                return true;
            }
            entry.getValue().tick(gameTime);
            return false;
        });
    }

    static List<ItemStack> getCachedStacks(ServerPlayer player) {
        PlayerCache cache = PLAYERS.get(player.getUUID());
        return cache == null ? List.of() : cache.getCachedStacks();
    }

    static long consumeMatching(ItemTask task, ServerPlayer player, long remaining) {
        if (!Config.consumeFromContainers || remaining <= 0L) {
            return 0L;
        }

        PlayerCache cache = PLAYERS.get(player.getUUID());
        if (cache == null || !cache.ready) {
            return 0L;
        }

        long consumed = 0L;
        for (Candidate candidate : cache.getCandidates(task)) {
            if (consumed >= remaining) {
                break;
            }

            BackpackBinding binding = candidate.binding;
            if (!binding.active || binding.handler.getSlots() <= candidate.slot) {
                continue;
            }
            ItemStack current = binding.handler.getStackInSlot(candidate.slot);
            if (current.isEmpty() || !task.test(current)) {
                continue;
            }

            long needed = remaining - consumed;
            int amount = (int) Math.min((long) current.getCount(), needed);
            if (amount <= 0) {
                continue;
            }

            ItemStack extracted = binding.handler.extractItem(candidate.slot, amount, false);
            if (!extracted.isEmpty()) {
                consumed += extracted.getCount();
            }
        }
        return consumed;
    }

    static void removePlayer(ServerPlayer player) {
        PlayerCache cache = PLAYERS.remove(player.getUUID());
        if (cache != null) {
            cache.close();
        }
    }

    static void clear() {
        PLAYERS.values().forEach(PlayerCache::close);
        PLAYERS.clear();
    }

    private record ScanEntry(BackpackBinding binding, int slot) {
    }

    private static final class Candidate {
        final BackpackBinding binding;
        final int slot;
        final ItemStack stack;

        Candidate(BackpackBinding binding, int slot, ItemStack stack) {
            this.binding = binding;
            this.slot = slot;
            this.stack = stack.copy();
        }
    }

    private static final class PlayerCache {
        final ServerPlayer player;
        final Map<String, BackpackBinding> roots = new HashMap<>();
        final Set<BackpackBinding> bindings = Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<ScanEntry> scanQueue = new ArrayDeque<>();
        long nextRootCheck;
        boolean ready;
        boolean scanning;
        boolean closed;

        PlayerCache(ServerPlayer player) {
            this.player = player;
        }

        void tick(long gameTime) {
            if (closed) {
                return;
            }

            Map<String, ItemStack> currentRoots = null;
            if (!roots.isEmpty() || gameTime >= nextRootCheck) {
                currentRoots = SophisticatedBackpacksProvider.collectTopLevel(player);
                for (Map.Entry<String, BackpackBinding> entry : new ArrayList<>(roots.entrySet())) {
                    ItemStack current = currentRoots.get(entry.getKey());
                    if (current == null || !entry.getValue().isSameSource(current)) {
                        roots.remove(entry.getKey());
                        removeBinding(entry.getValue());
                    }
                }
            }

            if (gameTime >= nextRootCheck) {
                reconcileRoots(currentRoots);
                nextRootCheck = gameTime + Math.max(1L, (long) Config.scanIntervalSeconds * 20L);
            }
            processBatch();
        }

        private void reconcileRoots(Map<String, ItemStack> currentRoots) {
            Set<String> removedKeys = new HashSet<>(roots.keySet());
            currentRoots.forEach((key, stack) -> {
                removedKeys.remove(key);
                BackpackBinding existing = roots.get(key);
                if (existing != null && existing.isSameSource(stack)) {
                    return;
                }
                if (existing != null) {
                    removeBinding(existing);
                }
                BackpackBinding created = createBinding(stack, 0, null, -1);
                if (created != null) {
                    roots.put(key, created);
                    addBinding(created);
                } else {
                    roots.remove(key);
                }
            });
            removedKeys.forEach(key -> {
                BackpackBinding removed = roots.remove(key);
                if (removed != null) {
                    removeBinding(removed);
                }
            });
            finishEmptyScan();
        }

        private void addBinding(BackpackBinding binding) {
            bindings.add(binding);
            ready = false;
            enqueue(binding);
        }

        private void updateNestedBinding(BackpackBinding parent, int slot, ItemStack stack) {
            BackpackBinding existing = parent.children.get(slot);
            if (stack.isEmpty() || parent.depth >= Config.nestedBackpackDepth) {
                if (existing != null) {
                    parent.children.remove(slot);
                    removeBinding(existing);
                }
                return;
            }

            if (existing != null && existing.isSameSource(stack)) {
                return;
            }
            if (existing != null) {
                parent.children.remove(slot);
                removeBinding(existing);
            }

            BackpackBinding created = createBinding(stack, parent.depth + 1, parent, slot);
            if (created != null) {
                parent.children.put(slot, created);
                addBinding(created);
            }
        }

        private void slotChanged(BackpackBinding binding, int slot) {
            if (closed || !binding.active || binding.handler.getSlots() <= slot) {
                return;
            }
            if (scanning) {
                binding.changedDuringScan.add(slot);
            }
            ItemStack stack = binding.handler.getStackInSlot(slot);
            updateSlot(binding, slot, stack);
            if (ready) {
                ContainerScanManager.requestDetection(player);
            }
        }

        private void handlerRefreshed(BackpackBinding binding) {
            if (closed || !binding.active) {
                return;
            }
            InventoryHandler refreshed = binding.wrapper.getInventoryHandler();
            if (refreshed == null || refreshed == binding.handler) {
                return;
            }
            new ArrayList<>(binding.children.values()).forEach(this::removeBinding);
            binding.children.clear();
            binding.handler = refreshed;
            binding.slots.clear();
            binding.changedDuringScan.clear();
            ready = false;
            binding.registerHandlerListener();
            enqueue(binding);
        }

        private void updateSlot(BackpackBinding binding, int slot, ItemStack stack) {
            if (stack.isEmpty()) {
                binding.slots.remove(slot);
            } else {
                binding.slots.put(slot, stack.copy());
            }
            updateNestedBinding(binding, slot, stack);
        }

        private void enqueue(BackpackBinding binding) {
            if (!binding.active) {
                return;
            }
            scanning = true;
            if (binding.handler.getSlots() > 0) {
                scanQueue.add(new ScanEntry(binding, 0));
            }
        }

        private void processBatch() {
            int budget = Math.max(1, Config.maxSlotsPerPlayerPerTick);
            while (budget-- > 0 && !scanQueue.isEmpty()) {
                ScanEntry entry = scanQueue.removeFirst();
                BackpackBinding binding = entry.binding();
                int slot = entry.slot();
                if (!binding.active || binding.handler.getSlots() <= slot) {
                    continue;
                }
                if (!binding.changedDuringScan.remove(slot)) {
                    updateSlot(binding, slot, binding.handler.getStackInSlot(slot));
                }
                if (slot + 1 < binding.handler.getSlots()) {
                    scanQueue.addLast(new ScanEntry(binding, slot + 1));
                }
            }
            finishEmptyScan();
        }

        private void finishEmptyScan() {
            if (scanning && scanQueue.isEmpty()) {
                scanning = false;
                ready = true;
                ContainerScanManager.requestDetection(player);
            }
        }

        List<ItemStack> getCachedStacks() {
            if (!ready) {
                return List.of();
            }
            List<ItemStack> result = new ArrayList<>();
            bindings.forEach(binding -> binding.slots.values().forEach(stack -> {
                if (!stack.isEmpty()) {
                    result.add(stack.copy());
                }
            }));
            return result;
        }

        List<Candidate> getCandidates(ItemTask task) {
            List<Candidate> result = new ArrayList<>();
            bindings.forEach(binding -> binding.slots.forEach((slot, stack) -> {
                if (!stack.isEmpty() && task.test(stack)) {
                    result.add(new Candidate(binding, slot, stack));
                }
            }));
            return result;
        }

        private void removeBinding(BackpackBinding binding) {
            if (!binding.active) {
                return;
            }
            new ArrayList<>(binding.children.values()).forEach(this::removeBinding);
            binding.children.clear();
            binding.active = false;
            binding.wrapper.unregisterOnInventoryHandlerRefreshListener();
            bindings.remove(binding);
            if (!closed) {
                ContainerScanManager.requestDetection(player);
            }
        }

        private BackpackBinding createBinding(ItemStack stack, int depth, BackpackBinding parent, int parentSlot) {
            IBackpackWrapper wrapper = resolveWrapper(stack);
            if (wrapper == null) {
                return null;
            }
            return new BackpackBinding(this, wrapper, stack, depth, parent, parentSlot);
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            new ArrayList<>(roots.values()).forEach(this::removeBinding);
            roots.clear();
            bindings.clear();
            scanQueue.clear();
        }
    }

    private static IBackpackWrapper resolveWrapper(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BackpackItem)) {
            return null;
        }
        try {
            IBackpackWrapper wrapper = BackpackWrapper.fromStack(stack);
            return wrapper == null || wrapper == IBackpackWrapper.Noop.INSTANCE ? null : wrapper;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static final class BackpackBinding {
        final PlayerCache owner;
        final IBackpackWrapper wrapper;
        final ItemStack sourceStackReference;
        final UUID contentsUuid;
        final int depth;
        final BackpackBinding parent;
        final int parentSlot;
        final Map<Integer, ItemStack> slots = new HashMap<>();
        final Map<Integer, BackpackBinding> children = new HashMap<>();
        final Set<Integer> changedDuringScan = new HashSet<>();
        InventoryHandler handler;
        boolean active = true;

        BackpackBinding(PlayerCache owner, IBackpackWrapper wrapper, ItemStack sourceStack, int depth, BackpackBinding parent, int parentSlot) {
            this.owner = owner;
            this.wrapper = wrapper;
            this.sourceStackReference = sourceStack;
            this.contentsUuid = wrapper.getContentsUuid().orElse(null);
            this.depth = depth;
            this.parent = parent;
            this.parentSlot = parentSlot;
            this.handler = wrapper.getInventoryHandler();
            registerHandlerListener();
            WeakReference<BackpackBinding> reference = new WeakReference<>(this);
            wrapper.registerOnInventoryHandlerRefreshListener(() -> {
                BackpackBinding binding = reference.get();
                if (binding != null && binding.active) {
                    binding.owner.handlerRefreshed(binding);
                }
            });
        }

        void registerHandlerListener() {
            WeakReference<BackpackBinding> reference = new WeakReference<>(this);
            InventoryHandler observedHandler = handler;
            observedHandler.addListener(slot -> {
                BackpackBinding binding = reference.get();
                if (binding != null && binding.active && binding.handler == observedHandler) {
                    binding.owner.slotChanged(binding, slot);
                }
            });
        }

        boolean isSameSource(ItemStack stack) {
            IBackpackWrapper currentWrapper = resolveWrapper(stack);
            if (currentWrapper == null) {
                return false;
            }
            UUID currentContentsUuid = currentWrapper.getContentsUuid().orElse(null);
            if (contentsUuid != null || currentContentsUuid != null) {
                return contentsUuid != null && contentsUuid.equals(currentContentsUuid);
            }
            return sourceStackReference == stack;
        }
    }
}
