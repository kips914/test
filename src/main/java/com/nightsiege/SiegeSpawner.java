package com.nightsiege;

import com.nightsiege.ai.ArcherGoal;
import com.nightsiege.ai.BreakBlockGoal;
import com.nightsiege.ai.LeapGoal;
import com.nightsiege.ai.SiegeMoveGoal;
import com.nightsiege.ai.TntGoal;
import com.nightsiege.ai.WallClimbGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.ZombieHorse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;

/** Поиск места спавна и создание всех видов зомби. */
public class SiegeSpawner {

    public enum Kind { NORMAL, TNT, ARCHER, RIDER, RUNNER, DIGGER, HUSK, LEAPER, TANK, PYRO, MAGE, SHIELD, CRAWLER }

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

    private static double angleCursor = 0;
    private static int failStreak = 0;

    // ================== ПОЗИЦИЯ ==================

    /** Спавнит одного зомби вокруг игрока. false = подходящее место не найдено. */
    public static boolean spawnOne(ServerLevel level, ServerPlayer p, int wave) {
        BlockPos pos = findPos(level, p, failStreak >= 5);
        if (pos == null) {
            failStreak++;
            return false;
        }
        failStreak = 0;
        spawnKind(level, pos, pick(wave, level.random), wave);
        return true;
    }

