package top.kdxiaoyi.mod.getskeeper.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Items that were refused during the current play session.
 *
 * <p>The privacy screen lists these at the bottom with a "use anyway" button. The list is deliberately
 * session scoped: entering the world again starts from an empty list, while granting an item through
 * the button is permanent and lands in the player's authorization record.
 */
public final class SessionGuards {
    private static final Map<UUID, Set<String>> REFUSED = new HashMap<>();

    private SessionGuards() {}

    public static synchronized void mark(UUID playerId, String itemId) {
        REFUSED.computeIfAbsent(playerId, key -> new LinkedHashSet<>()).add(itemId);
    }

    /**
     * Drops an item from the list, because it is no longer refused.
     *
     * <p>Granting an item through "use anyway" makes it permanently authorized; leaving it on the
     * list would keep offering a button for something the player has already settled.
     */
    public static synchronized void unmark(UUID playerId, String itemId) {
        Set<String> refused = REFUSED.get(playerId);
        if (refused != null) {
            refused.remove(itemId);
            if (refused.isEmpty()) {
                REFUSED.remove(playerId);
            }
        }
    }

    public static synchronized boolean wasRefused(UUID playerId, String itemId) {
        return REFUSED.getOrDefault(playerId, Set.of()).contains(itemId);
    }

    /** {@return the refused items, sorted so the screen does not reshuffle itself} */
    public static synchronized List<String> list(UUID playerId) {
        List<String> items = new ArrayList<>(REFUSED.getOrDefault(playerId, Set.of()));
        Collections.sort(items);
        return items;
    }

    public static synchronized void clear(UUID playerId) {
        REFUSED.remove(playerId);
    }

    public static synchronized void clearAll() {
        REFUSED.clear();
    }
}
