# GetsKeeper

As far as we known, the GateKeeper on macOS guaranteed the security of our computer and system.
Also, Minecraft is the most widely known game around the world.
So, to solve why GateKeeper doesn't expand the market in Minecraft, we are inspired to make it come true.

## Features

* 100% Reintroduced GateKeeper Experience: from item checks to anti-malitem
* ZeroTrust Ensured: salted-SHA256 guaranteed permission controller
* Original Text: enable u to adapt to migration quickly

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 |
| NeoForge | 21.11.41 or newer in the 21.11 line |
| Java | 21 |
| Gradle | 9.2 (the bundled wrapper) |
| Build plugin | ModDevGradle 2.0.124 |

## Building

```bash
./gradlew build
```

The mod jar lands in `build/libs/getskeeper-<version>.jar`.

To launch a development client or server:

```bash
./gradlew runClient
./gradlew runServer
```

The dedicated server writes `eula.txt` on first start; accept it, and set `online-mode=false` in
`server.properties` if you want to connect with a development account.

### If the NeoForge Maven is unreachable

`maven.neoforged.net` is the only place the NeoForge artifacts live, and it is occasionally offline.
When it is, Gradle cannot resolve `net.neoforged:neoforge` and the build stops before it compiles
anything. The project's own release notes point at a mirror for exactly this case; add it to
`settings.gradle` ahead of the official repository:

```groovy
maven {
    name = 'NeoForgedMirror'
    url = 'https://maven.creeperhost.net'
}
```

Remove it again once the official Maven is back, so the build goes back to the supported source.

## Installing

1. Install NeoForge 21.11.41 for Minecraft 1.21.11.
2. Drop the jar into `mods/` **on both the client and the server**.
3. Start the game. The config file is written to `config/getskeeper-common.toml` on first launch.

## Configuration

`config/getskeeper-common.toml`, with a comment on every entry:

```toml
[trust_lists]
    # Namespaces that are above suspicion...
    fully_trusted = ["minecraft"]
    # Namespaces that get a plain confirmation dialog...
    trusted = []
    # Namespaces that are refused outright...
    blocked = []
```

An entry is either a namespace (`minecraft`) or a full item id (`minecraft:tnt`); a full id always
takes precedence. **The same entry may not appear in two lists** — if it does, the game crashes with a
message telling you which entry is duplicated. That is intentional: a shared entry would make the
outcome depend on iteration order.

Other sections control the pickup scan radius and cooldown, the verification duration range, and the
password policy. All of them are documented inline in the file.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/mattr -r -d com.ojang.quarantine <item>` | operator (level 3) | Authorizes an item after a password. The three tokens are order independent. |
| `/passwd` | anyone | Opens the password prompt to set or change your own password. |

`/mattr` takes its arguments as a single line, so Brigadier offers no completions for it, and it
checks that the item exists only after the password has been accepted — reporting a name that does
not resolve the way vanilla does.

## Documentation

See [ARCHITECTURE.md](ARCHITECTURE.md) for the module map, the data flow, the persistence rules, the
1.21.11 API notes and the list of deliberate deviations.

## License

MIT. See [LICENSE](LICENSE).
