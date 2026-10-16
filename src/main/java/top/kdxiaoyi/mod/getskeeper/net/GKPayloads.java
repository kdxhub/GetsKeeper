package top.kdxiaoyi.mod.getskeeper.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;

/**
 * Every packet GetsKeeper sends.
 *
 * <p>All of them are declared in one file on purpose: the wire format is the contract between the two
 * halves of the mod, so it is easier to keep consistent when it can be read top to bottom. Each codec
 * is written out by hand rather than composed, which keeps the exact field order visible.
 *
 * <p>Only vanilla types travel over the wire - item stacks, strings, ints, booleans. Anything that
 * needs translating is sent as a key plus arguments and rendered on the client, so the player always
 * sees text in their own language even though the decision was made on the server.
 */
public final class GKPayloads {
    private GKPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GetsKeeper.MOD_ID, path));
    }

    // --- server to client ---------------------------------------------------------------------

    /**
     * Asks the client to open the pickup screen.
     *
     * @param stacks        the stacks on offer; duplicates are meaningful, they are separate sources
     * @param itemIds       {@code namespace:path} per stack, in the same order
     * @param inventoryFull when true the image was withheld and the client shows the warning dialog
     * @param ownerName     the player the warning dialog and the dialogs address by name
     */
    public record OpenPickupScreen(List<ItemStack> stacks, List<String> itemIds, boolean inventoryFull, String ownerName)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenPickupScreen> TYPE = GKPayloads.type("open_pickup_screen");

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPickupScreen> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    writeStacks(buf, payload.stacks());
                    writeStrings(buf, payload.itemIds());
                    buf.writeBoolean(payload.inventoryFull());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.ownerName());
                },
                buf -> new OpenPickupScreen(
                        readStacks(buf),
                        readStrings(buf),
                        buf.readBoolean(),
                        ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public CustomPacketPayload.Type<OpenPickupScreen> type() {
            return TYPE;
        }
    }

    /** Tells the client to start drawing the verification overlay. */
    public record VerificationStart(ItemStack stack, int durationTicks) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VerificationStart> TYPE = GKPayloads.type("verification_start");

        public static final StreamCodec<RegistryFriendlyByteBuf, VerificationStart> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                    buf.writeVarInt(payload.durationTicks());
                },
                buf -> new VerificationStart(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVarInt()));

        @Override
        public CustomPacketPayload.Type<VerificationStart> type() {
            return TYPE;
        }
    }

    /**
     * Delivers the verdict once the overlay has finished: the trust level plus where the stack came
     * from, so the dialog can say when and where the player acquired it.
     */
    public record VerificationResult(
            ItemStack stack,
            int trustOrdinal,
            String sourceKey,
            String sourceLabel,
            String sourceCoordinates,
            String sourceDimension,
            long acquiredAt,
            String acquiredBy) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<VerificationResult> TYPE = GKPayloads.type("verification_result");

        public static final StreamCodec<RegistryFriendlyByteBuf, VerificationResult> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                    buf.writeVarInt(payload.trustOrdinal());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.sourceKey());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.sourceLabel());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.sourceCoordinates());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.sourceDimension());
                    buf.writeLong(payload.acquiredAt());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.acquiredBy());
                },
                buf -> new VerificationResult(
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                        buf.readVarInt(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readLong(),
                        ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public CustomPacketPayload.Type<VerificationResult> type() {
            return TYPE;
        }
    }

    /**
     * Asks the client for a password.
     *
     * @param purpose           see {@code PasswordPurpose}
     * @param itemId            the item the password is about, may be empty
     * @param suggestedUsername prefilled user name; operators get their own name, others get nothing
     * @param mandatory         true for the forced first-time setup, which cannot be dismissed
     */
    public record PasswordPrompt(int purpose, String itemId, String suggestedUsername, boolean mandatory)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PasswordPrompt> TYPE = GKPayloads.type("password_prompt");

        public static final StreamCodec<RegistryFriendlyByteBuf, PasswordPrompt> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.purpose());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.itemId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.suggestedUsername());
                    buf.writeBoolean(payload.mandatory());
                },
                buf -> new PasswordPrompt(
                        buf.readVarInt(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<PasswordPrompt> type() {
            return TYPE;
        }
    }

    /** Reports the outcome of a password attempt back to the client that submitted it. */
    public record PasswordResult(int purpose, String itemId, boolean success, boolean kick) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PasswordResult> TYPE = GKPayloads.type("password_result");

        public static final StreamCodec<RegistryFriendlyByteBuf, PasswordResult> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.purpose());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.itemId());
                    buf.writeBoolean(payload.success());
                    buf.writeBoolean(payload.kick());
                },
                buf -> new PasswordResult(
                        buf.readVarInt(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean(),
                        buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<PasswordResult> type() {
            return TYPE;
        }
    }

    /** The items that were refused during this play session, for the bottom of the privacy screen. */
    public record SessionBlocked(List<String> itemIds) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SessionBlocked> TYPE = GKPayloads.type("session_blocked");

        public static final StreamCodec<RegistryFriendlyByteBuf, SessionBlocked> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> writeStrings(buf, payload.itemIds()),
                buf -> new SessionBlocked(readStrings(buf)));

        @Override
        public CustomPacketPayload.Type<SessionBlocked> type() {
            return TYPE;
        }
    }

    // --- client to server ---------------------------------------------------------------------

    /** The player's answer on the pickup screen. */
    public record PickupDecision(boolean accept) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PickupDecision> TYPE = GKPayloads.type("pickup_decision");

        public static final StreamCodec<RegistryFriendlyByteBuf, PickupDecision> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeBoolean(payload.accept()),
                buf -> new PickupDecision(buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<PickupDecision> type() {
            return TYPE;
        }
    }

    /** The player's answer on a verification dialog. See {@code VerificationDecision}. */
    public record UseDecision(int decision) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UseDecision> TYPE = GKPayloads.type("use_decision");

        public static final StreamCodec<RegistryFriendlyByteBuf, UseDecision> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeVarInt(payload.decision()),
                buf -> new UseDecision(buf.readVarInt()));

        @Override
        public CustomPacketPayload.Type<UseDecision> type() {
            return TYPE;
        }
    }

    /** Submits a password. The plain text only ever exists in transit and in memory. */
    public record PasswordSubmit(int purpose, String itemId, String username, String password) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PasswordSubmit> TYPE = GKPayloads.type("password_submit");

        public static final StreamCodec<RegistryFriendlyByteBuf, PasswordSubmit> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.purpose());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.itemId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.username());
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.password());
                },
                buf -> new PasswordSubmit(
                        buf.readVarInt(),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public CustomPacketPayload.Type<PasswordSubmit> type() {
            return TYPE;
        }
    }

    /** Sent when the privacy screen opens so the server can push the session list back. */
    public record RequestSessionData() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestSessionData> TYPE = GKPayloads.type("request_session_data");

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSessionData> STREAM_CODEC =
                StreamCodec.unit(new RequestSessionData());

        @Override
        public CustomPacketPayload.Type<RequestSessionData> type() {
            return TYPE;
        }
    }

    /**
     * The player dismissed a password prompt.
     *
     * <p>Only meaningful for the mandatory first-time setup, where dismissing means "I will not play
     * until I have set a password" and the server disconnects them.
     */
    public record PasswordCancel(int purpose) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PasswordCancel> TYPE = GKPayloads.type("password_cancel");

        public static final StreamCodec<RegistryFriendlyByteBuf, PasswordCancel> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeVarInt(payload.purpose()),
                buf -> new PasswordCancel(buf.readVarInt()));

        @Override
        public CustomPacketPayload.Type<PasswordCancel> type() {
            return TYPE;
        }
    }

    /** "Use anyway", pressed on an item that was refused this session. */
    public record RequestBlockedUse(String itemId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestBlockedUse> TYPE = GKPayloads.type("request_blocked_use");

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestBlockedUse> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> ByteBufCodecs.STRING_UTF8.encode(buf, payload.itemId()),
                buf -> new RequestBlockedUse(ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public CustomPacketPayload.Type<RequestBlockedUse> type() {
            return TYPE;
        }
    }

    // --- codec helpers ------------------------------------------------------------------------

    private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(values.size());
        for (String value : values) {
            ByteBufCodecs.STRING_UTF8.encode(buf, value);
        }
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> values = new ArrayList<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            values.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        }
        return values;
    }

    private static void writeStacks(RegistryFriendlyByteBuf buf, List<ItemStack> stacks) {
        buf.writeVarInt(stacks.size());
        for (ItemStack stack : stacks) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
    }

    private static List<ItemStack> readStacks(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<ItemStack> stacks = new ArrayList<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        return stacks;
    }
}
