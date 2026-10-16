package top.kdxiaoyi.mod.getskeeper.item;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The "mounted container image" item.
 *
 * <p>GateKeeper's container image is a token: while it is in your inventory, the items it vouches
 * for are yours. This mod keeps the same idea, and adds two hard rules that make the token
 * tamper-proof:
 *
 * <ul>
 *   <li>It is never dropped. Dropping it destroys it, so it can never be picked up a second time,
 *       and no {@code ItemEntity} for it ever exists in the world. See {@link MountedImageGuard}.</li>
 *   <li>It is never left behind on death, and it survives its owner's death.</li>
 * </ul>
 *
 * <p>It is also exempt from every gate this mod installs: it is not verified, not intercepted on
 * pickup, and not entered into the source records. Verifying the image would mean verifying the tool
 * that does the verifying.
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
}
