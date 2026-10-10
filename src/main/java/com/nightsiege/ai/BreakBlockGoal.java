package com.nightsiege.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Время зависит от прочности блока: дверь ~7 c, доски ~5 c, камень ~4.5 c, земля ~2.5 c.
 */
public class BreakBlockGoal extends Goal {
    /** Блоки прочнее этого значения не ломаются (обсидиан = 50). */
    private static final float MAX_HARDNESS = 5.0f;
    /** Базовое время + время на единицу прочности (в тиках). */
    private static final int BASE_TICKS = 30;
    private static final int TICKS_PER_HARDNESS = 40;

    private final Mob mob;
    private BlockPos pos;
    private int progress;
    private int needed;

    public BreakBlockGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.noneOf(Flag.class)); // работает параллельно с ходьбой/атакой
    }

    @Override
    public boolean canUse() {
        if (!mob.horizontalCollision) return false;
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
        BlockPos front = mob.blockPosition().relative(dir);
        if (canBreak(front)) return front;
        if (canBreak(front.above())) return front.above();
        return null;
    }

    private boolean canBreak(BlockPos p) {
        BlockState s = mob.level().getBlockState(p);
        if (s.isAir() || s.getCollisionShape(mob.level(), p).isEmpty()) return false;
        float h = s.getDestroySpeed(mob.level(), p);
        return h >= 0 && h <= MAX_HARDNESS && s.getFluidState().isEmpty();
    }

    @Override
    public void start() {
        progress = 0;
        float h = mob.level().getBlockState(pos).getDestroySpeed(mob.level(), pos);
        needed = (int) (BASE_TICKS + h * TICKS_PER_HARDNESS);
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
