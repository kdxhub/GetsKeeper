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
        │  the mod's own image, or a stack still inside its pickup delay
        │  (it was thrown a moment ago)?  ──────────────► let vanilla pick it up
        │  item id already in the session cache?  ───────► let vanilla pick it up
        └── otherwise ─► offer first, and only veto the pickup
                         if the screen really opens
                    │
                    ▼
              PickupInterceptor.offer()
                 ├── skip if a batch is already open for this player
                 ├── skip if the source is on cooldown for this player
                 ├── scan a sphere of radius pickup.scan_radius for ItemEntities
                 │     (drop anything already cached, cooling down, or still in its delay)
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

The pickup is vetoed only after the offer has produced a batch. Refusing first and then finding nothing
to offer would take the pickup away and give nothing back, stranding the stack on the ground where
nobody could take it.

Two kinds of stack are skipped rather than offered. The mod's own image is never handled by any part of
the mod. And a stack that was just thrown or dropped is still inside vanilla's own pickup delay - the
grace period that stops an item being grabbed back the instant you throw it - so offering it would mean
the mod asking whether you want to pick up the thing you deliberately just threw away.

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
UseGuard: put back every slot the operation touched, and the cursor with it
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
        ├── FULLY_TRUSTED ─► (unreachable in practice: those never start one)
        └── otherwise ─────► VerificationResult payload ──► dialog
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
                 CONTINUE         CANCEL          VOID
                    │               │               │
              record the       leave the undo   clear the stack
              authorization    as it is, hand   from the inventory
              and write the    back anything    and from the cursor
              operation back   the undo could
                               not restore
