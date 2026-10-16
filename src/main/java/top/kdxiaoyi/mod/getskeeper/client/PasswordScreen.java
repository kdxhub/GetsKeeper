package top.kdxiaoyi.mod.getskeeper.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import top.kdxiaoyi.mod.getskeeper.net.GKPayloads;
import top.kdxiaoyi.mod.getskeeper.net.PasswordPurpose;

/**
 * The password prompt.
 *
 * <p>A custom screen rather than a chat prompt, because the chat bar cannot hide what is typed. The
 * password field is masked with an {@code EditBox} formatter and carries no hint text at all, so the
 * width of what is typed never leaks.
 *
 * <p>The user name field is pre-filled with the player's own name for operators and left empty for
 * everybody else: an operator is the person whose password the screen is normally checking.
 */
public class PasswordScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int FIELD_WIDTH = 236;
    private static final int FIELD_HEIGHT = 20;

    private final PasswordPurpose purpose;
    private final String itemId;
    private final boolean mandatory;

    private EditBox usernameField;
    private EditBox passwordField;
    private Component error;

    private int panelX;
    private int panelY;
    private int panelHeight;

    /** The instance that is currently on screen, so a password result can reach it. */
    private static PasswordScreen active;

    public PasswordScreen(PasswordPurpose purpose, String itemId, String suggestedUsername, boolean mandatory) {
        super(Component.translatable("getskeeper.password.title"));
        this.purpose = purpose;
        this.itemId = itemId == null ? "" : itemId;
        this.mandatory = mandatory;
        this.suggestedUsername = suggestedUsername == null ? "" : suggestedUsername;
    }

    private final String suggestedUsername;

    @Override
    protected void init() {
        active = this;
        panelHeight = 190;
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = Math.max(20, (height - panelHeight) / 2);

        int fieldX = panelX + (PANEL_WIDTH - FIELD_WIDTH) / 2;
        int fieldsTop = panelY + 76;

        usernameField = new EditBox(font, fieldX, fieldsTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("getskeeper.password.username"));
        usernameField.setMaxLength(32);
        usernameField.setValue(suggestedUsername);
        usernameField.setHint(Component.translatable("getskeeper.password.username"));
        usernameField.setEditable(purpose != PasswordPurpose.SET_PASSWORD);
        addRenderableWidget(usernameField);

        // No hint and a formatter that replaces every character: the field never shows what was typed.
        passwordField = new EditBox(font, fieldX, fieldsTop + 46, FIELD_WIDTH, FIELD_HEIGHT, Component.empty());
        passwordField.setMaxLength(64);
        passwordField.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(passwordField);
        setInitialFocus(passwordField);

        int buttonY = panelY + panelHeight - 34;
        addRenderableWidget(new MacButton(panelX + PANEL_WIDTH - 16 - 84, buttonY, 84, MacTheme.BUTTON_HEIGHT,
                Component.translatable("getskeeper.password.submit"), true, this::submit));
        addRenderableWidget(new MacButton(panelX + PANEL_WIDTH - 16 - 84 - 8 - 84, buttonY, 84, MacTheme.BUTTON_HEIGHT,
                Component.translatable("getskeeper.password.cancel"), false, this::cancel));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MacTheme.card(graphics, panelX, panelY, PANEL_WIDTH, panelHeight, MacTheme.CARD_RADIUS,
                MacTheme.SURFACE, MacTheme.SURFACE_BORDER);

        Component headline = headline();
        int cursorY = panelY + 16;
        for (FormattedCharSequence line : font.split(headline, PANEL_WIDTH - 32)) {
            graphics.drawCenteredString(font, line, panelX + PANEL_WIDTH / 2, cursorY, MacTheme.TEXT_PRIMARY);
            cursorY += font.lineHeight + 2;
        }

        MacTheme.text(graphics, font, Component.translatable("getskeeper.password.username_label"),
                panelX + 32, panelY + 64, MacTheme.TEXT_SECONDARY);
        MacTheme.text(graphics, font, Component.translatable("getskeeper.password.password_label"),
                panelX + 32, panelY + 110, MacTheme.TEXT_SECONDARY);

        if (error != null) {
            MacTheme.text(graphics, font, error, panelX + 32, panelY + panelHeight - 52, MacTheme.DANGER);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Component headline() {
        return switch (purpose) {
            case SET_PASSWORD -> mandatory
                    ? Component.translatable("getskeeper.password.setup_required")
                    : Component.translatable("getskeeper.password.setup");
            case AUTHORIZE_ITEM -> Component.translatable("getskeeper.password.authorize", itemId);
            case GRANT_BLOCKED_USE -> Component.translatable("getskeeper.password.use_anyway", itemId);
            case TOGGLE_AUTHORIZATION -> Component.translatable("getskeeper.password.toggle", itemId);
        };
    }

    private void submit() {
        String username = usernameField.getValue().trim();
        String password = passwordField.getValue();
        if (password.isEmpty()) {
            error = Component.translatable("getskeeper.password.empty");
            return;
        }
        PacketDistributor.sendToServer(new GKPayloads.PasswordSubmit(purpose.ordinal(), itemId, username, password));
        passwordField.setValue("");
    }

    private void cancel() {
        PacketDistributor.sendToServer(new GKPayloads.PasswordCancel(purpose.ordinal()));
        onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !mandatory;
    }

    @Override
    public void removed() {
        if (active == this) {
            active = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Called by the client payload handler when the server answered. */
    public static void handleResult(PasswordPurpose purpose, boolean success) {
        PasswordScreen screen = active;
        if (screen == null || screen.purpose != purpose) {
            return;
        }
        if (success) {
            screen.onClose();
        } else {
            screen.error = Component.translatable("getskeeper.password.rejected").withStyle(ChatFormatting.RED);
        }
    }
}
