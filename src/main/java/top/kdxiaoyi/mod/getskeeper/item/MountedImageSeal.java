package top.kdxiaoyi.mod.getskeeper.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * The seal carried by a {@link MountedContainerImageItem}.
 *
 * <p>The image is created by the mod, so it can afford to remember where it came from. The seal is
 * a data component rather than NBT, which means it travels with the stack through vanilla's own
 * item stack codecs and survives a copy of the stack for free.
 *
 * @param dimension  the dimension the image was mounted in
 * @param createdAt  wall clock time of creation, in milliseconds since the epoch
 * @param mountedFor the name of the player whose pickup created this image
 */
public record MountedImageSeal(Identifier dimension, long createdAt, String mountedFor) {
    public static final Codec<MountedImageSeal> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("dimension").forGetter(MountedImageSeal::dimension),
            Codec.LONG.optionalFieldOf("created_at", 0L).forGetter(MountedImageSeal::createdAt),
            Codec.STRING.optionalFieldOf("mounted_for", "").forGetter(MountedImageSeal::mountedFor))
            .apply(instance, MountedImageSeal::new));

    /**
     * Written by hand rather than with {@code StreamCodec.composite} so the wire format is spelled
     * out in one place. The dimension id is encoded with vanilla's own identifier codec.
     */
    public static final StreamCodec<ByteBuf, MountedImageSeal> STREAM_CODEC = StreamCodec.of(
            (ByteBuf buf, MountedImageSeal seal) -> {
                Identifier.STREAM_CODEC.encode(buf, seal.dimension());
                buf.writeLong(seal.createdAt());
                ByteBufCodecs.STRING_UTF8.encode(buf, seal.mountedFor());
            },
            (ByteBuf buf) -> new MountedImageSeal(
                    Identifier.STREAM_CODEC.decode(buf),
                    buf.readLong(),
                    ByteBufCodecs.STRING_UTF8.decode(buf)));
}
