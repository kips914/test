package com.nightsiege;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import java.util.List;

public final class SiegeItems {
    private SiegeItems() {}
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, NightSiege.MODID);
    public static final RegistryObject<Item> SALVATION = ITEMS.register("potion_of_salvation", () -> new SiegePotion("Зелье спасения", 0));
    public static final RegistryObject<Item> RAID_I = ITEMS.register("raid_potion_1", () -> new SiegePotion("Зелье нападения I", 1));
    public static final RegistryObject<Item> RAID_II = ITEMS.register("raid_potion_2", () -> new SiegePotion("Зелье нападения II", 2));
    public static final RegistryObject<Item> RAID_III = ITEMS.register("raid_potion_3", () -> new SiegePotion("Зелье нападения III", 3));
    public static final RegistryObject<Item> WHISTLE = ITEMS.register("zombie_whistle", () -> new Whistle());
    public static void register(IEventBus bus) { ITEMS.register(bus); }

    private static final class Whistle extends Item {
        Whistle() { super(new Item.Properties().stacksTo(1)); }
        @Override public Component getName(ItemStack stack) { return Component.literal("Свисток приманки").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD); }
        @Override public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
            tip.add(Component.literal("Свистни, чтобы подозвать зомби в радиусе 48 блоков.").withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal("Перезарядка: 10 секунд.").withStyle(ChatFormatting.GRAY));
        }
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!level.isClientSide && level instanceof ServerLevel serverLevel && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                long now = serverLevel.getGameTime();
                long ready = sp.getPersistentData().getLong("NSWhistleReadyAt");
                if (now >= ready) {
                    int called = 0;
                    for (net.minecraft.world.entity.monster.Zombie z : serverLevel.getEntitiesOfClass(net.minecraft.world.entity.monster.Zombie.class, sp.getBoundingBox().inflate(48), z -> z.isAlive() && z.getTags().contains(WaveManager.TAG))) {
                        z.setTarget(sp);
                        z.getNavigation().moveTo(sp, 1.25);
                        called++;
                    }
                    sp.getPersistentData().putLong("NSWhistleReadyAt", now + 200);
                    serverLevel.playSound(null, sp.blockPosition(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL, net.minecraft.sounds.SoundSource.PLAYERS, 1.4f, 0.65f);
                    sp.displayClientMessage(Component.literal("Свисток привлёк зомби: " + called).withStyle(ChatFormatting.AQUA), true);
                    sp.getCooldowns().addCooldown(this, 200);
                } else {
                    sp.displayClientMessage(Component.literal("Свисток ещё перезаряжается.").withStyle(ChatFormatting.GRAY), true);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
    }

    private static final class SiegePotion extends Item {
        private final String title; private final int level;
        SiegePotion(String title, int level) { super(new Item.Properties().stacksTo(16)); this.title=title; this.level=level; }
        @Override public Component getName(ItemStack stack) { return Component.literal(title).withStyle(level == 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_RED); }
        @Override public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
            if (this.level == 0) tip.add(Component.literal("Отменяет ближайшую автоматическую волну. Очень редкий дроп.").withStyle(ChatFormatting.GRAY));
            else tip.add(Component.literal("Запускает нападение в любое время. Сила и награды: уровень " + this.level + ".").withStyle(ChatFormatting.GRAY));
        }
        @Override public InteractionResultHolder<ItemStack> use(Level levelWorld, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (!levelWorld.isClientSide && levelWorld instanceof ServerLevel level && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                boolean ok = this.level == 0 ? WaveManager.useSalvation(level, sp) : WaveManager.startPotionWave(level, sp, this.level);
                if (ok) { if (!player.getAbilities().instabuild) stack.shrink(1); player.getCooldowns().addCooldown(this, 30); }
                else player.displayClientMessage(Component.literal(this.level == 0 ? "Зелье спасения не сработало: волна уже идёт." : "Нападение не началось: волна уже идёт."), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, levelWorld.isClientSide());
        }
    }
}
