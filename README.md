# GetsKeeper
As far as we know, Gatekeeper on macOS is supposed to keep your Mac secure.
Minecraft is the most widely known game in the world.
So when we noticed Gatekeeper had never expanded into Minecraft, we took matters into our own hands.

## Features

* 100% Authentic Gatekeeper Experience: from item signature checks to anti-mal-item quarantine
* Zero Trust, Guaranteed: a salted-SHA256 permission controller that solves everything, probably
* Original Text: helps you migrate quickly, because learning new things is hard

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 |
| NeoForge | 21.11.48 (see the note on 21.11.41 below) |
| Java | 21 |
| Gradle | 9.2 (the bundled wrapper) |
| Build plugin | ModDevGradle 2.0.124 |

### About the NeoForge version

**21.11.41 cannot be built against.** It appears in the Maven metadata and carries a git tag, but no
artifact was ever published for it, so asking for it returns a plain 404 — which is easy to misread as
a network or mirror problem. `21.11.42`, `21.11.44`, `21.11.45` and `21.11.48` all resolve normally.
This project is pinned to `21.11.48`, the newest patch in the line; change `neo_version` in
`gradle.properties` to `21.11.42` if you want to stay as close as possible to the original target.

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

### Building from mainland China (mirrors)

`maven.neoforged.net` can be unreachable or DNS-intercepted on some networks. `build.gradle` therefore
lists two mirrors **ahead of** the official repository:

| Repository | What it carries |
|---|---|
| `https://mirror.sjtu.edu.cn/bmclapi` (BMCLAPI) | Fast from China. Mirrors the NeoForge Maven, but the copy is **partial** — it has no `neoform-runtime` and several version folders are missing files. |
| `https://maven.creeperhost.net` | A live proxy of `maven.neoforged.net`. Whatever exists upstream resolves here, including `neoform-runtime` and the NeoForm data. |

Gradle walks them in order, so BMCLAPI serves what it has and CreeperHost fills the rest. Together they
cover the whole toolchain, which is how this project builds where the official host is unreachable.

If your network reaches `maven.neoforged.net` normally, delete both blocks and keep the official
repository only — that is the supported source. Note that a 404 from a mirror does **not** always mean
the mirror is incomplete: `neoforge-21.11.41.pom` is a genuine 404 upstream (see the note above).

When resolution fails, it looks like this — and note that the compile task never runs, so no Java
compiler is invoked and no source error is ever reported:

```
> Could not resolve net.neoforged:neoform-runtime:2.0.5.
   > Could not GET 'https://maven.neoforged.net/releases/net/neoforged/neoform-runtime/2.0.5/neoform-runtime-2.0.5.pom'.
      > Connect to maven.neoforged.net:443 failed: Connect timed out
```

If the hostname resolves to an address that is not NeoForge's, your DNS is being intercepted; a
different resolver or a VPN is usually enough.

## Installing

1. Install NeoForge 21.11.48 for Minecraft 1.21.11.
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
