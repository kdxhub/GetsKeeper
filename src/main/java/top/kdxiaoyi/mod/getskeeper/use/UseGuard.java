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
import net.minecraft.world.entity.EquipmentSlot;
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
    /** Which slot an intercepted operation was aiming for. */
    private record GuardedSlot(String kind, int index) {}

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
        return AuthorizationService.isUsable(player, stack);
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
        begin(player, moving, new GuardedSlot("work", -1));
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
        begin(player, stack, new GuardedSlot("use", -1));
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
        begin(player, stack, new GuardedSlot("use", -1));
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

        List<GuardedSlot> slots = guardedSlots(player);
        List<ItemStack> current = snapshot(player, slots);
        List<ItemStack> previous = SNAPSHOTS.put(id, current);
        if (previous == null || previous.size() != current.size()) {
            return;
        }

        for (int i = 0; i < slots.size(); i++) {
            ItemStack before = previous.get(i);
            ItemStack after = current.get(i);
            if (after.isEmpty() || sameStack(before, after) || isTrusted(player, after)) {
                continue;
            }

            GuardedSlot slot = slots.get(i);
            // Step one of the design: undo the operation before asking anything.
            player.getInventory().setItem(slot.index(), before.copy());
            SNAPSHOTS.put(id, snapshot(player, slots));
            begin(player, after.copy(), slot);
            return;
        }
    }

    /**
     * The slots whose contents are verified.
     *
     * <p>The player's own inventory is deliberately not on this list: moving an item around inside
     * the backpack is not using it. Hands and equipment are.
     */
    private static List<GuardedSlot> guardedSlots(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        Set<Integer> indices = new LinkedHashSet<>();
        indices.add(inventory.getSelectedSlot());
        indices.add(Inventory.SLOT_OFFHAND);
        for (int index : Inventory.EQUIPMENT_SLOT_MAPPING.keySet()) {
            indices.add(index);
        }

        List<GuardedSlot> slots = new ArrayList<>(indices.size());
        for (int index : indices) {
            EquipmentSlot equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(index);
            slots.add(new GuardedSlot(equipment == null ? "inventory" : equipment.name().toLowerCase(java.util.Locale.ROOT), index));
        }
        return slots;
    }

    private static List<ItemStack> snapshot(ServerPlayer player, List<GuardedSlot> slots) {
        List<ItemStack> stacks = new ArrayList<>(slots.size());
        for (GuardedSlot slot : slots) {
            stacks.add(player.getInventory().getItem(slot.index()).copy());
        }
        return stacks;
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
                reapply(player, pending);
                GetsKeeper.LOGGER.info("{} chose to continue using {}", player.getScoreboardName(),
                        SourceRecorder.itemId(pending.stack()));
            }
            case VOID -> {
                int cleared = voidStacks(player, pending);
                GetsKeeper.LOGGER.info("{} moved {} to the void ({} stack(s))", player.getScoreboardName(),
                        SourceRecorder.itemId(pending.stack()), cleared);
            }
            case CANCEL -> GetsKeeper.LOGGER.info("{} cancelled the use of {}", player.getScoreboardName(),
                    SourceRecorder.itemId(pending.stack()));
        }

        SNAPSHOTS.remove(id);
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
