package top.kdxiaoyi.mod.getskeeper.pickup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player pickup state.
 *
 * <p>Three pieces of bookkeeping live here:
 *
 * <ul>
 *   <li><b>The session cache.</b> Item ids the player has already picked up since they entered the
 *       world. Items in this cache are picked up the vanilla way, which is what stops the screen from
 *       appearing for every single piece of dirt after the first one.</li>
 *   <li><b>The open batch.</b> The candidates the player is currently looking at, so the server can
 *       act on their answer and refuse to open a second screen on top.</li>
 *   <li><b>Cooldowns.</b> How long a given source entity is ignored for this player. This is what
 *       keeps a declined screen from reopening on the very next tick while the player is still
 *       standing on the item, and it is also what limits the screen to one player at a time.</li>
 * </ul>
 *
 * <p>Everything is dropped when the player leaves the world, which is what makes the cache
 * "this play session" and not "forever".
 */
public final class PickupSession {
    private static final Map<UUID, Set<String>> PICKED = new HashMap<>();
    private static final Map<UUID, Batch> BATCHES = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> COOLDOWNS = new HashMap<>();

    private PickupSession() {}

    /** The candidates the player is currently looking at, plus when the screen was opened. */
    public record Batch(List<PickupCandidate> candidates, long openedAt) {}

    public static synchronized boolean hasPicked(UUID playerId, String itemId) {
        return PICKED.getOrDefault(playerId, Set.of()).contains(itemId);
    }

    public static synchronized void markPicked(UUID playerId, String itemId) {
        PICKED.computeIfAbsent(playerId, key -> new HashSet<>()).add(itemId);
    }

    public static synchronized Batch batch(UUID playerId) {
        return BATCHES.get(playerId);
    }

    public static synchronized void begin(UUID playerId, List<PickupCandidate> candidates, long gameTime) {
        BATCHES.put(playerId, new Batch(List.copyOf(candidates), gameTime));
    }

    public static synchronized void end(UUID playerId) {
        BATCHES.remove(playerId);
    }

    public static synchronized boolean isCoolingDown(UUID playerId, UUID sourceId, long gameTime) {
        Map<UUID, Long> perPlayer = COOLDOWNS.get(playerId);
        if (perPlayer == null) {
            return false;
        }
        Long expiry = perPlayer.get(sourceId);
        return expiry != null && expiry > gameTime;
    }

    public static synchronized void cool(UUID playerId, UUID sourceId, long until) {
        COOLDOWNS.computeIfAbsent(playerId, key -> new HashMap<>()).put(sourceId, until);
    }

    /** Removes expired cooldowns so the map does not grow without bound on a long-lived server. */
    public static synchronized void pruneCooldowns(long gameTime) {
        for (Map<UUID, Long> perPlayer : COOLDOWNS.values()) {
            perPlayer.entrySet().removeIf(entry -> entry.getValue() <= gameTime);
        }
        COOLDOWNS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /** {@return the item ids this player has picked up this session, for diagnostics} */
    public static synchronized List<String> pickedThisSession(UUID playerId) {
        return new ArrayList<>(PICKED.getOrDefault(playerId, Set.of()));
    }

    /** Called when a player leaves the world, or when the world is unloaded. */
    public static synchronized void clear(UUID playerId) {
        PICKED.remove(playerId);
        BATCHES.remove(playerId);
        COOLDOWNS.remove(playerId);
    }

    /** Called when a server stops, so nothing leaks between worlds. */
    public static synchronized void clearAll() {
        PICKED.clear();
        BATCHES.clear();
        COOLDOWNS.clear();
    }
}
