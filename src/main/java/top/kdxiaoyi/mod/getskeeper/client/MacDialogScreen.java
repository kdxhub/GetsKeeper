package top.kdxiaoyi.mod.getskeeper.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The one dialog used by every prompt in the mod.
 *
 * <p>GateKeeper-style prompts all have the same shape - an optional glyph, a headline, a body, a
 * smaller attribution line, and up to two choices - so they all share one screen rather than a class
 * each. The wording is entirely up to the caller, which is how the mod keeps every string in the
 * language files.
 */
public class MacDialogScreen extends Screen {
    /** One button on the dialog. */
    public record Choice(Component label, boolean primary, boolean destructive, Runnable action) {
        public static Choice primary(Component label, Runnable action) {
            return new Choice(label, true, false, action);
        }

        public static Choice secondary(Component label, Runnable action) {
            return new Choice(label, false, false, action);
        }

        public static Choice destructive(Component label, Runnable action) {
            return new Choice(label, false, true, action);
        }
    }

    private static final int MAX_PANEL_WIDTH = 268;
    private static final int MIN_PANEL_WIDTH = 200;
    private static final int PADDING = 16;
    private static final int BUTTON_WIDTH = 76;
    private static final int ICON_SIZE = 32;

    protected final Component body;
    protected final Component attribution;
    protected final ItemStack icon;
    protected final boolean warning;
    protected final boolean closable;
    protected final List<Choice> choices;

    /**
     * The choice Escape stands in for, and whether a choice has already been taken.
     *
     * <p>Escape is not a silent dismissal: it takes the same action as the safe button, which for
     * every dialog in this mod is the non-destructive one ("Cancel", "Done", "OK"). Closing without
     * deciding would leave the server waiting for an answer and the intercepted item in limbo.
     */
    private Choice dismissChoice;
    private boolean answered;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    protected MacDialogScreen(Builder builder) {
        super(builder.title);
        this.body = builder.body;
        this.attribution = builder.attribution;
        this.icon = builder.icon;
        this.warning = builder.warning;
        this.closable = builder.closable;
        this.choices = List.copyOf(builder.choices);
    }

    public static Builder builder(Component title) {
        return new Builder(title);
    }

    /** Fluent builder; a dialog with no choices is closable by design. */
    public static final class Builder {
        private final Component title;
        private Component body;
        private Component attribution;
        private ItemStack icon = ItemStack.EMPTY;
        private boolean warning;
        private boolean closable = true;
        private final List<Choice> choices = new ArrayList<>();

        private Builder(Component title) {
            this.title = title;
        }

        public Builder body(Component body) {
            this.body = body;
            return this;
        }

        public Builder attribution(Component attribution) {
            this.attribution = attribution;
            return this;
        }

        public Builder icon(ItemStack icon) {
            this.icon = icon == null ? ItemStack.EMPTY : icon;
            return this;
        }

        public Builder warning() {
            this.warning = true;
            return this;
        }

        public Builder notClosable() {
            this.closable = false;
            return this;
        }

        public Builder choice(Choice choice) {
            this.choices.add(choice);
            return this;
        }

        public MacDialogScreen build() {
            if (this.choices.isEmpty()) {
                this.closable = true;
            }
            return new MacDialogScreen(this);
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.max(MIN_PANEL_WIDTH, Math.min(MAX_PANEL_WIDTH, this.width - 40));
        int innerWidth = panelWidth - PADDING * 2;

        int height = PADDING;
        if (warning || !icon.isEmpty()) {
            height += ICON_SIZE + 10;
        }
        height += MacTheme.paragraphHeight(font, title, innerWidth) + 8;
        if (body != null) {
            height += MacTheme.paragraphHeight(font, body, innerWidth) + 8;
        }
        if (attribution != null) {
            height += MacTheme.paragraphHeight(font, attribution, innerWidth) + 8;
        }
        if (!choices.isEmpty()) {
            height += MacTheme.BUTTON_HEIGHT + 12;
        }
        panelHeight = height + PADDING - 4;

        panelX = (this.width - panelWidth) / 2;
        panelY = Math.max(16, (this.height - panelHeight) / 2);

        layoutButtons();
    }

