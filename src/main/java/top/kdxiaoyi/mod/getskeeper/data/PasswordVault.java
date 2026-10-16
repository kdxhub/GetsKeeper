package top.kdxiaoyi.mod.getskeeper.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.config.GKConfig;

/**
 * Password hashing.
 *
 * <p>PBKDF2WithHmacSHA256, 256 bit derived key, with a salt derived from the player name as the
 * design asks. Deriving the salt from the name rather than storing it means two players who pick the
 * same password still end up with different hashes, and it removes a field from the save file. The
 * trade off is that a renamed player loses their password, which is the intended behaviour: the
 * password belongs to the name, and the name is what the privacy screen asks for.
 */
public final class PasswordVault {
    private static final int KEY_LENGTH_BITS = 256;
    private static final HexFormat HEX = HexFormat.of();

    private PasswordVault() {}

    /** A stored credential. The iteration count travels with the hash so config changes do not brick it. */
    public record Stored(String hash, int iterations) {
        public static final Codec<Stored> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("hash").forGetter(Stored::hash),
                Codec.INT.optionalFieldOf("iterations", 120_000).forGetter(Stored::iterations))
                .apply(instance, Stored::new));
    }

    /** {@return a stable salt for a player name} */
    public static byte[] saltFor(String playerName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(normalize(playerName).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }

    /** {@return the hex encoded PBKDF2 hash of a password} */
    public static String hash(String playerName, String password) {
        int iterations = GKConfig.passwordIterations();
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltFor(playerName), iterations, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return HEX.formatHex(factory.generateSecret(spec).getEncoded());
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is required but unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    /** {@return a fresh credential for a player} */
    public static Stored create(String playerName, String password) {
        return new Stored(hash(playerName, password), GKConfig.passwordIterations());
    }

    /**
     * Verifies a password against a stored credential in constant time.
     *
     * <p>The comparison is deliberately not {@code String.equals}: an early return leaks how many
     * leading characters were right.
     */
    public static boolean verify(String playerName, String password, Stored stored) {
        if (stored == null) {
            return false;
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltFor(playerName), stored.iterations(), KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] candidate = factory.generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(candidate, HEX.parseHex(stored.hash()));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException exception) {
            GetsKeeper.LOGGER.warn("Rejected a stored password for {}: {}", playerName, exception.toString());
            return false;
        } finally {
            spec.clearPassword();
        }
    }

    /** Player names are matched case insensitively, exactly like Mojang does for accounts. */
    public static String normalize(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(Locale.ROOT).trim();
    }
}
