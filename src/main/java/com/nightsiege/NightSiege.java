package com.nightsiege;

import com.nightsiege.net.Network;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(NightSiege.MODID)
public class NightSiege {
    public static final String MODID = "nightsiege";

    public NightSiege() {
        Network.register();
        MinecraftForge.EVENT_BUS.register(WaveManager.class);
    }
}
