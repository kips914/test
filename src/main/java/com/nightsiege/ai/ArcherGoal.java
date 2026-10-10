package com.nightsiege.ai;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;

import java.util.EnumSet;

/** Лучник держит дистанцию около 20 блоков и стреляет при прямой видимости. */
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
        return d <= 28 * 28 && mob.getSensing().hasLineOfSight(t);
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
        double distance = mob.distanceToSqr(t);
        mob.getLookControl().setLookAt(t, 30f, 30f);
        if (distance < 16 * 16) {
            double dx = mob.getX() - t.getX();
            double dz = mob.getZ() - t.getZ();
            double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
            mob.getNavigation().moveTo(mob.getX() + dx / len * 5.0, mob.getY(), mob.getZ() + dz / len * 5.0, 1.15);
        } else if (distance > 22 * 22) {
            mob.getNavigation().moveTo(t, 1.0);
        } else {
            mob.getNavigation().stop();
        }
        if (distance >= 16 * 16 && distance <= 23 * 23 && --cooldown <= 0) {
            shoot(t);
            cooldown = 30;
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
