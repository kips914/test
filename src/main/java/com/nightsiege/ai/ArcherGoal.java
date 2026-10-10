package com.nightsiege.ai;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;

import java.util.EnumSet;

/** Зомби-лучник: стреляет с 6..18 блоков при прямой видимости, вблизи дерётся как обычный зомби. */
public class ArcherGoal extends Goal {
    private final Zombie mob;
    private final double damage;
    private int cooldown;

    public ArcherGoal(Zombie mob, double damage) {
        this.mob = mob;
        this.damage = damage;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive()) return false;
        double d = mob.distanceToSqr(t);
        return d >= 36 && d <= 18 * 18 && mob.getSensing().hasLineOfSight(t);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        cooldown = 20;
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(t, 30f, 30f);
        if (--cooldown <= 0) {
            shoot(t);
            cooldown = 40;
        }
    }

    private void shoot(LivingEntity t) {
        Arrow arrow = new Arrow(mob.level(), mob);
        arrow.setBaseDamage(damage);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        double dx = t.getX() - mob.getX();
        double dy = t.getY(0.3333333333333333) - arrow.getY();
        double dz = t.getZ() - mob.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + h * 0.2, dz, 1.6f, 6.0f);
        mob.playSound(SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f / (mob.getRandom().nextFloat() * 0.4f + 0.8f));
        mob.level().addFreshEntity(arrow);
    }
}
