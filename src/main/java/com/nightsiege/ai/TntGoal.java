package com.nightsiege.ai;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * Зомби с динамитом: бежит к цели, при сближении (или упёршись в стену) зажигает фитиль
 * как крипер (1.5 c) и взрывается, пробивая стены.
 */
public class TntGoal extends Goal {
    private static final int FUSE_TICKS = 30;

    private final Zombie mob;
    private final float radius;
    private int fuse = -1;

    public TntGoal(Zombie mob, float radius) {
        this.mob = mob;
        this.radius = radius;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        return t != null && t.isAlive() && mob.distanceToSqr(t) < 40 * 40;
    }

    @Override
    public void start() {
        fuse = -1;
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        double d = mob.distanceTo(t);

        if (fuse < 0) {
            mob.getNavigation().moveTo(t, 1.25);
            if (mob.getNavigation().isDone()) {
                mob.getMoveControl().setWantedPosition(t.getX(), t.getY(), t.getZ(), 1.25);
            }
            // сблизился с игроком или упёрся в стену -> поджигаем фитиль
            if (d < 3.0 || (mob.horizontalCollision && d < 25)) {
                fuse = 0;
                mob.playSound(SoundEvents.CREEPER_PRIMED, 1.0f, 0.5f);
            }
        } else {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(t);
            if (d > 7 && !mob.horizontalCollision) {
                fuse = -1; // игрок убежал
            } else if (++fuse >= FUSE_TICKS) {
                mob.level().explode(mob, mob.getX(), mob.getY(0.5), mob.getZ(), radius,
                        Level.ExplosionInteraction.MOB);
                mob.discard();
            }
        }
    }
}
