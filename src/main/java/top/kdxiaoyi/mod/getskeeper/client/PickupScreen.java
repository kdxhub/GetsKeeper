package top.kdxiaoyi.mod.getskeeper.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import top.kdxiaoyi.mod.getskeeper.net.GKPayloads;

/**
 * The pickup screen.
 *
 * <p>Items the player has never picked up this session are held in the world until they are dragged
 * onto the container image on the right. Layout, left to right:
 *
 * <ul>
 *   <li>the icons of everything on offer, the topmost one in front,</li>
 *   <li>the aggregated name underneath, with the full list on hover,</li>
 *   <li>an arrow,</li>
 *   <li>the container image itself, labelled {@code Inventory} in every language.</li>
 * </ul>
 *
 * <p>Nothing is taken until the drag is dropped on the image. Closing the screen - with Escape, with
 * the button, or by replacing it - tells the server to leave everything where it is.
 */
public final class PickupScreen extends Screen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 168;
    private static final int ICON_SIZE = 32;
    private static final int SLOT_SIZE = 44;

    /** The vanilla chest artwork. Reused so the screen renders vanilla's model, not a stand-in. */
    private static final ItemStack CHEST = new ItemStack(Items.CHEST);

    private final List<ItemStack> stacks;
    private final List<String> itemIds;

    private boolean answered;
    private boolean dragging;
    private double dragX;
    private double dragY;

    private int panelX;
    private int panelY;
    private int iconX;
    private int iconY;
    private int chestX;
    private int chestY;

    public PickupScreen(List<ItemStack> stacks, List<String> itemIds) {
        super(Component.translatable("getskeeper.pickup.title"));
        this.stacks = List.copyOf(stacks);
        this.itemIds = List.copyOf(itemIds);
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        iconX = panelX + 34;
        iconY = panelY + 34;
        chestX = panelX + PANEL_WIDTH - 34 - SLOT_SIZE;
        chestY = panelY + 26;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MacTheme.card(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, MacTheme.CARD_RADIUS,
                MacTheme.SURFACE, MacTheme.SURFACE_BORDER);

        // The batch on the left, drawn back to front so the first stack stays on top.
        if (!dragging) {
            int depth = Math.min(3, stacks.size());
            for (int index = depth - 1; index >= 0; index--) {
                graphics.renderItem(stacks.get(index), iconX + index * 3, iconY + index * 3);
            }
        }

        // The aggregated name.
        Component label = aggregateLabel();
        int labelY = iconY + ICON_SIZE + 34;
        for (var line : font.split(label, PANEL_WIDTH - 120)) {
            graphics.drawCenteredString(font, line, panelX + PANEL_WIDTH / 2 - 46, labelY, MacTheme.TEXT_PRIMARY);
            labelY += font.lineHeight + 2;
        }

        drawArrow(graphics, panelX + PANEL_WIDTH / 2 + 8, panelY + PANEL_HEIGHT / 2, 26, MacTheme.TEXT_SECONDARY);

        // The drop target: the vanilla container, with the fixed label underneath it.
        MacTheme.fillRounded(graphics, chestX, chestY, SLOT_SIZE, SLOT_SIZE, 8, MacTheme.SURFACE_BORDER);
        MacTheme.fillRounded(graphics, chestX + 1, chestY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, 7,
                isOverDropZone(mouseX, mouseY) ? 0xFFEAF3FF : MacTheme.SURFACE_ELEVATED);
        graphics.renderItem(CHEST, chestX + (SLOT_SIZE - 16) / 2, chestY + (SLOT_SIZE - 16) / 2 - 2);
        // "Inventory" is deliberately not translated.
        graphics.drawCenteredString(font, Component.literal("Inventory"), chestX + SLOT_SIZE / 2,
                chestY + SLOT_SIZE - 2, MacTheme.TEXT_SECONDARY);

        // A hint under the image, and the dragged icon following the cursor.
        graphics.drawCenteredString(font, Component.translatable("getskeeper.pickup.hint"), panelX + PANEL_WIDTH / 2,
                panelY + PANEL_HEIGHT - 20, MacTheme.TEXT_SECONDARY);

        if (dragging && !stacks.isEmpty()) {
            graphics.renderItem(stacks.get(0), (int) dragX - 8, (int) dragY - 8);
        }

        if (!dragging && isOverIconStack(mouseX, mouseY)) {
            graphics.setComponentTooltipForNextFrame(font, GKDialog.describeBatch(stacks), mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Component aggregateLabel() {
        if (stacks.isEmpty()) {
            return Component.empty();
        }
        int distinct = GKDialog.distinctCount(stacks);
        if (distinct <= 1) {
            return stacks.get(0).getHoverName();
        }
        return Component.translatable("getskeeper.pickup.many", stacks.get(0).getHoverName(), distinct);
    }

    private void drawArrow(GuiGraphics graphics, int centerX, int centerY, int length, int color) {
        int startX = centerX - length / 2;
        graphics.fill(startX, centerY - 1, startX + length, centerY + 1, color);
        int head = 5;
        for (int index = 0; index < head; index++) {
            graphics.fill(startX + length - index, centerY - index, startX + length - index + 1, centerY - index + 1,
                    color);
            graphics.fill(startX + length - index, centerY + index, startX + length - index + 1, centerY + index + 1,
                    color);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && isOverIconStack(event.x(), event.y())) {
            dragging = true;
            dragX = event.x();
            dragY = event.y();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            this.dragX = event.x();
            this.dragY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            if (isOverDropZone(event.x(), event.y())) {
                accept();
                return true;
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        if (!answered) {
            answered = true;
            ClientPacketDistributor.sendToServer(new GKPayloads.PickupDecision(false));
        }
        ClientGuardState.clearPickup();
        super.onClose();
    }

    @Override
    public void removed() {
        // Covers the cases where the screen is replaced instead of closed, such as the full
        // inventory warning taking over.
        if (!answered) {
            answered = true;
            ClientPacketDistributor.sendToServer(new GKPayloads.PickupDecision(false));
        }
        ClientGuardState.clearPickup();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void accept() {
        if (answered) {
            return;
        }
        answered = true;
        ClientPacketDistributor.sendToServer(new GKPayloads.PickupDecision(true));
        ClientGuardState.clearPickup();
        onClose();
    }

    private boolean isOverIconStack(double mouseX, double mouseY) {
        int depth = Math.min(3, stacks.size());
        return mouseX >= iconX && mouseX <= iconX + ICON_SIZE + depth * 3
                && mouseY >= iconY && mouseY <= iconY + ICON_SIZE + depth * 3;
    }

    private boolean isOverDropZone(double mouseX, double mouseY) {
        return mouseX >= chestX && mouseX <= chestX + SLOT_SIZE
                && mouseY >= chestY && mouseY <= chestY + SLOT_SIZE;
    }

    /** {@return the ids of everything on offer, used by the diagnostics command} */
    public List<String> itemIds() {
        return new ArrayList<>(itemIds);
    }
}
