package top.kdxiaoyi.mod.getskeeper;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import top.kdxiaoyi.mod.getskeeper.item.MountedImageGuard;
import top.kdxiaoyi.mod.getskeeper.command.GKCommands;
import top.kdxiaoyi.mod.getskeeper.config.GKConfig;
import top.kdxiaoyi.mod.getskeeper.data.GKAttachments;
import top.kdxiaoyi.mod.getskeeper.item.GKDataComponents;
import top.kdxiaoyi.mod.getskeeper.item.GKItems;
import top.kdxiaoyi.mod.getskeeper.net.GKNetwork;
import top.kdxiaoyi.mod.getskeeper.pickup.PickupInterceptor;
import top.kdxiaoyi.mod.getskeeper.source.SourceRecorder;
import top.kdxiaoyi.mod.getskeeper.use.UseGuard;

/**
 * GetsKeeper entrypoint.
 *
 * <p>GetsKeeper is a GateKeeper-style guard for item trust. It does two things:
 *
 * <ol>
 *   <li><b>Nothing enters your inventory silently.</b> Item stacks that a player has never picked up
 *       before are held back in the world until the player drags them onto the container image in the
 *       {@code PickupScreen}.</li>
 *   <li><b>Nothing is used before it is verified.</b> Moving an item into a guarded slot (hands,
 *       equipment, crafting grids, other mobs' inventories) triggers a verification pass against a
 *       per-namespace trust list, optionally with a confirmation dialog that names the origin of the
 *       stack.</li>
 * </ol>
 *
 * <p>Both halves of the mod are required on client and server. The server owns authority (trust
 * lists, source records, authorizations, passwords); the client owns presentation (screens, dialogs,
 * the verification overlay). Single-player uses the same code paths as a dedicated server: the
 * integrated server is a real {@code MinecraftServer} and the host player is treated as an operator.
 */
@Mod(GetsKeeper.MOD_ID)
public final class GetsKeeper {
    /** The mod id. Must stay lowercase and must match {@code gradle.properties}. */
    public static final String MOD_ID = "getskeeper";

    /** Shared logger. UI actions are logged here instead of spamming the player. */
    public static final Logger LOGGER = LoggerFactory.getLogger("GetsKeeper");

    public GetsKeeper(IEventBus modBus, ModContainer container) {
        // The config has to be registered before anything reads it. Trust lists live here.
        container.registerConfig(ModConfig.Type.COMMON, GKConfig.SPEC);

        // --- Mod bus: registries, config reload, networking ---
        GKDataComponents.register(modBus);
        GKItems.register(modBus);
        GKAttachments.register(modBus);
        GKNetwork.register(modBus);
        GKConfig.registerConfigEvents(modBus);

        // --- Game bus: gameplay hooks ---
        MountedImageGuard.register();
        PickupInterceptor.register();
        SourceRecorder.register();
        UseGuard.register();
        GetsKeeperEvents.register();
        NeoForge.EVENT_BUS.addListener(GKCommands::onRegisterCommands);
    }

    /** Creates an {@link Identifier} in this mod's namespace. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
