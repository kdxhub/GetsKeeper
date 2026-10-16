package top.kdxiaoyi.mod.getskeeper.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import top.kdxiaoyi.mod.getskeeper.pickup.PickupCandidate;
import top.kdxiaoyi.mod.getskeeper.security.SessionGuards;
import top.kdxiaoyi.mod.getskeeper.source.ItemSource;
import top.kdxiaoyi.mod.getskeeper.source.SourceFormatter;
import top.kdxiaoyi.mod.getskeeper.source.SourceType;
import top.kdxiaoyi.mod.getskeeper.trust.TrustLevel;

/**
 * Networking setup and the server side send helpers.
 *
 * <p>Server to client payloads are registered here without handlers: their handlers live in
 * {@code client.GetsKeeperClient} and are attached through NeoForge's client-only
 * {@code RegisterClientPayloadHandlersEvent}. That split is what lets this mod keep its client code
 * in the main source set without the dedicated server ever loading a screen class.
 *
 * <p>Client to server payloads get their handler right here, because those handlers are ordinary
 * server side code.
 */
public final class GKNetwork {
    /** Protocol version. Bump this whenever a payload changes shape. */
    private static final String VERSION = "2";

    private GKNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(GKNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToClient(GKPayloads.OpenPickupScreen.TYPE, GKPayloads.OpenPickupScreen.STREAM_CODEC);
        registrar.playToClient(GKPayloads.VerificationStart.TYPE, GKPayloads.VerificationStart.STREAM_CODEC);
        registrar.playToClient(GKPayloads.VerificationResult.TYPE, GKPayloads.VerificationResult.STREAM_CODEC);
        registrar.playToClient(GKPayloads.PasswordPrompt.TYPE, GKPayloads.PasswordPrompt.STREAM_CODEC);
        registrar.playToClient(GKPayloads.PasswordResult.TYPE, GKPayloads.PasswordResult.STREAM_CODEC);
        registrar.playToClient(GKPayloads.SessionBlocked.TYPE, GKPayloads.SessionBlocked.STREAM_CODEC);

        registrar.playToServer(GKPayloads.PickupDecision.TYPE, GKPayloads.PickupDecision.STREAM_CODEC,
                GKServerHandler::onPickupDecision);
        registrar.playToServer(GKPayloads.UseDecision.TYPE, GKPayloads.UseDecision.STREAM_CODEC,
                GKServerHandler::onUseDecision);
        registrar.playToServer(GKPayloads.PasswordSubmit.TYPE, GKPayloads.PasswordSubmit.STREAM_CODEC,
                GKServerHandler::onPasswordSubmit);
        registrar.playToServer(GKPayloads.PasswordCancel.TYPE, GKPayloads.PasswordCancel.STREAM_CODEC,
                GKServerHandler::onPasswordCancel);
        registrar.playToServer(GKPayloads.RequestSessionData.TYPE, GKPayloads.RequestSessionData.STREAM_CODEC,
                GKServerHandler::onRequestSessionData);
        registrar.playToServer(GKPayloads.RequestBlockedUse.TYPE, GKPayloads.RequestBlockedUse.STREAM_CODEC,
                GKServerHandler::onRequestBlockedUse);
    }

    // --- send helpers -------------------------------------------------------------------------

    /** Offers a pickup batch to a single player. */
    public static void askPickupScreen(ServerPlayer player, List<PickupCandidate> candidates, boolean inventoryFull) {
        List<ItemStack> stacks = new ArrayList<>(candidates.size());
        List<String> itemIds = new ArrayList<>(candidates.size());
        for (PickupCandidate candidate : candidates) {
            stacks.add(candidate.stack());
            itemIds.add(candidate.itemId());
        }
        send(player, new GKPayloads.OpenPickupScreen(stacks, itemIds, inventoryFull, player.getScoreboardName()));
    }

    /** Starts the verification overlay on the client. */
    public static void startVerification(ServerPlayer player, ItemStack stack, int durationTicks) {
        send(player, new GKPayloads.VerificationStart(stack.copy(), durationTicks));
    }

    /** Delivers the verdict, including where the stack came from. */
    public static void sendVerificationResult(ServerPlayer player, ItemStack stack, TrustLevel trust,
            ItemSource source, String acquiredBy, long acquiredAt) {
        ItemSource effective = source != null
                ? source
                : ItemSource.locationless(player.level(), SourceType.UNKNOWN, "");
        SourceFormatter.SourceText text = SourceFormatter.of(effective);
        send(player, new GKPayloads.VerificationResult(
                stack.copy(),
                trust.ordinal(),
                text.key(),
                text.label(),
                text.coordinates(),
                text.dimension(),
                acquiredAt,
                acquiredBy == null ? "" : acquiredBy));
    }

    /** Asks the client for a password, telling it the length the server will accept. */
    public static void sendPasswordPrompt(ServerPlayer player, PasswordPurpose purpose, String itemId,
            String suggestedUsername, boolean mandatory, int minLength) {
        send(player, new GKPayloads.PasswordPrompt(purpose.ordinal(), itemId, suggestedUsername, mandatory,
                minLength));
    }

    /** Reports the outcome of a password attempt. */
    public static void sendPasswordResult(ServerPlayer player, PasswordPurpose purpose, String itemId,
            boolean success, boolean kick) {
        send(player, new GKPayloads.PasswordResult(purpose.ordinal(), itemId, success, kick));
    }

    /** Pushes the session list the privacy screen shows at the bottom. */
    public static void sendSessionBlocked(ServerPlayer player) {
        send(player, new GKPayloads.SessionBlocked(SessionGuards.list(player.getUUID())));
    }

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
