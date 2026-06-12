package gavinx.temperatureapi.api.internal;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Occlusion
 *
 * Shared passability and sky-exposure rules used by the air-flow models in this mod
 * (block thermal flood-fill, room scanning, and outdoor-exposure search).
 *
 * Centralizing these rules guarantees that "what counts as sealed" stays consistent
 * across every system: open doors/gates/trapdoors are passable, blocks with no collision
 * shape (e.g. leaves in some configs, torches, snow layers) are passable, and anything
 * with a collision shape (glass, full blocks, closed doors) is not.
 */
public final class Occlusion {

    private Occlusion() {}

    /**
     * True if air "flows" through this position: air, an empty collision shape, or an
     * explicitly-open door/gate/trapdoor.
     */
    public static boolean isPassable(World world, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        try {
            if (state.getCollisionShape(world, pos).isEmpty()) return true;
        } catch (Throwable ignored) {}
        try {
            if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.OPEN) && state.get(DoorBlock.OPEN)) return true;
            if (state.getBlock() instanceof FenceGateBlock && state.contains(FenceGateBlock.OPEN) && state.get(FenceGateBlock.OPEN)) return true;
            if (state.getBlock() instanceof TrapdoorBlock && state.contains(TrapdoorBlock.OPEN) && state.get(TrapdoorBlock.OPEN)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Convenience overload that resolves the block state from the world. */
    public static boolean isPassable(World world, BlockPos pos) {
        return isPassable(world, pos, world.getBlockState(pos));
    }

    /**
     * True if the vertical column above pos up to build height is passable per occlusion rules.
     * Uses world.isSkyVisible as a quick early-out, then validates passability to align with
     * flood-fill behavior (e.g., leaves are passable; glass is not).
     */
    public static boolean hasPassableSkyColumn(World world, BlockPos pos) {
        try { if (!world.isSkyVisible(pos)) return false; } catch (Throwable ignored) {}
        int top = world.getTopY();
        for (int y = pos.getY(); y < top; y++) {
            BlockPos p = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState st = world.getBlockState(p);
            if (!isPassable(world, p, st)) return false;
        }
        return true;
    }
}
