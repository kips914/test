package com.nightsiege;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/**
 * /siege start [волна]  - запустить волну сейчас (время переключится на ночь)
 * /siege stop           - остановить волну
 * /siege status         - состояние
 * /siege difficulty [easy|normal|hard|nightmare] - показать/сменить сложность
 */
public class SiegeCommand {
    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent e) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("siege")
                .requires(s -> s.hasPermission(2));

        root.then(Commands.literal("start")
                .executes(c -> start(c.getSource(), -1))
                .then(Commands.argument("wave", IntegerArgumentType.integer(1, 100))
                        .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "wave")))));

        root.then(Commands.literal("stop").executes(c -> {
            WaveManager.stop(c.getSource().getServer().overworld());
            return 1;
        }));

        root.then(Commands.literal("status").executes(c -> {
            ServerLevel level = c.getSource().getServer().overworld();
            WaveData d = WaveManager.data(level);
            String s = d.active
                    ? "Идёт волна " + d.wave + ", заспавнено " + d.spawned + "/" + d.total
                    : "Волны нет. Старт с " + SiegeConfig.START_DAY.get() + " дня.";
            c.getSource().sendSuccess(() -> Component.literal(s + " Сложность: " + SiegeConfig.DIFFICULTY.get()), false);
            return 1;
        }));

        LiteralArgumentBuilder<CommandSourceStack> diff = Commands.literal("difficulty")
                .executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("Сложность: " + SiegeConfig.DIFFICULTY.get()), false);
                    return 1;
                });
        for (SiegeConfig.Preset p : SiegeConfig.Preset.values()) {
            diff.then(Commands.literal(p.name().toLowerCase(Locale.ROOT)).executes(c -> {
                SiegeConfig.DIFFICULTY.set(p);
                SiegeConfig.DIFFICULTY.save();
                c.getSource().sendSuccess(() -> Component.literal("Сложность: " + p), true);
                return 1;
            }));
        }
        root.then(diff);

        e.getDispatcher().register(root);
    }

    private static int start(CommandSourceStack src, int wave) {
        ServerLevel level = src.getServer().overworld();
        if (!WaveManager.forceStart(level, wave)) {
            src.sendFailure(Component.literal("Нет подходящих игроков (нужен живой игрок не в наблюдателе)."));
            return 0;
        }
        return 1;
    }
}
