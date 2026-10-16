package top.kdxiaoyi.mod.getskeeper.client;


import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

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
 *
 * <p>When the screen is asking the player to <em>choose</em> a password it states the minimum length
 * the server enforces, and refuses a shorter one itself. The server sends that number with the
 * prompt, so what the screen shows is what the server will actually accept - being told only
 * "the password was not accepted" after a too-short attempt looks exactly like a wrong password.
 */
public class PasswordScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int FIELD_WIDTH = 236;
    private static final int FIELD_HEIGHT = 20;
    private static final int PANEL_HEIGHT = 200;

    /** Rows, relative to the top of the panel. */
    private static final int HEADLINE_Y = 16;
    private static final int USERNAME_LABEL_Y = 64;
    private static final int USERNAME_FIELD_Y = 76;
    private static final int PASSWORD_LABEL_Y = 106;
    private static final int PASSWORD_FIELD_Y = 118;
    private static final int ERROR_Y = 148;

    private final PasswordPurpose purpose;
    private final String itemId;
    private final boolean mandatory;
    private final int minLength;
    private final String suggestedUsername;

    private EditBox usernameField;
    private EditBox passwordField;
    private Component error;

    private int panelX;
    private int panelY;

    /** The instance that is currently on screen, so a password result can reach it. */
    private static PasswordScreen active;

    /** For a prompt that is asking for an existing password, where no length rule applies. */
    public PasswordScreen(PasswordPurpose purpose, String itemId, String suggestedUsername, boolean mandatory) {
        this(purpose, itemId, suggestedUsername, mandatory, 0);
    }

    public PasswordScreen(PasswordPurpose purpose, String itemId, String suggestedUsername, boolean mandatory,
            int minLength) {
        super(Component.translatable("getskeeper.password.title"));
        this.purpose = purpose;
        this.itemId = itemId == null ? "" : itemId;
        this.mandatory = mandatory;
        this.minLength = minLength;
        this.suggestedUsername = suggestedUsername == null ? "" : suggestedUsername;
    }

    /** {@return whether this screen is asking the player to pick a password rather than recall one} */
    private boolean choosingPassword() {
        return purpose == PasswordPurpose.SET_PASSWORD;
    }

    @Override
    protected void init() {
        active = this;
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = Math.max(20, (height - PANEL_HEIGHT) / 2);

        int fieldX = panelX + (PANEL_WIDTH - FIELD_WIDTH) / 2;

        usernameField = new EditBox(font, fieldX, panelY + USERNAME_FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("getskeeper.password.username"));
        usernameField.setMaxLength(32);
        usernameField.setValue(suggestedUsername);
        usernameField.setHint(Component.translatable("getskeeper.password.username"));
        usernameField.setEditable(!choosingPassword());
        addRenderableWidget(usernameField);

        // No hint and a formatter that replaces every character: the field never shows what was typed.
        passwordField = new EditBox(font, fieldX, panelY + PASSWORD_FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT,
                Component.empty());
        passwordField.setMaxLength(64);
        passwordField.addFormatter((text, offset) ->
                FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(passwordField);
        setInitialFocus(passwordField);

        int buttonY = panelY + PANEL_HEIGHT - 34;
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
        MacTheme.card(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, MacTheme.CARD_RADIUS,
                MacTheme.SURFACE, MacTheme.SURFACE_BORDER);

        int cursorY = panelY + HEADLINE_Y;
        for (FormattedCharSequence line : font.split(headline(), PANEL_WIDTH - 32)) {
            MacTheme.centered(graphics, font, line, panelX + PANEL_WIDTH / 2, cursorY, MacTheme.TEXT_PRIMARY);
            cursorY += font.lineHeight + 2;
        }

        MacTheme.text(graphics, font, Component.translatable("getskeeper.password.username_label"),
                panelX + 32, panelY + USERNAME_LABEL_Y, MacTheme.TEXT_SECONDARY);
        MacTheme.text(graphics, font, passwordLabel(), panelX + 32, panelY + PASSWORD_LABEL_Y,
                MacTheme.TEXT_SECONDARY);

        if (error != null) {
            MacTheme.text(graphics, font, error, panelX + 32, panelY + ERROR_Y, MacTheme.DANGER);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** {@return the label above the password field, which states the rule when choosing one} */
    private Component passwordLabel() {
        return choosingPassword()
                ? Component.translatable("getskeeper.password.password_label_min", minLength)
                : Component.translatable("getskeeper.password.password_label");
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
            error = failure("getskeeper.password.empty");
            return;
        }
        // Only when choosing a password: a password being recalled was stored under whatever rule was
        // in force then, so its length is not this screen's business.
        if (choosingPassword() && password.length() < minLength) {
            error = failure(Component.translatable("getskeeper.password.too_short", minLength));
            return;
        }
        ClientPacketDistributor.sendToServer(new GKPayloads.PasswordSubmit(purpose.ordinal(), itemId, username, password));
        passwordField.setValue("");
    }

    private static Component failure(String key) {
        return failure(Component.translatable(key));
    }

    private static Component failure(Component message) {
        return message.copy().withStyle(ChatFormatting.RED);
    }

    private void cancel() {
        ClientPacketDistributor.sendToServer(new GKPayloads.PasswordCancel(purpose.ordinal()));
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
            screen.error = failure("getskeeper.password.rejected");
        }
    }
}
