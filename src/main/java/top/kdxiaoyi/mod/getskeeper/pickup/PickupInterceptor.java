package top.kdxiaoyi.mod.getskeeper.pickup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.config.GKConfig;
import top.kdxiaoyi.mod.getskeeper.item.MountedContainerImageItem;
import top.kdxiaoyi.mod.getskeeper.net.GKNetwork;
import top.kdxiaoyi.mod.getskeeper.source.SourceRecorder;
import top.kdxiaoyi.mod.getskeeper.use.UseGuard;

/**
 * Holds items back until the player has explicitly picked them up.
 *
 * <p>Everything a player walks over is checked against the session cache. Items the player has
 * already picked up this session behave exactly like vanilla, but only while no batch is open.
 * Anything else is held in the world first and offered second: the vanilla pickup is refused whether
 * or not the screen ends up opening, and the offer is simply tried again on the next collision. That
 * order is what makes the rule "nothing enters your inventory while this mod is deciding" true, and
 * it is why an offered batch scans a sphere around the player rather than looking at one entity.
 *
 * <p>Only one batch can be open per player at a time, and a declined batch puts every source on a
 * short cooldown. That is what keeps the screen from reopening every tick while the player stands
 * on top of an item, and it is also what makes the screen show up for one player at a time: the
 * first player to open a batch owns it, while others are still free to pick the item up the vanilla
 * way once the owner declines.
 *
 * <p>Two things that reach the inventory without a screen are still this mod's doing rather than the
 * player's - a cached item vanilla takes, and a batch the screen hands over - so both tell the use
 * guard to start from the inventory as it is now. Without that, the arrival reads as a stack that
 * just moved into the player's hand and starts a verification over whatever they were doing.
 */
public final class PickupInterceptor {
    private static final int ARROW_SCAN_INTERVAL = 2;

    /** Below this squared speed an arrow counts as stopped; see {@link #hasComeToRest}. */
    private static final double RESTING_SPEED_SQR = 1.0E-4;

    /**
     * Arrows this mod has taken out of vanilla's hands and still owes an answer on.
     *
     * <p>An arrow's pickup is suspended by flipping its public {@code pickup} field, which leaves the
     * arrow indistinguishable from one vanilla means nobody to take. This set is what tells the two
     * apart, so it has to hold exactly the arrows the mod is holding itself.
     */
    private static final Map<UUID, Set<UUID>> HELD = new HashMap<>();

