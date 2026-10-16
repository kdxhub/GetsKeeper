package top.kdxiaoyi.mod.getskeeper.use;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
 *       equipment slot, puts the previous contents back, and starts a verification. Selecting a
 *       different hotbar slot counts as the same thing - it brings that slot's stack into the hand -
 *       even though no slot's contents changed.</li>
 *   <li><b>{@code ItemStackedOnOtherEvent}</b> catches stacks dropped onto a crafting grid or a work
 *       block's input slot, cancels the move, and starts a verification. Container menus are left
 *       alone, which is what "verification does not cover container interaction" means.</li>
 *   <li><b>{@code UseItemOnBlockEvent}</b> vetoes using an unauthorized item on a block.</li>
 *   <li><b>{@code LivingEntityUseItemEvent.Start}</b> vetoes using one (eating, drawing a bow, ...).</li>
 *   <li><b>{@code PlayerInteractEvent.LeftClickBlock}</b> vetoes mining with one.</li>
 *   <li><b>{@code LivingSwapItemsEvent.Hands}</b> vetoes swapping one into the other hand with F.</li>
 *   <li><b>{@code PlayerInteractEvent.RightClickItem}</b> vetoes right clicking with one, which is
 *       what equipping a piece of armour is.</li>
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

    /**
     * A verification that is in flight.
     *
     * @param stack         the stack the player is being asked about
     * @param slot          where it was heading; a negative index means the operation was a use
     * @param after         the slots the intercepted operation changed, and what they hold afterwards
     * @param carriedBefore the inventory menu's carried item before the operation, and after it; the
     *                      cursor is where a dragged stack lives between its two clicks, and it is
     *                      not part of the inventory, so undoing or re-applying a move has to move it
     *                      too - otherwise the stack exists both in the slot and on the cursor
     * @param resolveAt     when the wait is over
     */
    private record Pending(ItemStack stack, GuardedSlot slot, Map<Integer, ItemStack> after,
            ItemStack carriedBefore, ItemStack carriedAfter, long resolveAt) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, List<ItemStack>> SNAPSHOTS = new HashMap<>();

    /** The hotbar slot each player is holding, so that switching it can be told apart from a move. */
    private static final Map<UUID, Integer> SELECTED = new HashMap<>();

    /** The inventory menu's carried item, snapshotted like the inventory itself. */
    private static final Map<UUID, ItemStack> CARRIED = new HashMap<>();

    private UseGuard() {}

    /**
     * Forgets the inventory baseline for a player.
     *
     * <p>Called when this mod itself puts items into the inventory, which is what the pickup screen
     * does when it hands a batch over. That is the mod acting rather than the player moving a stack,
     * so it is not a use. It also has to be forgotten for a blunter reason: the undo restores the
     * inventory to a snapshot taken before the hand-over, so leaving the baseline in place would wipe
     * the whole batch that was just given out.
     */
    public static void resync(ServerPlayer player) {
        SNAPSHOTS.remove(player.getUUID());
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(UseGuard::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(UseGuard::onStackedOnOther);
        NeoForge.EVENT_BUS.addListener(UseGuard::onUseItemOnBlock);
        NeoForge.EVENT_BUS.addListener(UseGuard::onUseItemStart);
        NeoForge.EVENT_BUS.addListener(UseGuard::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(UseGuard::onSwapHands);
        NeoForge.EVENT_BUS.addListener(UseGuard::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(UseGuard::onPlayerLoggedOut);
    }

    /**
     * {@return {@code true} when the stack may be used without a verification pass at all}
     *
     * <p>Two things never start one: the mod's own items, because verifying the image would mean
     * verifying the tool that vouches for a batch, and anything the player has authorized.
     *
     * <p>A fully trusted namespace is deliberately *not* on this list. "Fully trusted" means the
     * operator wants it watched but never questioned, so it still runs a pass - the progress bar is
     * the whole of it, followed by a verdict the client shows nothing for. Skipping the pass
     * altogether took the bar away as well, which is what the trust list is meant to control: only
     * moving an entry to the other lists takes the bar away.
     */
    public static boolean isTrusted(Player player, ItemStack stack) {
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
        if (event.getUsePhase() == UseItemOnBlockEvent.UsePhase.BLOCK) {
            // The block answering a right click - a chest opening, a button being pressed - is not the
            // item being used, and refusing it stopped those blocks from working at all while an
            // unverified item happened to be in hand. Container interaction is outside verification.
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || isTrusted(player, stack)) {
            return;
        }
        // The before-block phase is the item's own pre-emptive action, and vanilla hands the block its
        // turn only if this returns PASS. The item is refused either way; failing hard here would take
        // the block's interaction down with it.
        event.cancelWithResult(event.getUsePhase() == UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK
                ? InteractionResult.PASS
                : InteractionResult.FAIL);
        begin(player, stack, new GuardedSlot(-1));
    }

    /**
     * Left clicking a block is using the item too, and it was the one way left to act with an
     * unverified stack: mining does not go through either the use-on-block or the use-item path.
     *
     * <p>Only the start of the swing is intercepted. Cancelling it keeps the block from being mined,
     * and the repeated events vanilla fires while the button is held all fall on the same pending
     * verification. Creative mode is the one exception: vanilla breaks the block there without
     * consulting this event, which is noted in the architecture document.
     */
    /**
     * Right clicking with an item: equipping it, drinking it, placing it at nothing.
     *
     * <p>This is the one hook that covers putting armour on. Equipping moves a stack into an
     * equipment slot without going through use-on-block or use-item at all, so nothing else asked -
     * and by the time the slot watcher saw it, the item was already worn. Vanilla asks this event
     * first, so the answer is simply "no" and there is nothing to undo.
     */
    private static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!GKConfig.useGuardEnabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || isTrusted(player, stack)) {
            return;
        }
        // PASS rather than FAIL: the item is refused, but the client should still be free to try the
        // block behind it, which is how vanilla chains a right click.
        event.setCancellationResult(InteractionResult.PASS);
        event.setCanceled(true);
        begin(player, stack, new GuardedSlot(-1));
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!GKConfig.useGuardEnabled()
                || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || isTrusted(player, stack)) {
            return;
        }
        event.setCanceled(true);
        begin(player, stack, new GuardedSlot(-1));
    }

    /**
     * Swapping the hands with F, which is vetoed before it happens.
     *
     * <p>Both hands are guarded slots, so the watcher would catch this one tick later - by undoing a
     * swap that had already happened, and then having to put both ends back exactly where they came
     * from. Vanilla asks first, through a cancellable event, so the swap is simply refused: there is
     * nothing to undo, and nothing that can be duplicated by an answer to the dialog. "Continue" runs
     * the swap itself through the recorded footprint.
     */
    private static void onSwapHands(LivingSwapItemsEvent.Hands event) {
        if (!GKConfig.useGuardEnabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack incoming = event.getItemSwappedToMainHand();
        ItemStack outgoing = event.getItemSwappedToOffHand();
        // Either hand being unverified is enough: one of them is about to be the item in use.
        ItemStack subject = !isTrusted(player, incoming) ? incoming
                : (!isTrusted(player, outgoing) ? outgoing : null);
        if (subject == null || subject.isEmpty()) {
            return;
        }

        event.setCanceled(true);
        int selected = player.getInventory().getSelectedSlot();
        Map<Integer, ItemStack> after = new LinkedHashMap<>();
        after.put(selected, incoming.copy());
        after.put(Inventory.SLOT_OFFHAND, outgoing.copy());
        ItemStack carried = carried(player);
        begin(player, subject.copy(), new GuardedSlot(selected), after, carried, carried);
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
        SELECTED.remove(id);
        CARRIED.remove(id);
    }

    // --- guarded slot watcher ----------------------------------------------------------------

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        UUID id = player.getUUID();
        long now = player.level().getGameTime();

        // The selection is tracked on every tick, a pending verification included: it is not a change
        // to react to, it is what "the slot the player is holding" means right now.
        int selected = player.getInventory().getSelectedSlot();
        Integer previousSelected = SELECTED.put(id, selected);

        Pending pending = PENDING.get(id);
        if (pending != null) {
            if (now >= pending.resolveAt()) {
                resolve(player, pending);
            }
            return;
        }

        if (!GKConfig.useGuardEnabled()) {
            // Drop the baselines instead of letting them go stale: switching the guard back on must not
            // compare the inventory against the world as it was when it was switched off.
            SNAPSHOTS.remove(id);
            CARRIED.remove(id);
            return;
        }

        List<ItemStack> current = snapshot(player);
        List<ItemStack> previous = SNAPSHOTS.put(id, current);
        ItemStack carriedNow = carried(player);
        ItemStack carriedBefore = CARRIED.put(id, carriedNow);
        if (previous == null || carriedBefore == null || previous.size() != current.size()) {
            return;
        }

        // Everything the operation touched, with its result: a move has two ends, and a swap has two
        // ends that both changed, so undoing and re-applying both work on the whole footprint.
        Map<Integer, ItemStack> after = new LinkedHashMap<>();
        int watched = -1;
        Set<Integer> guarded = guardedIndices(player);
        for (int index = 0; index < current.size(); index++) {
            ItemStack before = previous.get(index);
            ItemStack arriving = current.get(index);
            if (sameStack(before, arriving)) {
                continue;
            }
            after.put(index, arriving.copy());
            if (watched < 0 && guarded.contains(index) && !arriving.isEmpty()
                    && !isTrusted(player, arriving)) {
                watched = index;
            }
        }

        if (watched >= 0) {
            ItemStack arriving = current.get(watched);
            // Step one of the design: undo the operation before asking anything. Every slot it
            // touched goes back, and so does the cursor, or the stack would be sitting in its slot
            // and on the cursor at once.
            restore(player, previous, after.keySet(), carriedBefore);
            SNAPSHOTS.put(id, snapshot(player));
            CARRIED.put(id, carriedBefore);
            begin(player, arriving.copy(), new GuardedSlot(watched), after, carriedBefore, carriedNow);
            return;
        }

        // Selecting a different hotbar slot puts its stack in the player's hand without changing a
        // single slot, so the diff above cannot see it: what moved is the set of watched slots. That
        // is exactly what a guarded slot means, so the switch is checked on its own.
        if (previousSelected != null && previousSelected != selected) {
            ItemStack arriving = current.get(selected);
            if (!arriving.isEmpty() && !isTrusted(player, arriving)) {
                // Nothing is undone here: a switch leaves no trace in the inventory, and putting the
                // inventory back would only undo work the player did elsewhere in the same tick.
                begin(player, arriving.copy(), new GuardedSlot(selected));
            }
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

    /** {@return a copy of the inventory menu's carried item, which is the mouse cursor's stack} */
    private static ItemStack carried(ServerPlayer player) {
        return player.containerMenu.getCarried().copy();
    }

    /**
     * Puts the slots an operation touched - and the carried item - back to an earlier state.
     *
     * <p>Only the touched slots are written, rather than the inventory as a whole, so anything the
     * player did elsewhere in the same tick survives.
     */
    private static void restore(ServerPlayer player, List<ItemStack> previous, Set<Integer> touched, ItemStack carried) {
        Inventory inventory = player.getInventory();
        for (int slot : touched) {
            if (slot >= 0 && slot < previous.size() && slot < inventory.getContainerSize()) {
                inventory.setItem(slot, previous.get(slot).copy());
            }
        }
        player.containerMenu.setCarried(carried.copy());
    }

    /** Writes a recorded footprint back, which is what re-applies an intercepted operation. */
    private static void apply(ServerPlayer player, Map<Integer, ItemStack> footprint, ItemStack carried) {
        Inventory inventory = player.getInventory();
        for (Map.Entry<Integer, ItemStack> entry : footprint.entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < inventory.getContainerSize()) {
                inventory.setItem(slot, entry.getValue().copy());
            }
        }
        player.containerMenu.setCarried(carried.copy());
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

    private static void begin(ServerPlayer player, ItemStack stack, GuardedSlot slot,
            Map<Integer, ItemStack> after, ItemStack carriedBefore, ItemStack carriedAfter) {
        UUID id = player.getUUID();
        if (PENDING.containsKey(id)) {
            return;
        }
        int ticks = GKConfig.rollVerificationTicks(player.getRandom());
        PENDING.put(id, new Pending(stack.copy(), slot, after, carriedBefore, carriedAfter,
                player.level().getGameTime() + ticks));
        GKNetwork.startVerification(player, stack, ticks);
    }

    /** Starts a verification for an operation that changed nothing: a use that was vetoed. */
    private static void begin(ServerPlayer player, ItemStack stack, GuardedSlot slot) {
        ItemStack carried = carried(player);
        begin(player, stack, slot, Map.of(), carried, carried);
    }

    private static void resolve(ServerPlayer player, Pending pending) {
        UUID id = player.getUUID();
        ItemStack stack = pending.stack();
        String itemId = SourceRecorder.itemId(stack);
        Identifier registryId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        TrustLevel trust = registryId == null ? TrustLevel.UNTRUSTED : TrustService.resolve(registryId);

        if (trust.passesSilently()) {
            // Fully trusted: no dialog. The verdict still has to be sent, because that is what tells
            // the client the wait is over - without it the progress bar stayed on screen for ever.
            // The client draws nothing for a silent verdict.
            PENDING.remove(id);
            apply(player, pending.after(), pending.carriedAfter());
            // The snapshot still describes the state from before the interception, so it has to go:
            // leaving it behind made the next tick read the stack this method just put back as a
            // brand new change, and the item was verified over and over for ever.
            SNAPSHOTS.remove(id);
            ItemSource origin = player.getData(GKAttachments.SOURCES.get()).latestFor(itemId);
            GKNetwork.sendVerificationResult(player, stack, trust, origin,
                    origin == null ? "" : origin.acquiredBy(),
                    origin == null ? 0L : origin.acquiredAt());
            GetsKeeper.LOGGER.debug("{} passed verification without a dialog", itemId);
            return;
        }

        if (trust == TrustLevel.UNTRUSTED) {
            // Only untrusted items are offered a "use anyway": a blocked one cannot be granted here at
            // all, so listing it would advertise a button that does nothing.
            SessionGuards.mark(id, itemId);
            GKNetwork.sendSessionBlocked(player);
        }

        ItemSource source = player.getData(GKAttachments.SOURCES.get()).latestFor(itemId);
        GKNetwork.sendVerificationResult(player, stack, trust, source,
                source == null ? "" : source.acquiredBy(),
                source == null ? 0L : source.acquiredAt());
        // Stay pending until the player answers; the resolver below is what ends it.
        PENDING.put(id, new Pending(stack, pending.slot(), pending.after(), pending.carriedBefore(),
                pending.carriedAfter(), Long.MAX_VALUE));
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
                int handedBack = returnToInventory(player, pending);
                GetsKeeper.LOGGER.info("{} cancelled the use of {} ({} stack(s) had to be handed back)",
                        player.getScoreboardName(), SourceRecorder.itemId(pending.stack()), handedBack);
            }
        }

        SNAPSHOTS.remove(id);
        CARRIED.remove(id);
    }

    /**
     * Hands an intercepted stack back, but only when the undo could not have done it already.
     *
     * <p>Undoing an interception puts every slot the operation touched - and the cursor - back, so for
     * a move inside the inventory the stack is already where it was and adding it again would hand the
     * player a second copy. What the undo cannot put back is a stack that came from outside the
     * player's own slots - out of a chest, say - because those slots are not ours to restore. That is
     * the one that has to be handed over here. Picking a different hotbar slot never removed anything.
     *
     * @return how many stacks were handed back
     */
    private static int returnToInventory(ServerPlayer player, Pending pending) {
        if (pending.slot().index() < 0 || pending.stack().isEmpty() || holds(player, pending.stack())) {
            return 0;
        }
        ItemStack returning = pending.stack().copy();
        if (!player.getInventory().add(returning) && !returning.isEmpty()) {
            // Nowhere to put it: drop it rather than delete it.
            player.drop(returning, false);
        }
        return 1;
    }

    /**
     * {@return whether the inventory already holds as much of that stack as was intercepted}
     *
     * <p>The test is on the total count rather than on any one slot, because an undo can restore a
     * stack of the same item split across slots - a merge has two ends as well. Counting makes the
     * answer one sided, which is what keeps it safe: an undo restores whole stacks, so an inventory
     * that holds less than the intercepted amount cannot possibly be holding the stack in question,
     * and handing another copy over cannot duplicate it. All the test can be wrong about is the
     * mirror image - an inventory that happens to hold enough of the same item anyway - and then the
     * stack is simply not handed back a second time.
     */
    private static boolean holds(ServerPlayer player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        int found = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack held = inventory.getItem(slot);
            if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, stack)) {
                found += held.getCount();
                if (found >= stack.getCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Runs the intercepted operation again, by writing back the slots it had changed and the cursor
     * it had moved.
     *
     * <p>Re-applying only the watched slot would duplicate the stack, because the undo already put a
     * copy of it back wherever it came from. Writing the whole footprint is what makes a swap come out
     * as a swap rather than as two copies, and it is also what puts a dragged stack back on the cursor
     * between its two clicks.
     */
    private static void reapply(ServerPlayer player, Pending pending) {
        if (pending.slot().index() < 0) {
            // The operation was a use, not a slot move. Nothing was undone and there is nothing to put
            // back; the player simply repeats the action.
            return;
        }
        try {
            apply(player, pending.after(), pending.carriedAfter());
        } catch (RuntimeException exception) {
            GetsKeeper.LOGGER.debug("Could not re-apply an intercepted operation", exception);
        }
    }

    /**
     * Clears the verified stack from the inventory, and any other stack of the same item with it.
     *
     * <p>The undo has already put the inventory back the way it was, so the stack to destroy is
     * wherever it was before the operation - and it is matched on the item and its components rather
     * than on an exact count, because that copy may well hold a different amount than the one that was
     * being moved.
     */
    private static int voidStacks(ServerPlayer player, Pending pending) {
        Inventory inventory = player.getInventory();
        int cleared = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, pending.stack())) {
                inventory.setItem(slot, ItemStack.EMPTY);
                cleared++;
            }
        }
        if (player.containerMenu.getCarried() instanceof ItemStack carried
                && !carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, pending.stack())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            cleared++;
        }
        return cleared;
    }
}
