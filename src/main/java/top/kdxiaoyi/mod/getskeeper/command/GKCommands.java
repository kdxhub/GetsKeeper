package top.kdxiaoyi.mod.getskeeper.command;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import top.kdxiaoyi.mod.getskeeper.config.GKConfig;
import top.kdxiaoyi.mod.getskeeper.net.GKNetwork;
import top.kdxiaoyi.mod.getskeeper.net.PasswordPurpose;
import top.kdxiaoyi.mod.getskeeper.security.PasswordService;

/**
 * {@code /mattr} and {@code /passwd}.
 *
 * <p>{@code /mattr} reads its arguments as one greedy string instead of a Brigadier argument tree.
 * That is deliberate: the three required tokens are order independent, and a tree would either force
 * an order or require every permutation to be registered. Taking the line as a whole also means
 * Brigadier offers no completions for it, which is what "no argument hints" asks for.
 *
 * <p>The item is still parsed with the vanilla parser: the last token has to be a well formed
 * {@code Identifier}. Whether the item actually exists is only checked after the password has been
 * verified, exactly in the order the design specifies.
 */
public final class GKCommands {
    /** The three tokens {@code /mattr} insists on, in any order. */
    private static final Set<String> REQUIRED_FLAGS = Set.of("-r", "-d", "com.ojang.quarantine");

    private static final String ARGUMENTS = "arguments";

    private GKCommands() {}

    public static void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("mattr")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> usage(context.getSource()))
                .then(Commands.argument(ARGUMENTS, StringArgumentType.greedyString())
                        .executes(GKCommands::mattr)));

        dispatcher.register(Commands.literal("passwd")
                .executes(context -> passwd(context.getSource())));
    }

    private static int usage(CommandSourceStack source) {
        source.sendFailure(Component.translatable("getskeeper.command.mattr.usage"));
        return 0;
    }

    private static int mattr(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String raw = StringArgumentType.getString(context, ARGUMENTS);

        List<String> tokens = Arrays.stream(raw.trim().split("\\s+")).filter(token -> !token.isEmpty()).toList();
        if (tokens.size() < REQUIRED_FLAGS.size() + 1) {
            return usage(context.getSource());
        }

        Set<String> flags = new HashSet<>(tokens.subList(0, tokens.size() - 1));
        if (!flags.containsAll(REQUIRED_FLAGS)) {
            return usage(context.getSource());
        }

        String itemToken = tokens.get(tokens.size() - 1);
        Identifier itemId = Identifier.tryParse(itemToken);
        if (itemId == null) {
            context.getSource().sendFailure(Component.translatable("argument.item.id.invalid", itemToken));
            return 0;
        }

        // The password comes next; the item is authorized once it has been verified.
        GKNetwork.sendPasswordPrompt(player, PasswordPurpose.AUTHORIZE_ITEM, itemId.toString(),
                player.getScoreboardName(), false, GKConfig.passwordMinLength());
        return 1;
    }

    private static int passwd(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PasswordService.promptChange(player);
        return 1;
    }
}
