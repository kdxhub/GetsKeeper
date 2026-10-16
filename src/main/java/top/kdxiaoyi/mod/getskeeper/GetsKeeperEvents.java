package top.kdxiaoyi.mod.getskeeper;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import top.kdxiaoyi.mod.getskeeper.security.PasswordService;
import top.kdxiaoyi.mod.getskeeper.security.SessionGuards;

/**
 * Lifecycle glue: the parts that are about entering and leaving a world rather than about items.
 *
 * <p>This is where "per play session" data actually gets reset. Leaving a world clears the session
 * lists; stopping the server clears them all. Death deliberately does not: the authorizations and the
 * source records are attachments with {@code copyOnDeath}, so respawning keeps them.
 */
public final class GetsKeeperEvents {
    private GetsKeeperEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(GetsKeeperEvents::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(GetsKeeperEvents::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(GetsKeeperEvents::onServerStopping);
    }

    /**
     * Asks an operator without a password to set one.
     *
     * <p>This is the only moment a password is ever demanded. Once it exists, logging in never asks
     * for it again.
     */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && PasswordService.needsSetup(player)) {
            GetsKeeper.LOGGER.info("{} is an operator without a password and will be asked to set one",
                    player.getScoreboardName());
            PasswordService.promptSetup(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SessionGuards.clear(player.getUUID());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        SessionGuards.clearAll();
    }
}
