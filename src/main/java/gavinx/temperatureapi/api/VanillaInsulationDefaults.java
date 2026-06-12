package gavinx.temperatureapi.api;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChainBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * VanillaInsulationDefaults
 *
 * Installs sensible default insulation values for blocks, classified by shape/type rather than
 * enumerated one by one. Blocks with visible holes (fences, walls, panes, bars) or that are only
 * partial barriers (slabs, stairs) insulate less than a solid full block; wool is a strong
 * insulator (0.5); metals are conductive (negative, so they make the interior overshoot the
 * outdoor swing); everything else falls through to {@link BlockInsulationAPI#DEFAULT_INSULATION}
 * (0.3).
 *
 * Because the checks are {@code instanceof} on the vanilla base classes, well-behaved modded
 * blocks that extend them (modded fences, slabs, etc.) get the same sensible defaults for free.
 *
 * This is registered as the lowest-priority fallback, so any mod can override a specific block by
 * calling {@link BlockInsulationAPI#register(Block, double)} (or registering its own provider),
 * regardless of mod load order.
 */
public final class VanillaInsulationDefaults {

    private VanillaInsulationDefaults() {}

    /** Hook the classifier in as the default insulation provider. Call once during mod init. */
    public static void install() {
        BlockInsulationAPI.setDefaultProvider(VanillaInsulationDefaults::classify);
    }

    /**
     * Classify a block's default insulation. Returns null (no opinion -> DEFAULT_INSULATION) for
     * full solid blocks and anything unrecognized, so only the leakier shapes are lowered.
     */
    static Double classify(World world, BlockPos pos, BlockState state) {
        Block b = state.getBlock();

        // See-through / holey barriers: air flows around the bars and pickets.
        if (b instanceof FenceBlock || b instanceof FenceGateBlock || b instanceof WallBlock
                || b instanceof PaneBlock || b instanceof LadderBlock || b instanceof ChainBlock) {
            return 0.10;
        }
        // Gappy canopy.
        if (b instanceof LeavesBlock) {
            return 0.15;
        }

        // Material-based buffs/debuffs (by sound group, so modded metals/wool are covered too).
        BlockSoundGroup sg = state.getSoundGroup();
        // Metals conduct heat: negative insulation makes the interior overshoot the outdoor swing.
        if (sg == BlockSoundGroup.METAL || sg == BlockSoundGroup.COPPER || sg == BlockSoundGroup.NETHERITE) {
            return -0.50;
        }
        // Wool is a strong insulator.
        if (sg == BlockSoundGroup.WOOL) {
            return 0.50;
        }

        // Half / partial solids. A double slab is a full block, so leave it at the default.
        if (b instanceof SlabBlock) {
            boolean doubleSlab = state.contains(SlabBlock.TYPE) && state.get(SlabBlock.TYPE) == SlabType.DOUBLE;
            return doubleSlab ? null : 0.20;
        }
        if (b instanceof StairsBlock) {
            return 0.20;
        }
        // Closed doors / trapdoors are thinner solid barriers (open ones are passable and never
        // reach the room scanner as a wall face).
        if (b instanceof DoorBlock || b instanceof TrapdoorBlock) {
            return 0.25;
        }
        // Full solid blocks, glass, etc.: no opinion -> BlockInsulationAPI.DEFAULT_INSULATION.
        return null;
    }
}
