package top.kdxiaoyi.mod.getskeeper.source;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.item.MountedContainerImageItem;
import top.kdxiaoyi.mod.getskeeper.data.GKAttachments;

/**
 * Records where the items in a player's inventory came from.
 *
 * <p>Two mechanisms cooperate:
 *
 * <ul>
 *   <li><b>Direct hooks</b> for the acquisition paths vanilla announces properly: fishing, trading,
 *       crafting and smelting.</li>
 *   <li><b>An inventory diff</b> for everything else. Once per second the player's inventory is
 *       compared against the previous sample; item ids that gained items are attributed to the most
 *       recent "pending" source. Pending sources are opened by container closes and commands and
 *       expire after a few seconds, so they cannot leak into an unrelated later acquisition.</li>
 * </ul>
 *
 * <p>Anything the diff cannot explain is recorded as {@link SourceType#UNKNOWN} or, in creative mode,
 * as {@link SourceType#CREATIVE}. Other mods' custom paths therefore end up as {@code UNKNOWN},
 * which is the honest answer.
 */
public final class SourceRecorder {
    /** How long a pending source stays valid, in ticks. */
    private static final int PENDING_TTL = 200;

    /** How often the inventory is diffed, in ticks. */
    private static final int SWEEP_INTERVAL = 20;

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Map<String, Integer>> SAMPLES = new HashMap<>();
    private static final Map<UUID, Integer> NEXT_SWEEP = new HashMap<>();

    private SourceRecorder() {}

