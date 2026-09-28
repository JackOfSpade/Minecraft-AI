package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public final class Node {
    private final BlockPos pos;
    private final double gCost;
    private final double hCost;
    private final MoveType moveType;
    private final Node parent;

    public Node(BlockPos pos, double gCost, double hCost, MoveType moveType, Node parent) {
        this.pos = pos.toImmutable();
        this.gCost = gCost;
        this.hCost = hCost;
        this.moveType = moveType;
        this.parent = parent;
    }

    public BlockPos pos() {
        return pos;
    }

    public double gCost() {
        return gCost;
    }

    public double hCost() {
        return hCost;
    }

    public MoveType moveType() {
        return moveType;
    }

    public Node parent() {
        return parent;
    }

    public double fCost() {
        return gCost + hCost;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Node node && pos.equals(node.pos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos);
    }
}
