#!/usr/bin/env python3
"""Regenerate the local stand-in for net.neoforged:minecraft-dependencies.

Why this exists
---------------
ModDevGradle's ``RepositoriesPlugin`` declares a repository named "Mojang Meta"
(``https://maven.neoforged.net/mojang-meta/``) whose *only* content filter is
``net.neoforged:minecraft-dependencies``. That module carries the list of Minecraft's own
libraries, and ``net.neoforged:neoform`` depends on it, so it ends up on both the mod's compile
classpath and NFRT's recompile classpath. Without it the build fails with a few dozen
"package com.mojang.serialization does not exist" / "package io.netty.buffer does not exist"
errors.

The artifact is published only to that one host, and it is absent from every community mirror:
BMCLAPI/SJTU has no such path, and CreeperHost proxies ``/releases/`` and ``/snapshots/`` but not
``/mojang-meta/``. On a network where that host is unreachable there is no way to fetch it, so
this script rebuilds an equivalent module from data the build already has, and ``build.gradle``
points the "Mojang Meta" repository at ``gradle/local-repo`` so Gradle picks it up transparently.

Input
-----
The Minecraft version manifest, which NFRT downloads during the build. If the cached copy is
missing, pass ``--manifest`` or let the script fetch it from Mojang.

Output
------
``gradle/local-repo/net/neoforged/minecraft-dependencies/<version>/`` containing Gradle Module
Metadata, a POM, and an empty jar. The dependency list is exactly the ``libraries`` array of the
Minecraft version manifest minus natives -- nothing here is guessed.

Usage
-----
    python3 tools/generate-minecraft-dependencies.py [--version 1.21.11] [--manifest PATH]

Rerun it when bumping the Minecraft version, then commit the regenerated files.
"""

from __future__ import annotations

import argparse
import base64
import glob
import hashlib
import json
import os
import pathlib
import sys
import urllib.request
import zipfile

DEFAULT_VERSION = "1.21.11"
OUTPUT_ROOT = pathlib.Path(__file__).resolve().parent.parent / "gradle/local-repo"
MODULE_GROUP = "net.neoforged"
MODULE_NAME = "minecraft-dependencies"

# The manifest NFRT has already cached, checked before anything is downloaded.
CACHE_PATTERN = "~/.gradle/caches/neoformruntime/artifacts/minecraft_{version}_version_manifest.json"


def cached_manifest(version: str) -> pathlib.Path | None:
    """Return NFRT's cached version manifest when it matches the wanted version."""
    for hit in glob.glob(os.path.expanduser(CACHE_PATTERN.format(version=version))):
        path = pathlib.Path(hit)
        try:
            if json.loads(path.read_text())["id"] == version:
                return path
        except (KeyError, ValueError):
            continue
    return None


def download_manifest(version: str) -> dict:
    """Fetch the version manifest from Mojang, going through the version index."""
    with urllib.request.urlopen(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", timeout=60
    ) as response:
        index = json.load(response)
    url = next((entry["url"] for entry in index["versions"] if entry["id"] == version), None)
    if url is None:
        raise SystemExit(f"Minecraft {version} is not in Mojang's version index")
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def maven_libraries(manifest: dict) -> list[tuple[str, str, str]]:
    """Return (group, artifact, version) for every plain library Minecraft ships."""
    libraries = []
    for library in manifest["libraries"]:
        parts = library["name"].split(":")
        # "group:artifact:version" is an ordinary library. A fourth part is a classifier, i.e. a
        # natives jar, which NFRT unpacks at runtime instead of resolving it through Gradle.
        if len(parts) != 3 or "artifact" not in library.get("downloads", {}):
            continue
        libraries.append((parts[0], parts[1], parts[2]))
    return libraries


def write_empty_jar(path: pathlib.Path) -> dict[str, object]:
    """Create the placeholder jar and return the size/checksums the module file must record."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    data = path.read_bytes()
    return {
        "name": path.name,
        "url": path.name,
        "size": len(data),
        "sha512": base64.b64encode(hashlib.sha512(data).digest()).decode(),
        "sha256": hashlib.sha256(data).hexdigest(),
        "sha1": hashlib.sha1(data).hexdigest(),
        "md5": hashlib.md5(data).hexdigest(),
    }


def write_module(path: pathlib.Path, version: str, libraries: list[tuple[str, str, str]], artifact: dict) -> None:
    dependencies = [
        {"group": group, "module": artifact_name, "version": {"requires": library_version}}
        for group, artifact_name, library_version in sorted(libraries)
    ]

    def variant(name: str, usage: str) -> dict:
        return {
            "name": name,
            "attributes": {
                "org.gradle.category": "library",
                "org.gradle.dependency.bundling": "external",
                "org.gradle.libraryelements": "jar",
                "org.gradle.usage": usage,
            },
            "dependencies": dependencies,
            "files": [artifact],
        }

    metadata = {
        "formatVersion": "1.1",
        "component": {
            "group": MODULE_GROUP,
            "module": MODULE_NAME,
            "version": version,
            "attributes": {"org.gradle.status": "release"},
        },
        "createdBy": {"gradle": {"version": "9.2.0"}},
        "variants": [variant("apiElements", "java-api"), variant("runtimeElements", "java-runtime")],
    }
    path.write_text(json.dumps(metadata, indent=2) + "\n")


def write_pom(path: pathlib.Path, version: str, libraries: list[tuple[str, str, str]]) -> None:
    """Write a matching POM as well, for anything that resolves this module as Maven metadata."""
    dependencies = "\n".join(
        "    <dependency>\n"
        f"      <groupId>{group}</groupId>\n"
        f"      <artifactId>{artifact}</artifactId>\n"
        f"      <version>{library_version}</version>\n"
        "      <scope>compile</scope>\n"
        "    </dependency>"
        for group, artifact, library_version in sorted(libraries)
    )
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
        "  <modelVersion>4.0.0</modelVersion>\n"
        f"  <groupId>{MODULE_GROUP}</groupId>\n"
        f"  <artifactId>{MODULE_NAME}</artifactId>\n"
        f"  <version>{version}</version>\n"
        "  <packaging>jar</packaging>\n"
        "  <dependencies>\n"
        f"{dependencies}\n"
        "  </dependencies>\n"
        "</project>\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Regenerate the local minecraft-dependencies module")
    parser.add_argument("--version", default=DEFAULT_VERSION, help="Minecraft version")
    parser.add_argument("--manifest", type=pathlib.Path, help="Use this manifest instead of a cached one")
    arguments = parser.parse_args()

    if arguments.manifest:
        manifest = json.loads(arguments.manifest.read_text())
    elif (cached := cached_manifest(arguments.version)) is not None:
        print(f"using cached manifest {cached}")
        manifest = json.loads(cached.read_text())
    else:
        print(f"no cached manifest for {arguments.version}, downloading from Mojang")
        manifest = download_manifest(arguments.version)

    libraries = maven_libraries(manifest)
    destination = OUTPUT_ROOT / MODULE_GROUP.replace(".", "/") / MODULE_NAME / arguments.version
    destination.mkdir(parents=True, exist_ok=True)

    artifact = write_empty_jar(destination / f"{MODULE_NAME}-{arguments.version}.jar")
    write_module(destination / f"{MODULE_NAME}-{arguments.version}.module", arguments.version, libraries, artifact)
    write_pom(destination / f"{MODULE_NAME}-{arguments.version}.pom", arguments.version, libraries)

    print(f"wrote {len(libraries)} libraries to {destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
