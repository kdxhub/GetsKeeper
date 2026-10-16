package top.kdxiaoyi.mod.getskeeper.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The look of every GetsKeeper screen.
 *
 * <p>A flat, light, macOS-flavoured palette: white cards on a dimmed world, hairline separators, one
 * accent blue, one system green for switches. Nothing here is glassy or animated; the goal is that
 * the screens read as one family and stay legible over any world.
 *
 * <p>All shapes are filled row by row with {@code GuiGraphics.fill}, so the mod depends on no GUI
 * sprites, no textures and no shaders of its own. The one exception is the verification progress bar,
 * which deliberately reuses the vanilla experience bar sprite.
 */
public final class MacTheme {
    /** System blue, used for primary buttons and selection. */
    public static final int ACCENT = 0xFF0A84FF;
    public static final int ACCENT_DARK = 0xFF0060DF;
    public static final int ACCENT_DISABLED = 0xFFB9D9FF;

    /** Card colours. */
    public static final int SURFACE = 0xFFF7F7F9;
    public static final int SURFACE_ELEVATED = 0xFFFFFFFF;
    public static final int SURFACE_BORDER = 0x1A000000;
    public static final int SEPARATOR = 0x14000000;

    /** Hairline borders. */
    public static final int HAIRLINE = 0x26000000;

    /** Text colours. */
    public static final int TEXT_PRIMARY = 0xFF1D1D1F;
    public static final int TEXT_SECONDARY = 0xFF6E6E73;
    public static final int TEXT_ON_ACCENT = 0xFFFFFFFF;

    /** Controls. */
    public static final int SWITCH_ON = 0xFF34C759;
    public static final int SWITCH_OFF = 0xFFD8D8DC;
    public static final int SWITCH_KNOB = 0xFFFFFFFF;
    public static final int SCRIM = 0x66000000;
    public static final int WARNING = 0xFFFF9F0A;
    public static final int DANGER = 0xFFFF3B30;

    /** Corner radius used for cards. */
    public static final int CARD_RADIUS = 10;
    public static final int CONTROL_RADIUS = 6;
    public static final int BUTTON_HEIGHT = 20;

    private MacTheme() {}

    /** Draws a rectangle with rounded corners. */
    public static void fillRounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int effective = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        for (int row = 0; row < height; row++) {
            int inset = 0;
            if (row < effective) {
                inset = cornerInset(effective, row);
            } else if (row >= height - effective) {
                inset = cornerInset(effective, height - 1 - row);
            }
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }

    /** Draws a rounded card with a hairline border. */
    public static void card(GuiGraphics graphics, int x, int y, int width, int height, int radius, int fill, int border) {
        fillRounded(graphics, x, y, width, height, radius, border);
        fillRounded(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fill);
    }

    /** Draws a filled circle by stacking rows, for switch knobs. */
    public static void fillCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int offset = -radius; offset <= radius; offset++) {
            int halfWidth = (int) Math.sqrt(Math.max(0, radius * radius - offset * offset));
            if (halfWidth > 0) {
                graphics.fill(centerX - halfWidth, centerY + offset, centerX + halfWidth, centerY + offset + 1, color);
            }
        }
    }

    private static int cornerInset(int radius, int row) {
        int distance = radius - 1 - row;
        double value = Math.sqrt(Math.max(0, radius * radius - distance * distance));
        return (int) Math.round(radius - value);
    }

    // --- text ---------------------------------------------------------------------------------

    public static void text(GuiGraphics graphics, Font font, Component component, int x, int y, int color) {
        graphics.drawString(font, component, x, y, color, false);
    }

    public static void textWithShadow(GuiGraphics graphics, Font font, Component component, int x, int y, int color) {
        graphics.drawString(font, component, x, y, color, true);
    }

    public static void centered(GuiGraphics graphics, Font font, Component component, int centerX, int y, int color) {
        graphics.drawCenteredString(font, component, centerX, y, color);
    }

    /** Draws wrapped text and returns the height it used. */
    public static int paragraph(GuiGraphics graphics, Font font, Component component, int x, int y, int width, int color) {
        int lineY = y;
        for (var line : font.split(component, width)) {
            graphics.drawString(font, line, x, lineY, color, false);
            lineY += font.lineHeight + 2;
        }
        return lineY - y;
    }

    /** {@return the height wrapped text would use} */
    public static int paragraphHeight(Font font, Component component, int width) {
        return font.split(component, width).size() * (font.lineHeight + 2);
    }

    // --- sizing helpers ----------------------------------------------------------------------

    /** {@return the width of the widest wrapped line} */
    public static int widestLine(Font font, Component component, int width) {
        int widest = 0;
        for (var line : font.split(component, width)) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }
}
