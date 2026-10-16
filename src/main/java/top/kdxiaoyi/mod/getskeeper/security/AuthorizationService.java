package top.kdxiaoyi.mod.getskeeper.security;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.data.AuthorizationRecord;
import top.kdxiaoyi.mod.getskeeper.data.GKAttachments;
import top.kdxiaoyi.mod.getskeeper.source.SourceRecorder;

/**
 * Reads and writes the per-player authorization record.
 *
 * <p>Authorizations are keyed by {@code namespace:path} and ignore components, so authorizing a
 * diamond sword covers every diamond sword regardless of enchantments. An authorization never
 * expires, and turning its switch off keeps it on file instead of deleting it.
 */
public final class AuthorizationService {
    private AuthorizationService() {}

    public static AuthorizationRecord record(Player player) {
        return player.getData(GKAttachments.AUTHORIZATIONS.get());
    }

    /** {@return {@code true} when the stack is authorized and its switch is on} */
    public static boolean isUsable(Player player, ItemStack stack) {
        return stack != null && !stack.isEmpty() && record(player).isUsable(SourceRecorder.itemId(stack));
    }

    /** Grants an item for good. Does nothing when it is already usable. */
    public static void authorize(ServerPlayer player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        AuthorizationRecord current = record(player);
        if (current.isUsable(itemId)) {
            return;
        }
        player.setData(GKAttachments.AUTHORIZATIONS.get(), current.authorize(itemId));
        GetsKeeper.LOGGER.info("{} authorized {}", player.getScoreboardName(), itemId);
    }

    /** Turns an item's switch off while keeping the authorization on file. */
    public static void disable(ServerPlayer player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        AuthorizationRecord current = record(player);
        if (!current.isUsable(itemId)) {
            return;
        }
        player.setData(GKAttachments.AUTHORIZATIONS.get(), current.disable(itemId));
        GetsKeeper.LOGGER.info("{} restricted {} until it is authorized again", player.getScoreboardName(), itemId);
    }
}
