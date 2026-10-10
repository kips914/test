package com.nightsiege.ai;

import com.nightsiege.SiegeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.EnumSet;

/**
 * Моб упёрся в препятствие на пути к игроку -> ломает блок постепенно.
 * Время зависит от прочности блока и настроек (blockBreakSpeedMultiplier).
 */
public class BreakBlockGoal extends Goal {
    private static final float MAX_HARDNESS = 5.0f;
    private static final int BASE_TICKS = 30;
    private static final int TICKS_PER_HARDNESS = 40;

    private final Mob mob;
    private final double speedFactor; // 1.0 обычный, 2.5 шахтёр
    private BlockPos pos;
    private int progress;
    private int needed;

    public BreakBlockGoal(Mob mob, double speedFactor) {
        this.mob = mob;
        this.speedFactor = speedFactor;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!mob.horizontalCollision) return false;
        if (!SiegeConfig.BLOCK_BREAKING.get()) return false;
        if (!ForgeEventFactory.getMobGriefingEvent(mob.level(), mob)) return false;
        Player p = mob.level().getNearestPlayer(mob, 64);
        if (p == null) return false;
        pos = find(p);
        return pos != null;
    }

    private BlockPos find(Player p) {
        double dx = p.getX() - mob.getX();
        double dz = p.getZ() - mob.getZ();
        Direction dir = Math.abs(dx) > Math.abs(dz)
                ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos origin = mob.blockPosition();
        // Inspect a small corridor toward the player instead of blindly hitting the first block.
        // Weak wooden defenses (doors, planks, logs, fences) are preferred over stone.
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int forward = 1; forward <= 2; forward++) {
            BlockPos front = origin.relative(dir, forward);
            for (int up = 0; up <= 2; up++) {
                BlockPos candidate = front.above(up);
                if (!canBreak(candidate)) continue;
                int score = blockScore(candidate);
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private int blockScore(BlockPos p) {
        BlockState state = mob.level().getBlockState(p);
        // Lowest score is the most attractive target.
        if (state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR)) return 0;
        if (state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.FENCE_GATES)) return 1;
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)) return 2;
        if (state.is(BlockTags.LOGS)) return 3;
        float hardness = state.getDestroySpeed(mob.level(), p);
        return 10 + Math.round(hardness * 4);
    }

    private boolean canBreak(BlockPos p) {
        BlockState s = mob.level().getBlockState(p);
        if (s.isAir() || s.getCollisionShape(mob.level(), p).isEmpty()) return false;
        float h = s.getDestroySpeed(mob.level(), p);
        // Include doors/fences and common wood defenses, while refusing unbreakable blocks.
        boolean woodDefense = s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS)
                || s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.FENCE_GATES)
                || s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS)
                || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_STAIRS);
        return h >= 0 && h <= MAX_HARDNESS && s.getFluidState().isEmpty()
                && (woodDefense || h <= MAX_HARDNESS);
    }

    @Override
    public void start() {
        progress = 0;
        float h = mob.level().getBlockState(pos).getDestroySpeed(mob.level(), pos);
        double ticks = (BASE_TICKS + h * TICKS_PER_HARDNESS) / (SiegeConfig.breakSpeed() * speedFactor);
        needed = Math.max(10, (int) ticks);
    }

    @Override
    public boolean canContinueToUse() {
        return pos != null && canBreak(pos)
                && mob.distanceToSqr(Vec3.atCenterOf(pos)) < 6.25;
    }

    @Override
    public void tick() {
        if (pos == null) return;
        ServerLevel level = (ServerLevel) mob.level();
        progress++;
        if (progress % 10 == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
            BlockState s = level.getBlockState(pos);
            level.playSound(null, pos, s.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.8f, 0.7f);
        }
        level.destroyBlockProgress(mob.getId(), pos, Math.min(9, progress * 10 / needed));
        if (progress >= needed) {
            level.destroyBlock(pos, true, mob);
            level.destroyBlockProgress(mob.getId(), pos, -1);
            pos = null;
        }
    }

    @Override
    public void stop() {
        if (pos != null) {
            mob.level().destroyBlockProgress(mob.getId(), pos, -1);
        }
        pos = null;
        progress = 0;
    }
}
