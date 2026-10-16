package top.kdxiaoyi.mod.getskeeper.data;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;

/**
 * Save level data for GetsKeeper: the passwords.
 *
 * <p>Passwords live with the world, not with the player and not in the config file, exactly as the
 * design requires: copying a world brings its passwords along, and copying a config file does not
 * hand anybody a password.
 *
 * <p>1.21.11 serializes saved data through {@link SavedDataType} and a {@code Codec} instead of the
 * old {@code save(CompoundTag, HolderLookup.Provider)} override, so this class declares one codec and
 * keeps its state in ordinary mutable fields.
 */
public final class GKWorldData extends SavedData {
    /** The file name is the id; vanilla appends {@code .dat}. */
    public static final SavedDataType<GKWorldData> TYPE =
            new SavedDataType<>("getskeeper", GKWorldData::new, codec());

    private final Map<String, PasswordVault.Stored> passwords = new HashMap<>();

    public GKWorldData() {}

    private GKWorldData(Map<String, PasswordVault.Stored> passwords) {
        this.passwords.putAll(passwords);
    }

    private static Codec<GKWorldData> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.unboundedMap(Codec.STRING, PasswordVault.Stored.CODEC)
                        .optionalFieldOf("passwords", Map.<String, PasswordVault.Stored>of())
                        .forGetter(data -> data.passwords))
                .apply(instance, GKWorldData::new));
    }

    /** {@return the per-world GetsKeeper data of a running server} */
    public static GKWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** {@return {@code true} when this name already has a password} */
    public boolean hasPassword(String playerName) {
        return passwords.containsKey(PasswordVault.normalize(playerName));
    }

    /** Stores a new password for a name. The plain text is never kept. */
    public void setPassword(String playerName, String password) {
        passwords.put(PasswordVault.normalize(playerName), PasswordVault.create(playerName, password));
        setDirty();
        GetsKeeper.LOGGER.info("Password stored for {}", playerName);
    }

    /** Verifies a password that the named player submitted. */
    public boolean verify(String playerName, String password) {
        return PasswordVault.verify(playerName, password, passwords.get(PasswordVault.normalize(playerName)));
    }

    /** {@return the names that currently have a password} */
    public java.util.Set<String> knownNames() {
        return java.util.Set.copyOf(passwords.keySet());
    }
}
