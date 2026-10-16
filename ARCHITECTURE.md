# GetsKeeper — Architecture

GetsKeeper is a GateKeeper-style guard for item trust on Minecraft 1.21.11 / NeoForge 21.11.
It does two things, and everything in the codebase serves one of them:

1. **Nothing enters your inventory silently.** Items a player has never picked up this session are
   held in the world until they are dragged onto the container image in the pickup screen.
2. **Nothing is used before it is verified.** Moving an item into a guarded slot triggers a
   verification pass against a per-namespace trust list, optionally followed by a dialog that names
   the origin of the stack.

The mod installs on **both sides**. The server owns authority; the client owns presentation.

---

## 1. Module map

```
top.kdxiaoyi.mod.getskeeper
├── GetsKeeper                     mod entrypoint, wiring, MOD_ID / LOGGER / id()
├── GetsKeeperEvents               login/logout/server-stop glue; forces operator password setup
├── config/GKConfig                the TOML spec, the trust lists, the snapshot accessors
├── trust/
│   ├── TrustLevel                 FULLY_TRUSTED | TRUSTED | UNTRUSTED | BLOCKED
│   └── TrustService               item id -> trust level, most specific entry first
├── item/
│   ├── GKItems                    item registration
│   ├── GKDataComponents           the image_seal data component
│   ├── MountedImageSeal           codec + stream codec for the seal
│   └── MountedContainerImageItem  the item itself: unstackable, never dropped
├── data/
│   ├── GKAttachments              player attachments (authorizations, sources), copyOnDeath
│   ├── AuthorizationRecord        enabled / disabled item id sets
│   ├── SourceLedger               per item id: list of source entries with counts
│   ├── GKWorldData                save-level SavedData: the passwords
│   └── PasswordVault              PBKDF2WithHmacSHA256 hashing, name-derived salt
├── source/
│   ├── SourceType                 CONTAINER, COMMAND, CRAFTING, FURNACE, TRADE, FISHING,
│   │                              CREATIVE, PICKUP, UNKNOWN
│   ├── ItemSource                 one acquisition: dimension, x/y/z, type, label, who, when
│   ├── SourceFormatter            translation key plus arguments; first 3 sources then an ellipsis
│   └── SourceRecorder             direct hooks + the inventory diff
├── pickup/
│   ├── PickupCandidate            one offered stack, and how to consume its source
│   ├── InventoryProbe             simulated insertion; free slots vs. slots needed
│   ├── PickupSession              session cache, open batch, cooldowns
│   └── PickupInterceptor          the interception itself
├── use/UseGuard                   the verification state machine
├── security/
│   ├── PasswordService            operator check, prompt, store, verify, disconnect
│   ├── AuthorizationService       read/write the authorization record
│   └── SessionGuards              items refused during this play session
├── net/
│   ├── GKPayloads                 every packet, hand written codecs
│   ├── GKNetwork                  registration + send helpers
│   ├── GKServerHandler            everything the client sends
│   ├── PasswordPurpose            SET_PASSWORD | AUTHORIZE_ITEM | TOGGLE_AUTHORIZATION |
│   │                              GRANT_BLOCKED_USE
│   └── VerificationDecision       CANCEL | CONTINUE | VOID
└── client/                        (loaded on the client only)
    ├── GetsKeeperClient           client entrypoint: payload handlers, HUD layer, pause button
    ├── GKClientPayloadHandler     packets -> screens
    ├── ClientGuardState           what the client is currently showing
    ├── MacTheme                   palette, rounded rectangles, circles, text helpers
    ├── MacButton / MacToggle      the two controls every screen is built from
    ├── MacDialogScreen            one dialog class for every prompt
    ├── GKDialog                   builds each prompt from translation keys
    ├── PickupScreen               the drag and drop pickup screen
    ├── PasswordScreen             the masked password prompt
    ├── PrivacySecurityScreen      the pause menu page
    └── VerificationOverlay        the experience-bar verification overlay
```

