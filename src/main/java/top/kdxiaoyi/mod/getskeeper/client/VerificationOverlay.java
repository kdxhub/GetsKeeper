package top.kdxiaoyi.mod.getskeeper.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.gui.GuiLayer;


/**
 * The "verifying ..." overlay.
 *
 * <p>A line of text with the vanilla experience bar underneath it, drawn with the vanilla experience
 * bar sprites so it reads as part of the HUD rather than as a mod panel. The bar is filled by clipping
 * a full width copy of the progress sprite, which is exactly how vanilla draws a partial bar and keeps
 * the texture from stretching.
 */
public final class VerificationOverlay implements GuiLayer {
    /** The vanilla experience bar sprite, confirmed against the 1.21.11 GUI atlas. */
    private static final Identifier BAR_BACKGROUND = Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final Identifier BAR_PROGRESS = Identifier.withDefaultNamespace("hud/experience_bar_progress");

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;

    private static ItemStack verifying = ItemStack.EMPTY;
    private static long startedAt;
    private static long durationMillis;

    public static final VerificationOverlay INSTANCE = new VerificationOverlay();

    private VerificationOverlay() {}

    /** Registers the overlay on the client. */
    public static void begin(ItemStack stack, int durationTicks) {
        verifying = stack.copy();
        startedAt = System.currentTimeMillis();
        // One tick is 50 ms at full speed; the server already waited this long.
        durationMillis = Math.max(250L, durationTicks * 50L);
    }

    /** Called when the verdict arrives, so the bar disappears exactly when the dialog opens. */
    public static void finish() {
        verifying = ItemStack.EMPTY;
    }

    public static boolean isRunning() {
        return !verifying.isEmpty();
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        // While a screen is open this layer is drawn *behind* it - vanilla renders the HUD before the
        // screen, and NeoForge's screen hook renders this layer even earlier than that - so the screen
        // variant of this method is what puts the bar on top. See GetsKeeperClient.
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        draw(graphics);
    }

    /** Draws the bar on top of the open screen, which is what the HUD layer cannot do. */
    public void renderOverScreen(GuiGraphics graphics) {
        if (Minecraft.getInstance().screen != null) {
            draw(graphics);
        }
    }

    private void draw(GuiGraphics graphics) {
        if (verifying.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        float progress = Mth.clamp(elapsed / (float) durationMillis, 0.0F, 1.0F);

        int centerX = graphics.guiWidth() / 2;
        int barX = centerX - BAR_WIDTH / 2;
        int barY = graphics.guiHeight() / 2 + 24;

        Component label = Component.translatable("getskeeper.verify.in_progress", verifying.getHoverName());
        // The one label in the mod that keeps vanilla's shadow: it is drawn straight over the world.
        graphics.drawCenteredString(minecraft.font, label, centerX, barY - 14, 0xFFF2F2F2);

        // The bar sits on a dark plate so it stays readable over bright terrain.
        MacTheme.fillRounded(graphics, barX - 3, barY - 3, BAR_WIDTH + 6, BAR_HEIGHT + 6, 4, 0x66000000);

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_BACKGROUND, barX, barY, BAR_WIDTH, BAR_HEIGHT);

        int filled = Math.round(progress * BAR_WIDTH);
        if (filled > 0) {
            graphics.enableScissor(barX, barY, barX + filled, barY + BAR_HEIGHT);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_PROGRESS, barX, barY, BAR_WIDTH, BAR_HEIGHT);
            graphics.disableScissor();
        }
    }
}