    private void layoutButtons() {
        if (choices.isEmpty()) {
            return;
        }
        int count = choices.size();
        int totalWidth = count * BUTTON_WIDTH + (count - 1) * 8;
        int startX = panelX + panelWidth - PADDING - totalWidth;
        // A single choice reads better centred under the text than pinned to the right edge.
        if (count == 1) {
            startX = panelX + (panelWidth - BUTTON_WIDTH) / 2;
        }
        int y = panelY + panelHeight - PADDING - MacTheme.BUTTON_HEIGHT;

        for (int index = 0; index < count; index++) {
            Choice choice = choices.get(index);
            int x = startX + index * (BUTTON_WIDTH + 8);
            // Every button closes the dialog once it has acted. Forgetting that is why the dialogs
            // used to stay on screen after a click.
            Runnable action = () -> choose(choice);
            if (!choice.destructive() && dismissChoice == null) {
                dismissChoice = choice;
            }
            MacButton button = choice.destructive()
                    ? MacButton.destructive(x, y, BUTTON_WIDTH, MacTheme.BUTTON_HEIGHT, choice.label(), action)
                    : new MacButton(x, y, BUTTON_WIDTH, MacTheme.BUTTON_HEIGHT, choice.label(), choice.primary(),
                            action);
            addRenderableWidget(button);
        }
    }

    /** Runs a choice and closes the dialog, exactly once. */
    private void choose(Choice choice) {
        if (answered) {
            return;
        }
        answered = true;
        try {
            choice.action().run();
        } finally {
            close();
        }
    }

    private void close() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Dim rather than blur: the world stays readable so the player can see what is being asked about.
        renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics);

        int innerWidth = panelWidth - PADDING * 2;
        int cursorY = panelY + PADDING;

        if (warning) {
            drawWarningTriangle(graphics, panelX + (panelWidth - ICON_SIZE) / 2, cursorY, ICON_SIZE);
            cursorY += ICON_SIZE + 10;
        } else if (!icon.isEmpty()) {
            graphics.renderItem(icon, panelX + (panelWidth - 16) / 2, cursorY + (ICON_SIZE - 16) / 2);
            cursorY += ICON_SIZE + 10;
        }

        for (var line : font.split(title, innerWidth)) {
            MacTheme.centered(graphics, font, line, panelX + panelWidth / 2, cursorY, MacTheme.TEXT_PRIMARY);
            cursorY += font.lineHeight + 2;
        }
        cursorY += 6;

        if (body != null) {
            cursorY += MacTheme.paragraph(graphics, font, body, panelX + PADDING, cursorY, innerWidth,
                    MacTheme.TEXT_SECONDARY);
            cursorY += 8;
        }
        if (attribution != null) {
            cursorY += MacTheme.paragraph(graphics, font, attribution, panelX + PADDING, cursorY, innerWidth,
                    MacTheme.TEXT_SECONDARY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPanel(GuiGraphics graphics) {
        MacTheme.card(graphics, panelX, panelY, panelWidth, panelHeight, MacTheme.CARD_RADIUS,
                MacTheme.SURFACE, MacTheme.SURFACE_BORDER);
    }

    /** The triangle warning glyph used by the full-inventory prompt. */
    private void drawWarningTriangle(GuiGraphics graphics, int x, int y, int size) {
        int centerX = x + size / 2;
        int rows = size - 6;
        for (int row = 0; row < rows; row++) {
            int halfWidth = (int) Math.round((row + 1) * (size / 2.0) / rows);
            graphics.fill(centerX - halfWidth, y + row + 3, centerX + halfWidth, y + row + 4, MacTheme.WARNING);
        }
        graphics.fill(centerX - 1, y + 12, centerX + 1, y + size - 6, MacTheme.SURFACE_ELEVATED);
        graphics.fill(centerX - 1, y + size - 6, centerX + 1, y + size - 4, MacTheme.SURFACE_ELEVATED);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return closable;
    }

    /**
     * Escape acts as the safe choice rather than as a dismissal.
     *
     * <p>Vanilla's screen already routes Escape here, and only when {@link #shouldCloseOnEsc()} allows
     * it, so a dialog marked {@link Builder#notClosable()} is unaffected.
     */
    @Override
    public void onClose() {
        if (!answered && dismissChoice != null) {
            choose(dismissChoice);
            return;
        }
        answered = true;
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
