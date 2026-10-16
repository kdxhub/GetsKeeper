package top.kdxiaoyi.mod.getskeeper.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import top.kdxiaoyi.mod.getskeeper.net.GKPayloads;
import top.kdxiaoyi.mod.getskeeper.net.PasswordPurpose;
import top.kdxiaoyi.mod.getskeeper.net.VerificationDecision;
import top.kdxiaoyi.mod.getskeeper.security.SessionGuards;

/**
 * Builds every prompt in the mod.
 *
 * <p>Keeping the construction in one place means the wording of a prompt is never spread across the
 * code that happens to trigger it, and it makes it easy to see that no string is hard coded: every
 * headline and every body line is a translation key plus arguments.
 */
public final class GKDialog {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private GKDialog() {}

    private static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    /** The "the backpack is full" prompt, shown over the pickup screen. */
    public static MacDialogScreen inventoryFull(String ownerName, Runnable onAcknowledge) {
        return MacDialogScreen.builder(Component.translatable("getskeeper.dialog.inventory_full.title"))
                .warning()
                .body(Component.translatable("getskeeper.dialog.inventory_full.body", ownerName))
                .notClosable()
                .choice(MacDialogScreen.Choice.primary(Component.translatable("getskeeper.dialog.ok"), onAcknowledge))
                .build();
    }

    /** The trusted prompt: "use this?" with the item's origin underneath. */
    public static MacDialogScreen trusted(ItemStack stack, String ownerName, long acquiredAt, String sourceKey,
            String sourceLabel, String sourceCoordinates, String sourceDimension) {
        var builder = MacDialogScreen.builder(
                Component.translatable("getskeeper.dialog.use.title", stack.getHoverName()))
                .icon(stack)
                .body(Component.translatable("getskeeper.dialog.use.trusted_note"));

        if (acquiredAt > 0) {
            Component source = Component.translatable(sourceKey, sourceLabel, sourceCoordinates, sourceDimension);
            Component origin = Component.translatable("getskeeper.dialog.use.attribution", ownerName,
                    TIMESTAMP.format(Instant.ofEpochMilli(acquiredAt)), source);
            builder.attribution(origin);
        }

        return builder
                .choice(MacDialogScreen.Choice.secondary(Component.translatable("getskeeper.dialog.cancel"),
                        () -> decide(VerificationDecision.CANCEL)))
                .choice(MacDialogScreen.Choice.primary(Component.translatable("getskeeper.dialog.continue"),
                        () -> decide(VerificationDecision.CONTINUE)))
                .onDismiss(() -> decide(VerificationDecision.CANCEL))
                .build();
    }

    /** The untrusted prompt. The headline is kept verbatim from the original mod. */
    public static MacDialogScreen untrusted(ItemStack stack) {
        return MacDialogScreen.builder(Component.translatable("getskeeper.dialog.untrusted.title", stack.getHoverName()))
                .icon(stack)
                .body(Component.translatable("getskeeper.dialog.untrusted.body", stack.getHoverName()))
                .choice(MacDialogScreen.Choice.secondary(Component.translatable("getskeeper.dialog.done"),
                        () -> decide(VerificationDecision.CANCEL)))
                .choice(MacDialogScreen.Choice.destructive(Component.translatable("getskeeper.dialog.void"),
                        () -> decide(VerificationDecision.VOID)))
                .onDismiss(() -> decide(VerificationDecision.CANCEL))
                .build();
    }

    /** The blocked prompt. */
    public static MacDialogScreen blocked(ItemStack stack) {
        return MacDialogScreen.builder(Component.translatable("getskeeper.dialog.blocked.title", stack.getHoverName()))
                .icon(stack)
                .body(Component.translatable("getskeeper.dialog.blocked.body"))
                .choice(MacDialogScreen.Choice.secondary(Component.translatable("getskeeper.dialog.cancel"),
                        () -> decide(VerificationDecision.CANCEL)))
                .choice(MacDialogScreen.Choice.destructive(Component.translatable("getskeeper.dialog.void"),
                        () -> decide(VerificationDecision.VOID)))
                .onDismiss(() -> decide(VerificationDecision.CANCEL))
                .build();
    }

