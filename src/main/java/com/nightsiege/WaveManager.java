package com.nightsiege;

import com.nightsiege.net.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Iterator;

public class WaveManager {
    public static final String TAG = "nightsiege_zombie";
    public static final String MOUNT_TAG = "nightsiege_mount";
    public static final String PYRO_TAG = "nightsiege_pyro";

    private static final long NIGHT_START = 13000L;
    private static final long NIGHT_END = 23000L;

    public enum EndReason { VICTORY, DAWN, STOPPED }

    private static int lastSyncKey = Integer.MIN_VALUE;
    private static final List<TemporaryBlock> TEMP_BLOCKS = new ArrayList<>();
    private record TemporaryBlock(ServerLevel level, BlockPos pos, BlockState original, long expiresAt) {}

    public static WaveData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WaveData::load, WaveData::new, "nightsiege_waves");
    }

    /** Живые игроки в выживании/приключении (спектаторы не считаются). */
    public static List<ServerPlayer> targets(ServerLevel level) {
        List<ServerPlayer> l = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isAlive() && !p.isSpectator()) l.add(p);
        }
        return l;
    }

    // ===================== ГЛАВНЫЙ ЦИКЛ =====================

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)) return;
        restoreExpiredBlocks(level);
        if (level.dimension() != Level.OVERWORLD || level.players().isEmpty()) return;

        WaveData d = data(level);
        long time = level.getDayTime();
        int day = (int) (time / 24000L) + 1;
        long tod = time % 24000L;
        boolean night = tod >= NIGHT_START && tod < NIGHT_END;
        long gt = level.getGameTime();
        int startDay = SiegeConfig.START_DAY.get();

        if (!d.active) {
            List<ServerPlayer> t = targets(level);
            if (day >= startDay && tod >= NIGHT_START && tod < NIGHT_END - 1000
                    && d.lastDay != day && !t.isEmpty()) {
                if (d.cancelNextWave) {
                    d.cancelNextWave = false; d.lastDay = day; d.setDirty();
                    level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("Зелье спасения отменило нападение этой ночи!").withStyle(ChatFormatting.GREEN), false);
                    return;
                }
                start(level, d, day, t.size(), day - startDay + 1);
            } else {
                dailyWarning(level, d, day, tod, startDay);
                if (gt % 100 == 0) sync(level, 0, 0, 0, 0, true);
            }
            return;
        }

        if (!night && !d.anytimeWave) { // наступило утро
            end(level, d, EndReason.DAWN);
            return;
        }
        if (gt % 20 == 0) { tickBossAbilities(level, gt); tickMageAbilities(level, gt); tickShieldKnockback(level, gt); }
        if (gt % 10 != 0) return;

        List<ServerPlayer> t = targets(level);
        if (t.isEmpty()) return; // все мертвы/спектаторы -> пауза

        List<? extends Zombie> alive = aliveZombies(level);

        // постепенный спавн: порция каждые spawnIntervalTicks
        int interval = Math.max(10, SiegeConfig.SPAWN_INTERVAL.get() / 10 * 10);
        if (d.spawned < d.total && gt % interval == 0) {
            if (spawnBatch(level, d, t, alive)) {
                d.setDirty();
                alive = aliveZombies(level);
            }
        }

        // оптимизация: зомби, ушедшие далеко от всех игроков, переносятся поближе
        if (gt % 100 == 0) relocateFar(level, alive, t);

        int remaining = (d.total - d.spawned) + alive.size();
        if (d.spawned >= d.total && alive.isEmpty()) {
            end(level, d, EndReason.VICTORY);
            return;
        }
        sync(level, 1, d.wave, remaining, d.total, gt % 100 == 0);
    }

    private static List<? extends Zombie> aliveZombies(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(Zombie.class),
                z -> z.isAlive() && z.getTags().contains(TAG));
    }

    /** Каждому игроку по порции зомби, но не больше maxAlivePerPlayer рядом с ним. */
    private static boolean spawnBatch(ServerLevel level, WaveData d, List<ServerPlayer> targets,
                                      List<? extends Zombie> alive) {
        int batch = Math.min(3 + d.potionLevel, 1 + d.wave / 6 + d.potionLevel);
        int cap = SiegeConfig.MAX_ALIVE_PER_PLAYER.get();
        if (d.bossPending) {
            for (ServerPlayer p : targets) {
                if (SiegeSpawner.spawnBoss(level, p, d.wave)) {
                    d.bossPending = false;
                    d.spawned++;
                    d.setDirty();
                    return true;
                }
            }
            return false;
        }
        double near = SiegeConfig.MAX_SPAWN_DIST.get() + 40.0;
        double near2 = near * near;
        boolean any = false;

        for (ServerPlayer p : targets) {
            int around = 0;
            for (Zombie z : alive) {
                if (z.distanceToSqr(p) < near2) around++;
            }
            for (int i = 0; i < batch && d.spawned < d.total && around < cap; i++) {
                if (SiegeSpawner.spawnOne(level, p, d.wave + d.potionLevel * 3)) {
                    d.spawned++;
                    around++;
                    any = true;
                }
            }
        }
        return any;
    }

    private static void relocateFar(ServerLevel level, List<? extends Zombie> alive, List<ServerPlayer> targets) {
        for (Zombie z : alive) {
            if (z.isPassenger() || z.isVehicle()) continue;
            ServerPlayer nearest = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayer p : targets) {
                double dd = z.distanceToSqr(p);
                if (dd < best) {
                    best = dd;
                    nearest = p;
                }
            }
            if (nearest != null && best > 100.0 * 100.0) {
                BlockPos pos = SiegeSpawner.findPos(level, nearest, true);
                if (pos != null) {
                    z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    z.getNavigation().stop();
                }
            }
        }
    }

    /** Способности боссов имеют отдельные перезарядки и не срабатывают каждый тик. */
    private static void tickBossAbilities(ServerLevel level, long gameTime) {
        List<? extends Zombie> bosses = level.getEntities(EntityTypeTest.forClass(Zombie.class),
                z -> z.isAlive() && z.getTags().contains("nightsiege_boss"));
        for (Zombie boss : bosses) {
            int tier = 1;
            for (int i = 1; i <= 5; i++) if (boss.getTags().contains("nightsiege_boss_tier_" + i)) tier = i;
            String key = "NightSiegeBossAbilityTime";
            long last = boss.getPersistentData().getLong(key);
            long cooldown = tier == 3 ? 300 : tier == 4 ? 100 : 160;
            if (gameTime - last < cooldown) continue;
            boss.getPersistentData().putLong(key, gameTime);
            for (ServerPlayer player : level.players()) {
                if (!player.isAlive() || player.isSpectator() || boss.distanceToSqr(player) > 12 * 12) continue;
                switch (tier) {
                    case 1 -> { // Разрушитель: ударная волна и временные магматические ловушки
                        Vec3 away = player.position().subtract(boss.position()).normalize();
                        player.push(away.x * 1.8, 0.65, away.z * 1.8);
                        player.hurt(level.damageSources().mobAttack(boss), 5.0F);
                        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 1));
                        placeTemporaryPatch(level, player.blockPosition(), Blocks.MAGMA_BLOCK, gameTime, 4);
                    }
                    case 2 -> { // Чумной властелин: яд, слабость и чумная аура
                        player.addEffect(new MobEffectInstance(MobEffects.POISON, 140, 0));
                        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 0));
                        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 70, 0));
                        placeTemporaryPatch(level, player.blockPosition(), Blocks.COBWEB, gameTime, 3);
                    }
                    case 3 -> { // Призыватель: создаёт прислужников и замедляющие руны
                        placeTemporaryPatch(level, player.blockPosition(), Blocks.SOUL_SAND, gameTime, 3);
                        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0));
                        for (int i = 0; i < 2; i++) {
                            Zombie minion = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
                            if (minion == null) continue;
                            minion.moveTo(boss.getX() + (i == 0 ? 1.5 : -1.5), boss.getY(), boss.getZ() + 1.0, level.random.nextFloat() * 360, 0);
                            minion.addTag(TAG);
                            minion.addTag("nightsiege_boss_minion");
                            minion.setPersistenceRequired();
                            minion.getAttribute(Attributes.MAX_HEALTH).setBaseValue(30.0);
                            minion.setHealth(minion.getMaxHealth());
                            minion.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(5.0);
                            minion.setCustomName(Component.literal("Прислужник босса").withStyle(ChatFormatting.DARK_PURPLE));
                            minion.setCustomNameVisible(false);
                            level.addFreshEntity(minion);
                        }
                    }
                    case 4 -> { // Теневой охотник: рывок, слепота и скользкая ледяная зона
                        Vec3 toward = player.position().subtract(boss.position()).normalize();
                        boss.setDeltaMovement(toward.x * 1.65, 0.45, toward.z * 1.65);
                        boss.hurtMarked = true;
                        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 70, 1));
                        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 35, 0));
                        placeTemporaryPatch(level, player.blockPosition(), Blocks.BLUE_ICE, gameTime, 3);
                    }
                    default -> { // Инфернальный король: огненная аура и временные магматические блоки
                        player.setSecondsOnFire(4);
                        player.hurt(level.damageSources().onFire(), 3.0F);
                        player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80, 0));
                        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0));
                        placeTemporaryPatch(level, player.blockPosition(), Blocks.MAGMA_BLOCK, gameTime, 3);
                    }
                }
            }
        }
    }

    /** Создаёт небольшую временную зону из тематических блоков. Меняются только заменяемые блоки,
     * исходное состояние восстанавливается через 4 секунды, чтобы не портить постройки игроков. */
    private static void placeTemporaryPatch(ServerLevel level, BlockPos center, Block block, long gameTime, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > radius + 1) continue;
                if (dx == 0 && dz == 0) continue;
                BlockPos pos = center.offset(dx, 0, dz);
                BlockState old = level.getBlockState(pos);
                if (!old.isAir() && !old.canBeReplaced()) continue;
                boolean alreadyTracked = TEMP_BLOCKS.stream().anyMatch(t -> t.level == level && t.pos.equals(pos));
                if (alreadyTracked) continue;
                TEMP_BLOCKS.add(new TemporaryBlock(level, pos.immutable(), old, gameTime + 80));
                level.setBlock(pos, block.defaultBlockState(), 3);
            }
        }
    }

    private static void tickMageAbilities(ServerLevel level, long gameTime) {
        List<? extends Zombie> mages = level.getEntities(EntityTypeTest.forClass(Zombie.class), z -> z.isAlive() && z.getTags().contains("nightsiege_mage"));
        for (Zombie mage : mages) {
            ServerPlayer target = null; double best = 18 * 18;
            for (ServerPlayer p : level.players()) {
                if (!p.isAlive() || p.isSpectator()) continue;
                double dist = mage.distanceToSqr(p);
                if (dist < best) { best = dist; target = p; }
            }
            if (target == null) continue;
            long hypnosisAt = mage.getPersistentData().getLong("NSHypnosisAt");
            if (best <= 10 * 10 && gameTime - hypnosisAt >= 6000) {
                mage.getPersistentData().putLong("NSHypnosisAt", gameTime);
                target.getPersistentData().putLong("NSHypnotizedUntil", gameTime + 40);
                target.displayClientMessage(Component.literal("✦ Зомби-маг загипнотизировал вас!").withStyle(ChatFormatting.LIGHT_PURPLE), true);
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL, target.getX(), target.getY() + 1, target.getZ(), 25, .4, .6, .4, .2);
            } else {
                long shotAt = mage.getPersistentData().getLong("NSMageShotAt");
                if (gameTime - shotAt >= 40 && best <= 18 * 18) {
                    mage.getPersistentData().putLong("NSMageShotAt", gameTime);
                    Vec3 aim = target.getEyePosition().subtract(mage.getEyePosition()).normalize();
                    SmallFireball orb = new SmallFireball(level, mage, aim.x, aim.y, aim.z);
                    orb.setPos(mage.getX(), mage.getEyeY() - 0.1, mage.getZ());
                    level.addFreshEntity(orb);
                }
            }
        }
    }

    /** Щитоносец не наносит урон: только отталкивает подошедших игроков спереди. */
    private static void tickShieldKnockback(ServerLevel level, long gameTime) {
        List<? extends Zombie> shields = level.getEntities(EntityTypeTest.forClass(Zombie.class), z -> z.isAlive() && z.getTags().contains("nightsiege_shield"));
        for (Zombie shield : shields) {
            long last = shield.getPersistentData().getLong("NSShieldPushAt");
            if (gameTime - last < 40) continue;
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(shield) > 3.2 * 3.2) continue;
                Vec3 away = player.position().subtract(shield.position()).normalize();
                if (away.lengthSqr() < 0.001) continue;
                player.push(away.x * 1.25, 0.28, away.z * 1.25);
                player.hurtMarked = true;
                shield.getLookControl().setLookAt(player, 30f, 30f);
                shield.getPersistentData().putLong("NSShieldPushAt", gameTime);
                break;
            }
        }
    }

    @SubscribeEvent
    public static void onShieldZombieHurt(LivingHurtEvent e) {
        if (!(e.getEntity() instanceof Zombie shield) || !shield.getTags().contains("nightsiege_shield")) return;
        if (!(e.getSource().getEntity() instanceof Player attacker)) return;
        Vec3 towardAttacker = attacker.position().subtract(shield.position()).normalize();
        Vec3 facing = shield.getLookAngle().normalize();
        // Блокирует удары спереди; сзади получает полный урон.
        if (facing.dot(towardAttacker) > 0.25) e.setAmount(e.getAmount() * 0.08f);
    }

    /** Called by the client keybind and the optional whistle item. */
    public static void useWhistle(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        long ready = player.getPersistentData().getLong("NSWhistleReadyAt");
        if (now < ready) {
            player.displayClientMessage(Component.literal("Свисток перезаряжается: " + ((ready - now + 19) / 20) + " сек.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int called = 0;
        for (Zombie z : level.getEntitiesOfClass(Zombie.class, player.getBoundingBox().inflate(48),
                z -> z.isAlive() && z.getTags().contains(TAG))) {
            z.setTarget(player);
            z.getNavigation().moveTo(player, z.getTags().contains("nightsiege_runner") ? 1.6 : 1.25);
            called++;
        }
        player.getPersistentData().putLong("NSWhistleReadyAt", now + 200);
        level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.4f, 0.65f);
                net.minecraft.sounds.SoundSource.PLAYERS, 1.4f, 0.65f);
        player.displayClientMessage(Component.literal("Свисток привлёк зомби: " + called).withStyle(ChatFormatting.AQUA), true);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer player)) return;
        // Added mechanic: every 5 seconds, warn the player if a siege group is nearby.
        long pulseAt = player.getPersistentData().getLong("NSThreatPulseAt");
        long now = player.level().getGameTime();
        if (now >= pulseAt) {
            player.getPersistentData().putLong("NSThreatPulseAt", now + 100);
            int nearby = player.serverLevel().getEntitiesOfClass(Zombie.class, player.getBoundingBox().inflate(24),
                    z -> z.isAlive() && z.getTags().contains(TAG)).size();
            if (nearby > 0) player.displayClientMessage(Component.literal("⚠ Угроза рядом: " + nearby + " зомби в радиусе 24 блоков")
                    .withStyle(ChatFormatting.RED), true);
        }
        long until = player.getPersistentData().getLong("NSHypnotizedUntil");
        if (until <= player.level().getGameTime()) return;
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
        player.setDeltaMovement(flat.x * 0.24, player.getDeltaMovement().y, flat.z * 0.24);
        player.hurtMarked = true;
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 3, 0, true, false, true));
    }

    @SubscribeEvent
    public static void onSiegeDrops(LivingDropsEvent e) {
        if (!(e.getEntity() instanceof Zombie z) || !z.getTags().contains(TAG) || !(z.level() instanceof ServerLevel level)) return;
        // Очень редкий дроп зелья спасения: около 0,35% с обычного зомби нападения.
        if (!z.getTags().contains("nightsiege_mage") && level.random.nextFloat() < 0.0035f) {
            e.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(level, z.getX(), z.getY(), z.getZ(), new ItemStack(SiegeItems.SALVATION.get())));
        }
        WaveData d = data(level);
        if (d.active && d.anytimeWave && d.potionLevel > 0) {
            int count = d.potionLevel;
            e.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(level, z.getX(), z.getY(), z.getZ(), new ItemStack(Items.EMERALD, count)));
            if (d.potionLevel >= 2 && level.random.nextFloat() < 0.30f) e.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(level, z.getX(), z.getY(), z.getZ(), new ItemStack(Items.GOLD_INGOT, 1 + d.potionLevel - 2)));
            if (d.potionLevel >= 3 && level.random.nextFloat() < 0.12f) e.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(level, z.getX(), z.getY(), z.getZ(), new ItemStack(Items.DIAMOND)));
        }
    }

    private static void restoreExpiredBlocks(ServerLevel level) {
        long now = level.getGameTime();
        Iterator<TemporaryBlock> it = TEMP_BLOCKS.iterator();
        while (it.hasNext()) {
            TemporaryBlock t = it.next();
            if (t.level != level || now < t.expiresAt) continue;
            // Не затираем блок, если игрок или другой процесс уже заменил нашу ловушку.
            if (level.getBlockState(t.pos).getBlock() == Blocks.MAGMA_BLOCK
                    || level.getBlockState(t.pos).getBlock() == Blocks.COBWEB
                    || level.getBlockState(t.pos).getBlock() == Blocks.SOUL_SAND
                    || level.getBlockState(t.pos).getBlock() == Blocks.BLUE_ICE) {
                level.setBlock(t.pos, t.original, 3);
            }
            it.remove();
        }
    }

    // ===================== СТАРТ / КОНЕЦ =====================

    private static void start(ServerLevel level, WaveData d, int day, int playerCount, int wave) {
        d.active = true;
        d.anytimeWave = false;
        d.potionLevel = 0;
        d.lastDay = day;
        d.wave = wave;
        int base = Math.min(SiegeConfig.MAX_TOTAL.get(), 10 + 5 * wave);
        double mp = 1 + SiegeConfig.MP_SCALING.get() * (playerCount - 1);
        boolean bossNight = day >= 10 && day % 5 == 0;
        d.bossPending = bossNight;
        d.total = (int) Math.max(5, Math.round(base * SiegeConfig.count() * mp)) + (bossNight ? 1 : 0);
        d.spawned = 0;
        d.setDirty();

        Component title = Component.literal(bossNight ? "БОСС-ВОЛНА " + wave : "ВОЛНА " + wave)
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        Component sub = Component.literal(bossNight ? "Ночи 10, 15, 20 и далее — босс зомби!" : "Зомби идут! Защищайте базу")
                .withStyle(ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) title(p, title, sub);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal((bossNight ? "БОСС-ВОЛНА " : "Волна ") + wave + " начинается! Зомби: " + d.total)
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        sync(level, 1, wave, d.total, d.total, true);
    }

    public static boolean useSalvation(ServerLevel level, ServerPlayer player) {
        WaveData d = data(level);
        if (d.active) return false;
        d.cancelNextWave = true;
        d.setDirty();
        player.displayClientMessage(Component.literal("Зелье спасения активировано: ближайшая автоматическая волна отменена.").withStyle(ChatFormatting.GREEN), false);
        return true;
    }

    public static boolean startPotionWave(ServerLevel level, ServerPlayer player, int potionLevel) {
        WaveData d = data(level);
        if (d.active || targets(level).isEmpty()) return false;
        int day = (int)(level.getDayTime() / 24000L) + 1;
        int wave = Math.max(1, day - SiegeConfig.START_DAY.get() + 1) + potionLevel * 2;
        start(level, d, day, targets(level).size(), wave);
        d.anytimeWave = true;
        d.potionLevel = potionLevel;
        d.bossPending = false;
        d.total = Math.min(SiegeConfig.MAX_TOTAL.get(), d.total + potionLevel * 8);
        d.setDirty();
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("ЗЕЛЬЕ НАПАДЕНИЯ " + potionLevel + "! Зомби усилены, добыча улучшена.").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        return true;
    }

    /** Принудительный запуск (команда /siege start). wave <= 0 -> по текущему дню. */
    public static boolean forceStart(ServerLevel level, int wave) {
        List<ServerPlayer> t = targets(level);
        if (t.isEmpty()) return false;
        WaveData d = data(level);
        if (d.active) end(level, d, EndReason.STOPPED);

        long time = level.getDayTime();
        int day = (int) (time / 24000L) + 1;
        long tod = time % 24000L;
        if (!(tod >= NIGHT_START && tod < NIGHT_END - 1000)) {
            level.setDayTime((day - 1) * 24000L + NIGHT_START + 500);
        }
        int w = wave > 0 ? wave : Math.max(1, day - SiegeConfig.START_DAY.get() + 1);
        start(level, d, day, t.size(), w);
        return true;
    }

    public static void stop(ServerLevel level) {
        WaveData d = data(level);
        if (d.active) end(level, d, EndReason.STOPPED);
    }

    private static void end(ServerLevel level, WaveData d, EndReason reason) {
        d.active = false;
        d.anytimeWave = false;
        d.potionLevel = 0;
        d.setDirty();

        List<Entity> remove = new ArrayList<>();
        for (Entity en : level.getAllEntities()) {
            if (en.getTags().contains(TAG) || en.getTags().contains(MOUNT_TAG)) remove.add(en);
        }
        remove.forEach(Entity::discard);

        Component msg = switch (reason) {
            case VICTORY -> Component.literal("Волна " + d.wave + " отбита!")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
            case DAWN -> Component.literal("Рассвет. Волна " + d.wave + " пережита.")
                    .withStyle(ChatFormatting.YELLOW);
            case STOPPED -> Component.literal("Волна остановлена.").withStyle(ChatFormatting.GRAY);
        };
        level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        int state = reason == EndReason.VICTORY ? 2 : (reason == EndReason.DAWN ? 3 : 0);
        sync(level, state, d.wave, 0, d.total, true);
    }

    private static void sync(ServerLevel level, int state, int wave, int remaining, int total, boolean force) {
        int key = Objects.hash(state, wave, remaining, total);
        if (!force && key == lastSyncKey) return; // не шлём одинаковые пакеты
        lastSyncKey = key;
        Network.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),
                new Network.SyncPacket(state, wave, remaining, total));
    }

    // ===================== ОБЪЯВЛЕНИЯ =====================

    static void title(ServerPlayer p, Component title, Component sub) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    static String days(int n) {
        int m = n % 100, k = n % 10;
        String w = (m >= 11 && m <= 14) ? "дней" : (k == 1 ? "день" : (k >= 2 && k <= 4 ? "дня" : "дней"));
        return n + " " + w;
    }

    /** Напоминание в начале каждого дня до первой волны. */
    private static void dailyWarning(ServerLevel level, WaveData d, int day, long tod, int startDay) {
        if (!SiegeConfig.ANNOUNCE.get()) return;
        if (day >= 2 && day <= startDay && tod >= 60 && tod < 12000 && d.lastWarnDay != day) {
            d.lastWarnDay = day;
            d.setDirty();
            int n = startDay - day + 1;
            Component msg = n == 1
                    ? Component.literal("Сегодня ночью нападёт первая волна зомби! Готовьте оборону!")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                    : Component.literal("До нападения зомби: " + days(n) + ".").withStyle(ChatFormatting.GOLD);
            level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    /** Надпись при создании мира (первый вход) и напоминание остальным игрокам. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !SiegeConfig.ANNOUNCE.get()) return;
        ServerLevel level = sp.server.overworld();
        WaveData d = data(level);
        int startDay = SiegeConfig.START_DAY.get();
        int day = (int) (level.getDayTime() / 24000L) + 1;
        if (day >= startDay) return;

        int n = startDay - day + 1;
        if (!d.announced) {
            d.announced = true;
            d.lastWarnDay = day;
            d.setDirty();
            title(sp,
                    Component.literal("Скоро нападение!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    Component.literal("Через " + days(n) + " начнутся ночные волны зомби").withStyle(ChatFormatting.GOLD));
        }
        sp.sendSystemMessage(Component.literal("Скоро нападение! Через " + days(n)
                + " начнутся ночные волны зомби. Стройте базу!").withStyle(ChatFormatting.RED));
    }

    // ===================== ПРОЧЕЕ =====================

    /** Во время волны спать нельзя (настраивается). */
    @SubscribeEvent
    public static void onSleep(PlayerSleepInBedEvent e) {
        Player p = e.getEntity();
        if (SiegeConfig.ALLOW_SLEEP.get()) return;
        if (p.level() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && data(level).active) {
            e.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
            p.displayClientMessage(Component.literal("Нельзя спать во время волны!"), true);
        }
    }

    /** Зомби-поджигатель поджигает жертву. */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent e) {
        if (e.getSource().getEntity() instanceof Zombie z && z.getTags().contains(PYRO_TAG)) {
            e.getEntity().setSecondsOnFire(5);
        }
    }
}
