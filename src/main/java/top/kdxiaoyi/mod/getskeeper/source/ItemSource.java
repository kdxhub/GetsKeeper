package top.kdxiaoyi.mod.getskeeper.source;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * One recorded acquisition of an item.
 *
 * @param dimension  the dimension the item was obtained in, as a string id
 * @param x          block x coordinate of the origin
 * @param y          block y coordinate of the origin
 * @param z          block z coordinate of the origin
 * @param type       how the item was obtained
 * @param label      free form detail, e.g. the block or menu the item came out of; may be empty
 * @param acquiredBy the name of the player who obtained the item
 * @param acquiredAt wall clock time of the acquisition, in milliseconds since the epoch
 */
public record ItemSource(String dimension, int x, int y, int z, SourceType type, String label,
        String acquiredBy, long acquiredAt) {
    public static final Codec<ItemSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("dimension", "minecraft:overworld").forGetter(ItemSource::dimension),
            Codec.INT.optionalFieldOf("x", 0).forGetter(ItemSource::x),
            Codec.INT.optionalFieldOf("y", 0).forGetter(ItemSource::y),
            Codec.INT.optionalFieldOf("z", 0).forGetter(ItemSource::z),
            Codec.STRING.optionalFieldOf("type", SourceType.UNKNOWN.serializedName())
                    .xmap(SourceType::bySerializedName, SourceType::serializedName)
                    .forGetter(ItemSource::type),
            Codec.STRING.optionalFieldOf("label", "").forGetter(ItemSource::label),
            Codec.STRING.optionalFieldOf("acquired_by", "").forGetter(ItemSource::acquiredBy),
            Codec.LONG.optionalFieldOf("acquired_at", 0L).forGetter(ItemSource::acquiredAt))
            .apply(instance, ItemSource::new));

    /** {@return a copy of this source stamped with who obtained it and when} */
    public ItemSource withAcquisition(String who, long when) {
        return new ItemSource(dimension, x, y, z, type, label, who, when);
    }

    /** Records a source at the position of a level. */
    public static ItemSource at(Level level, BlockPos pos, SourceType type, String label) {
        Identifier dimension = level.dimension().identifier();
        return new ItemSource(dimension.toString(), pos.getX(), pos.getY(), pos.getZ(), type, label, "", 0L);
    }

    /** Records a source at an exact position, for sources that have no block to point at. */
    public static ItemSource at(Level level, double x, double y, double z, SourceType type, String label) {
        Identifier dimension = level.dimension().identifier();
        return new ItemSource(dimension.toString(), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
                type, label, "", 0L);
    }

    /** Records a source with no position at all, such as a trade or a creative inventory entry. */
    public static ItemSource locationless(Level level, SourceType type, String label) {
        Identifier dimension = level.dimension().identifier();
        return new ItemSource(dimension.toString(), 0, 0, 0, type, label, "", 0L);
    }

    /** {@return the plain text description this source is logged with} */
    public String describe() {
        StringBuilder builder = new StringBuilder(type.serializedName());
        if (!label.isEmpty()) {
            builder.append('/').append(label);
        }
        builder.append(" @ ").append(dimension).append(' ').append(x).append(' ').append(y).append(' ').append(z);
        return builder.toString();
    }
}
