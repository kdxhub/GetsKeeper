package top.kdxiaoyi.mod.getskeeper.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A flat, rounded button in the style of the rest of the mod.
 *
 * <p>Two variants: the primary button is filled with the accent blue, the secondary one is a pale
 * grey capsule. Both are drawn from {@link MacTheme}, and both stay legible when disabled.
 */
public final class MacButton extends AbstractWidget {
    private final Runnable action;
    private final boolean primary;
    private final int fillColor;
    private final int textColor;

    public MacButton(int x, int y, int width, int height, Component message, boolean primary, Runnable action) {
        this(x, y, width, height, message, primary, MacTheme.SURFACE_ELEVATED, MacTheme.TEXT_PRIMARY, action);
    }

    /** A button with explicit colours, used for the destructive "move to the void" choice. */
    public MacButton(int x, int y, int width, int height, Component message, boolean primary, int fillColor,
            int textColor, Runnable action) {
        super(x, y, width, height, message);
        this.primary = primary;
        this.fillColor = fillColor;
        this.textColor = textColor;
        this.action = action;
    }

    public static MacButton destructive(int x, int y, int width, int height, Component message, Runnable action) {
        return new MacButton(x, y, width, height, message, false, 0xFFFFE5E3, MacTheme.DANGER, action);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = super.mouseClicked(event, doubleClick);
        if (this.active && this.visible && event.button() == 0 && isMouseOver(event.x(), event.y())) {
            action.run();
            return true;
        }
        return handled;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.active && isMouseOver(mouseX, mouseY);
        int background;
        if (!this.active) {
            background = 0xFFEDEDF0;
        } else if (primary) {
            background = hovered ? MacTheme.ACCENT_DARK : MacTheme.ACCENT;
        } else if (fillColor != MacTheme.SURFACE_ELEVATED) {
            // A custom fill (the destructive "move to the void" choice) keeps its tint and only
            // reacts to hovering.
            background = hovered ? darken(fillColor) : fillColor;
        } else {
            background = hovered ? 0xFFEDEDF2 : fillColor;
        }

        MacTheme.fillRounded(graphics, getX(), getY(), getWidth(), getHeight(), MacTheme.CONTROL_RADIUS, background);
        int label = this.active ? (primary ? MacTheme.TEXT_ON_ACCENT : textColor) : MacTheme.TEXT_SECONDARY;
        graphics.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2 + 1, label);
    }

    private static int darken(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = Math.max(0, (int) (((argb >> 16) & 0xFF) * 0.94F));
        int green = Math.max(0, (int) (((argb >> 8) & 0xFF) * 0.94F));
        int blue = Math.max(0, (int) ((argb & 0xFF) * 0.94F));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
