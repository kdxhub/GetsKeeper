package top.kdxiaoyi.mod.getskeeper.source;

import java.util.List;

import net.minecraft.network.chat.Component;

/**
 * Turns {@link ItemSource} records into something a player can read.
 *
 * <p>All wording lives in the language files, so this class only produces translation keys and
 * arguments. Nothing here is hard coded English.
 */
public final class SourceFormatter {
    /** How many sources are spelled out before the list is cut short with an ellipsis. */
    public static final int VISIBLE_SOURCES = 3;

    private SourceFormatter() {}

    /** A source reduced to translatable pieces. */
    public record SourceText(String key, String label, String coordinates, String dimension) {
        /** {@return the rendered component for this source} */
        public Component toComponent() {
            return Component.translatable(key, label, coordinates, dimension);
        }
    }

    public static SourceText of(ItemSource source) {
        return new SourceText(
                source.type().translationKey(),
                source.label(),
                source.x() + ", " + source.y() + ", " + source.z(),
                source.dimension());
    }

    /**
     * Joins the first {@value #VISIBLE_SOURCES} sources and adds an ellipsis when there are more.
     *
     * <p>Used for log lines and for the header of a source list, where a full dump would not fit.
     */
    public static String summarize(List<ItemSource> sources) {
        if (sources.isEmpty()) {
            return SourceType.UNKNOWN.serializedName();
        }
        StringBuilder builder = new StringBuilder();
        int shown = Math.min(sources.size(), VISIBLE_SOURCES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(sources.get(i).describe());
        }
        if (sources.size() > shown) {
            builder.append("\u2026");
        }
        return builder.toString();
    }
}
