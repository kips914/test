package com.nightsiege.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import java.util.Comparator;

import java.util.EnumSet;

/**
 * Если пути к игроку нет (база закрыта стенами), моб идёт напрямую к игроку,
 * упирается в стену, а BreakBlockGoal её ломает.
 * pathing=true: моб сам строит путь (лошадь под всадником).
 * Поиск игрока ограничен раз в 10 тиков (оптимизация).
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
        if ((mob.tickCount + mob.getId()) % 10 != 0) return false;
        // Prefer vulnerable players, while discounting targets that are much farther away.
        AABB area = mob.getBoundingBox().inflate(64.0);
        target = mob.level().getEntitiesOfClass(Player.class, area,
                        p -> p.isAlive() && !p.isSpectator())
                .stream()
                .min(Comparator.comparingDouble(p -> vulnerabilityScore(p)))
                .orElse(null);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && mob.distanceToSqr(target) > 4.0;
    }

    @Override
    public void tick() {
        if (--repath <= 0) {
            repath = 10;
            // Recalculate the route regularly to route around obstacles and react to moving players.
            mob.getNavigation().moveTo(target, speed);
        }
        if (!pathing && mob.getNavigation().isDone()) {
            // Fallback only when no route can be built; BreakBlockGoal handles destructible barriers.
            mob.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
        }
        mob.getLookControl().setLookAt(target);
    }

    private double vulnerabilityScore(Player player) {
        double armor = player.getArmorValue();
        double distancePenalty = Math.sqrt(mob.distanceToSqr(player)) * 0.12;
        return player.getHealth() + armor * 0.65 + distancePenalty;
    }
}
