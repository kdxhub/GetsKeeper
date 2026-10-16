package top.kdxiaoyi.mod.getskeeper.use;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.config.GKConfig;
import top.kdxiaoyi.mod.getskeeper.item.MountedContainerImageItem;
import top.kdxiaoyi.mod.getskeeper.data.GKAttachments;
import top.kdxiaoyi.mod.getskeeper.net.GKNetwork;
import top.kdxiaoyi.mod.getskeeper.net.VerificationDecision;
import top.kdxiaoyi.mod.getskeeper.security.AuthorizationService;
import top.kdxiaoyi.mod.getskeeper.security.SessionGuards;
import top.kdxiaoyi.mod.getskeeper.source.ItemSource;
import top.kdxiaoyi.mod.getskeeper.source.SourceRecorder;
import top.kdxiaoyi.mod.getskeeper.trust.TrustLevel;
import top.kdxiaoyi.mod.getskeeper.trust.TrustService;

/**
 * Verifies items before they may be used.
 *
 * <p>Four hooks feed one state machine:
 *
 * <ol>
 *   <li><b>The guarded slot watcher</b> notices an unauthorized stack that appeared in a hand or an
 *       equipment slot, puts the previous contents back, and starts a verification.</li>
 *   <li><b>{@code ItemStackedOnOtherEvent}</b> catches stacks dropped onto a crafting grid or a work
 *       block's input slot, cancels the move, and starts a verification. Container menus are left
 *       alone, which is what "verification does not cover container interaction" means.</li>
 *   <li><b>{@code UseItemOnBlockEvent}</b> vetoes using an unauthorized item on a block.</li>
 *   <li><b>{@code LivingEntityUseItemEvent.Start}</b> vetoes using one (eating, drawing a bow, ...).</li>
 * </ol>
 *
 * <p>A verification is a randomized wait, drawn as the experience bar overlay, followed by a dialog
 * unless the item is fully trusted or already authorized. Authorized items never reach any of this.
 */
public final class UseGuard {
    /**
     * Which slot an intercepted operation was aiming for.
     *
     * <p>A negative index means the operation was not a slot move - a use of some kind - and there is
     * therefore nothing to put back.
     */
    private record GuardedSlot(int index) {}

    /** A verification that is in flight. */
    private record Pending(ItemStack stack, GuardedSlot slot, long resolveAt) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, List<ItemStack>> SNAPSHOTS = new HashMap<>();

    private UseGuard() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(UseGuard::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(UseGuard::onStackedOnOther);
        NeoForge.EVENT_BUS.addListener(UseGuard::onUseItemOnBlock);
        NeoForge.EVENT_BUS.addListener(UseGuard::onUseItemStart);
        NeoForge.EVENT_BUS.addListener(UseGuard::onPlayerLoggedOut);
    }

    /** {@return {@code true} when the stack may be used without any verification} */
    public static boolean isTrusted(Player player, ItemStack stack) {
        // The mod's own items are never verified: they are the tool this whole system runs on, and
        // verifying the image would make mounting a batch verify the thing that vouches for it.
        return MountedContainerImageItem.isImage(stack) || AuthorizationService.isUsable(player, stack);
    }

    // --- interception ------------------------------------------------------------------------

