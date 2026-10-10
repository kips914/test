package com.nightsiege.net;

import com.nightsiege.NightSiege;
import com.nightsiege.client.ClientWave;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public class Network {
    private static final String V = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NightSiege.MODID, "main"), () -> V, V::equals, V::equals);

    public static void register() {
        CHANNEL.registerMessage(0, SyncPacket.class, SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
        CHANNEL.registerMessage(1, WhistlePacket.class, WhistlePacket::encode, WhistlePacket::decode, WhistlePacket::handle);
    }

    /** Client -> server: activate the whistle keybind. */
    public record WhistlePacket() {
        public void encode(FriendlyByteBuf b) {}
        public static WhistlePacket decode(FriendlyByteBuf b) { return new WhistlePacket(); }
        public void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                if (c.getSender() != null) com.nightsiege.WaveManager.useWhistle(c.getSender());
            });
            c.setPacketHandled(true);
        }
    }

    /**
     * Сервер -> клиент: состояние для HUD.
     * state: 0 = нет волны, 1 = идёт волна, 2 = волна отбита, 3 = рассвет (волна пережита).
     */
    public record SyncPacket(int state, int wave, int remaining, int total) {
        public void encode(FriendlyByteBuf b) {
            b.writeVarInt(state);
            b.writeVarInt(wave);
            b.writeVarInt(remaining);
            b.writeVarInt(total);
        }

        public static SyncPacket decode(FriendlyByteBuf b) {
            return new SyncPacket(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientWave.set(state, wave, remaining, total)));
            ctx.get().setPacketHandled(true);
        }
    }
}
