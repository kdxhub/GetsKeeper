package top.kdxiaoyi.mod.getskeeper.item;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;

/** Item registration. */
public final class GKItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(GetsKeeper.MOD_ID);

    /**
     * The mounted container image.
     *
     * <p>Unstackable by design: one image vouches for one batch of picked up items, so there is
     * never a reason for two of them to share a slot.
     */
    public static final DeferredItem<MountedContainerImageItem> MOUNTED_CONTAINER_IMAGE = ITEMS.register(
            "mounted_container_image",
            () -> new MountedContainerImageItem(new Item.Properties().stacksTo(1)));

    private GKItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
