package com.nightsiege.client;

import com.nightsiege.NightSiege;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = NightSiege.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientWave {
    private static int state, wave, remaining, total;
    private static float shown = 1f; // текущая шкала
    private static float lag = 1f;   // "хвост" потерянного здоровья (светлая полоска)
    private static long stateSince;

    public static void set(int st, int w, int r, int t) {
        long now = Util.getMillis();
        if (st == 0 && (state == 2 || state == 3) && now - stateSince < 4500) return; // не прерываем баннер
        if (st != state) {
            stateSince = now;
            if (st == 1) {
                shown = 1f;
                lag = 1f;
            }
        }
        state = st;
        wave = w;
        remaining = r;
        total = t;
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("wave_bar", (gui, g, partialTick, w, h) -> {
            Minecraft mc = Minecraft.getInstance();
            if (state == 0 || mc.options.hideGui) return;
            long now = Util.getMillis();
            long el = now - stateSince;
            if (state == 1) drawBar(g, mc.font, w, now, el);
            else drawBanner(g, mc.font, w, el);
        });
    }

    private static void drawBar(GuiGraphics g, Font font, int sw, long now, long el) {
        // плавный выезд сверху
        float slide = Math.min(1f, el / 600f);
        float eased = 1f - (1f - slide) * (1f - slide) * (1f - slide);
        int yOff = (int) (-(1f - eased) * 45f);

        float target = total <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) remaining / total));
        shown += (target - shown) * 0.2f;
        if (lag < shown) lag = shown;
        else lag = Math.max(shown, lag - 0.004f);

        int bw = 220, bh = 12;
        int x = (sw - bw) / 2;
        int y = 8 + yOff;

        // панель и рамка
        g.fill(x - 4, y - 4, x + bw + 4, y + bh + 4, 0xCC0A0A0A);
        g.renderOutline(x - 4, y - 4, bw + 8, bh + 8, 0xFF6B1414);
        g.fill(x, y, x + bw, y + bh, 0xFF2A0707);

        // "хвост" потерь
        int lw = (int) (bw * lag);
        if (lw > 0) g.fill(x, y, x + lw, y + bh, 0xFFE9B04A);

        // основная шкала с градиентом и бликом
        int fw = (int) (bw * shown);
        if (fw > 0) {
            g.fillGradient(x, y, x + fw, y + bh, 0xFFFF5A3C, 0xFF9A1010);
            g.fill(x, y, x + fw, y + 2, 0x55FFFFFF);
        }

        // деления
        for (int i = 1; i < 10; i++) {
            int xx = x + bw * i / 10;
            g.fill(xx, y, xx + 1, y + bh, 0x66000000);
        }

        // пульсация, когда осталось мало
        if (total > 0 && target <= 0.25f) {
            int a = (int) (90 + 70 * Math.sin(now / 130.0));
            g.renderOutline(x - 5, y - 5, bw + 10, bh + 10, (a << 24) | 0xFF2020);
        }

        // заголовок "ВОЛНА N"
        Component title = Component.literal("ВОЛНА " + wave).withStyle(ChatFormatting.BOLD);
        g.drawCenteredString(font, title, sw / 2, y + bh + 7, 0xFFE2A0);

        // подпись: сколько осталось
        int pct = Math.round(target * 100f);
        Component sub = Component.literal("Зомби: " + remaining + " / " + total + "  •  " + pct + "%");
        g.pose().pushPose();
        g.pose().translate(sw / 2f, y + bh + 19, 0f);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawCenteredString(font, sub, 0, 0, 0xCFCFCF);
        g.pose().popPose();
    }

    private static void drawBanner(GuiGraphics g, Font font, int sw, long el) {
        if (el > 4500) return;
        float a = el < 400 ? el / 400f : (el > 3800 ? Math.max(0f, (4500 - el) / 700f) : 1f);
        int alpha = (int) (a * 255);
        if (alpha < 8) return;
        String text = state == 2 ? "ВОЛНА " + wave + " ОТБИТА!" : "РАССВЕТ — ВОЛНА " + wave + " ПРОЙДЕНА";
        int rgb = state == 2 ? 0x55FF55 : 0xFFD060;
        g.pose().pushPose();
        g.pose().translate(sw / 2f, 16, 0f);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.drawCenteredString(font, Component.literal(text).withStyle(ChatFormatting.BOLD), 0, 0, (alpha << 24) | rgb);
        g.pose().popPose();
    }
}
