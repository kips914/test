package com.nightsiege.client;

import com.nightsiege.NightSiege;
import com.nightsiege.net.Network;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = NightSiege.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WhistleKeybind {
    private static final KeyMapping WHISTLE = new KeyMapping("key.nightsiege.whistle", GLFW.GLFW_KEY_K, "key.categories.nightsiege");
    private WhistleKeybind() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) { event.register(WHISTLE); }

    @Mod.EventBusSubscriber(modid = NightSiege.MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (WHISTLE.consumeClick()) {
                if (net.minecraft.client.Minecraft.getInstance().player != null) {
                    Network.CHANNEL.sendToServer(new Network.WhistlePacket());
                }
            }
        }
    }
}
