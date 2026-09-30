# Aeromon Launcher

Aeromon Launcher is the planned cross-platform player app for the Aeromon Minecraft 1.21.1 / NeoForge modpack. This repository tracks the launcher separately from the private server management panel and game server.

## Intended experience

- Install, update, repair, and play an isolated Aeromon instance on Windows, macOS, and Linux.
- Read signed, version-pinned pack releases from Aeromon's public release feed.
- Download mods from their permitted source: Modrinth by default, CurseForge when approved API access and project distribution allow it, and Aeromon hosting for files we may distribute.
- Verify file hashes before installation and preserve player-owned saves and preferences during updates.
- Use a shared Java 21 application and interface packaged with platform-specific native wrappers and a bundled runtime.

## Status

Planning and initial implementation. There is no downloadable launcher release yet. The server panel and release feed are being completed before the player installer.

## Repository boundaries

No API keys, account credentials, signing private keys, Minecraft game files, or third-party mod jars belong in this repository. CurseForge access, if approved, will follow its API and each project's distribution settings.

See [architecture](docs/ARCHITECTURE.md) for the initial design.