    private static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        if (!GKConfig.useGuardEnabled() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!isWorkSlot(event.getSlot())) {
            return;
        }
        ItemStack moving = event.getCarriedItem();
        if (moving.isEmpty() || isTrusted(player, moving)) {
            return;
        }
        event.setCanceled(true);
        begin(player, moving, new GuardedSlot(-1));
    }

    private static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (!GKConfig.useGuardEnabled() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || isTrusted(player, stack)) {
            return;
        }
        event.cancelWithResult(InteractionResult.FAIL);
        begin(player, stack, new GuardedSlot(-1));
    }

    private static void onUseItemStart(LivingEntityUseItemEvent.Start event) {
        if (!GKConfig.useGuardEnabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItem();
        if (stack.isEmpty() || isTrusted(player, stack)) {
            return;
        }
        event.setCanceled(true);
        begin(player, stack, new GuardedSlot(-1));
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        PENDING.remove(id);
        SNAPSHOTS.remove(id);
    }

    // --- guarded slot watcher ----------------------------------------------------------------

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        UUID id = player.getUUID();
        long now = player.level().getGameTime();

        Pending pending = PENDING.get(id);
        if (pending != null) {
            if (now >= pending.resolveAt()) {
                resolve(player, pending);
            }
            return;
        }

        if (!GKConfig.useGuardEnabled()) {
            return;
        }

        List<ItemStack> current = snapshot(player);
        List<ItemStack> previous = SNAPSHOTS.put(id, current);
        if (previous == null || previous.size() != current.size()) {
            return;
        }

        for (int index : guardedIndices(player)) {
            ItemStack before = previous.get(index);
            ItemStack after = current.get(index);
            if (after.isEmpty() || sameStack(before, after) || isTrusted(player, after)) {
                continue;
            }

            GuardedSlot slot = new GuardedSlot(index);
            // Step one of the design: undo the operation before asking anything. The whole inventory
            // is put back rather than just this slot, because a move has two ends - restoring one end
            // of a swap leaves the other end behind, which both loses and duplicates stacks.
            revert(player, previous);
            SNAPSHOTS.put(id, snapshot(player));
            begin(player, after.copy(), slot);
            return;
        }
    }

    /**
     * The slots whose contents are verified.
     *
     * <p>The rest of the player's own inventory is deliberately not watched: moving an item around
     * inside the backpack is not using it. Hands and equipment are. The whole inventory is still
     * snapshotted, because undoing an intercepted move has to restore both ends of it.
     */
    private static Set<Integer> guardedIndices(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        Set<Integer> indices = new LinkedHashSet<>();
        indices.add(inventory.getSelectedSlot());
        indices.add(Inventory.SLOT_OFFHAND);
        indices.addAll(Inventory.EQUIPMENT_SLOT_MAPPING.keySet());
        return indices;
    }

    /** {@return a copy of every inventory slot} */
    private static List<ItemStack> snapshot(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            stacks.add(inventory.getItem(slot).copy());
        }
        return stacks;
    }

    /** Puts every inventory slot back to a previous snapshot. */
    private static void revert(ServerPlayer player, List<ItemStack> previous) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Math.min(previous.size(), inventory.getContainerSize()); slot++) {
            inventory.setItem(slot, previous.get(slot).copy());
        }
    }

    /**
     * A work slot is a crafting grid or a work block's input.
     *
     * <p>Vanilla implements all of those with a {@code CraftingContainer}: the player's 2x2 grid, the
     * crafting table, and the anvil, smithing table, loom, stonecutter and cartography table input
     * slots. Container menus such as chests are excluded, because verification is not meant to cover
     * container interaction. Grindstone and enchanting table inputs are plain containers and stay out
     * of scope here; the guarded slot watcher still covers the resulting hand and equipment use.
     */
    private static boolean isWorkSlot(Slot slot) {
        return slot.container instanceof CraftingContainer;
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return ItemStack.isSameItemSameComponents(first, second) && first.getCount() == second.getCount();
    }

    // --- the verification state machine ------------------------------------------------------

    private static void begin(ServerPlayer player, ItemStack stack, GuardedSlot slot) {
        UUID id = player.getUUID();
        if (PENDING.containsKey(id)) {
            return;
        }
        int ticks = GKConfig.rollVerificationTicks(player.getRandom());
        PENDING.put(id, new Pending(stack.copy(), slot, player.level().getGameTime() + ticks));
        GKNetwork.startVerification(player, stack, ticks);
    }

    private static void resolve(ServerPlayer player, Pending pending) {
        UUID id = player.getUUID();
        ItemStack stack = pending.stack();
        String itemId = SourceRecorder.itemId(stack);
        net.minecraft.resources.Identifier registryId =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        TrustLevel trust = registryId == null ? TrustLevel.UNTRUSTED : TrustService.resolve(registryId);

        if (trust.passesSilently()) {
            // Fully trusted: the progress bar is the whole story, no dialog.
            PENDING.remove(id);
            reapply(player, pending);
            // The snapshot still describes the state from before the interception, so it has to go:
            // leaving it behind made the next tick read the stack this method just put back as a
            // brand new change, and the item was verified over and over for ever.
            SNAPSHOTS.remove(id);
            GetsKeeper.LOGGER.debug("{} passed verification without a dialog", itemId);
            return;
        }

        if (trust == TrustLevel.UNTRUSTED || trust == TrustLevel.BLOCKED) {
            SessionGuards.mark(id, itemId);
            GKNetwork.sendSessionBlocked(player);
        }

        ItemSource source = player.getData(GKAttachments.SOURCES.get()).latestFor(itemId);
        GKNetwork.sendVerificationResult(player, stack, trust, source,
                source == null ? "" : source.acquiredBy(),
                source == null ? 0L : source.acquiredAt());
        // Stay pending until the player answers; the resolver below is what ends it.
        PENDING.put(id, new Pending(stack, pending.slot(), Long.MAX_VALUE));
    }

    /** Applies the player's answer on a verification dialog. */
    public static void handleDecision(ServerPlayer player, VerificationDecision decision) {
        UUID id = player.getUUID();
        Pending pending = PENDING.remove(id);
        if (pending == null) {
            return;
        }

        switch (decision) {
            case CONTINUE -> {
                // Pressing "Continue" is what authorizes an item, which is why the answer has to be
                // written down: without this the same item was verified again on its next use for ever.
                AuthorizationService.authorize(player, SourceRecorder.itemId(pending.stack()));
                reapply(player, pending);
                GetsKeeper.LOGGER.info("{} chose to continue using {}", player.getScoreboardName(),
                        SourceRecorder.itemId(pending.stack()));
            }
            case VOID -> {
                int cleared = voidStacks(player, pending);
                GetsKeeper.LOGGER.info("{} moved {} to the void ({} stack(s))", player.getScoreboardName(),
                        SourceRecorder.itemId(pending.stack()), cleared);
            }
            case CANCEL -> {
                int returned = returnToInventory(player, pending);
                GetsKeeper.LOGGER.info("{} cancelled the use of {} ({} stack(s) returned)", player.getScoreboardName(),
                        SourceRecorder.itemId(pending.stack()), returned);
            }
        }

        SNAPSHOTS.remove(id);
    }

    /**
     * Puts the intercepted stack back into the inventory after a cancelled verification.
     *
     * <p>The interception took the stack out of the inventory, so cancelling has to hand it back; the
     * player asked for nothing to happen, not for the item to disappear. Only a slot move has an item
     * to give back - a cancelled use never removed anything.
     *
     * @return how many stacks were returned
     */
    private static int returnToInventory(ServerPlayer player, Pending pending) {
        if (pending.slot().index() < 0 || pending.stack().isEmpty()) {
            return 0;
        }
        ItemStack returning = pending.stack().copy();
        if (!player.getInventory().add(returning) && !returning.isEmpty()) {
            // Nowhere to put it: drop it rather than delete it.
            player.drop(returning, false);
        }
        return 1;
    }

    /** Tries to run the intercepted operation again. Failures are swallowed on purpose. */
    private static void reapply(ServerPlayer player, Pending pending) {
        if (pending.slot().index() < 0) {
            // The operation was a use, not a slot move. Nothing to put back safely; the next tick's
            // snapshot has already forgotten it, so the action simply has to be repeated by the player.
            return;
        }
        try {
            player.getInventory().setItem(pending.slot().index(), pending.stack().copy());
        } catch (RuntimeException exception) {
            GetsKeeper.LOGGER.debug("Could not re-apply an intercepted operation", exception);
        }
    }

    /** Clears the verified stack, and any identical stack, from the inventory. */
    private static int voidStacks(ServerPlayer player, Pending pending) {
        Inventory inventory = player.getInventory();
        int cleared = 0;

        int index = pending.slot().index();
        if (index >= 0 && index < inventory.getContainerSize()) {
            ItemStack slotStack = inventory.getItem(index);
            if (sameStack(slotStack, pending.stack())) {
                inventory.setItem(index, ItemStack.EMPTY);
                return 1;
            }
        }

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (sameStack(stack, pending.stack())) {
                inventory.setItem(slot, ItemStack.EMPTY);
                cleared++;
            }
        }
        return cleared;
    }
}
