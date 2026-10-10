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
    private static final String V = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NightSiege.MODID, "main"), () -> V, V::equals, V::equals);

    public static void register() {
        CHANNEL.registerMessage(0, SyncPacket.class, SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
    }

    /** Сервер -> клиент: состояние волны для HUD. */
    public record SyncPacket(boolean active, int wave, int remaining, int total) {
        public void encode(FriendlyByteBuf b) {
            b.writeBoolean(active);
            b.writeVarInt(wave);
            b.writeVarInt(remaining);
            b.writeVarInt(total);
        }

        public static SyncPacket decode(FriendlyByteBuf b) {
            return new SyncPacket(b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientWave.set(active, wave, remaining, total)));
            ctx.get().setPacketHandled(true);
        }
    }
}
