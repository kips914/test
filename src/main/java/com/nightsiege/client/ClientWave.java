package com.nightsiege.client;

import com.nightsiege.NightSiege;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = NightSiege.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientWave {
    private static boolean active;
    private static int wave, remaining, total;
    private static float shown = 1f;

    public static void set(boolean a, int w, int r, int t) {
        if (a && !active) shown = 1f;
        active = a;
        wave = w;
        remaining = r;
        total = t;
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("wave_bar", (gui, g, partialTick, w, h) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!active || mc.options.hideGui) return;

            float target = total <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) remaining / total));
            shown += (target - shown) * 0.15f; // плавное уменьшение шкалы

            int bw = 200, bx = (w - bw) / 2, by = 10;
            g.fill(bx - 1, by - 1, bx + bw + 1, by + 9, 0xAA000000);   // рамка
            g.fill(bx, by, bx + bw, by + 8, 0xFF3A0000);               // фон шкалы
            g.fill(bx, by, bx + (int) (bw * shown), by + 8, 0xFFC81E1E); // сама шкала

            g.drawCenteredString(mc.font,
                    Component.literal("Волна " + wave + "  •  " + remaining + "/" + total),
                    w / 2, by + 13, 0xFFFFFF);
        });
    }
}
