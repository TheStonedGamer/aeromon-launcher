# Launcher architecture

## Shared application

Java 21 provides the shared interface and pack update logic across Windows, macOS, and Linux. The application will consume only the public, signed Aeromon release feed. It will pin exact files and verify size and hash before replacing any managed file. Updates stage downloads first and retain a recoverable prior state. Player-owned files remain outside the managed file map.

## Platform wrappers

Each platform gets a small native wrapper and installer. The wrapper launches the bundled Java runtime and shared application, integrates with operating-system paths and credential storage, and handles launcher updates and signing. Windows, macOS, and Linux releases will be built and tested separately.

## Sources

- Modrinth: exact project/version and official file URL.
- CurseForge: exact project/file through approved third-party API access, subject to author distribution settings.
- Aeromon: original files and other files with redistribution permission.

The launcher will not contain a CurseForge API key. The private panel manages pack drafts; the launcher receives published releases only. Source-specific failures must be shown clearly instead of silently switching to a mirror.

## Implementation order

1. Finish the private panel's import, source selection, validation, and signed release flow.
2. Build the Java updater and signed-manifest verifier against a test feed.
3. Validate Minecraft account sign-in, Java runtime installation, NeoForge launch, and server join.
4. Package the shared interface with native wrappers and installers for each supported OS.
5. Test clean installs, upgrades, interrupted updates, repair, and rollback before public release.
