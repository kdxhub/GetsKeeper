package top.kdxiaoyi.mod.getskeeper.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Where the items in one player's inventory came from.
 *
 * <p>The ledger is keyed by item id ({@code namespace:path}) and ignores components, exactly like
 * authorizations do: two stacks of the same item from different sources share one key but keep
 * separate {@link Entry entries}, so the dialog can still say where <em>this</em> stack came from.
 *
 * <p>Entries are immutable. Every mutation returns a new ledger, which is what makes it safe to hand
 * to a {@code DataAttachment} without worrying about aliasing.
 */
public final class SourceLedger {
    /** One acquisition: a source plus how many items arrived from it. */
    public record Entry(ItemSource source, int count) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemSource.CODEC.fieldOf("source").forGetter(Entry::source),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Entry::count))
                .apply(instance, Entry::new));

        Entry withCount(int newCount) {
            return new Entry(source, newCount);
        }
    }

    public static final Codec<SourceLedger> CODEC = Codec
            .unboundedMap(Codec.STRING, Entry.CODEC.listOf())
            .xmap(SourceLedger::new, SourceLedger::asMap);

    private static final SourceLedger EMPTY = new SourceLedger(Map.of());

    private final Map<String, List<Entry>> entries;

    public SourceLedger(Map<String, List<Entry>> entries) {
        Map<String, List<Entry>> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        this.entries = Collections.unmodifiableMap(copy);
    }

    public static SourceLedger empty() {
        return EMPTY;
    }

    private Map<String, List<Entry>> asMap() {
        return entries;
    }

    /**
     * Records an acquisition.
     *
     * <p>When the very same source is already recorded - which happens whenever a stack merges into
     * an existing one - the entry's count is increased instead of appending a duplicate. That is the
     * "merge the stacks, but remember how many were merged" rule from the design.
     */
    public SourceLedger withSource(String itemId, ItemSource source, int count) {
        if (count <= 0) {
            return this;
        }

        Map<String, List<Entry>> updated = new LinkedHashMap<>(entries);
        List<Entry> existing = new ArrayList<>(updated.getOrDefault(itemId, List.of()));

        for (int i = 0; i < existing.size(); i++) {
            if (existing.get(i).source().equals(source)) {
                existing.set(i, existing.get(i).withCount(existing.get(i).count() + count));
                updated.put(itemId, List.copyOf(existing));
                return new SourceLedger(updated);
            }
        }

        existing.add(new Entry(source, count));
        updated.put(itemId, List.copyOf(existing));
        return new SourceLedger(updated);
    }

    /** Drops every record for an item id. Called when the last of that item leaves the inventory. */
    public SourceLedger without(String itemId) {
        if (!entries.containsKey(itemId)) {
            return this;
        }
        Map<String, List<Entry>> updated = new LinkedHashMap<>(entries);
        updated.remove(itemId);
        return new SourceLedger(updated);
    }

    /** {@return every entry recorded for an item id, oldest first} */
    public List<Entry> entriesFor(String itemId) {
        return entries.getOrDefault(itemId, List.of());
    }

    /**
     * {@return the most recent source recorded for an item id}
     *
     * <p>"Where did this come from" is answered with the newest acquisition, because that is the one
     * the player most plausibly just triggered.
     */
    public ItemSource latestFor(String itemId) {
        List<Entry> list = entries.getOrDefault(itemId, List.of());
        return list.isEmpty() ? null : list.get(list.size() - 1).source();
    }

    /** {@return how many items of this id the ledger accounts for} */
    public int totalFor(String itemId) {
        int total = 0;
        for (Entry entry : entries.getOrDefault(itemId, List.of())) {
            total += entry.count();
        }
        return total;
    }

    public Set<String> itemIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Keeps only the item ids that are still in the inventory, and trims the recorded counts so the
     * ledger never claims more items than the player actually holds.
     *
     * @param present   item ids still present in the inventory
     * @param available maps an item id to how many of it the inventory currently holds
     */
    public SourceLedger retained(Set<String> present, ToIntFunction<String> available) {
        Map<String, List<Entry>> updated = new LinkedHashMap<>();
        for (Map.Entry<String, List<Entry>> entry : entries.entrySet()) {
            if (!present.contains(entry.getKey())) {
                continue;
            }
            int budget = Math.max(0, available.applyAsInt(entry.getKey()));
            List<Entry> kept = new ArrayList<>();
            // Walk backwards so the newest acquisitions are the ones that survive trimming.
            for (int i = entry.getValue().size() - 1; i >= 0 && budget > 0; i--) {
                Entry candidate = entry.getValue().get(i);
                int take = Math.min(budget, candidate.count());
                kept.add(0, candidate.withCount(take));
                budget -= take;
            }
            if (!kept.isEmpty()) {
                updated.put(entry.getKey(), List.copyOf(kept));
            }
        }
        return new SourceLedger(updated);
    }
}
