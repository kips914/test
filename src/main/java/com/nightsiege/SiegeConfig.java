package com.nightsiege;

import net.minecraftforge.common.ForgeConfigSpec;

/** Настройки: config/nightsiege-common.toml (после первого запуска). */
public class SiegeConfig {
    public enum Preset {
        //     кол-во здоровье урон скорость ломание особые
        EASY(0.7, 0.8, 0.8, 0.9, 0.7, 0.6),
        NORMAL(1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
        HARD(1.3, 1.3, 1.2, 1.05, 1.3, 1.3),
        NIGHTMARE(1.7, 1.7, 1.5, 1.1, 1.8, 1.6);

        public final double count, health, damage, speed, breakSpeed, special;

        Preset(double count, double health, double damage, double speed, double breakSpeed, double special) {
            this.count = count;
            this.health = health;
            this.damage = damage;
            this.speed = speed;
            this.breakSpeed = breakSpeed;
            this.special = special;
        }
    }

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.EnumValue<Preset> DIFFICULTY;
    public static final ForgeConfigSpec.DoubleValue COUNT_MULT, HEALTH_MULT, DAMAGE_MULT, SPEED_MULT,
            BREAK_SPEED_MULT, SPECIAL_MULT, MP_SCALING;
    public static final ForgeConfigSpec.IntValue START_DAY, MAX_TOTAL, MAX_ALIVE_PER_PLAYER, SPAWN_INTERVAL,
            MIN_SPAWN_DIST, MAX_SPAWN_DIST, MAX_LIGHT, ARMOR_TIER_BONUS;
    public static final ForgeConfigSpec.BooleanValue BLOCK_BREAKING, REQUIRE_DARK, ALLOW_SLEEP, ANNOUNCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("difficulty");
        DIFFICULTY = b.comment("Пресет сложности: EASY, NORMAL, HARD, NIGHTMARE (меняется и командой /siege difficulty <пресет>)")
                .defineEnum("preset", Preset.NORMAL);
        COUNT_MULT = b.comment("Доп. множитель количества зомби (умножается на пресет)")
                .defineInRange("zombieCountMultiplier", 1.0, 0.1, 10.0);
        HEALTH_MULT = b.comment("Доп. множитель здоровья")
                .defineInRange("healthMultiplier", 1.0, 0.1, 10.0);
        DAMAGE_MULT = b.comment("Доп. множитель урона")
                .defineInRange("damageMultiplier", 1.0, 0.1, 10.0);
        SPEED_MULT = b.comment("Доп. множитель скорости")
                .defineInRange("speedMultiplier", 1.0, 0.5, 2.0);
        BREAK_SPEED_MULT = b.comment("Скорость ломания блоков (больше = быстрее ломают стены)")
                .defineInRange("blockBreakSpeedMultiplier", 1.0, 0.1, 10.0);
        SPECIAL_MULT = b.comment("Шанс особых зомби (TNT, лучники, всадники и т.д.). 0 = только обычные")
                .defineInRange("specialZombieMultiplier", 1.0, 0.0, 3.0);
        ARMOR_TIER_BONUS = b.comment("Бонус к уровню брони (0..3). Каждый +1 = материал лучше")
                .defineInRange("armorTierBonus", 0, -2, 3);
        b.pop();

        b.push("waves");
        START_DAY = b.comment("С какого дня начинаются нападения")
                .defineInRange("startDay", 5, 1, 100);
        MAX_TOTAL = b.comment("Максимум зомби в одной волне (на одного игрока)")
                .defineInRange("maxZombiesPerWave", 120, 5, 500);
        MP_SCALING = b.comment("Прибавка к количеству зомби за каждого дополнительного игрока (0.5 = +50%)")
                .defineInRange("multiplayerScaling", 0.5, 0.0, 2.0);
        ALLOW_SLEEP = b.comment("Можно ли спать во время волны")
                .define("allowSleepDuringWave", false);
        ANNOUNCE = b.comment("Показывать сообщения о приближении нападения")
                .define("announceAttack", true);
        b.pop();

        b.push("spawning");
        MAX_ALIVE_PER_PLAYER = b.comment("Макс. зомби одновременно рядом с одним игроком (оптимизация)")
                .defineInRange("maxAlivePerPlayer", 25, 5, 100);
        SPAWN_INTERVAL = b.comment("Как часто появляются новые зомби (в тиках, 20 = раз в секунду)")
                .defineInRange("spawnIntervalTicks", 20, 10, 200);
        MIN_SPAWN_DIST = b.comment("Мин. расстояние спавна от игрока")
                .defineInRange("minSpawnDistance", 18, 8, 64);
        MAX_SPAWN_DIST = b.comment("Макс. расстояние спавна от игрока")
                .defineInRange("maxSpawnDistance", 36, 16, 80);
        REQUIRE_DARK = b.comment("Спавнить только в тёмных местах")
                .define("requireDarkSpawn", true);
        MAX_LIGHT = b.comment("Макс. освещённость точки спавна (0-15). Факелы рядом отпугивают спавн")
                .defineInRange("maxSpawnLight", 7, 0, 15);
        b.pop();

        b.push("zombies");
        BLOCK_BREAKING = b.comment("Могут ли зомби ломать блоки и двери")
                .define("zombiesBreakBlocks", true);
        b.pop();

        SPEC = b.build();
    }

    public static double count() { return DIFFICULTY.get().count * COUNT_MULT.get(); }
    public static double health() { return DIFFICULTY.get().health * HEALTH_MULT.get(); }
    public static double damage() { return DIFFICULTY.get().damage * DAMAGE_MULT.get(); }
    public static double speed() { return DIFFICULTY.get().speed * SPEED_MULT.get(); }
    public static double breakSpeed() { return DIFFICULTY.get().breakSpeed * BREAK_SPEED_MULT.get(); }
    public static double special() { return DIFFICULTY.get().special * SPECIAL_MULT.get(); }
}