    /** Shown when an item's switch was turned off in the privacy screen. */
    public static MacDialogScreen restricted(ItemStack stack) {
        return MacDialogScreen.builder(Component.translatable("getskeeper.dialog.restricted.title", stack.getHoverName()))
                .icon(stack)
                .body(Component.translatable("getskeeper.dialog.restricted.body", stack.getHoverName()))
                .choice(MacDialogScreen.Choice.primary(Component.translatable("getskeeper.dialog.ok"),
                        () -> decide(VerificationDecision.CANCEL)))
                .onDismiss(() -> decide(VerificationDecision.CANCEL))
                .build();
    }

    /** Shown when a switch is turned back on: the item cannot be used until it is used again. */
    public static MacDialogScreen reauthorized(ItemStack stack) {
        return MacDialogScreen.builder(Component.translatable("getskeeper.dialog.restricted.title", stack.getHoverName()))
                .icon(stack)
                .body(Component.translatable("getskeeper.dialog.reauthorized.body", stack.getHoverName(),
                        stack.getHoverName()))
                .choice(MacDialogScreen.Choice.secondary(Component.translatable("getskeeper.dialog.ok"), () -> {}))
                .choice(MacDialogScreen.Choice.primary(Component.translatable("getskeeper.dialog.reuse"), () -> {}))
                .build();
    }

    private static void decide(VerificationDecision decision) {
        VerificationOverlay.finish();
        PacketDistributor.sendToServer(new GKPayloads.UseDecision(decision.ordinal()));
    }

    /** Opens the screen for a verification verdict. */
    public static void showVerificationResult(ItemStack stack, int trustOrdinal, String ownerName, long acquiredAt,
            String sourceKey, String sourceLabel, String sourceCoordinates, String sourceDimension) {
        VerificationOverlay.finish();
        var trust = top.kdxiaoyi.mod.getskeeper.trust.TrustLevel.values()[Math.max(0,
                Math.min(trustOrdinal, top.kdxiaoyi.mod.getskeeper.trust.TrustLevel.values().length - 1))];
        MacDialogScreen screen = switch (trust) {
            case FULLY_TRUSTED -> null;
            case TRUSTED -> trusted(stack, ownerName, acquiredAt, sourceKey, sourceLabel, sourceCoordinates, sourceDimension);
            case UNTRUSTED -> untrusted(stack);
            case BLOCKED -> blocked(stack);
        };
        if (screen != null) {
            minecraft().setScreen(screen);
        }
    }

    /** Collects the distinct item names in a batch, for the pickup screen tooltip. */
    public static List<Component> describeBatch(List<ItemStack> stacks) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("getskeeper.pickup.tooltip_header"));
        boolean[] seen = new boolean[stacks.size()];
        for (int i = 0; i < stacks.size(); i++) {
            if (seen[i]) {
                continue;
            }
            ItemStack stack = stacks.get(i);
            int count = 0;
            for (int j = i; j < stacks.size(); j++) {
                if (ItemStack.isSameItemSameComponents(stack, stacks.get(j))) {
                    seen[j] = true;
                    count += stacks.get(j).getCount();
                }
            }
            lines.add(Component.translatable("getskeeper.pickup.tooltip_line", stack.getHoverName(), count));
        }
        return lines;
    }

    /** {@return the number of distinct items in a batch} */
    public static int distinctCount(List<ItemStack> stacks) {
        List<ItemStack> distinct = new ArrayList<>();
        for (ItemStack stack : stacks) {
            boolean known = false;
            for (ItemStack other : distinct) {
                if (ItemStack.isSameItemSameComponents(stack, other)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                distinct.add(stack);
            }
        }
        return distinct.size();
    }

    /** Opens the password prompt. */
    public static void showPasswordPrompt(PasswordPurpose purpose, String itemId, String suggestedUsername,
            boolean mandatory) {
        minecraft().setScreen(new PasswordScreen(purpose, itemId, suggestedUsername, mandatory));
    }
}
