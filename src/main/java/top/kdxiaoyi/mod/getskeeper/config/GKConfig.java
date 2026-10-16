package top.kdxiaoyi.mod.getskeeper.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.trust.TrustLevel;

/**
 * The TOML configuration. Everything a server operator might want to tune lives here.
 *
 * <p>The three trust lists are the heart of the mod. They hold <em>namespaces</em> (for example
 * {@code minecraft}) and optionally fully qualified item ids (for example {@code minecraft:tnt}) so
 * that a single item can be treated differently from the rest of its namespace. An entry that
 * contains a colon is looked up first; if there is no match, the namespace part is used.
 *
 * <p>An entry may only appear in one list. If the same entry shows up twice, the mod fails loudly
 * instead of silently guessing which list wins.
 */
public final class GKConfig {
    /** Namespace, or {@code namespace:path}. */
    private static final Pattern ENTRY = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<List<? extends String>> FULLY_TRUSTED;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> TRUSTED;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCKED;

    private static final ModConfigSpec.DoubleValue PICKUP_RADIUS;
    private static final ModConfigSpec.IntValue PICKUP_COOLDOWN_TICKS;
    private static final ModConfigSpec.BooleanValue PICKUP_INCLUDE_ARROWS;
    private static final ModConfigSpec.BooleanValue PICKUP_GUARD_ENABLED;
    private static final ModConfigSpec.BooleanValue USE_GUARD_ENABLED;
    private static final ModConfigSpec.IntValue VERIFY_MIN_TICKS;
    private static final ModConfigSpec.IntValue VERIFY_MAX_TICKS;
    private static final ModConfigSpec.BooleanValue REQUIRE_ADMIN_PASSWORD;
    private static final ModConfigSpec.IntValue PASSWORD_MIN_LENGTH;
    private static final ModConfigSpec.IntValue PASSWORD_ITERATIONS;

