# Launcher delivery

Shared Java 21 Swing desktop application, packaged by jpackage with a bundled runtime on Windows, macOS and Linux. CurseForge is the primary pack source; the published signed manifest chooses exact download URLs. The CurseForge API key stays on the server.

1. Promote Brian's existing 1.0.5 pack to stable.
2. Implement pinned Ed25519 verification, verified downloads, transactional install and repair.
3. Build the retro Aeromon desktop interface.
4. Implement Microsoft login using Aeromon's registered public application, then official Minecraft and NeoForge installation and launch.
5. Build platform wrappers and verify clean install, upgrade preservation, invalid hash rejection and launch.

Account registration is being prepared in Chrome. Microsoft sign-in and any agreements require Brian's participation. Platform packaging can be built locally for Windows; macOS/Linux builds require their own OS runners. Do not label platform builds tested until they actually run there.
