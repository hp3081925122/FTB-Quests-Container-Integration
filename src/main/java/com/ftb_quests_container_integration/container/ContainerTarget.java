package com.ftb_quests_container_integration.container;

import net.neoforged.neoforge.items.IItemHandler;

final class ContainerTarget {
    final IItemHandler handler;
    final int depth;
    int slotIndex;

    ContainerTarget(IItemHandler handler, int depth) {
        this.handler = handler;
        this.depth = depth;
    }
}
