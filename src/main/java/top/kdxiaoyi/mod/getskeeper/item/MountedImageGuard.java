package top.kdxiaoyi.mod.getskeeper.item;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;

/**
 * Keeps the mounted container image out of the world.
 *
 * <p>The image is a token rather than a possession, so it is never allowed to exist as an entity and
 * is never scattered on the ground when its owner dies. Three hooks cover that between them.
 */
public final class MountedImageGuard {
    private MountedImageGuard() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(MountedImageGuard::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(MountedImageGuard::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(MountedImageGuard::onPlayerClone);
    }

    /**
     * Destroys an image the moment it would become an entity.
     *
     * <p>This is what "dropping it destroys it" means in practice. By the time the entity exists,
     * vanilla has already taken the stack out of the inventory, so refusing the entity <em>is</em> the
     * destruction. Cancelling the drop instead - the obvious reading of the same requirement - leaves
     * the item sitting in the inventory, which is not destroying it at all.
     */
    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity itemEntity
                && MountedContainerImageItem.isImage(itemEntity.getItem())) {
            event.setCanceled(true);
            GetsKeeper.LOGGER.debug("Destroyed a mounted container image that was about to enter the world");
        }
    }

    /** Keeps the image out of a death drop. */
    private static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        event.getDrops().removeIf(drop -> MountedContainerImageItem.isImage(drop.getItem()));
    }

    /**
     * Hands the image to the respawned player.
     *
     * <p>Because it never reaches the ground, a death would otherwise erase it silently, and with it
     * every batch it vouched for. The token has to outlive its owner.
     */
    private static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (ItemStack stack : imagesIn(event.getOriginal().getInventory())) {
            ItemStack returning = stack.copy();
            if (!player.getInventory().add(returning) && !returning.isEmpty()) {
                GetsKeeper.LOGGER.warn("{} died with no room for a mounted container image; it was lost",
                        player.getScoreboardName());
            }
        }
    }

    /** {@return every mounted container image in an inventory} */
    private static List<ItemStack> imagesIn(Inventory inventory) {
        List<ItemStack> images = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (MountedContainerImageItem.isImage(stack)) {
                images.add(stack);
            }
        }
        return images;
    }
}
