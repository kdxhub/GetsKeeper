package top.kdxiaoyi.mod.getskeeper.data;

import java.util.function.Supplier;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import top.kdxiaoyi.mod.getskeeper.GetsKeeper;
import top.kdxiaoyi.mod.getskeeper.source.SourceLedger;

/**
 * Player data attachments.
 *
 * <p>Both attachments are marked {@code copyOnDeath}, so dying does not quietly reset a player's
 * authorizations or erase where their items came from. Both are also skipped on disk when empty,
 * which keeps player data small for the majority of players who never authorize anything.
 */
public final class GKAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, GetsKeeper.MOD_ID);

    /**
     * The items this player has authorized, plus the ones whose switch is currently off. Synced to
     * the owning player only: the privacy screen reads it, nobody else needs to.
     */
    public static final Supplier<AttachmentType<AuthorizationRecord>> AUTHORIZATIONS = ATTACHMENTS.register(
            "authorizations",
            () -> AttachmentType.builder(AuthorizationRecord::empty)
                    .serialize(AuthorizationRecord.CODEC.fieldOf("data"), record -> !record.isEmpty())
                    .copyOnDeath()
                    .sync((holder, player) -> holder == player, AuthorizationRecord.STREAM_CODEC)
                    .build());

    /**
     * Where the items in this player's inventory came from.
     *
     * <p>Not synced: the client is told the source of an item when it needs to show it, as part of
     * the verification dialog, instead of mirroring the whole ledger.
     */
    public static final Supplier<AttachmentType<SourceLedger>> SOURCES = ATTACHMENTS.register(
            "sources",
            () -> AttachmentType.builder(SourceLedger::empty)
                    .serialize(SourceLedger.CODEC.fieldOf("data"), ledger -> !ledger.isEmpty())
                    .copyOnDeath()
                    .build());

    private GKAttachments() {}

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
