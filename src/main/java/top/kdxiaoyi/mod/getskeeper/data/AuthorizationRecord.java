package top.kdxiaoyi.mod.getskeeper.data;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The per-player authorization list.
 *
 * <p>Two sets, not one, because disabling a switch must not lose the fact that the item was ever
 * authorized: an item moves between {@code enabled} and {@code disabled} and the record stays.
 *
 * <p>Keys are full item ids ({@code namespace:path}) and components are ignored, so authorizing
 * {@code minecraft:diamond_sword} covers every diamond sword no matter what is enchanted onto it.
 */
public final class AuthorizationRecord {
    public static final Codec<AuthorizationRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("enabled", java.util.List.<String>of()).forGetter(AuthorizationRecord::enabledList),
            Codec.STRING.listOf().optionalFieldOf("disabled", java.util.List.<String>of()).forGetter(AuthorizationRecord::disabledList))
            .apply(instance, (enabled, disabled) -> new AuthorizationRecord(new LinkedHashSet<>(enabled), new LinkedHashSet<>(disabled))));

    public static final StreamCodec<RegistryFriendlyByteBuf, AuthorizationRecord> STREAM_CODEC = StreamCodec.of(
            (RegistryFriendlyByteBuf buf, AuthorizationRecord record) -> {
                writeStrings(buf, record.enabled);
                writeStrings(buf, record.disabled);
            },
            (RegistryFriendlyByteBuf buf) -> new AuthorizationRecord(readStrings(buf), readStrings(buf)));

    private static final AuthorizationRecord EMPTY = new AuthorizationRecord(Set.of(), Set.of());

    private final Set<String> enabled;
    private final Set<String> disabled;

    public AuthorizationRecord(Set<String> enabled, Set<String> disabled) {
        this.enabled = Collections.unmodifiableSet(new LinkedHashSet<>(enabled));
        this.disabled = Collections.unmodifiableSet(new LinkedHashSet<>(disabled));
    }

    public static AuthorizationRecord empty() {
        return EMPTY;
    }

    private static void writeStrings(ByteBuf buf, Set<String> values) {
        buf.writeInt(values.size());
        for (String value : values) {
            ByteBufCodecs.STRING_UTF8.encode(buf, value);
        }
    }

    private static Set<String> readStrings(ByteBuf buf) {
        int size = buf.readInt();
        Set<String> values = new LinkedHashSet<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            values.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        }
        return values;
    }

    public Set<String> enabled() {
        return enabled;
    }

    public Set<String> disabled() {
        return disabled;
    }

    private java.util.List<String> enabledList() {
        return java.util.List.copyOf(enabled);
    }

    private java.util.List<String> disabledList() {
        return java.util.List.copyOf(disabled);
    }

    /** {@return {@code true} when the item is authorized and its switch is on} */
    public boolean isUsable(String itemId) {
        return enabled.contains(itemId);
    }

    /** {@return {@code true} when the item was authorized at some point but its switch is off} */
    public boolean isDisabled(String itemId) {
        return disabled.contains(itemId);
    }

    public boolean isKnown(String itemId) {
        return enabled.contains(itemId) || disabled.contains(itemId);
    }

    /** Grants an item, or turns its switch back on. */
    public AuthorizationRecord authorize(String itemId) {
        if (enabled.contains(itemId)) {
            return this;
        }
        Set<String> nextEnabled = new LinkedHashSet<>(enabled);
        Set<String> nextDisabled = new LinkedHashSet<>(disabled);
        nextEnabled.add(itemId);
        nextDisabled.remove(itemId);
        return new AuthorizationRecord(nextEnabled, nextDisabled);
    }

    /** Turns an item's switch off while keeping the authorization on file. */
    public AuthorizationRecord disable(String itemId) {
        if (!enabled.contains(itemId)) {
            return this;
        }
        Set<String> nextEnabled = new LinkedHashSet<>(enabled);
        Set<String> nextDisabled = new LinkedHashSet<>(disabled);
        nextEnabled.remove(itemId);
        nextDisabled.add(itemId);
        return new AuthorizationRecord(nextEnabled, nextDisabled);
    }

    /** {@return every item id this record knows about, enabled or not} */
    public Set<String> knownItems() {
        Set<String> all = new LinkedHashSet<>(enabled);
        all.addAll(disabled);
        return Collections.unmodifiableSet(all);
    }

    public boolean isEmpty() {
        return enabled.isEmpty() && disabled.isEmpty();
    }
}
