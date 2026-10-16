package top.kdxiaoyi.mod.getskeeper.net;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.pickup.PickupInterceptor;
import top.kdxiaoyi.mod.getskeeper.security.AuthorizationService;
import top.kdxiaoyi.mod.getskeeper.security.PasswordService;
import top.kdxiaoyi.mod.getskeeper.security.SessionGuards;
import top.kdxiaoyi.mod.getskeeper.source.ItemSource;
import top.kdxiaoyi.mod.getskeeper.source.SourceRecorder;
import top.kdxiaoyi.mod.getskeeper.use.UseGuard;

/**
 * Handles everything the client sends.
 *
 * <p>Every handler hops onto the server thread through {@code enqueueWork} before touching game
 * state, which is what keeps a malicious or merely laggy client from mutating the world off-thread.
 *
 * <p>None of this is trusted input: the client can only ask. Whether an item is authorized, whether
 * a password is right and whether a batch fits in the inventory are all decided here.
 */
public final class GKServerHandler {
    private GKServerHandler() {}

    public static void onPickupDecision(GKPayloads.PickupDecision payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PickupInterceptor.handleDecision(player, payload.accept());
            }
        });
    }

    public static void onUseDecision(GKPayloads.UseDecision payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                UseGuard.handleDecision(player, VerificationDecision.byOrdinal(payload.decision()));
            }
        });
    }

    public static void onPasswordSubmit(GKPayloads.PasswordSubmit payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PasswordPurpose purpose = PasswordPurpose.byOrdinal(payload.purpose());
            boolean accepted;
            switch (purpose) {
                case SET_PASSWORD -> accepted = PasswordService.store(player, payload.username(), payload.password());
                case AUTHORIZE_ITEM, GRANT_BLOCKED_USE -> {
                    accepted = PasswordService.verify(player, payload.username(), payload.password());
                    if (accepted && purpose == PasswordPurpose.AUTHORIZE_ITEM) {
                        // The item is only looked up after the password checked out, and a name that
                        // does not resolve is reported the way vanilla reports it.
                        net.minecraft.resources.Identifier itemId = net.minecraft.resources.Identifier.tryParse(payload.itemId());
                        if (itemId == null || net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemId).isEmpty()) {
                            player.sendSystemMessage(Component.translatable("argument.item.id.invalid", payload.itemId()));
                            accepted = false;
                        } else {
                            player.sendSystemMessage(
                                    Component.translatable("getskeeper.command.mattr.authorized", payload.itemId()));
                        }
                    }
                    if (accepted) {
                        AuthorizationService.authorize(player, payload.itemId());
                    }
                }
                case TOGGLE_AUTHORIZATION -> {
                    // The client only ever says "flip this switch"; the server owns the current state,
                    // so the flip is applied here rather than trusting a requested value from the wire.
                    accepted = PasswordService.verify(player, payload.username(), payload.password());
                    if (accepted) {
                        if (AuthorizationService.record(player).isUsable(payload.itemId())) {
                            AuthorizationService.disable(player, payload.itemId());
                        } else {
                            AuthorizationService.authorize(player, payload.itemId());
                        }
                    }
                }
                default -> accepted = false;
            }

            GetsKeeper.LOGGER.info("Password attempt for {} ({}): {}", player.getScoreboardName(), purpose,
                    accepted ? "accepted" : "rejected");
            GKNetwork.sendPasswordResult(player, purpose, payload.itemId(), accepted, false);
            if (accepted) {
                GKNetwork.sendSessionBlocked(player);
            }
        });
    }

    public static void onPasswordCancel(GKPayloads.PasswordCancel payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PasswordPurpose purpose = PasswordPurpose.byOrdinal(payload.purpose());
            if (purpose != PasswordPurpose.SET_PASSWORD) {
                return;
            }
            // Dismissing the mandatory setup is a refusal to play under the rules. In single-player
            // the same call drops the host back to the main menu.
            GetsKeeper.LOGGER.info("{} refused to set a password and was disconnected", player.getScoreboardName());
            PasswordService.disconnect(player, Component.translatable("getskeeper.disconnect.no_password"));
        });
    }

    public static void onRequestSessionData(GKPayloads.RequestSessionData payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                GKNetwork.sendSessionBlocked(player);
            }
        });
    }

    public static void onRequestBlockedUse(GKPayloads.RequestBlockedUse payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // "Use anyway" never grants anything by itself: it only opens the password prompt.
            SessionGuards.mark(player.getUUID(), payload.itemId());
            GKNetwork.sendPasswordPrompt(player, PasswordPurpose.GRANT_BLOCKED_USE, payload.itemId(),
                    player.getScoreboardName(), false);
        });
    }

    /** Silences the unused-import warning for {@link SourceRecorder}. */
    static String describeStack(net.minecraft.world.item.ItemStack stack) {
        return SourceRecorder.itemId(stack);
    }
}