The client code lives in the main source set but is only ever reached from `GetsKeeperClient`, which
is declared `@Mod(value = "getskeeper", dist = Dist.CLIENT)`. FML never loads that class on a
dedicated server, so no screen class is ever initialised there. Keeping one source set keeps the
Gradle setup in the shape the official MDK ships, which matters more for compatibility than the
tidier separation would.

---

## 2. Data flow

### 2.1 Pickup

```
player walks over an ItemEntity
        │
        ▼
ItemEntityPickupEvent.Pre  (server)
        │  item id already in the session cache?
        ├── yes ─► let vanilla pick it up
        └── no  ─► setCanPickup(FALSE)
                    │
                    ▼
              PickupInterceptor.offer()
                 ├── skip if a batch is already open for this player
                 ├── skip if the source is on cooldown for this player
                 ├── scan a sphere of radius pickup.scan_radius for ItemEntities
                 │     (drop anything already cached or on cooldown)
                 ├── InventoryProbe.probe(): simulate inserting the batch
                 └── if it fits and the player holds no image yet, add a sealed image
                    │
                    ▼
              OpenPickupScreen payload ──► client shows PickupScreen
                    │
        ┌───────────┴────────────┐
        ▼                        ▼
  drag onto the image      close / Escape
        │                        │
        ▼                        ▼
 PickupDecision(true)     PickupDecision(false)
        │                        │
        ▼                        ▼
 give each stack,        restore flipped arrow pickup,
 mark it picked,         put every source on cooldown,
 discard the source      cache nothing
```

Arrows take a different route because vanilla adds them to the inventory from inside entity
collision code, which no event can veto. The tick scanner finds a stuck, pickable arrow, sets
`AbstractArrow.pickup` (a public field) to `DISALLOWED`, and offers it. Declining puts the field back,
so nothing is removed from the world by merely being offered.

The AABB scan also runs every tick for items when no batch is open, which is what makes the screen
appear even if the player never physically collides with the entity (for example, standing inside a
pile of drops).

### 2.2 Verification

```
an unauthorized stack appears in a guarded slot
        │
        ▼
UseGuard: put the previous contents back
        │
        ▼
roll a duration between verification.min_ticks and verification.max_ticks
        │
        ▼
VerificationStart payload ──► client draws the experience-bar overlay
        │
   (server waits the same number of ticks)
        ▼
resolve the trust level and the recorded source
        │
        ├── FULLY_TRUSTED ─► re-apply silently, no dialog
        └── otherwise ─────► VerificationResult payload ──► dialog
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
                 CANCEL         CONTINUE          VOID
                    │               │               │
              drop the state   re-apply the    clear the stack
                               intercepted     from the inventory
                               operation
```

The four interception points are:

| Point | What it catches |
|---|---|
| guarded slot watcher (`PlayerTickEvent.Post`) | an unauthorized stack appearing in a hand or an equipment slot |
| `ItemStackedOnOtherEvent` | a stack dropped onto a crafting grid or a work block input |
| `UseItemOnBlockEvent` | using an unauthorized item on a block |
| `LivingEntityUseItemEvent.Start` | using one at all: eating, drawing a bow, and so on |

"Guarded slots" are the selected hotbar slot, the offhand, and every slot in
`Inventory.EQUIPMENT_SLOT_MAPPING`. The rest of the backpack is deliberately not guarded: moving an
item around inside the backpack is not using it.

### 2.3 Authorization and the privacy page

```
Pause menu ─► "Privacy & Security" ─► PrivacySecurityScreen
                                        │
                                        ├── authorized items from the synced attachment
                                        │     switch flipped ─► PasswordScreen
                                        │                          │
                                        │              PasswordSubmit(TOGGLE_AUTHORIZATION)
                                        │                          │
                                        │        server verifies, inverts the state,
                                        │        attachment sync redraws the switch
                                        │
                                        └── session refusals from SessionGuards
                                              "use anyway" ─► PasswordPrompt
                                                                │
                                                    PasswordSubmit(GRANT_BLOCKED_USE)
                                                                │
                                                       permanent authorization
```