    /** Создаёт одного из пяти боссов; типы чередуются по каждой пятой ночи. */
    public static boolean spawnBoss(ServerLevel level, ServerPlayer p, int wave) {
        BlockPos pos = findPos(level, p, true);
        if (pos == null) return false;
        Zombie boss = EntityType.ZOMBIE.create(level);
        if (boss == null) return false;
        int tier = Math.floorMod((Math.max(10, wave) / 5) - 2, 5) + 1;
        boss.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.random.nextFloat() * 360f, 0f);
        prepare(boss, wave, 2.0);
        boss.addTag("nightsiege_boss");
        boss.addTag("nightsiege_boss_tier_" + tier);
        String[] names = {"Разрушитель", "Чумной властелин", "Призыватель", "Теневой охотник", "Инфернальный король"};
        boss.setCustomName(Component.literal("☠ БОСС " + tier + "/5: " + names[tier - 1] + " ☠")
                .withStyle(net.minecraft.ChatFormatting.DARK_RED, net.minecraft.ChatFormatting.BOLD));
        boss.setCustomNameVisible(true);
        boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue((250.0 + tier * 80.0) + wave * 8.0);
        boss.setHealth(boss.getMaxHealth());
        boss.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue((9.0 + tier * 2.5 + wave * 0.25) * SiegeConfig.damage());
        boss.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue((tier == 4 ? 0.36 : 0.25 + tier * 0.012) * SiegeConfig.speed());
        boss.getAttribute(Attributes.ARMOR).setBaseValue(12.0 + tier * 3.0);
        boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0.65 + tier * 0.07);
        boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false));

        // У каждого босса свой визуальный образ и начальные эффекты.
        Item[] bossArmor = ARMOR[tier == 1 ? 5 : tier == 2 ? 2 : tier == 3 ? 3 : tier == 4 ? 4 : 5];
        for (int i = 0; i < 4; i++) {
            ItemStack armor = new ItemStack(bossArmor[i]);
            armor.enchant(Enchantments.ALL_DAMAGE_PROTECTION, Math.min(4, tier));
            armor.enchant(Enchantments.UNBREAKING, 3);
            boss.setItemSlot(SLOTS[i], armor);
            boss.setDropChance(SLOTS[i], 0.0f);
        }
        switch (tier) {
            case 1 -> { boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.ANVIL)); boss.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE)); boss.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 0, false, false)); }
            case 2 -> { boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.WITHER_SKELETON_SKULL)); boss.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD)); boss.addEffect(new MobEffectInstance(MobEffects.REGENERATION, Integer.MAX_VALUE, 0, false, false)); }
            case 3 -> { boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN)); boss.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TOTEM_OF_UNDYING)); boss.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 1, false, false)); }
            case 4 -> { boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET)); boss.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.ENDER_EYE)); boss.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, Integer.MAX_VALUE, 1, false, false)); }
            default -> { boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.JACK_O_LANTERN)); boss.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BLAZE_ROD)); boss.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false)); boss.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, Integer.MAX_VALUE, 1, false, false)); }
        }
        boss.setDropChance(EquipmentSlot.HEAD, 0.0f);
        boss.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
        level.addFreshEntity(boss);
        String[] abilities = {"ударная волна", "чумная аура", "призыв миньонов", "рывок охотника", "огненная аура"};
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("☠ БОСС " + tier + "/5: " + names[tier - 1] + "! Способность: " + abilities[tier - 1])
                        .withStyle(net.minecraft.ChatFormatting.DARK_RED, net.minecraft.ChatFormatting.BOLD), false);
        return true;
    }

    /**
     * Ищет точку по кругу вокруг игрока (угол каждый раз сдвигается => равномерно со всех сторон),
     * на любой высоте рядом с игроком (поверхность, пещеры, подвалы), по возможности в тёмном месте.
     */
    public static BlockPos findPos(ServerLevel level, ServerPlayer p, boolean relaxDark) {
        RandomSource r = level.random;
        int min = SiegeConfig.MIN_SPAWN_DIST.get();
        int max = Math.max(min + 4, SiegeConfig.MAX_SPAWN_DIST.get());
        boolean dark = SiegeConfig.REQUIRE_DARK.get() && !relaxDark;
        int light = SiegeConfig.MAX_LIGHT.get();
        int py = p.blockPosition().getY();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int t = 0; t < 14; t++) {
            angleCursor += 2.399963 + (r.nextDouble() - 0.5) * 0.6; // золотой угол
            double dist = min + r.nextDouble() * (max - min);
            int x = Mth.floor(p.getX() + Math.cos(angleCursor) * dist);
            int z = Mth.floor(p.getZ() + Math.sin(angleCursor) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;

            // ищем пол рядом по высоте с игроком: 0, +1, -1, +2, -2 ... до +-8
            for (int k = 0; k <= 16; k++) {
                int off = ((k + 1) / 2) * ((k & 1) == 1 ? 1 : -1);
                m.set(x, py + off, z);
                if (isGoodSpot(level, m) && (!dark || level.getMaxLocalRawBrightness(m) <= light)) {
                    return m.immutable();
                }
            }
            // запасной вариант: поверхность
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(sy - py) <= 24) {
                m.set(x, sy, z);
                if (isGoodSpot(level, m) && (!dark || level.getMaxLocalRawBrightness(m) <= light)) {
                    return m.immutable();
                }
            }
        }
        return null;
    }

    private static boolean isGoodSpot(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockPos above = pos.above();
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(above);
        BlockState floor = level.getBlockState(below);
        return feet.getCollisionShape(level, pos).isEmpty() && feet.getFluidState().isEmpty()
                && head.getCollisionShape(level, above).isEmpty() && head.getFluidState().isEmpty()
                && floor.isFaceSturdy(level, below, Direction.UP)
                && !floor.is(BlockTags.LEAVES) && floor.getFluidState().isEmpty();
    }

    // ================== ВЫБОР ВИДА ==================

    public static Kind pick(int wave, RandomSource r) {
        double[] w = new double[Kind.values().length];
        w[Kind.TNT.ordinal()] = wave >= 2 ? Math.min(0.20, 0.06 + 0.02 * wave) : 0;
        w[Kind.ARCHER.ordinal()] = wave >= 2 ? Math.min(0.22, 0.08 + 0.02 * wave) : 0;
        w[Kind.RUNNER.ordinal()] = wave >= 2 ? Math.min(0.18, 0.08 + 0.01 * wave) : 0;
        w[Kind.RIDER.ordinal()] = wave >= 3 ? Math.min(0.15, 0.03 + 0.015 * wave) : 0;
        w[Kind.DIGGER.ordinal()] = wave >= 3 ? Math.min(0.12, 0.05 + 0.01 * wave) : 0;
        w[Kind.HUSK.ordinal()] = wave >= 3 ? Math.min(0.15, 0.06 + 0.01 * wave) : 0;
        w[Kind.LEAPER.ordinal()] = wave >= 4 ? Math.min(0.12, 0.05 + 0.01 * wave) : 0;
        w[Kind.TANK.ordinal()] = wave >= 4 ? Math.min(0.10, 0.03 + 0.01 * wave) : 0;
        w[Kind.PYRO.ordinal()] = wave >= 5 ? Math.min(0.10, 0.03 + 0.01 * wave) : 0;
        w[Kind.MAGE.ordinal()] = wave >= 3 ? Math.min(0.09, 0.025 + 0.008 * wave) : 0;
        w[Kind.SHIELD.ordinal()] = wave >= 3 ? Math.min(0.12, 0.035 + 0.01 * wave) : 0;
        w[Kind.CRAWLER.ordinal()] = wave >= 2 ? Math.min(0.16, 0.04 + 0.012 * wave) : 0;

        double sum = 0;
        for (double v : w) sum += v;
        if (sum <= 0) return Kind.NORMAL;
        double f = Math.min(SiegeConfig.special(), 0.85 / sum); // особые не более 85% волны
        double roll = r.nextDouble();
        double acc = 0;
        for (Kind k : Kind.values()) {
            acc += w[k.ordinal()] * f;
            if (roll < acc) return k;
        }
        return Kind.NORMAL;
    }

    // ================== СОЗДАНИЕ ==================

    private static void spawnKind(ServerLevel level, BlockPos pos, Kind kind, int wave) {
        RandomSource r = level.random;
        Zombie z = (kind == Kind.HUSK ? EntityType.HUSK : EntityType.ZOMBIE).create(level);
        if (z == null) return;
        z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, r.nextFloat() * 360f, 0f);
        prepare(z, wave, kind == Kind.DIGGER ? 2.5 : 1.0);

        boolean canWearArmor = false;
        boolean fullArmor = false;
        boolean mounted = false;

        switch (kind) {
            case TNT -> {
                z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TNT));
                z.setDropChance(EquipmentSlot.HEAD, 0f);
                setSpeed(z, 0.27);
                z.goalSelector.addGoal(1, new TntGoal(z, 2.5f + Math.min(2.0f, wave * 0.15f)));
                z.setCustomName(Component.literal("Зомби-подрывник"));
            }
            case ARCHER -> {
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
                z.goalSelector.addGoal(1, new ArcherGoal(z, (2.0 + Math.min(3.0, wave * 0.2)) * SiegeConfig.damage()));
                z.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(32.0);
                z.setCustomName(Component.literal("Зомби-лучник"));
                canWearArmor = true;
            }
            case RIDER -> {
                z.setCustomName(Component.literal("Зомби-всадник"));
                canWearArmor = true;
                mounted = true;
            }
            case RUNNER -> {
                mulAttr(z, Attributes.MOVEMENT_SPEED, 1.4, 0.40);
                mulAttr(z, Attributes.MAX_HEALTH, 0.6, 1000);
                z.setHealth(z.getMaxHealth());
                z.setCustomName(Component.literal("Зомби-бегун"));
            }
            case DIGGER -> {
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(wave >= 8 ? Items.DIAMOND_PICKAXE : Items.IRON_PICKAXE));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
                z.setCustomName(Component.literal("Зомби-шахтёр"));
                canWearArmor = true;
            }
            case HUSK -> {
                z.setCustomName(Component.literal("Зомби-пустынник"));
                canWearArmor = true;
            }
            case LEAPER -> {
                mulAttr(z, Attributes.MOVEMENT_SPEED, 1.1, 0.36);
                z.goalSelector.addGoal(2, new LeapGoal(z));
                z.setCustomName(Component.literal("Зомби-прыгун"));
            }
            case TANK -> {
                mulAttr(z, Attributes.MAX_HEALTH, 3.0, 1000);
                z.setHealth(z.getMaxHealth());
                mulAttr(z, Attributes.MOVEMENT_SPEED, 0.8, 1.0);
                mulAttr(z, Attributes.ATTACK_DAMAGE, 1.3, 20);
                z.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0.8);
                z.setCustomName(Component.literal("Зомби-танк"));
                fullArmor = true;
            }
            case MAGE -> {
                z.addTag("nightsiege_mage");
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.AMETHYST_SHARD));
                z.setCustomName(Component.literal("✦ Зомби-маг ✦").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE, net.minecraft.ChatFormatting.BOLD));
                z.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false));
            }
            case CRAWLER -> {
                z.addTag("nightsiege_crawler");
                z.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Math.min(2.0, 1.0 + wave * 0.05) * SiegeConfig.damage());
                z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.24 * SiegeConfig.speed());
                z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(12.0 + wave * 0.5);
                z.setHealth(z.getMaxHealth());
                z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.AIR));
                z.setCustomName(Component.literal("Зомби-ползун").withStyle(net.minecraft.ChatFormatting.DARK_GREEN, net.minecraft.ChatFormatting.BOLD));
            }
            case SHIELD -> {
                z.addTag("nightsiege_shield");
                z.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                z.setDropChance(EquipmentSlot.OFFHAND, 0f);
                z.setDropChance(EquipmentSlot.HEAD, 0f);
                z.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0.0);
                z.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0.85);
                z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.16 * SiegeConfig.speed());
                z.setCustomName(Component.literal("Зомби-щитоносец").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
            }
            case PYRO -> {
                z.addTag(WaveManager.PYRO_TAG);
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BLAZE_ROD));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
                z.setCustomName(Component.literal("Зомби-поджигатель"));
            }
            default -> {
                z.setCustomName(Component.literal("Зомби"));
                canWearArmor = true;
            }
        }

        if (fullArmor) {
            armor(z, wave, r, true);
        } else if (canWearArmor && r.nextDouble() < Math.min(0.9, 0.15 + 0.08 * wave)) {
            armor(z, wave, r, false);
            if (kind != Kind.ARCHER && kind != Kind.DIGGER && wave >= 4 && r.nextFloat() < 0.4f) {
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(
                        wave >= 9 ? Items.DIAMOND_SWORD : (wave >= 6 ? Items.IRON_SWORD : Items.STONE_SWORD)));
                z.setDropChance(EquipmentSlot.MAINHAND, 0.05f);
            }
        }

        // Читаемые силуэты классов: отдельные головные уборы/оружие и характерные эффекты.
        switch (kind) {
            case NORMAL -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); z.addEffect(new MobEffectInstance(MobEffects.GLOWING, 50, 0, false, false)); }
            case TNT -> { z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 1, false, false)); z.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100, 0, false, false)); }
            case ARCHER -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.SKELETON_SKULL)); z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE)); z.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 240, 0, false, false)); }
            case RIDER -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET)); z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0, false, false)); }
            case RUNNER -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.RABBIT_FOOT)); z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1, false, false)); }
            case DIGGER -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); z.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 0, false, false)); }
            case HUSK -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.SAND)); z.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 0, false, false)); }
            case LEAPER -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.SLIME_BLOCK)); z.addEffect(new MobEffectInstance(MobEffects.JUMP, 200, 2, false, false)); }
            case TANK -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_BLOCK)); z.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 1, false, false)); }
            case PYRO -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN)); z.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 0, false, false)); }
            case MAGE -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.AMETHYST_BLOCK)); z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.PURPLE_STAINED_GLASS)); z.setDropChance(EquipmentSlot.HEAD, 0f); z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0, false, false)); }
            case CRAWLER -> { z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET)); z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE)); z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0, false, false)); z.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80, 0, false, false)); }
            case SHIELD -> { z.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD)); z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); z.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 1, false, false)); }
        }
        if (mounted) {
            ZombieHorse horse = EntityType.ZOMBIE_HORSE.create(level);
            if (horse != null) {
                horse.moveTo(z.getX(), z.getY(), z.getZ(), z.getYRot(), 0f);
                horse.addTag(WaveManager.MOUNT_TAG);
                horse.setPersistenceRequired();
                horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.33 * SiegeConfig.speed());
                horse.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
                horse.goalSelector.getAvailableGoals().clear();
                horse.goalSelector.addGoal(1, new BreakBlockGoal(horse, 1.0));
                horse.goalSelector.addGoal(2, new SiegeMoveGoal(horse, 1.4, true));
                level.addFreshEntity(horse);
                level.addFreshEntity(z);
                z.startRiding(horse, true);
                return;
            }
        }
        level.addFreshEntity(z);
    }

    /** Общие параметры всех зомби волны: рост характеристик с номером волны + настройки сложности. */
    private static void prepare(Zombie z, int wave, double breakFactor) {
        z.addTag(WaveManager.TAG);
        z.setPersistenceRequired();
        z.setCanPickUpLoot(false);
        z.setBaby(false);

        double hp = Math.min(2.5, 1 + 0.07 * (wave - 1)) * SiegeConfig.health();
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20 * hp);
        z.setHealth(z.getMaxHealth());
        z.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(Math.min(8, 3 + 0.25 * (wave - 1)) * SiegeConfig.damage());
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(Math.min(0.30, 0.23 + 0.004 * wave) * SiegeConfig.speed());
        z.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
        z.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(0);
        // естественная броня растёт с каждой волной
        z.getAttribute(Attributes.ARMOR).setBaseValue(2 + Math.min(12, 0.6 * wave));

        z.goalSelector.addGoal(1, new BreakBlockGoal(z, breakFactor));
        z.goalSelector.addGoal(2, new WallClimbGoal(z));
        z.goalSelector.addGoal(3, new SiegeMoveGoal(z, 1.0, false));
    }

    private static void setSpeed(Zombie z, double base) {
        z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(base * SiegeConfig.speed());
    }

    private static void mulAttr(Zombie z, Attribute attr, double mul, double cap) {
        AttributeInstance a = z.getAttribute(attr);
        if (a != null) a.setBaseValue(Math.min(cap, a.getBaseValue() * mul));
    }

    /**
     * Броня становится сильнее с каждой волной:
     *  - материал: кожа -> золото -> кольчуга -> железо -> алмаз -> незерит (ещё +бонус из конфига)
     *  - больше надетых частей
     *  - чары "Защита" с 3 волны, уровень растёт
     *  - плюс естественная броня моба (см. prepare)
     */
    private static void armor(Zombie z, int wave, RandomSource r, boolean full) {
        int tierMax = Math.max(0, Math.min(5, wave * 2 / 3 + SiegeConfig.ARMOR_TIER_BONUS.get()));
        if (full) tierMax = Math.min(5, Math.max(3, tierMax));
        double pieceChance = full ? 1.0 : Math.min(1.0, 0.45 + 0.06 * wave);
        int prot = Math.min(4, wave / 3);
        boolean any = false;

        for (int i = 0; i < 4; i++) {
            if (r.nextDouble() < pieceChance) {
                int tier = Math.max(0, tierMax - (r.nextInt(3) == 0 ? 1 : 0));
                ItemStack st = new ItemStack(ARMOR[tier][i]);
                if (prot > 0 && r.nextFloat() < 0.6f) {
                    st.enchant(Enchantments.ALL_DAMAGE_PROTECTION, Math.max(1, prot - r.nextInt(2)));
                }
                z.setItemSlot(SLOTS[i], st);
                z.setDropChance(SLOTS[i], 0.05f);
                any = true;
            }
        }
        if (!any) {
            z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ARMOR[tierMax][0]));
            z.setDropChance(EquipmentSlot.HEAD, 0.05f);
        }
    }
}
