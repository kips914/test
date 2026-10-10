package com.nightsiege;

import com.nightsiege.ai.ArcherGoal;
import com.nightsiege.ai.BreakBlockGoal;
import com.nightsiege.ai.SiegeMoveGoal;
import com.nightsiege.ai.TntGoal;
import com.nightsiege.net.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.ZombieHorse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

public class WaveManager {
    // ===== НАСТРОЙКИ =====
    public static final int START_DAY = 5;          // с какого дня начинаются волны
    public static final int MAX_ALIVE = 35;         // макс. зомби одновременно
    public static final int MAX_TOTAL = 120;        // потолок зомби в одной волне
    private static final long NIGHT_START = 13000L; // начало ночи
    private static final long NIGHT_END = 23000L;   // конец ночи (утро)

    public static final String TAG = "nightsiege_zombie";
    public static final String MOUNT_TAG = "nightsiege_mount";

    private static final Item[][] ARMOR = {
            {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
            {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS},
            {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
            {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}
    };
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static WaveData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WaveData::load, WaveData::new, "nightsiege_waves");
    }

    // ===== ГЛАВНЫЙ ЦИКЛ =====
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        WaveData d = data(level);
        long time = level.getDayTime();
        int day = (int) (time / 24000L) + 1;
        long tod = time % 24000L;
        boolean night = tod >= NIGHT_START && tod < NIGHT_END;
        long gt = level.getGameTime();

        if (!d.active) {
            if (day >= START_DAY && tod >= NIGHT_START && tod < NIGHT_END - 1000 && d.lastDay != day) {
                start(level, d, day, players.size());
            } else if (gt % 100 == 0) {
                sync(level, false, 0, 0, 0);
            }
            return;
        }

        if (!night) { // наступило утро -> волна заканчивается
            end(level, d, false);
            return;
        }
        if (gt % 10 != 0) return;

        // спавн порциями
        List<? extends Zombie> alive = aliveZombies(level);
        if (d.spawned < d.total && alive.size() < MAX_ALIVE) {
            for (int i = 0; i < 3 && d.spawned < d.total; i++) {
                ServerPlayer p = players.get(level.random.nextInt(players.size()));
                if (spawnOne(level, p, d.wave)) {
                    d.spawned++;
                    d.setDirty();
                }
            }
            alive = aliveZombies(level);
        }

