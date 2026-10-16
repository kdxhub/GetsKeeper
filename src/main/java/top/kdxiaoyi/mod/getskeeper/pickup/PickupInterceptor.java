package top.kdxiaoyi.mod.getskeeper.pickup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

/**
 * Holds items back until the player has explicitly picked them up.
 *
 * <p>Everything a player walks over is checked against the session cache. Items the player has
 * already picked up this session behave exactly like vanilla. Anything new cancels the vanilla
 * pickup, scans a sphere around the player and offers the whole batch on the pickup screen.
 *
 * <p>Only one batch can be open per player at a time, and a declined batch puts every source on a
 * short cooldown. That is what keeps the screen from reopening every tick while the player stands
 * on top of an item, and it is also what makes the screen show up for one player at a time: the
 * first player to open a batch owns it, while others are still free to pick the item up the vanilla
 * way once the owner declines.
 */
public final class PickupInterceptor {
    private static final int ARROW_SCAN_INTERVAL = 2;

    private PickupInterceptor() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onItemEntityPickup);
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(PickupInterceptor::onServerStopping);
    }

    /** Fired when a player collides with an item entity. */
    private static void onItemEntityPickup(ItemEntityPickupEvent.Pre event) {
        if (!GKConfig.pickupEnabled() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemEntity().getItem();
        if (stack.isEmpty()) {
            return;
        }
        String itemId = SourceRecorder.itemId(stack);
        if (PickupSession.hasPicked(player.getUUID(), itemId)) {
            // Already picked up this session: let vanilla do its thing.
            return;
        }

        event.setCanPickup(TriState.FALSE);
        offer(player, List.of(), event.getItemEntity().getUUID());
    }

    /**
     * Rescans every other tick for stuck arrows.
     *
     * <p>Arrows never fire a pickup event: vanilla adds them to the inventory from inside entity
     * collision handling. Setting the (public) {@code pickup} field to {@code DISALLOWED} suspends
     * that, and it is put back if the player declines, so nothing is destroyed by merely offering.
     */
    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }

        long now = player.level().getGameTime();
        PickupSession.pruneCooldowns(now);

        if (!GKConfig.pickupEnabled() || !GKConfig.includeArrows()) {
            return;
        }
        UUID playerId = player.getUUID();
        if (PickupSession.batch(playerId) != null) {
            return;
        }
        if (now % ARROW_SCAN_INTERVAL != 0) {
            return;
        }

        for (AbstractArrow arrow : pickableArrows(player)) {
            if (PickupSession.isCoolingDown(playerId, arrow.getUUID(), now)) {
                continue;
            }
            ItemStack stack = arrow.getPickupItem().copy();
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = SourceRecorder.itemId(stack);
            if (PickupSession.hasPicked(playerId, itemId)) {
                continue;
            }

            PickupCandidate candidate = PickupCandidate.arrow(arrow.getUUID(), stack, itemId);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            if (!offer(player, List.of(candidate), arrow.getUUID())) {
                // The screen did not open after all, so give vanilla its pickup back.
                arrow.pickup = AbstractArrow.Pickup.ALLOWED;
            }
            return;
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        restoreSuspendedArrows(player);
        PickupSession.clear(player.getUUID());
        SourceRecorder.forget(player.getUUID());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            restoreSuspendedArrows(player);
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
                if (candidate.kind() == PickupCandidate.Kind.ARROW) {
                    restoreArrow(player, candidate.sourceId());
                }
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

    private static List<AbstractArrow> pickableArrows(ServerPlayer player) {
        AABB box = player.getBoundingBox().inflate(GKConfig.pickupRadius());
        List<AbstractArrow> found = player.level().getEntitiesOfClass(AbstractArrow.class, box);

        List<AbstractArrow> pickable = new ArrayList<>(found.size());
        for (AbstractArrow arrow : found) {
            if (!arrow.isInGround() || arrow.pickup != AbstractArrow.Pickup.ALLOWED) {
                continue;
            }
            if (arrow.getPickupItem().isEmpty()) {
                continue;
            }
            pickable.add(arrow);
        }
        pickable.sort(Comparator.comparingDouble(arrow -> arrow.distanceToSqr(player)));
        return pickable;
    }

    // --- helpers -----------------------------------------------------------------------------

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

    private static void restoreSuspendedArrows(ServerPlayer player) {
        PickupSession.Batch batch = PickupSession.batch(player.getUUID());
        if (batch == null) {
            return;
        }
        for (PickupCandidate candidate : batch.candidates()) {
            if (candidate.kind() == PickupCandidate.Kind.ARROW) {
                restoreArrow(player, candidate.sourceId());
            }
        }
    }
}
