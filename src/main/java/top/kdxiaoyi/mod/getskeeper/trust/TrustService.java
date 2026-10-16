package top.kdxiaoyi.mod.getskeeper.trust;

import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;

import top.kdxiaoyi.mod.getskeeper.config.GKConfig;

/**
 * Resolves the trust level of an item.
 *
 * <p>Lookup order, most specific first:
 *
 * <ol>
 *   <li>an exact {@code namespace:path} entry,</li>
 *   <li>a bare {@code namespace} entry,</li>
 *   <li>{@link TrustLevel#UNTRUSTED}.</li>
 * </ol>
 */
public final class TrustService {
    private TrustService() {}

    /** {@return the trust level configured for the given item id} */
    public static TrustLevel resolve(Identifier itemId) {
        Map<String, TrustLevel> exact = GKConfig.exactEntries();
        TrustLevel direct = exact.get(itemId.toString());
        if (direct != null) {
            return direct;
        }

        String namespace = itemId.getNamespace();
        TrustLevel namespaceLevel = exact.get(namespace);
        if (namespaceLevel != null) {
            return namespaceLevel;
        }

        // The exact-entry map only contains configured entries, but the three sets are what the
        // operator actually edits, so fall back to them for entries that were never indexed.
        if (contains(GKConfig.fullyTrusted(), namespace, itemId)) {
            return TrustLevel.FULLY_TRUSTED;
        }
        if (contains(GKConfig.trusted(), namespace, itemId)) {
            return TrustLevel.TRUSTED;
        }
        if (contains(GKConfig.blocked(), namespace, itemId)) {
            return TrustLevel.BLOCKED;
        }
        return TrustLevel.UNTRUSTED;
    }

    private static boolean contains(Set<String> entries, String namespace, Identifier itemId) {
        return entries.contains(namespace) || entries.contains(itemId.toString());
    }
}
