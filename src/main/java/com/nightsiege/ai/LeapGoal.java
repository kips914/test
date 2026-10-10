package com.nightsiege.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Прыгун: делает рывок к цели с 3..9 блоков (перепрыгивает заборы и сокращает дистанцию). */
public class LeapGoal extends Goal {
    private final Mob mob;
    private long next;

    public LeapGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive() || !mob.onGround()) return false;
        long now = mob.level().getGameTime();
        if (now < next) return false;
        double d = mob.distanceToSqr(t);
        return d >= 9 && d <= 81 && mob.getSensing().hasLineOfSight(t);
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        Vec3 dir = new Vec3(t.getX() - mob.getX(), 0, t.getZ() - mob.getZ()).normalize();
        mob.setDeltaMovement(dir.x * 0.95, 0.5, dir.z * 0.95);
        mob.hasImpulse = true;
        next = mob.level().getGameTime() + 50 + mob.getRandom().nextInt(40);
    }
}
