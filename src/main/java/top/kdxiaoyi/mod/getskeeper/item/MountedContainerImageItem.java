package top.kdxiaoyi.mod.getskeeper.item;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;

/**
 * The "mounted container image" item.
 *
 * <p>GateKeeper's container image is a token: while it is in your inventory, the items it vouches
 * for are yours. This mod keeps the same idea, and adds two hard rules that make the token
 * tamper-proof:
 *
 * <ul>
 *   <li>It is never dropped. Dropping it destroys it, so it can never be picked up a second time,
 *       and no {@code ItemEntity} for it ever exists in the world.</li>
 *   <li>It is never left behind on death.</li>
 * </ul>
 */
public class MountedContainerImageItem extends Item {
    public MountedContainerImageItem(Properties properties) {
        super(properties);
    }

    /** {@return {@code true} when the stack is a mounted container image} */
    public static boolean isImage(ItemStack stack) {
        return stack.getItem() instanceof MountedContainerImageItem;
    }

    /**
     * Creates a sealed image for the given owner.
     *
     * @param level   the level the image is mounted in, recorded as the seal's dimension
     * @param owner   the name of the player the image belongs to
     */
    public static ItemStack createSealedImage(ServerLevel level, String owner) {
        ItemStack stack = new ItemStack(GKItems.MOUNTED_CONTAINER_IMAGE.get());
        Identifier dimension = level.dimension().identifier();
        stack.set(GKDataComponents.IMAGE_SEAL.get(), new MountedImageSeal(dimension, System.currentTimeMillis(), owner));
        return stack;
    }

    /**
     * Called by vanilla before an item leaves the inventory as a dropped entity.
     *
     * <p>Returning {@code false} cancels the drop; emptying the stack is what actually destroys the
     * image. Both halves are needed: the first keeps vanilla from spawning an item entity, the
     * second keeps the image from staying in the inventory forever.
     */
    @Override
    public boolean onDroppedByPlayer(ItemStack stack, Player player) {
        if (stack.getCount() > 0) {
            GetsKeeper.LOGGER.debug("Destroying a mounted container image dropped by {}", player.getScoreboardName());
            stack.setCount(0);
        }
        return false;
    }
}
