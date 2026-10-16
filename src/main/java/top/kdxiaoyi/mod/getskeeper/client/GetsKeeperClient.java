package top.kdxiaoyi.mod.getskeeper.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.net.GKPayloads;

/**
 * The client half of GetsKeeper.
 *
 * <p>Declared with {@code dist = Dist.CLIENT}, so FML never even loads this class on a dedicated
 * server and none of the screen code can leak into the server process.
 *
 * <p>Four things happen here: the client payload handlers are attached, the verification overlay is
 * registered as a HUD layer just above the hotbar and also drawn on top of any open screen, and the
 * privacy page gets its entry point on the pause menu.
 */
@Mod(value = GetsKeeper.MOD_ID, dist = Dist.CLIENT)
public final class GetsKeeperClient {
    public GetsKeeperClient(IEventBus modBus) {
        modBus.addListener(GetsKeeperClient::onRegisterClientPayloads);
        modBus.addListener(GetsKeeperClient::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.addListener(GetsKeeperClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(GetsKeeperClient::onScreenRender);
    }

    private static void onRegisterClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(GKPayloads.OpenPickupScreen.TYPE, GKClientPayloadHandler::onOpenPickupScreen);
        event.register(GKPayloads.VerificationStart.TYPE, GKClientPayloadHandler::onVerificationStart);
        event.register(GKPayloads.VerificationResult.TYPE, GKClientPayloadHandler::onVerificationResult);
        event.register(GKPayloads.PasswordPrompt.TYPE, GKClientPayloadHandler::onPasswordPrompt);
        event.register(GKPayloads.PasswordResult.TYPE, GKClientPayloadHandler::onPasswordResult);
        event.register(GKPayloads.SessionBlocked.TYPE, GKClientPayloadHandler::onSessionBlocked);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // Above the hotbar so the bar lands where the experience bar would be, and above everything
        // else so a dialog cannot cover it up.
        event.registerAboveAll(GetsKeeper.id("verification_overlay"), VerificationOverlay.INSTANCE);
    }

    /**
     * Draws the verification bar over an open screen.
     *
     * <p>The HUD layer is not enough on its own: vanilla renders the HUD before the screen, so the bar
     * ended up behind the inventory and every other screen. This runs after the screen has drawn.
     */
    private static void onScreenRender(ScreenEvent.Render.Post event) {
        VerificationOverlay.INSTANCE.renderOverScreen(event.getGuiGraphics());
    }

    /** Adds the "Privacy & Security" entry to the pause menu. */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof PauseScreen pauseScreen)) {
            return;
        }
        event.addListener(new MacButton(6, screen.height - 26, 128, MacTheme.BUTTON_HEIGHT,
                Component.translatable("getskeeper.privacy.button"), false,
                () -> Minecraft.getInstance().setScreen(new PrivacySecurityScreen(pauseScreen))));
    }
}