---

## 3. Persistence

| Data | Where | Lifetime | Survives death |
|---|---|---|---|
| Authorizations (enabled + disabled sets) | player attachment `getskeeper:authorizations` | per world, per player | yes (`copyOnDeath`) |
| Source ledger | player attachment `getskeeper:sources` | per world, per player | yes (`copyOnDeath`) |
| Passwords | `GKWorldData` (`SavedData`, file `getskeeper.dat`) | per world | n/a |
| Session pickup cache | `PickupSession` (in memory) | one play session | no |
| Session refusal list | `SessionGuards` (in memory) | one play session | no |
| Trust lists | config file `config/getskeeper-common.toml` | per installation | n/a |

Both attachments are skipped on disk while empty, so a player who never authorizes anything costs
nothing. The authorizations attachment is synced to its owner only
(`sync((holder, player) -> holder == player, ...)`); the source ledger is never synced at all — the
server sends the relevant source with the verification dialog instead of mirroring the whole map.

### Password storage

`PBKDF2WithHmacSHA256`, a 256 bit derived key, and a salt derived from `SHA-256(lowercase name)`.
Deriving the salt from the name means no salt has to be stored, and two players who choose the same
password still get different hashes. Comparison uses `MessageDigest.isEqual`, so a wrong password does
not leak how many characters were right. The iteration count travels with each hash, so raising the
config value does not invalidate existing passwords.

---

## 4. Where the UI's data comes from

The client never computes anything that matters:

* the pickup batch is a list of stacks and ids in a payload;
* the verification verdict carries the trust level and the origin as a translation key plus
  arguments, so the player reads it in their own language even though the server decided;
* the authorized list arrives through the synced attachment;
* the session refusal list arrives through a payload, both when it changes and when the page opens.

The one thing the client decides is the *shape* of the number it sends for a switch flip: it says
"flip this switch" and the server inverts the authoritative state.

---

## 5. Configuration

`config/getskeeper-common.toml`, written from `GKConfig` with comments on every entry.

* `trust_lists.fully_trusted` — default `["minecraft"]`, and the operator may remove it.
* `trust_lists.trusted` — default empty.
* `trust_lists.blocked` — default empty.
* `pickup.enabled`, `pickup.scan_radius`, `pickup.cooldown_ticks`, `pickup.include_arrows`.
* `verification.enabled`, `verification.min_ticks`, `verification.max_ticks`.
* `security.require_admin_password`, `security.min_length`, `security.pbkdf2_iterations`.

An entry may be a namespace (`minecraft`) or a full item id (`minecraft:tnt`), and a full id always
wins. **An entry that appears in more than one list crashes the game** with a message naming the
conflicting entry, from both the initial load and any later reload. Guessing which list wins would
make the outcome depend on iteration order, which is exactly the sort of thing that silently changes
behaviour between restarts.

---

## 6. 1.21.11 specifics

The port from older versions is not cosmetic. Things this code depends on that changed in 21.11:

* `ResourceLocation` is now `net.minecraft.resources.Identifier`, and `ResourceKey#location` is
  `ResourceKey#identifier`.
* The pickup event is `ItemEntityPickupEvent.Pre` / `.Post`. `EntityItemPickupEvent` no longer exists.
* Player input arrives as event objects: `MouseButtonEvent`, `KeyEvent`, `CharacterEvent`. Screens
  override `mouseClicked(MouseButtonEvent, boolean)`, not `mouseClicked(double, double, int)`.
* Blitting needs a `RenderPipeline`: `blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h)`.
* Saved data uses `SavedDataType` plus a `Codec`; the old `save(CompoundTag, ...)` override is gone.
  NeoForge adds a three argument `SavedDataType` constructor that is used here.
* `Commands.LEVEL_*` are `PermissionCheck` values, so a command requires an operator with
  `.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))` instead of `hasPermission(3)`.
