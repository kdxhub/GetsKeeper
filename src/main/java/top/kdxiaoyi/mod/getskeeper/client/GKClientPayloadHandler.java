package top.kdxiaoyi.mod.getskeeper.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import top.kdxiaoyi.mod.getskeeper.net.GKPayloads;
import top.kdxiaoyi.mod.getskeeper.net.PasswordPurpose;

/**
 * Turns packets into screens.
 *
 * <p>Handlers hop onto the client thread first, so nothing here has to worry about being called from
 * the netty thread while the game is mid-frame. Nothing in this class decides anything: it renders
 * what the server said and sends the player's answer back.
 */
public final class GKClientPayloadHandler {
    private GKClientPayloadHandler() {}

    public static void onOpenPickupScreen(GKPayloads.OpenPickupScreen payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientGuardState.setPickup(payload.stacks(), payload.itemIds(), payload.inventoryFull(),
                    payload.ownerName());

            Minecraft minecraft = Minecraft.getInstance();
            if (payload.inventoryFull()) {
                // A full inventory means no image was handed over, so the warning is the only thing to
                // show. Setting it as the screen replaces the pickup screen, whose removal already
                // tells the server to leave everything alone - one acknowledgement closes it all.
                minecraft.setScreen(GKDialog.inventoryFull(payload.ownerName()));
            } else {
                minecraft.setScreen(new PickupScreen(payload.stacks(), payload.itemIds()));
            }
        });
    }

    public static void onVerificationStart(GKPayloads.VerificationStart payload, IPayloadContext context) {
        context.enqueueWork(() -> VerificationOverlay.begin(payload.stack(), payload.durationTicks()));
    }

    public static void onVerificationResult(GKPayloads.VerificationResult payload, IPayloadContext context) {
        context.enqueueWork(() -> GKDialog.showVerificationResult(
                payload.stack(),
                payload.trustOrdinal(),
                payload.acquiredBy(),
                payload.acquiredAt(),
                payload.sourceKey(),
                payload.sourceLabel(),
                payload.sourceCoordinates(),
                payload.sourceDimension()));
    }

    public static void onPasswordPrompt(GKPayloads.PasswordPrompt payload, IPayloadContext context) {
        context.enqueueWork(() -> GKDialog.showPasswordPrompt(
                PasswordPurpose.byOrdinal(payload.purpose()),
                payload.itemId(),
                payload.suggestedUsername(),
                payload.mandatory(),
                payload.minLength()));
    }

    public static void onPasswordResult(GKPayloads.PasswordResult payload, IPayloadContext context) {
        context.enqueueWork(() -> PasswordScreen.handleResult(
                PasswordPurpose.byOrdinal(payload.purpose()), payload.success()));
    }

    public static void onSessionBlocked(GKPayloads.SessionBlocked payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientGuardState.setSessionBlocked(payload.itemIds()));
    }
}
