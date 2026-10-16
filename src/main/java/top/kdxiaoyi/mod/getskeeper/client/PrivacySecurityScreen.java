package top.kdxiaoyi.mod.getskeeper.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import top.kdxiaoyi.mod.getskeeper.data.AuthorizationRecord;
import top.kdxiaoyi.mod.getskeeper.data.GKAttachments;
import top.kdxiaoyi.mod.getskeeper.net.GKPayloads;
import top.kdxiaoyi.mod.getskeeper.net.PasswordPurpose;

/**
 * The privacy and security page, reached from the pause menu.
 *
 * <p>Two lists:
 *
 * <ul>
 *   <li><b>Authorized items.</b> Every item this player has ever authorized, each with an Apple style
 *       switch. Flipping a switch needs a password, and the switch only moves once the server has
 *       accepted it. Turning one off does not delete the authorization - it marks it restricted, which
 *       is what the server records in its disabled set.</li>
 *   <li><b>Refused this session.</b> Everything that was refused while playing, each with a "use
 *       anyway" button that leads to the same password prompt. Granting there is permanent.</li>
 * </ul>
 */
public final class PrivacySecurityScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 240;
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_HEIGHT = 96;
    private static final int PADDING = 18;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int authorizedTop;
    private int blockedTop;

    private int authorizedScroll;
    private int blockedScroll;

    private String signature = "";

    public PrivacySecurityScreen(Screen parent) {
        super(Component.translatable("getskeeper.privacy.title"));
        this.parent = parent;
        // Ask the server for the session list straight away; the authorizations arrive by themselves
        // through the synced attachment.
        ClientPacketDistributor.sendToServer(new GKPayloads.RequestSessionData());
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = Math.max(16, (height - PANEL_HEIGHT) / 2);
        authorizedTop = panelY + 54;
        blockedTop = panelY + PANEL_HEIGHT - 12 - LIST_HEIGHT - 18;

        List<String> authorized = authorizedItems();
        List<String> blocked = ClientGuardState.sessionBlocked();

        int toggleX = panelX + PANEL_WIDTH - PADDING - 34;
        for (int row = 0; row < visibleRows(); row++) {
            int listIndex = authorizedScroll + row;
            if (listIndex >= authorized.size()) {
                break;
            }
            String itemId = authorized.get(listIndex);
            int rowY = authorizedTop + row * ROW_HEIGHT + 3;
            addRenderableWidget(new MacToggle(toggleX, rowY, isEnabled(itemId), Component.literal(itemId),
                    requested -> requestToggle(itemId)));
        }

        int buttonX = panelX + PANEL_WIDTH - PADDING - 84;
        for (int row = 0; row < visibleRows(); row++) {
            int listIndex = blockedScroll + row;
            if (listIndex >= blocked.size()) {
                break;
            }
            String itemId = blocked.get(listIndex);
            int rowY = blockedTop + row * ROW_HEIGHT + 1;
            addRenderableWidget(new MacButton(buttonX, rowY, 84, 18,
                    Component.translatable("getskeeper.privacy.use_anyway"), false,
                    () -> requestUseAnyway(itemId)));
        }

        addRenderableWidget(new MacButton(panelX + PANEL_WIDTH - PADDING - 84,
                panelY + PANEL_HEIGHT - 12 - 22, 84, MacTheme.BUTTON_HEIGHT,
                Component.translatable("getskeeper.privacy.close"), true, this::onClose));
    }

    @Override
    public void tick() {
        super.tick();
        String current = String.join("|", authorizedItems()) + "#" + String.join("|", ClientGuardState.sessionBlocked());
        if (!current.equals(signature)) {
            signature = current;
            rebuildWidgets();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MacTheme.card(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, MacTheme.CARD_RADIUS,
                MacTheme.SURFACE, MacTheme.SURFACE_BORDER);

        graphics.drawCenteredString(font, Component.translatable("getskeeper.privacy.title"), panelX + PANEL_WIDTH / 2,
                panelY + 16, MacTheme.TEXT_PRIMARY);
        MacTheme.text(graphics, font, Component.translatable("getskeeper.privacy.subtitle"), panelX + PADDING,
                panelY + 32, MacTheme.TEXT_SECONDARY);

        MacTheme.text(graphics, font, Component.translatable("getskeeper.privacy.authorized_header"), panelX + PADDING,
                authorizedTop - 12, MacTheme.TEXT_PRIMARY);

        List<String> authorized = authorizedItems();
        if (authorized.isEmpty()) {
            MacTheme.text(graphics, font, Component.translatable("getskeeper.privacy.empty"), panelX + PADDING,
                    authorizedTop + 4, MacTheme.TEXT_SECONDARY);
        } else {
            graphics.enableScissor(panelX + PADDING, authorizedTop, panelX + PANEL_WIDTH - PADDING,
                    authorizedTop + LIST_HEIGHT);
            for (int row = 0; row < visibleRows(); row++) {
                int listIndex = authorizedScroll + row;
                if (listIndex >= authorized.size()) {
                    break;
                }
                String itemId = authorized.get(listIndex);
                int rowY = authorizedTop + row * ROW_HEIGHT;
                if (row % 2 == 0) {
                    MacTheme.fillRounded(graphics, panelX + PADDING - 4, rowY, PANEL_WIDTH - PADDING * 2 + 8,
                            ROW_HEIGHT - 2, 5, 0xFFF0F0F4);
                }
                MacTheme.text(graphics, font, displayName(itemId), panelX + PADDING, rowY + 5, MacTheme.TEXT_PRIMARY);
            }
            graphics.disableScissor();
            drawScrollHint(graphics, authorized, authorizedScroll, authorizedTop);
        }

        MacTheme.text(graphics, font, Component.translatable("getskeeper.privacy.blocked_header"), panelX + PADDING,
                blockedTop - 12, MacTheme.TEXT_PRIMARY);

        List<String> blocked = ClientGuardState.sessionBlocked();
        if (blocked.isEmpty()) {
            MacTheme.text(graphics, font, Component.translatable("getskeeper.privacy.blocked_empty"), panelX + PADDING,
                    blockedTop + 4, MacTheme.TEXT_SECONDARY);
        } else {
            graphics.enableScissor(panelX + PADDING, blockedTop, panelX + PANEL_WIDTH - PADDING,
                    blockedTop + LIST_HEIGHT);
            for (int row = 0; row < visibleRows(); row++) {
                int listIndex = blockedScroll + row;
                if (listIndex >= blocked.size()) {
                    break;
                }
                String itemId = blocked.get(listIndex);
                int rowY = blockedTop + row * ROW_HEIGHT;
                MacTheme.text(graphics, font,
                        Component.translatable("getskeeper.privacy.blocked_line", displayName(itemId)),
                        panelX + PADDING, rowY + 5, MacTheme.TEXT_SECONDARY);
            }
            graphics.disableScissor();
            drawScrollHint(graphics, blocked, blockedScroll, blockedTop);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int visibleRows() {
        return Math.max(1, LIST_HEIGHT / ROW_HEIGHT);
    }

    /** {@return every authorized item id, enabled ones first} */
    private List<String> authorizedItems() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return List.of();
        }
        AuthorizationRecord record = minecraft.player.getData(GKAttachments.AUTHORIZATIONS.get());
        List<String> items = new ArrayList<>(record.enabled());
        items.addAll(record.disabled());
        return items;
    }

    private boolean isEnabled(String itemId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        return minecraft.player.getData(GKAttachments.AUTHORIZATIONS.get()).isUsable(itemId);
    }

    private void requestToggle(String itemId) {
        Minecraft minecraft = Minecraft.getInstance();
        String username = minecraft.player == null ? "" : minecraft.player.getScoreboardName();
        // The switch does not move here: the server decides, and the synced attachment redraws it.
        minecraft.setScreen(new TogglePrompt(itemId, username, this));
    }

    private void requestUseAnyway(String itemId) {
        ClientPacketDistributor.sendToServer(new GKPayloads.RequestBlockedUse(itemId));
    }

    /** Draws a thin scrollbar when a list is longer than the window. */
    private void drawScrollHint(GuiGraphics graphics, List<String> items, int offset, int top) {
        if (items.size() <= visibleRows()) {
            return;
        }
        int trackX = panelX + PANEL_WIDTH - PADDING + 4;
        MacTheme.fillRounded(graphics, trackX, top, 2, LIST_HEIGHT, 1, 0x22000000);
        int knobHeight = Math.max(12, LIST_HEIGHT * visibleRows() / items.size());
        int maxOffset = Math.max(1, items.size() - visibleRows());
        int knobY = top + (LIST_HEIGHT - knobHeight) * Math.min(offset, maxOffset) / maxOffset;
        MacTheme.fillRounded(graphics, trackX, knobY, 2, knobHeight, 1, 0x66000000);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int authorizedMax = Math.max(0, authorizedItems().size() - visibleRows());
        int blockedMax = Math.max(0, ClientGuardState.sessionBlocked().size() - visibleRows());
        if (scrollY > 0) {
            authorizedScroll = Math.max(0, authorizedScroll - 1);
            blockedScroll = Math.max(0, blockedScroll - 1);
        } else if (scrollY < 0) {
            authorizedScroll = Math.min(authorizedMax, authorizedScroll + 1);
            blockedScroll = Math.min(blockedMax, blockedScroll + 1);
        }
        rebuildWidgets();
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /** {@return an item id rendered as the item's own name, falling back to the raw id} */
    public static Component displayName(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id != null) {
            var holder = BuiltInRegistries.ITEM.getOptional(id);
            if (holder.isPresent()) {
                return new ItemStack(holder.get()).getHoverName();
            }
        }
        return Component.literal(itemId);
    }

    /**
     * The password prompt used when flipping a switch.
     *
     * <p>It is a separate class purely so that returning to the privacy page after a successful or
     * cancelled toggle is possible; {@link PasswordScreen} itself has no notion of a parent screen.
     */
    private static final class TogglePrompt extends PasswordScreen {
        private final PrivacySecurityScreen parent;

        private TogglePrompt(String itemId, String username, PrivacySecurityScreen parent) {
            super(PasswordPurpose.TOGGLE_AUTHORIZATION, itemId, username, false);
            this.parent = parent;
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(parent);
        }
    }
}