* Attachments serialize through a `MapCodec`, hence `CODEC.fieldOf("data")`.
* `@Nullable` is `org.jspecify.annotations.Nullable`. This mod uses no such annotations at all, which
  sidesteps the type-use subtleties entirely.
* Client HUD content is registered as a `GuiLayer` through `RegisterGuiLayersEvent`.
* Client payload handlers are attached through `RegisterClientPayloadHandlersEvent`, which is what
  keeps server to client payloads registered without a handler in common code.
* `PacketDistributor` only carries the server's send methods; a client sending to the server goes
  through `net.neoforged.neoforge.client.network.ClientPacketDistributor#sendToServer`.
* `AbstractArrow` exposes `getPickupItemStackOrigin()` publicly but keeps `getPickupItem()` and
  `isInGround()` protected. See deviation 4 below for how that is worked around.
* `Item.Properties` now carries the item's own id. A `DeferredRegister.Items` factory that ignores the
  registry key and builds `new Item.Properties()` itself fails at registration time with
  `NullPointerException: Item id not set`, thrown from `Item.Properties#effectiveDescriptionId`. The
  key-aware overload (`register(name, Function<Identifier, ...>)`, or `registerItem(...)`) sets it.

The mod uses **no mixins and no access transformers**: everything goes through the public API.

---

## 7. Known deviations and limits

These are deliberate, and each one is a place where the original design could not be followed exactly.

1. **Chest artwork.** 1.21.11's GUI atlas has no chest-front sprite (`container/*` holds crafting,
   furnace, beacon and similar icons only), so the pickup screen renders vanilla's chest **item**
   model with `GuiGraphics#renderItem`. The artwork is vanilla, it cannot break between versions, and
   it is instantly readable as a chest. The `Inventory` label under it is a literal, never translated,
   as specified.
2. **Experience bar.** The overlay uses the real vanilla sprites
   (`hud/experience_bar_background` and `hud/experience_bar_progress`, verified against the 1.21.11
   client asset index) and clips the progress sprite with a scissor, which is how vanilla fills a
   partial bar without stretching the texture.
3. **Work slots.** The work-slot rule is `slot.container instanceof CraftingContainer`, which covers
   the player's 2x2 grid, the crafting table, and the anvil, smithing table, loom, stonecutter and
   cartography table inputs. A grindstone's and an enchanting table's input slots are plain
   containers in vanilla, so they are not intercepted there; the resulting hand or equipment use
   still is. Widening the rule would have meant intercepting container menus too, which the design
   explicitly excludes.
4. **Arrow pickup.** Vanilla performs arrow pickup from inside collision handling, so the arrow's
   public `pickup` field is flipped to `DISALLOWED` to suspend it and restored if the player declines,
   instead of cancelling an event. Two of the accessors vanilla uses there are not available:
   `isInGround()` and `getPickupItem()` are both `protected`, and this mod takes no access
   transformers, so the resting test is reconstructed from `isNoPhysics()`, `onGround()` and a
   near-zero velocity check, and the item comes from the public `getPickupItemStackOrigin()`. An
   arrow in flight fails all three tests, which is the case that must not be intercepted.
5. **"Move to the void".** The stack that is being verified is cleared, together with any identical
   stack in the inventory. Both are logged.
6. **Operators in single-player.** "Single-player counts as administrator" is implemented as
   "permission level 3". The host of a single-player world already holds that level, so the behaviour
   is identical to a dedicated server without a special case that a LAN guest could trip over.
7. **Re-apply after "Continue".** The intercepted operation is retried by putting the stack back into
   the slot it came from. Uses that are not slot moves (right clicking a block, starting to eat)
   cannot be retried for the player; the action simply has to be repeated. Failures are swallowed and
   logged at debug level, as specified.

---

## 8. Logging

Nothing nudges the player about bookkeeping. Authorizations, restrictions, refusals, password
attempts, voided stacks and overflowed pickups all go to the log through
`GetsKeeper.LOGGER` and are never shown as a chat message or a toast. The only thing the player ever
sees is a screen that is waiting for a decision.
