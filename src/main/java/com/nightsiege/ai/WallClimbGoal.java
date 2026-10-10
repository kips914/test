package com.nightsiege.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/** Allows Night Siege zombies to climb vertical obstacles up to five blocks at a time. */
public class WallClimbGoal extends Goal {
    private final Mob mob;
    private Player target;
    private int climbTicks;
    private double startY;
    private int cooldown;

    public WallClimbGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) cooldown--;
        if (cooldown > 0 || mob.isPassenger() || mob.isInWaterOrBubble()) return false;
        target = mob.level().getNearestPlayer(mob, 48.0);
        if (target == null || !target.isAlive()) return false;
        return mob.horizontalCollision;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && climbTicks < 45
                && mob.getY() - startY < 5.0 && mob.horizontalCollision;
    }

    @Override
    public void start() {
        startY = mob.getY();
        climbTicks = 0;
    }

    @Override
    public void tick() {
        if (target == null) return;
        climbTicks++;
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        double x = dx / len * 0.12;
        double z = dz / len * 0.12;
        // Climb only when a solid face is directly in front; otherwise normal pathfinding resumes.
        BlockPos front = mob.blockPosition().relative(mob.getDirection());
        BlockState state = mob.level().getBlockState(front);
        if (!state.getCollisionShape(mob.level(), front).isEmpty()) {
            mob.setDeltaMovement(mob.getDeltaMovement().x * 0.25 + x,
                    Math.min(0.32, Math.max(0.22, mob.getDeltaMovement().y + 0.08)),
                    mob.getDeltaMovement().z * 0.25 + z);
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        if (mob.getY() - startY >= 4.9 || climbTicks >= 45) stop();
    }

    @Override
    public void stop() {
        target = null;
        cooldown = 12;
        mob.getNavigation().stop();
    }
}
