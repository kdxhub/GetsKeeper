package top.kdxiaoyi.mod.getskeeper.pickup;

import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/**
 * One thing the pickup screen is offering.
 *
 * <p>Two kinds of source exist, because vanilla treats them very differently:
 *
 * <ul>
 *   <li>{@link Kind#ITEM_ENTITY} - an {@code ItemEntity} on the ground. It is left untouched while
 *       the screen is open and only consumed when the player accepts.</li>
 *   <li>{@link Kind#ARROW} - a stuck arrow. Vanilla picks arrows up inside entity collision code,
 *       which no event can veto, so {@code AbstractArrow.pickup} (a public field) is switched to
 *       {@code DISALLOWED} while the screen is open and switched back if the player declines. That
 *       suspends the pickup without removing anything from the world, so a cancelled screen costs
 *       the player nothing.</li>
 * </ul>
 *
 * @param sourceId the entity behind this candidate
 * @param stack    a copy of the stack, for display and for the accept path
 * @param itemId   {@code namespace:path} of the stack, used for the "already picked up" cache
 * @param kind     how the source has to be handled
 */
public record PickupCandidate(UUID sourceId, ItemStack stack, String itemId, Kind kind) {
    public enum Kind {
        ITEM_ENTITY,
        ARROW
    }

    public static PickupCandidate itemEntity(UUID sourceId, ItemStack stack, String itemId) {
        return new PickupCandidate(sourceId, stack, itemId, Kind.ITEM_ENTITY);
    }

    public static PickupCandidate arrow(UUID sourceId, ItemStack stack, String itemId) {
        return new PickupCandidate(sourceId, stack, itemId, Kind.ARROW);
    }
}