```

Every answer also drops the snapshot baseline, so the next tick compares against the world as it is
now rather than against the state from before the interception. Without that, a stack that was just
put back reads as a brand new change and the item is verified again for ever.

#### What "undo" and "re-apply" mean

An interception records the **footprint** of the operation: every slot whose contents changed, what
they hold afterwards, and the inventory menu's carried item on both sides of it. The cursor is part
of that footprint because a dragged stack lives on it between the two clicks, and it is not an
inventory slot - a revert that ignored it left the stack in the slot *and* on the cursor.

* **Undo** writes the touched slots back and puts the cursor back. Only the touched slots, so work
  the player did elsewhere in the same tick survives.
* **Continue** writes the footprint forward again, which is the operation itself: a swap comes out as
  a swap, a drag puts the stack back on the cursor where it was heading from. Writing only the watched
  slot instead duplicated the stack, because the undo had already restored a copy of it somewhere else.
* **Cancel** needs to do nothing at all for that case. The one stack the undo cannot restore is one
  that came from outside the player's own slots - out of a chest, say - and that one is handed back.
* **Move to the void** clears every stack of that item, matched on the item and its components rather
  than on an exact count, since the copy left in place may well hold a different amount.

The interception points are:

| Point | What it catches |
|---|---|
| guarded slot watcher (`PlayerTickEvent.Post`) | an unauthorized stack appearing in a hand or an equipment slot, and a different hotbar slot being selected |
| `ItemStackedOnOtherEvent` | a stack dropped onto a crafting grid or a work block input |
| `UseItemOnBlockEvent` | using an unauthorized item on a block |
| `LivingEntityUseItemEvent.Start` | using one at all: eating, drawing a bow, and so on |
| `PlayerInteractEvent.LeftClickBlock` | mining with one |
| `LivingSwapItemsEvent.Hands` | swapping one into the other hand with F |
| `PlayerInteractEvent.RightClickItem` | right clicking with one, which is what equipping armour is |

The last three were added because they were ways to act with an unverified stack that nothing else
covered, and because all of them are asked *before* they happen: the F swap in particular used to be
undone after the fact, and answering that dialog could duplicate the stack. Now the event is simply
refused, so there is nothing to undo and nothing to duplicate. Equipping armour goes through the
right-click event alone - it moves a stack into an equipment slot without ever touching the
use-on-block or use-item path - and the slot watcher only saw it after the item was already worn.

The use-on-block event is the one that is filtered rather than taken whole. It is fired three times
for a single right click: twice for the item's own action and once, in the block phase, for the
block's answer to being clicked. Only the item's phases are the item being used; refusing the block
phase meant a chest or a door would not open at all while an unverified item happened to be in hand,
which is also the opposite of what the design says - container interaction is not verified. The item's
pre-emptive phase is cancelled with `PASS` rather than `FAIL`, because vanilla hands the block its
turn only if that phase returns `PASS`: the item is refused, and the block still gets to behave.

"Guarded slots" are the selected hotbar slot, the offhand, and every slot in
`Inventory.EQUIPMENT_SLOT_MAPPING`. The rest of the backpack is deliberately not watched: moving an
item around inside the backpack is not using it - although it is snapshotted, because the other end of
a move into a guarded slot needs to be put back too. Selecting a different hotbar slot counts as the
stack arriving in the hand even though no slot's contents changed, which is why the watcher tracks the
selection as well as the slots.

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
* the session refusal list arrives through a payload, both when it changes and when the page opens;
* the password prompt carries the minimum length the server enforces, so the prompt can state the
  rule on the field instead of letting a too-short password come back as a bare rejection that looks
  like a wrong password.

The one thing the client decides is the *shape* of the number it sends for a switch flip: it says
"flip this switch" and the server inverts the authoritative state.

Changing a payload's fields is a protocol change: `GKNetwork.VERSION` is bumped whenever one changes
shape, which is how a client and a server running different builds of the mod refuse to talk instead
of misreading each other. It is currently `2` — `PasswordPrompt` gained its `minLength` field.

---

## 5. Configuration

`config/getskeeper-common.toml`, written from `GKConfig` with comments on every entry.

* `trust_lists.fully_trusted` — default `["minecraft"]`, and the operator may remove it.
* `trust_lists.trusted` — default empty.
* `trust_lists.blocked` — default empty.
* `pickup.enabled`, `pickup.scan_radius`, `pickup.cooldown_ticks`, `pickup.include_arrows`.
* `verification.enabled`, `verification.min_ticks`, `verification.max_ticks`.
* `security.require_admin_password`, `security.min_length` (default 6), `security.pbkdf2_iterations`.

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

## 7. Deliberate choices and limits

These are intentional. Some are places where the original design could not be followed exactly; others
are the rules the design implies but never spells out.

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
7. **What happens to an intercepted stack.** Interception puts the operation back and records what it
   had touched, so all three answers have to be consistent with that undo. "Continue" writes the
   operation forward again - into the slots it had changed and onto the cursor - and records the
   authorization; "Move to the void" clears the stack; "Cancel" leaves the undo standing and hands
   back only the stack the undo could not restore, which is one that came from outside the player's
   own slots. A cancelled verification must never cost the player an item, and a confirmed one must
   never hand out a second copy: both of those came from restoring one end of a move while writing the
   other end back by hand.
   Uses that are not slot moves (right clicking a block, starting to eat, swinging at a block) have
   nothing to put back: they were vetoed, not undone, so the player simply repeats the action.
   Failures are swallowed and logged at debug level, as specified.
8. **A prompt is answered, never just dismissed.** Every button closes its dialog once it has acted,
   and Escape takes the same action as the safe button - the non-destructive one, "Cancel" or "Done"
   or "OK". Escape is therefore not a way to close a dialog without answering it, which would leave
   the server waiting for a decision and the intercepted item in limbo. Dialogs marked not closable
   ignore Escape entirely. The full-inventory warning is an ordinary screen rather than a GUI layer
   stacked over the pickup screen, so one acknowledgement closes the whole thing - the pickup screen's
   removal is what tells the server to leave the items where they are.
9. **The container image never touches the world.** Dropping it destroys it: the join of the would-be
   `ItemEntity` is refused, which is the moment the stack has already left the inventory, so refusing
   the entity *is* the destruction. (Cancelling the drop instead - the obvious reading - leaves the
   item sitting in the inventory, which is what the mod used to do.) On death it is neither dropped nor
   lost: it is filtered out of the death drops and copied to the respawned player. It is also exempt
   from every gate in the mod - not verified, not intercepted on pickup, not written into the source
   records - because verifying the image would mean verifying the tool that does the verifying.
10. **A fully trusted namespace is verified, silently.** The pass runs - the progress bar plays - and
    the verdict that ends it is sent, which is what makes the bar disappear; the client draws nothing
    else for `FULLY_TRUSTED`, so there is no dialog. Skipping the pass altogether was tried and was
    wrong: the trust lists are how the operator chooses between "watched but never questioned" and
    "watched and questioned", so removing the bar took away the only feedback a fully trusted item
    had. Only the mod's own items and authorized ones skip a pass entirely.
11. **Left clicking is starting to mine.** Mining does not go through the use-on-block or the
    use-item path, so an unverified pickaxe could break a block. The swing is now vetoed at its
    start, which keeps the block intact; the repeat events vanilla sends while the button is held all
    land on the same pending verification. Creative mode is the exception, and it is vanilla's:
    breaking a block there does not consult this event, so nothing can be refused.
12. **Blocked items are not offered a grant.** The privacy page's session list is a list of refusals
    with a "use anyway" button, and a blocked item has nothing to grant - the dialog only offers the
    void. Listing it advertised a button that could not do anything, so blocked items are no longer
    marked, and an entry disappears the moment a grant goes through.
13. **The verification bar is drawn twice, in two places.** Once as a HUD layer for when nothing is
    open, and once from `ScreenEvent.Render.Post` for when something is. Vanilla renders the HUD
    *before* the screen, and NeoForge's screen hook renders the registered layers even earlier than
    that, so the layer alone put the bar behind the inventory and every other screen.
14. **Text in this mod's own UI has no drop shadow.** Every panel is drawn on an opaque card, where
    vanilla's shadow only smears the glyphs. The one exception is the verification bar's label, which
    sits over the world rather than over a card and asks for a shadow explicitly.
15. **The pickup screen has a Close button.** Escape does the same thing and the server holds the
    items back until the screen is answered either way, but a screen that can only be left with a key
    the player has to guess reads as a screen that is stuck.
16. **The password prompt goes back where it came from.** Opened from the privacy page, it returns to
    the privacy page, which is where the answer's effect is visible: a granted item leaves the list of
    refusals, and the list is redrawn from the payload the server sends back. Opened as the forced
    first-time setup, it has no parent and closes to the world as before.

---

## 8. Logging

Nothing nudges the player about bookkeeping. Authorizations, restrictions, refusals, password
attempts, voided stacks and overflowed pickups all go to the log through
`GetsKeeper.LOGGER` and are never shown as a chat message or a toast. The only thing the player ever
sees is a screen that is waiting for a decision.
