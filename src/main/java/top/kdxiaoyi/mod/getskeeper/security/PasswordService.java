package top.kdxiaoyi.mod.getskeeper.security;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import top.kdxiaoyi.mod.getskeeper.config.GKConfig;
import top.kdxiaoyi.mod.getskeeper.data.GKWorldData;
import top.kdxiaoyi.mod.getskeeper.net.GKNetwork;
import top.kdxiaoyi.mod.getskeeper.net.PasswordPurpose;

/**
 * Password policy.
 *
 * <p>An operator is a player with permission level 3 or higher. On a dedicated server that means an
 * op; in single-player the host already holds that permission level, so "single-player counts as
 * administrator" needs no special case and behaves identically to the dedicated server.
 *
 * <p>An operator without a password cannot play: the prompt is mandatory and dismissing it disconnects
 * them. Once a password exists it is never asked for at login again, exactly as specified - it is only
 * used to authorize things.
 */
public final class PasswordService {
    private PasswordService() {}

    /** {@return {@code true} when the player holds operator permissions} */
    public static boolean isOperator(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    /** {@return the per-world data, or {@code null} when the player is not on a server} */
    public static GKWorldData world(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? null : GKWorldData.get(server);
    }

    public static boolean hasPassword(ServerPlayer player) {
        GKWorldData data = world(player);
        return data != null && data.hasPassword(player.getScoreboardName());
    }

    /** {@return {@code true} when this player must set a password before being allowed to play} */
    public static boolean needsSetup(ServerPlayer player) {
        return GKConfig.requireAdminPassword() && isOperator(player) && !hasPassword(player);
    }

    /** Sends the mandatory first-time setup prompt. */
    public static void promptSetup(ServerPlayer player) {
        GKNetwork.sendPasswordPrompt(player, PasswordPurpose.SET_PASSWORD, "", player.getScoreboardName(), true);
    }

    /** Sends the voluntary prompt behind {@code /passwd}. */
    public static void promptChange(ServerPlayer player) {
        GKNetwork.sendPasswordPrompt(player, PasswordPurpose.SET_PASSWORD, "", player.getScoreboardName(), false);
    }

    /**
     * Stores a password for the player's own name.
     *
     * @return {@code false} when the password is too short or aimed at somebody else's name
     */
    public static boolean store(ServerPlayer player, String username, String password) {
        if (password == null || password.length() < GKConfig.passwordMinLength()) {
            return false;
        }
        if (!username.isEmpty() && !username.equalsIgnoreCase(player.getScoreboardName())) {
            // Setting another player's password is not a thing this mod does.
            return false;
        }
        GKWorldData data = world(player);
        if (data == null) {
            return false;
        }
        data.setPassword(player.getScoreboardName(), password);
        return true;
    }

    /** Verifies a submitted user name and password pair. */
    public static boolean verify(ServerPlayer player, String username, String password) {
        GKWorldData data = world(player);
        if (data == null || username == null || username.isEmpty() || password == null) {
            return false;
        }
        return data.verify(username, password);
    }

    /** Disconnects a player, which in single-player drops them back to the main menu. */
    public static void disconnect(ServerPlayer player, Component reason) {
        player.connection.disconnect(reason);
    }
}
