package top.kdxiaoyi.mod.getskeeper.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * An Apple style sliding switch.
 *
 * <p>A rounded track that turns system green when on, with a white knob that slides across. The
 * switch is a real widget, so it participates in narration and focus like any vanilla control.
 *
 * <p>Flipping it does not change anything by itself: the callback decides, and the switch is only
 * moved once the change has actually been accepted. That is what lets the privacy screen ask for a
 * password first and leave the switch alone when the password is wrong.
 */
public final class MacToggle extends AbstractWidget {
    private static final int TRACK_WIDTH = 34;
    private static final int TRACK_HEIGHT = 16;
    private static final int KNOB_RADIUS = 6;

    private final Consumer<Boolean> onFlip;
    private boolean on;

    public MacToggle(int x, int y, boolean initial, Component message, Consumer<Boolean> onFlip) {
        super(x, y, TRACK_WIDTH, TRACK_HEIGHT, message);
        this.on = initial;
        this.onFlip = onFlip;
    }

    public boolean isOn() {
        return on;
    }

    /** Moves the switch without firing the callback. Used when the server is the authority. */
    public void setOn(boolean value) {
        this.on = value;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = super.mouseClicked(event, doubleClick);
        if (this.active && this.visible && event.button() == 0 && isMouseOver(event.x(), event.y())) {
            onFlip.accept(!on);
            return true;
        }
        return handled;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int trackColor = !this.active ? 0xFFE5E5EA : (on ? MacTheme.SWITCH_ON : MacTheme.SWITCH_OFF);
        MacTheme.fillRounded(graphics, getX(), getY(), TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2, trackColor);

        int knobX = on ? getX() + TRACK_WIDTH - 1 - KNOB_RADIUS - 2 : getX() + 2 + KNOB_RADIUS;
        MacTheme.fillCircle(graphics, knobX, getY() + TRACK_HEIGHT / 2, KNOB_RADIUS,
                this.active ? MacTheme.SWITCH_KNOB : 0xFFEFEFF4);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(NarratedElementType.USAGE, on ? Component.translatable("getskeeper.toggle.on")
                : Component.translatable("getskeeper.toggle.off"));
    }
}
