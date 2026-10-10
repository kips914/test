package com.nightsiege;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Состояние волны, сохраняется вместе с миром. */
public class WaveData extends SavedData {
    public boolean active;
    public int wave;     // номер текущей волны
    public int total;    // сколько зомби всего в волне
    public int spawned;  // сколько уже заспавнено
    public int lastDay;  // день, в который последний раз стартовала волна

    public static WaveData load(CompoundTag t) {
        WaveData d = new WaveData();
        d.active = t.getBoolean("active");
        d.wave = t.getInt("wave");
        d.total = t.getInt("total");
        d.spawned = t.getInt("spawned");
        d.lastDay = t.getInt("lastDay");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        t.putBoolean("active", active);
        t.putInt("wave", wave);
        t.putInt("total", total);
        t.putInt("spawned", spawned);
        t.putInt("lastDay", lastDay);
        return t;
    }
}
