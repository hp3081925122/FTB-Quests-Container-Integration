package com.ftb_quests_container_integration.container;

import net.minecraftforge.items.IItemHandler;

// 记录一个便携容器能力及其嵌套层级。
final class ContainerTarget {
    final IItemHandler handler;
    final int depth;
    int slotIndex;

    // 创建一个从零开始读取的容器目标。
    ContainerTarget(IItemHandler handler, int depth) {
        this.handler = handler;
        this.depth = depth;
    }
}
