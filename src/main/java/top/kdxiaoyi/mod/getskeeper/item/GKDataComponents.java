package top.kdxiaoyi.mod.getskeeper.item;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;

/**
 * Data components added by GetsKeeper.
 *
 * <p>1.21 replaced item NBT with data components, and this mod follows that: the seal a mounted
 * container image carries is a component, not a hand rolled {@code CompoundTag}. That means vanilla
 * codecs, the creative inventory, the recipe book and every other vanilla system already know how
 * to copy, compare and serialize it.
 *
 * <p>The builder is spelled out with an explicit type witness rather than hidden behind a helper so
 * that the component type is unambiguous at the registration site.
 */
public final class GKDataComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, GetsKeeper.MOD_ID);

    /**
     * Set on every mounted container image this mod creates. Persisted to disk and synchronized to
     * the client so that the pickup screen and the tooltip can show where the image came from.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<MountedImageSeal>> IMAGE_SEAL =
            COMPONENTS.register("image_seal", () -> DataComponentType.<MountedImageSeal>builder()
                    .persistent(MountedImageSeal.CODEC)
                    .networkSynchronized(MountedImageSeal.STREAM_CODEC)
                    .build());

    private GKDataComponents() {}

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}
