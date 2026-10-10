package com.nightsiege;

import com.nightsiege.net.Network;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(NightSiege.MODID)
public class NightSiege {
    public static final String MODID = "nightsiege";

    public NightSiege() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SiegeConfig.SPEC);
        Network.register();
        SiegeItems.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        MinecraftForge.EVENT_BUS.register(WaveManager.class);
        MinecraftForge.EVENT_BUS.register(SiegeCommand.class);
    }
}