    private PickupInterceptor() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onItemEntityPickup);
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onItemEntityPickedUp);
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onServerStopping);
    }

    /** Fired when a player collides with an item entity. */
    private static void onItemEntityPickup(ItemEntityPickupEvent.Pre event) {
        if (!GKConfig.pickupEnabled() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemEntity entity = event.getItemEntity();
        ItemStack stack = entity.getItem();
        if (stack.isEmpty() || !isOfferable(entity, stack)) {
            return;
        }
        UUID playerId = player.getUUID();
        if (PickupSession.batch(playerId) == null
                && PickupSession.hasPicked(playerId, SourceRecorder.itemId(stack))) {
            // Picked up this session already, and no answer is outstanding: vanilla takes it, which is
            // what keeps the screen from appearing for every further piece of dirt. Note the batch
            // test: while the player is answering one, nothing at all is let through, because a stack
            // arriving in the middle of that answer is what used to bury the question under a
            // verification.
            return;
        }

        // Everything else is held in the world first and offered second, in that order. Vetoing only
        // once the offer had succeeded let stacks slip in whenever the offer was refused instead -
        // while a batch was already up, or while the source was on cooldown - and a stack that slips
        // in lands in the selected slot, so it started a verification on top of the pickup screen.
        // Held, it is offered again as soon as the reasons for refusing are gone: vanilla fires this
        // event every tick, from Player#aiStep, for as long as the player stands near the item.
        event.setCanPickup(TriState.FALSE);
        offer(player, List.of(), entity.getUUID());
    }

    /**
     * Fired once vanilla has actually put a stack into the player's inventory.
     *
     * <p>The arrival is this mod's decision rather than the player's move - either the stack was in
     * the session cache and let through, or the pickup screen handed it over - so it must not read as
     * a use. Vanilla fills the selected slot first (see {@code Inventory#getSlotWithRemainingSpace}),
     * and the selected slot is a guarded one, so without this every pickup would start a verification
     * the moment it landed, in the middle of whatever the player was doing.
     */
    private static void onItemEntityPickedUp(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            UseGuard.resync(player);
        }
    }

    /**
     * Rescans every other tick for stuck arrows.
     *
     * <p>Arrows never fire a pickup event: vanilla adds them to the inventory from inside entity
     * collision handling. Setting the (public) {@code pickup} field to {@code DISALLOWED} suspends
     * that, and it is put back when the mod stops handling the arrow, so nothing is destroyed by
     * merely offering.
     */
    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }

        long now = player.level().getGameTime();
        PickupSession.pruneCooldowns(now);
        if (now % ARROW_SCAN_INTERVAL != 0) {
            return;
        }

        boolean scanning = GKConfig.pickupEnabled() && GKConfig.includeArrows();
        UUID playerId = player.getUUID();
        if (!scanning && !HELD.containsKey(playerId)) {
            return;
        }

        AABB box = player.getBoundingBox().inflate(GKConfig.pickupRadius());
        List<AbstractArrow> found = player.level().getEntitiesOfClass(AbstractArrow.class, box);
        releaseOutOfReach(player, found);
        if (!scanning) {
            return;
        }

        for (AbstractArrow arrow : pickableArrows(player, found)) {
            ItemStack stack = arrow.getPickupItemStackOrigin().copy();
            if (stack.isEmpty()) {
                releaseArrow(player, arrow.getUUID());
                continue;
            }
            String itemId = SourceRecorder.itemId(stack);
            if (PickupSession.hasPicked(playerId, itemId)) {
                // The player has been asked about this item already this session, so from here on it
                // is vanilla's business. Releasing it matters: a held arrow is invisible to vanilla,
                // and holding one the mod will never offer would be holding an arrow nobody can take.
                releaseArrow(player, arrow.getUUID());
                continue;
            }
            if (PickupSession.isCoolingDown(playerId, arrow.getUUID(), now)) {
                // Declined a moment ago. It stays held, so vanilla cannot take it behind the screen's
                // back, and it is offered again once the cooldown is out.
                continue;
            }

            // Hold the arrow before asking about it, exactly like the item path, and for the same
            // reason: an arrow vanilla takes silently is an arrow in the player's hand, and the hand
            // is a guarded slot, so the verification would land on top of the screen.
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            HELD.computeIfAbsent(playerId, key -> new HashSet<>()).add(arrow.getUUID());
            PickupCandidate candidate = PickupCandidate.arrow(arrow.getUUID(), stack, itemId);
            if (offer(player, List.of(candidate), arrow.getUUID())) {
                return;
            }
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        releaseHeldArrows(player);
        PickupSession.clear(player.getUUID());
        SourceRecorder.forget(player.getUUID());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            releaseHeldArrows(player);
        }
        PickupSession.clearAll();
    }

    // --- the batch ---------------------------------------------------------------------------

    /**
     * Offers a pickup batch to a player.
     *
     * @param player   the player to offer it to
     * @param extra    candidates discovered by the caller (suspended arrows)
     * @param focusId  the entity that triggered the offer, used for the cooldown check
     * @return {@code true} when a screen was opened
     */
    private static boolean offer(ServerPlayer player, List<PickupCandidate> extra, UUID focusId) {
        UUID playerId = player.getUUID();
        if (PickupSession.batch(playerId) != null) {
            return false;
        }

        long now = player.level().getGameTime();
        if (PickupSession.isCoolingDown(playerId, focusId, now)) {
            return false;
        }

        List<PickupCandidate> candidates = new ArrayList<>(extra);
        candidates.addAll(scanItems(player));
        if (candidates.isEmpty()) {
            return false;
        }

        List<ItemStack> stacks = new ArrayList<>(candidates.size());
        for (PickupCandidate candidate : candidates) {
            stacks.add(candidate.stack());
        }

        InventoryProbe.Result probe = InventoryProbe.probe(player.getInventory(), stacks);
        boolean fits = probe.fits();

        // The image is handed over when the screen opens, but only when there is room for it. A full
        // inventory still opens the screen - it just opens without the image and with a warning.
        if (fits && !holdsImage(player)) {
            ItemStack image = MountedContainerImageItem.createSealedImage(
                    (ServerLevel) player.level(), player.getScoreboardName());
            if (!player.getInventory().add(image)) {
                fits = false;
            }
        }

        if (!fits && probe.leftover().isEmpty() && InventoryProbe.freeSlots(player.getInventory()) <= 0) {
            GetsKeeper.LOGGER.debug("{} cannot mount a container image: inventory is full", player.getScoreboardName());
        }

        PickupSession.begin(playerId, candidates, now);
        GKNetwork.askPickupScreen(player, candidates, !fits);
        return true;
    }

    /**
     * Applies the player's answer.
     *
     * @param player the player who answered
     * @param accept {@code true} to take the batch, {@code false} to leave everything where it is
     */
    public static void handleDecision(ServerPlayer player, boolean accept) {
        UUID playerId = player.getUUID();
        PickupSession.Batch batch = PickupSession.batch(playerId);
        if (batch == null) {
            return;
        }
        PickupSession.end(playerId);

        long now = player.level().getGameTime();
        int cooldown = GKConfig.pickupCooldownTicks();

        if (!accept) {
            for (PickupCandidate candidate : batch.candidates()) {
                // A declined arrow stays held. It is put back in vanilla's hands when the mod stops
                // handling it - when the player walks out of reach - and until then it is offered
                // again, which is what stops vanilla from taking it behind the screen's back.
                PickupSession.cool(playerId, candidate.sourceId(), now + cooldown);
            }
            return;
        }

        boolean changed = false;
        for (PickupCandidate candidate : batch.candidates()) {
            if (!handOver(player, candidate)) {
                PickupSession.cool(playerId, candidate.sourceId(), now + cooldown);
                continue;
            }
            PickupSession.markPicked(playerId, candidate.itemId());
            changed = true;
        }
        if (changed) {
            SourceRecorder.sample(player);
            // The batch went in through this mod's own hands rather than the player's, so the
            // use guard has to start from the inventory as it is now. Without this it compares
            // against the state from before the hand-over, reads the batch as a stack that just
            // moved into a guarded slot, and undoes the whole thing back out again.
            UseGuard.resync(player);
        }
    }

    /** Moves one candidate into the player's inventory, then removes the source. */
    private static boolean handOver(ServerPlayer player, PickupCandidate candidate) {
        Entity source = player.level().getEntity(candidate.sourceId());

        ItemStack stack = switch (candidate.kind()) {
            case ITEM_ENTITY -> source instanceof ItemEntity itemEntity ? itemEntity.getItem() : ItemStack.EMPTY;
            case ARROW -> candidate.stack();
        };
        if (stack.isEmpty()) {
            return false;
        }

        ItemStack toGive = stack.copy();
        boolean fullyAdded = player.getInventory().add(toGive);
        if (!toGive.isEmpty()) {
            // Anything that did not fit is dropped in front of the player rather than deleted.
            player.drop(toGive, false);
        }

        SourceRecorder.recordPickup(player, stack, source != null ? source.position() : player.position());
        if (source != null) {
            source.discard();
        }
        if (candidate.kind() == PickupCandidate.Kind.ARROW) {
            // The arrow has left the world, so the mod is done holding it.
            releaseArrow(player, candidate.sourceId());
        }
        if (!fullyAdded) {
            GetsKeeper.LOGGER.debug("Pickup batch for {} overflowed the inventory", player.getScoreboardName());
        }
        return true;
    }

    // --- scanning ----------------------------------------------------------------------------

    private static List<PickupCandidate> scanItems(ServerPlayer player) {
        UUID playerId = player.getUUID();
        long now = player.level().getGameTime();
        AABB box = player.getBoundingBox().inflate(GKConfig.pickupRadius());

        List<ItemEntity> found = player.level().getEntitiesOfClass(ItemEntity.class, box);
        found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));

        List<PickupCandidate> candidates = new ArrayList<>(found.size());
        for (ItemEntity entity : found) {
            ItemStack stack = entity.getItem();
            if (stack.isEmpty() || !entity.isAlive()) {
                continue;
            }
            if (!isOfferable(entity, stack)) {
                continue;
            }
            if (PickupSession.isCoolingDown(playerId, entity.getUUID(), now)) {
                continue;
            }
            String itemId = SourceRecorder.itemId(stack);
            if (PickupSession.hasPicked(playerId, itemId)) {
                continue;
            }
            candidates.add(PickupCandidate.itemEntity(entity.getUUID(), stack.copy(), itemId));
        }
        return candidates;
    }

    private static List<AbstractArrow> pickableArrows(ServerPlayer player, List<AbstractArrow> found) {
        UUID playerId = player.getUUID();
        List<AbstractArrow> pickable = new ArrayList<>(found.size());
        for (AbstractArrow arrow : found) {
            // An arrow whose pickup the mod suspended itself is still the mod's business even though
            // its flag says otherwise; one suspended by vanilla, or by an infinity bow, is not.
            if (arrow.pickup != AbstractArrow.Pickup.ALLOWED && !isHeld(playerId, arrow.getUUID())) {
                continue;
            }
            if (!hasComeToRest(arrow)) {
                continue;
            }
            if (arrow.getPickupItemStackOrigin().isEmpty()) {
                continue;
            }
            pickable.add(arrow);
        }
        pickable.sort(Comparator.comparingDouble(arrow -> arrow.distanceToSqr(player)));
        return pickable;
    }

    /**
     * {@return whether an arrow has stopped and can be taken}
     *
     * <p>Vanilla gates arrow pickup on {@code inGround || isNoPhysics()}. {@code isInGround()} is
     * protected and this mod deliberately stays on the public API - no mixin, no access transformer -
     * so the resting state is inferred instead: an arrow is takeable once it reports itself as
     * supported, has had physics switched off, or has simply stopped moving. A mid-flight arrow fails
     * all three, which is the case that matters, because intercepting one would hand the player a
     * projectile that is still in the air.
     */
    private static boolean hasComeToRest(AbstractArrow arrow) {
        return arrow.isNoPhysics() || arrow.onGround() || arrow.getDeltaMovement().lengthSqr() < RESTING_SPEED_SQR;
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * {@return whether this ground stack is something the screen may be opened for}
     *
     * <p>Two kinds of stack are quietly skipped:
     *
     * <ul>
     *   <li>the mod's own image, which is never handled by any part of the mod, and</li>
     *   <li>anything still inside its pickup delay. Vanilla gives a stack that was just thrown or
     *       dropped a short grace period before anyone may take it, and that is exactly the moment a
     *       player is most likely to be standing on top of it - so without this check, throwing an
     *       item away immediately offered it back as a pickup.</li>
     * </ul>
     */
    private static boolean isOfferable(ItemEntity entity, ItemStack stack) {
        return !MountedContainerImageItem.isImage(stack) && !entity.hasPickUpDelay();
    }

    private static boolean holdsImage(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (MountedContainerImageItem.isImage(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private static void restoreArrow(ServerPlayer player, UUID arrowId) {
        if (player.level().getEntity(arrowId) instanceof AbstractArrow arrow) {
            arrow.pickup = AbstractArrow.Pickup.ALLOWED;
        }
    }

    /** {@return whether this mod is the reason that arrow's pickup is suspended} */
    private static boolean isHeld(UUID playerId, UUID arrowId) {
        Set<UUID> held = HELD.get(playerId);
        return held != null && held.contains(arrowId);
    }

    /**
     * Gives vanilla its pickup back, and stops holding the arrow.
     *
     * <p>A held arrow is invisible to vanilla, so one left held is an arrow nobody can ever take. It
     * is released as soon as the mod is done with it: when the player answers and it is handed over,
     * and when the player walks out of reach, which is what keeps the held set equal to the arrows
     * the mod can still offer.
     */
    private static void releaseArrow(ServerPlayer player, UUID arrowId) {
        Set<UUID> held = HELD.get(player.getUUID());
        if (held != null) {
            held.remove(arrowId);
            if (held.isEmpty()) {
                // Keep the map free of empty sets: a leftover one would have this player's arrows
                // scanned for ever.
                HELD.remove(player.getUUID());
            }
        }
        restoreArrow(player, arrowId);
    }

    private static void releaseOutOfReach(ServerPlayer player, List<AbstractArrow> found) {
        Set<UUID> held = HELD.get(player.getUUID());
        if (held == null || held.isEmpty()) {
            return;
        }
        Set<UUID> inReach = new HashSet<>(found.size());
        for (AbstractArrow arrow : found) {
            inReach.add(arrow.getUUID());
        }
        for (Iterator<UUID> iterator = held.iterator(); iterator.hasNext();) {
            UUID arrowId = iterator.next();
            if (!inReach.contains(arrowId)) {
                iterator.remove();
                restoreArrow(player, arrowId);
            }
        }
        if (held.isEmpty()) {
            HELD.remove(player.getUUID());
        }
    }

    /** Hands every arrow this mod is holding back to vanilla. Used when a player leaves. */
    private static void releaseHeldArrows(ServerPlayer player) {
        Set<UUID> held = HELD.remove(player.getUUID());
        if (held == null) {
            return;
        }
        for (UUID arrowId : held) {
            restoreArrow(player, arrowId);
        }
    }
}