        int remaining = (d.total - d.spawned) + alive.size();
        if (d.spawned >= d.total && alive.isEmpty()) {
            end(level, d, true); // убиты все
            return;
        }
        sync(level, true, d.wave, remaining, d.total);
    }

    private static List<? extends Zombie> aliveZombies(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(Zombie.class),
                z -> z.isAlive() && z.getTags().contains(TAG));
    }

    private static void start(ServerLevel level, WaveData d, int day, int playerCount) {
        d.active = true;
        d.lastDay = day;
        d.wave = day - START_DAY + 1;
        int base = Math.min(MAX_TOTAL, 10 + 5 * d.wave);
        d.total = (int) (base * (1 + 0.5 * (playerCount - 1)));
        d.spawned = 0;
        d.setDirty();
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("Волна " + d.wave + " начинается! Защищайте базу!")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        sync(level, true, d.wave, d.total, d.total);
    }

    private static void end(ServerLevel level, WaveData d, boolean victory) {
        d.active = false;
        d.setDirty();
        List<Entity> toRemove = new ArrayList<>();
        for (Entity en : level.getAllEntities()) {
            if (en.getTags().contains(TAG) || en.getTags().contains(MOUNT_TAG)) toRemove.add(en);
        }
        toRemove.forEach(Entity::discard);

        level.getServer().getPlayerList().broadcastSystemMessage(
                victory
                        ? Component.literal("Волна " + d.wave + " отбита!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                        : Component.literal("Рассвет. Волна " + d.wave + " закончилась.").withStyle(ChatFormatting.YELLOW),
                false);
        sync(level, false, 0, 0, 0);
    }

    private static void sync(ServerLevel level, boolean active, int wave, int remaining, int total) {
        Network.CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),
                new Network.SyncPacket(active, wave, remaining, total));
    }

    /** Во время волны спать нельзя (иначе игрок пропустит волну сном). */
    @SubscribeEvent
    public static void onSleep(PlayerSleepInBedEvent e) {
        Player p = e.getEntity();
        if (p.level() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && data(level).active) {
            e.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
            p.displayClientMessage(Component.literal("Нельзя спать во время волны!"), true);
        }
    }

    // ===== СПАВН =====
    private static boolean spawnOne(ServerLevel level, ServerPlayer p, int wave) {
        RandomSource r = level.random;
        for (int i = 0; i < 10; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double dist = 24 + r.nextDouble() * 16;
            int x = Mth.floor(p.getX() + Math.cos(a) * dist);
            int z = Mth.floor(p.getZ() + Math.sin(a) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos below = pos.below();
            if (Math.abs(y - p.getY()) > 24
                    || !level.getFluidState(below).isEmpty()
                    || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
            spawnType(level, pos, wave);
            return true;
        }
        return false;
    }

    private static void spawnType(ServerLevel level, BlockPos pos, int wave) {
        RandomSource r = level.random;
        // шансы растут с номером волны
        double tnt = wave >= 2 ? Math.min(0.20, 0.06 + 0.02 * wave) : 0;
        double archer = wave >= 2 ? Math.min(0.22, 0.08 + 0.02 * wave) : 0;
        double rider = wave >= 3 ? Math.min(0.15, 0.03 + 0.015 * wave) : 0;
        double roll = r.nextDouble();

        Zombie z = EntityType.ZOMBIE.create(level);
        if (z == null) return;
        z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, r.nextFloat() * 360f, 0f);
        prepare(z, wave);

        if (roll < tnt) {
            makeTnt(z, wave);
            level.addFreshEntity(z);
            return;
        }

        boolean isArcher = roll < tnt + archer;
        boolean mounted = !isArcher && roll < tnt + archer + rider;
        if (isArcher) makeArcher(z, wave);
        if (r.nextDouble() < Math.min(0.75, 0.10 + 0.07 * wave)) armor(z, wave, r, isArcher);

        if (mounted) {
            ZombieHorse horse = EntityType.ZOMBIE_HORSE.create(level);
            if (horse != null) {
                horse.moveTo(z.getX(), z.getY(), z.getZ(), z.getYRot(), 0f);
                horse.addTag(MOUNT_TAG);
                horse.setPersistenceRequired();
                horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.33);
                horse.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
                horse.goalSelector.getAvailableGoals().clear(); // убираем вандер лошади
                horse.goalSelector.addGoal(1, new BreakBlockGoal(horse));
                horse.goalSelector.addGoal(2, new SiegeMoveGoal(horse, 1.4, true));
                level.addFreshEntity(horse);
                level.addFreshEntity(z);
                z.startRiding(horse, true);
                return;
            }
        }
        level.addFreshEntity(z);
    }

    /** Общие параметры всех зомби волны + масштабирование по номеру волны. */
    private static void prepare(Zombie z, int wave) {
        z.addTag(TAG);
        z.setPersistenceRequired();
        z.setCanPickUpLoot(false);
        z.setBaby(false);

        double hpMul = Math.min(2.5, 1 + 0.07 * (wave - 1));
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20 * hpMul);
        z.setHealth(z.getMaxHealth());
        z.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Math.min(8, 3 + 0.25 * (wave - 1)));
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(Math.min(0.30, 0.23 + 0.004 * wave));
        z.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
        z.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(0);

        z.goalSelector.addGoal(1, new BreakBlockGoal(z));
        z.goalSelector.addGoal(3, new SiegeMoveGoal(z, 1.0, false));
    }

    private static void makeTnt(Zombie z, int wave) {
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TNT));
        z.setDropChance(EquipmentSlot.HEAD, 0f);
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.27);
        float radius = 2.5f + Math.min(2.0f, wave * 0.15f);
        z.goalSelector.addGoal(1, new TntGoal(z, radius));
    }

    private static void makeArcher(Zombie z, int wave) {
        z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
        z.goalSelector.addGoal(1, new ArcherGoal(z, 2.0 + Math.min(3.0, wave * 0.2)));
    }

    /** Случайная броня: чем больше волна, тем лучше материал (кожа -> золото -> кольчуга -> железо -> алмаз -> незерит). */
    private static void armor(Zombie z, int wave, RandomSource r, boolean skipWeapon) {
        int max = Math.min(5, wave / 2);
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (r.nextFloat() < 0.7f) {
                int tier = Math.min(5, Math.max(0, max - 1) + r.nextInt(2));
                z.setItemSlot(SLOTS[i], new ItemStack(ARMOR[tier][i]));
                z.setDropChance(SLOTS[i], 0.05f);
                any = true;
            }
        }
        if (!any) {
            z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ARMOR[Math.min(5, max)][0]));
            z.setDropChance(EquipmentSlot.HEAD, 0.05f);
        }
        if (!skipWeapon && wave >= 4 && r.nextFloat() < 0.4f) {
            z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(wave >= 6 ? Items.IRON_SWORD : Items.STONE_SWORD));
            z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
        }
    }
}