    private record Pending(ItemSource source, long expiresAt) {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onPickup);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onItemFished);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onTradeWithVillager);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onItemCrafted);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onItemSmelted);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onCommand);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onContainerClosed);
        NeoForge.EVENT_BUS.addListener(SourceRecorder::onPlayerTick);
    }

    /** Forgets everything cached for a player. Called when they leave the world. */
    public static void forget(UUID playerId) {
        PENDING.remove(playerId);
        SAMPLES.remove(playerId);
        NEXT_SWEEP.remove(playerId);
    }

    // --- Direct hooks --------------------------------------------------------------------------

    private static void onPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getOriginalStack();
        if (stack.isEmpty()) {
            return;
        }
        ItemSource source = ItemSource.at(
                player.level(),
                event.getItemEntity().getX(),
                event.getItemEntity().getY(),
                event.getItemEntity().getZ(),
                SourceType.PICKUP,
                itemId(stack));
        record(player, stack, source);
    }

    private static void onItemFished(ItemFishedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (ItemStack stack : event.getDrops()) {
            if (!stack.isEmpty()) {
                record(player, stack, ItemSource.at(
                        player.level(),
                        event.getHookEntity().getX(),
                        event.getHookEntity().getY(),
                        event.getHookEntity().getZ(),
                        SourceType.FISHING,
                        itemId(stack)));
            }
        }
    }

    private static void onTradeWithVillager(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // The offer's result is what the player receives; the event itself does not hand out a stack.
        ItemStack result = event.getMerchantOffer().getResult();
        if (!result.isEmpty()) {
            Identifier villagerId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getAbstractVillager().getType());
            record(player, result, ItemSource.locationless(
                    player.level(),
                    SourceType.TRADE,
                    villagerId == null ? "" : villagerId.toString()));
        }
    }

    private static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.getCrafting().isEmpty()) {
            record(player, event.getCrafting(), ItemSource.locationless(player.level(), SourceType.CRAFTING, ""));
        }
    }

    private static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.getSmelting().isEmpty()) {
            record(player, event.getSmelting(), ItemSource.locationless(player.level(), SourceType.FURNACE, ""));
        }
    }

    private static void onCommand(CommandEvent event) {
        Player player = event.getParseResults().getContext().getSource().getPlayer();
        if (player == null) {
            return;
        }
        String root = event.getParseResults().getReader().getString().trim();
        if (!root.startsWith("/")) {
            root = "/" + root;
        }
        // Commands that hand items out are marked as a pending source so that whatever appears in the
        // inventory in the next few seconds is attributed to the command rather than to nothing.
        PENDING.put(player.getUUID(), new Pending(
                ItemSource.locationless(player.level(), SourceType.COMMAND, root.split(" ")[0]),
                player.level().getGameTime() + PENDING_TTL));
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        PENDING.put(player.getUUID(), new Pending(
                ItemSource.at(level, pos, SourceType.CONTAINER, blockId == null ? "" : blockId.toString()),
                level.getGameTime() + PENDING_TTL));
    }

    private static void onContainerClosed(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Closing a container is what actually ends "taking things out of a chest": keep the pending
        // source alive briefly so the next sweep attributes the items to it.
        Pending pending = PENDING.get(player.getUUID());
        if (pending != null && pending.source().type() == SourceType.CONTAINER) {
            PENDING.put(player.getUUID(), new Pending(pending.source(), player.level().getGameTime() + PENDING_TTL));
        }
        sample(player);
    }

    // --- Inventory diff ------------------------------------------------------------------------

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        UUID id = player.getUUID();
        long now = player.level().getGameTime();

        Map<String, Integer> previous = SAMPLES.get(id);
        if (previous == null) {
            SAMPLES.put(id, snapshot(player));
            NEXT_SWEEP.put(id, (int) now + SWEEP_INTERVAL);
            return;
        }

        Integer next = NEXT_SWEEP.get(id);
        if (next != null && now < next) {
            return;
        }
        NEXT_SWEEP.put(id, (int) now + SWEEP_INTERVAL);

        Map<String, Integer> current = snapshot(player);
        attribute(player, previous, current, now);
        SAMPLES.put(id, current);
        expire(id, now);
    }

    private static void attribute(ServerPlayer player, Map<String, Integer> previous, Map<String, Integer> current, long now) {
        var ledger = player.getData(GKAttachments.SOURCES.get());
        boolean changed = false;

        for (Map.Entry<String, Integer> entry : current.entrySet()) {
            String itemId = entry.getKey();
            int increase = entry.getValue() - previous.getOrDefault(itemId, 0);
            if (increase <= 0) {
                continue;
            }
            // Never claim more items than are actually unexplained. The ledger is the single source
            // of truth for that, which keeps the direct hooks and the diff from double counting.
            int unexplained = Math.min(increase, Math.max(0, entry.getValue() - ledger.totalFor(itemId)));
            if (unexplained <= 0) {
                continue;
            }
            ItemSource source = resolveSource(player, now);
            ledger = ledger.withSource(itemId, source, unexplained);
            changed = true;
        }

        // Drop records for items that are gone, and trim counts back to what is really held.
        var retained = ledger.retained(current.keySet(), id -> current.getOrDefault(id, 0));
        if (!retained.equals(ledger)) {
            ledger = retained;
            changed = true;
        }

        if (changed) {
            player.setData(GKAttachments.SOURCES.get(), ledger);
        }
    }

    private static ItemSource resolveSource(ServerPlayer player, long now) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending != null && pending.expiresAt() >= now) {
            return pending.source();
        }
        if (player.isCreative()) {
            return ItemSource.locationless(player.level(), SourceType.CREATIVE, "");
        }
        return ItemSource.locationless(player.level(), SourceType.UNKNOWN, "");
    }

    private static void expire(UUID playerId, long now) {
        Pending pending = PENDING.get(playerId);
        if (pending != null && pending.expiresAt() < now) {
            PENDING.remove(playerId);
        }
    }

    /** Forces a resample, e.g. right after the pickup screen handed items over. */
    public static void sample(ServerPlayer player) {
        SAMPLES.put(player.getUUID(), snapshot(player));
    }

    private static Map<String, Integer> snapshot(ServerPlayer player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && isRecordable(stack)) {
                counts.merge(itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Records an acquisition that this mod itself performed, such as a completed pickup batch. */
    public static void recordPickup(ServerPlayer player, ItemStack stack, net.minecraft.world.phys.Vec3 position) {
        record(player, stack, ItemSource.at(
                player.level(), position.x, position.y, position.z, SourceType.PICKUP, itemId(stack)));
    }

    private static void record(ServerPlayer player, ItemStack stack, ItemSource source) {
        if (!isRecordable(stack)) {
            return;
        }
        var ledger = player.getData(GKAttachments.SOURCES.get());
        ItemSource stamped = source.withAcquisition(player.getScoreboardName(), System.currentTimeMillis());
        player.setData(GKAttachments.SOURCES.get(), ledger.withSource(itemId(stack), stamped, stack.getCount()));
    }

    /**
     * {@return whether this stack is one the records should mention}
     *
     * <p>The mod's own container image is never recorded. It is handed out by the mod itself, so
     * writing a source for it would only describe the mod to itself.
     */
    public static boolean isRecordable(ItemStack stack) {
        return !MountedContainerImageItem.isImage(stack);
    }

    /** {@return the {@code namespace:path} id of an item stack, ignoring components} */
    public static String itemId(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String value = id == null ? "minecraft:air" : id.toString();
        if (value.isEmpty()) {
            GetsKeeper.LOGGER.warn("Encountered an unregistered item while recording a source");
        }
        return value;
    }
}
