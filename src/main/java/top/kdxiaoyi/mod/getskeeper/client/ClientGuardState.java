package top.kdxiaoyi.mod.getskeeper.client;

import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * What the client currently knows.
 *
 * <p>The client is a display for the server: it holds the batch it is being asked about, the session
 * list the privacy screen shows, and nothing that decides anything. Every mutation here comes from a
 * payload.
 */
public final class ClientGuardState {
    private static List<ItemStack> pickupStacks = List.of();
    private static List<String> pickupIds = List.of();
    private static boolean inventoryFull;
    private static String ownerName = "";

    private static List<String> sessionBlocked = List.of();

    private ClientGuardState() {}

    // --- pickup -------------------------------------------------------------------------------

    public static void setPickup(List<ItemStack> stacks, List<String> itemIds, boolean full, String owner) {
        pickupStacks = List.copyOf(stacks);
        pickupIds = List.copyOf(itemIds);
        inventoryFull = full;
        ownerName = owner;
    }

    public static List<ItemStack> pickupStacks() {
        return pickupStacks;
    }

    public static List<String> pickupIds() {
        return pickupIds;
    }

    public static boolean inventoryFull() {
        return inventoryFull;
    }

    public static String ownerName() {
        return ownerName;
    }

    public static void clearPickup() {
        pickupStacks = List.of();
        pickupIds = List.of();
        inventoryFull = false;
    }

    public static boolean hasPickup() {
        return !pickupStacks.isEmpty();
    }

    // --- session list -------------------------------------------------------------------------

    public static void setSessionBlocked(List<String> itemIds) {
        sessionBlocked = List.copyOf(itemIds);
    }

    public static List<String> sessionBlocked() {
        return sessionBlocked;
    }
}