    private static volatile Snapshot snapshot = Snapshot.DEFAULTS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                "The three trust lists.",
                "",
                "Each entry is a namespace (e.g. 'minecraft') or a fully qualified item id",
                "(e.g. 'minecraft:tnt'). An item id entry always wins over a namespace entry.",
                "An entry may appear in only one list: if the same entry shows up twice, Minecraft",
                "will crash on startup with an explanation rather than guessing which list wins.",
                "",
                "Anything that is on none of these lists counts as UNTRUSTED.",
                "",
                "  fully_trusted - verify silently: the bar plays, then the operation goes through.",
                "  trusted       - verify, then ask the player to confirm.",
                "  blocked       - verify, then state that the item is broken and offer to void it.");

        builder.push("trust_lists");
        FULLY_TRUSTED = builder
                .comment(
                        "Namespaces that are above suspicion. Their items are still verified, but the",
                        "verification is the whole of it: no dialog ever follows, and nothing has to be",
                        "answered. Vanilla is trusted out of the box, but the operator may remove",
                        "'minecraft' here - do so if you do not even trust the base game. Removing an",
                        "entry does not change anything else: the entry just falls through to the next",
                        "list.")
                .defineListAllowEmpty("fully_trusted", List.of("minecraft"), () -> "minecraft", GKConfig::isValidEntry);
        TRUSTED = builder
                .comment(
                        "Namespaces that get a plain confirmation dialog before their items may be used.")
                .defineListAllowEmpty("trusted", List.<String>of(), () -> "modid", GKConfig::isValidEntry);
        BLOCKED = builder
                .comment(
                        "Namespaces that are refused outright. Items from these namespaces are never",
                        "usable; the dialog only offers to move the stack to the void, and they are not",
                        "listed on the privacy page, because there is no grant to offer for them.")
                .defineListAllowEmpty("blocked", List.<String>of(), () -> "modid", GKConfig::isValidEntry);
        builder.pop();

        builder.comment(
                "",
                "Pickup interception: items never enter the inventory without going through the",
                "pickup screen first.").push("pickup");
        PICKUP_GUARD_ENABLED = builder
                .comment("Set to false to disable the pickup screen entirely (items are picked up the vanilla way).")
                .define("enabled", true);
        PICKUP_RADIUS = builder
                .comment("How far around the player the scanner looks for item entities, in blocks.")
                .defineInRange("scan_radius", 4.0D, 0.5D, 16.0D);
        PICKUP_COOLDOWN_TICKS = builder
                .comment(
                        "How long a single item entity is ignored by the scanner after its pickup screen",
                        "was closed without picking anything up. This is what keeps the screen from",
                        "reopening every tick while the player stands on top of the item, and it also",
                        "makes sure only one player is shown a screen for the same item entity at a time.")
                .defineInRange("cooldown_ticks", 60, 0, 600);
        PICKUP_INCLUDE_ARROWS = builder
                .comment("Whether stuck arrows are offered through the pickup screen as well.")
                .define("include_arrows", true);
        builder.pop();

        builder.comment(
                "",
                "Use verification: moving an item into a guarded slot starts a verification pass.").push("verification");
        USE_GUARD_ENABLED = builder
                .comment("Set to false to disable use verification. The pickup screen is unaffected by this.")
                .define("enabled", true);
        VERIFY_MIN_TICKS = builder
                .comment("Lower bound of the randomized verification duration, in ticks (20 ticks = 1 second).")
                .defineInRange("min_ticks", 20, 1, 600);
        VERIFY_MAX_TICKS = builder
                .comment("Upper bound of the randomized verification duration, in ticks.")
                .defineInRange("max_ticks", 60, 1, 600);
        builder.pop();

        builder.comment(
                "",
                "Passwords. Passwords are stored per world, never in this file.").push("security");
        REQUIRE_ADMIN_PASSWORD = builder
                .comment(
                        "When true, an operator (permission level 3) who has no password yet is asked to",
                        "set one before playing. Cancelling that prompt disconnects the operator.",
                        "In single-player the host always counts as an operator.")
                .define("require_admin_password", true);
        PASSWORD_MIN_LENGTH = builder
                .comment("Minimum number of characters accepted by /passwd and the password prompt.")
                .defineInRange("min_length", 6, 1, 64);
        PASSWORD_ITERATIONS = builder
                .comment(
                        "PBKDF2WithHmacSHA256 iteration count. Higher is slower to brute force and slower",
                        "to verify. The salt is derived from the player name, so two players with the same",
                        "password still get different hashes.")
                .defineInRange("pbkdf2_iterations", 120_000, 1_000, 2_000_000);
        builder.pop();

        SPEC = builder.build();
    }

    private GKConfig() {}

    /**
     * Registers the config lifecycle listeners.
     *
     * <p>Both the first load and every later reload rebuild the snapshot; a reload that introduces a
     * conflicting entry therefore fails just as loudly as a bad startup.
     */
    public static void registerConfigEvents(IEventBus modBus) {
        modBus.addListener(net.neoforged.fml.event.config.ModConfigEvent.Loading.class, event -> reload());
        modBus.addListener(net.neoforged.fml.event.config.ModConfigEvent.Reloading.class, event -> reload());
    }

    /** {@return {@code true} when the passed value is a well formed list entry} */
    private static boolean isValidEntry(Object value) {
        return value instanceof String string && ENTRY.matcher(string).matches();
    }

    /** Re-reads the TOML and rebuilds the trust snapshot. Throws when the lists contradict each other. */
    public static void reload() {
        List<String> fullyTrusted = List.copyOf(FULLY_TRUSTED.get());
        List<String> trusted = List.copyOf(TRUSTED.get());
        List<String> blocked = List.copyOf(BLOCKED.get());

        Map<String, TrustLevel> index = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();

        collect(index, fullyTrusted, TrustLevel.FULLY_TRUSTED, conflicts);
        collect(index, trusted, TrustLevel.TRUSTED, conflicts);
        collect(index, blocked, TrustLevel.BLOCKED, conflicts);

        if (!conflicts.isEmpty()) {
            // Failing loudly beats guessing. A shared entry would make the outcome depend on
            // iteration order, which is exactly the kind of thing that silently changes behaviour
            // between restarts.
            throw new IllegalStateException("""
                    GetsKeeper config error: the same entry appears in more than one trust list.
                    %s
                    An entry may appear in exactly one of trust_lists.fully_trusted, trust_lists.trusted and trust_lists.blocked.
                    Fix the config file and start the game again.""".formatted(String.join("\n", conflicts)));
        }

        snapshot = new Snapshot(
                Set.copyOf(fullyTrusted),
                Set.copyOf(trusted),
                Set.copyOf(blocked),
                index,
                PICKUP_GUARD_ENABLED.get(),
                PICKUP_RADIUS.get(),
                PICKUP_COOLDOWN_TICKS.get(),
                PICKUP_INCLUDE_ARROWS.get(),
                USE_GUARD_ENABLED.get(),
                VERIFY_MIN_TICKS.get(),
                VERIFY_MAX_TICKS.get(),
                REQUIRE_ADMIN_PASSWORD.get(),
                PASSWORD_MIN_LENGTH.get(),
                PASSWORD_ITERATIONS.get());

        GetsKeeper.LOGGER.info(
                "Trust lists loaded: {} fully trusted, {} trusted, {} blocked",
                fullyTrusted.size(), trusted.size(), blocked.size());
    }

    private static void collect(Map<String, TrustLevel> index, List<String> entries, TrustLevel level, List<String> conflicts) {
        for (String entry : entries) {
            TrustLevel previous = index.putIfAbsent(entry, level);
            if (previous != null && previous != level) {
                conflicts.add("  - '" + entry + "' is listed as both " + previous + " and " + level);
            } else if (previous == level) {
                conflicts.add("  - '" + entry + "' is listed twice as " + level);
            }
        }
    }

    // --- Accessors used by the rest of the mod -------------------------------------------------

    public static Set<String> fullyTrusted() {
        return snapshot.fullyTrusted;
    }

    public static Set<String> trusted() {
        return snapshot.trusted;
    }

    public static Set<String> blocked() {
        return snapshot.blocked;
    }

    /** {@return exact item id entries, mapped to the trust level they were configured with} */
    public static Map<String, TrustLevel> exactEntries() {
        return snapshot.index;
    }

    public static boolean pickupEnabled() {
        return snapshot.pickupEnabled;
    }

    public static double pickupRadius() {
        return snapshot.pickupRadius;
    }

    public static int pickupCooldownTicks() {
        return snapshot.pickupCooldownTicks;
    }

    public static boolean includeArrows() {
        return snapshot.includeArrows;
    }

    public static boolean useGuardEnabled() {
        return snapshot.useGuardEnabled;
    }

    public static boolean requireAdminPassword() {
        return snapshot.requireAdminPassword;
    }

    public static int passwordMinLength() {
        return snapshot.passwordMinLength;
    }

    public static int passwordIterations() {
        return snapshot.passwordIterations;
    }

    /** {@return a random verification duration in ticks between the configured bounds} */
    public static int rollVerificationTicks(RandomSource random) {
        int min = Math.min(snapshot.verifyMinTicks, snapshot.verifyMaxTicks);
        int max = Math.max(snapshot.verifyMinTicks, snapshot.verifyMaxTicks);
        return min >= max ? min : min + random.nextInt(max - min + 1);
    }

    /** Immutable view of the config so that gameplay code never touches {@code ConfigValue} directly. */
    private record Snapshot(
            Set<String> fullyTrusted,
            Set<String> trusted,
            Set<String> blocked,
            Map<String, TrustLevel> index,
            boolean pickupEnabled,
            double pickupRadius,
            int pickupCooldownTicks,
            boolean includeArrows,
            boolean useGuardEnabled,
            int verifyMinTicks,
            int verifyMaxTicks,
            boolean requireAdminPassword,
            int passwordMinLength,
            int passwordIterations) {

        /** Used before the TOML has been read, and by unit tests. Mirrors the shipped defaults. */
        static final Snapshot DEFAULTS = new Snapshot(
                Set.of("minecraft"),
                Set.of(),
                Set.of(),
                Map.of(),
                true,
                4.0D,
                60,
                true,
                true,
                20,
                60,
                true,
                6,
                120_000);
    }

}
