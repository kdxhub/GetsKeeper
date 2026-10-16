package top.kdxiaoyi.mod.getskeeper.pickup;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Works out whether a batch of items fits in a player's inventory.
 *
 * <p>The answer is computed by simulating the insertion against a copy of the inventory rather than
 * by counting empty slots: partial stacks, items that merge into an existing stack and items whose
 * max stack size comes from a data component all have to be taken into account, and only a real
 * simulation gets those right.
 *
 * <p>One extra slot is always reserved for the mounted container image that goes with the batch.
 */
public final class InventoryProbe {
    private InventoryProbe() {}

    /**
     * @param freeSlots  empty slots before the batch is inserted
     * @param usedSlots  additional slots the batch would occupy
     * @param needed     {@code usedSlots + 1}, the reservation for the container image
     * @param fits       whether the batch plus the image fit
     * @param leftover   items that would not fit, for diagnostics
     */
    public record Result(int freeSlots, int usedSlots, int needed, boolean fits, List<ItemStack> leftover) {}

    /** {@return the number of completely empty slots} */
    public static int freeSlots(Inventory inventory) {
        int free = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * Simulates inserting {@code incoming} into the inventory.
     *
     * @param inventory the player's inventory, never modified
     * @param incoming  the stacks that are about to be picked up
     */
    public static Result probe(Inventory inventory, List<ItemStack> incoming) {
        int size = inventory.getContainerSize();
        List<ItemStack> slots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            slots.add(inventory.getItem(slot).copy());
        }

        int free = 0;
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) {
                free++;
            }
        }

        boolean[] consumed = new boolean[size];
        List<ItemStack> leftover = new ArrayList<>();

        for (ItemStack stack : incoming) {
            if (stack.isEmpty()) {
                continue;
            }
            int remaining = stack.getCount();

            // First pass: top up stacks of the same item that have room left. Merging needs the same
            // components, not just the same item, or two differently enchanted swords would merge.
            for (int slot = 0; slot < size && remaining > 0; slot++) {
                ItemStack existing = slots.get(slot);
                if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                    continue;
                }
                int room = existing.getMaxStackSize() - existing.getCount();
                if (room <= 0) {
                    continue;
                }
                int moved = Math.min(room, remaining);
                existing.grow(moved);
                remaining -= moved;
            }

            // Second pass: spill what is left into empty slots.
            for (int slot = 0; slot < size && remaining > 0; slot++) {
                if (!slots.get(slot).isEmpty()) {
                    continue;
                }
                int moved = Math.min(stack.getMaxStackSize(), remaining);
                ItemStack placed = stack.copy();
                placed.setCount(moved);
                slots.set(slot, placed);
                consumed[slot] = true;
                remaining -= moved;
            }

            if (remaining > 0) {
                ItemStack overflow = stack.copy();
                overflow.setCount(remaining);
                leftover.add(overflow);
            }
        }

        int used = 0;
        for (boolean flag : consumed) {
            if (flag) {
                used++;
            }
        }

        // The image is unstackable, so it always needs a slot of its own.
        int needed = used + 1;
        return new Result(free, used, needed, leftover.isEmpty() && free >= needed, leftover);
    }
}
