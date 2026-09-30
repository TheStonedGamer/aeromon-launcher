# Aeromon Launcher

Aeromon Launcher is the shared Java desktop player app for the Aeromon Minecraft 1.21.1 / NeoForge modpack, with native platform wrappers and a bundled runtime. The launcher is separate from the private server panel and game server.

## Intended experience

- Install, update, repair, and play an isolated Aeromon instance on Windows, macOS, and Linux.
- Read signed, version-pinned pack releases from Aeromon's public release feed.
- Download exact mods from the published manifest: CurseForge primarily, Modrinth for exact fallback matches, and Aeromon hosting for permitted custom files.
- Verify file hashes before installation and preserve player-owned saves and preferences during updates.
- Use a shared Java 21 application and interface packaged with platform-specific native wrappers and a bundled runtime.

## Status

The first implementation includes the branded desktop interface, pinned Ed25519 release verification, verified pack installation, repair, interrupted-install recovery, Minecraft asset/library installation, NeoForge installation, and Microsoft OAuth with PKCE.

Stable pack 1.0.5 has been verified and installed from the live feed. The Windows application image has been built and opened with a bundled Java 21 runtime. Minecraft 1.21.1 and NeoForge 21.1.248 installation succeeded in an isolated test instance.

**Minecraft API approval is pending.** Microsoft OAuth, Xbox Live and XSTS succeeded in the live sign-in test, but Minecraft Services returned HTTP 403 for Aeromon's new app ID. The Mojang AppID Review request was submitted on September 30, 2026. Licensed sign-in, game launch and server join cannot be declared working until approval and a successful live test. macOS/Linux packages are built by CI; gameplay on those systems remains unverified. Native package signing, secure refresh-token persistence and launcher self-update remain release work.

## Build and run

Requires a Java 21 JDK for development. Packaged users receive a runtime.

```powershell
./build.ps1
./build.ps1 -Package
java -jar build/dist/aeromon-launcher.jar
```

```sh
bash build.sh --package
java -jar build/dist/aeromon-launcher.jar
```

Keep `gson.jar` alongside the shared launcher JAR. Platform application images contain everything. On Windows use `-OutputDir build/another-package` when producing a second package without overwriting an earlier build.

Instance data lives in `%APPDATA%/Aeromon` (Windows), `~/Library/Application Support/Aeromon` (macOS), or `$XDG_DATA_HOME/Aeromon` (Linux, default `~/.local/share/Aeromon`). Use `--home PATH` for isolated tests. `--check` verifies the stable release; `--install --prepare` installs the pack and game runtime; `--beta` selects beta. Development sign-in can be checked with `--login-test`. Access tokens stay in memory and are never included in logs or pack manifests.

## Repository boundaries

No API keys, account credentials, signing private keys, Minecraft game files, or third-party mod jars belong in this repository. CurseForge access, if approved, will follow its API and each project's distribution settings.

See [architecture](docs/ARCHITECTURE.md) for the initial design.

The interface will follow the [visual direction](docs/VISUAL-DIRECTION.md): a retro, atmospheric game portal with original Aeromon artwork.
