package com.nightsiege.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Если пути к игроку нет (база закрыта стенами), моб идёт напрямую к игроку,
 * упирается в стену, а BreakBlockGoal начинает её ломать.
 * pathing=true: моб сам строит путь (нужно для лошади под всадником).
 */
public class SiegeMoveGoal extends Goal {
    private final Mob mob;
    private final double speed;
    private final boolean pathing;
    private Player target;
    private int repath;

    public SiegeMoveGoal(Mob mob, double speed, boolean pathing) {
        this.mob = mob;
        this.speed = speed;
        this.pathing = pathing;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        target = mob.level().getNearestPlayer(mob, 64);
        return target != null && (pathing || mob.getNavigation().isDone());
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && mob.distanceToSqr(target) > 4.0;
    }

    @Override
    public void tick() {
        if (pathing && --repath <= 0) {
            repath = 10;
            mob.getNavigation().moveTo(target, speed);
        }
        if (mob.getNavigation().isDone()) {
            mob.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
        }
        mob.getLookControl().setLookAt(target);
    }
}
