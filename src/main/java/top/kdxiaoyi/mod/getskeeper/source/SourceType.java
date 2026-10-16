package top.kdxiaoyi.mod.getskeeper.source;

import java.util.Locale;

/**
 * How an item stack entered a player's inventory.
 *
 * <p>Every vanilla path a player can obtain an item through has its own constant. Anything this mod
 * cannot classify - notably custom sources added by other mods - is {@link #UNKNOWN}. Reporting a
 * source as {@link #UNKNOWN} is deliberate: the mod never claims to know more than it does.
 */
public enum SourceType {
    /** Taken out of a container: chest, barrel, shulker box, hopper, ... */
    CONTAINER("container"),
    /** Produced by a command, e.g. {@code /give}. */
    COMMAND("command"),
    /** Crafted in a crafting table or the inventory grid. */
    CRAFTING("crafting"),
    /** Taken out of a furnace, blast furnace or smoker. */
    FURNACE("furnace"),
    /** Bought from a villager or wandering trader. */
    TRADE("trade"),
    /** Pulled out of the water with a fishing rod. */
    FISHING("fishing"),
    /** Taken from the creative inventory. */
    CREATIVE("creative"),
    /** Picked up off the ground. */
    PICKUP("pickup"),
    /** Anything this mod could not attribute - including custom sources added by other mods. */
    UNKNOWN("unknown");

    private final String serializedName;

    SourceType(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /** {@return the i18n key describing this source} */
    public String translationKey() {
        return "getskeeper.source." + serializedName;
    }

    /** {@return the constant with the given serialized name, or {@link #UNKNOWN}} */
    public static SourceType bySerializedName(String name) {
        if (name != null) {
            String normalized = name.toLowerCase(Locale.ROOT);
            for (SourceType type : values()) {
                if (type.serializedName.equals(normalized)) {
                    return type;
                }
            }
        }
        return UNKNOWN;
    }
}
