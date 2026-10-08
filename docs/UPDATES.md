# Launcher updates and Java

The native wrapper contains a Java 21 bootstrap runtime. Players can open the application without a system Java installation. Minecraft gets a separate private Temurin Java 21 JRE from Adoptium, selected for the host OS and CPU, verified against the official package SHA256, and tested before use.

At startup the launcher checks `https://aeromon.cc/updates/launcher-channel.json`. Updates require an Ed25519 signature from the existing Aeromon release key, a matching manifest SHA256, and exact hashes for both application JARs. The JAR's embedded version must match the signed version. The legacy GitHub channel points at the same signed update so older installed launchers can upgrade once and acquire delta support.

Updates reuse unchanged local files. For changed JARs, signed manifests may advertise `aeromon-copy-add-v1` patches keyed by the exact base SHA256. The patch reuses identical compressed ZIP payloads and supplies changed bytes in a gzip copy/add stream. The client verifies the base match, patch size/hash, bounded reconstruction, final size/hash and embedded application version. A missing, corrupt or incompatible patch falls back to the signed full download. No installed native executable is overwritten. Only launcher JARs use these binary patches; pack updates already download only changed manifest files.

Pack updates reserve player-owned paths: saves, screenshots, logs, JourneyMap data and configuration, Xaero/VoxelMap data, resource packs, shader packs, schematics, player options/server lists, and selected client-mod preferences. These paths are excluded from installation, stale-file cleanup, rollback recovery, and official-launcher synchronization, including cleanup based on older ownership records. `options.txt` and `servers.dat` may be seeded when absent; existing files are preserved.

Downloads go to user storage under `launcher/updates/VERSION`. A separate process waits for the launcher to exit, atomically selects the new release, and restarts it. The original native wrapper verifies and opens the selected updated JAR on subsequent starts. Previous releases remain on disk. The wrapper itself and its bundled bootstrap runtime are not replaced by this JAR update mechanism.

## Publishing

Launcher 1.0.13 removes the retired beta option. The pack selector now exposes stable and test; test uses the separate test pack and `test.aeromon.cc` server.

1. Pass the numeric version to the build (`build.ps1 -Version VERSION`, or `AEROMON_VERSION` for shell/CI). Build and verify packages on all four CI targets.
2. Transfer the two application JARs, `scripts/sign-release.py` and `scripts/delta.py` to the signing host. Run the signer with `--version VERSION --key KEY --dist DIST --output OUTPUT --base-url https://aeromon.cc/updates/VERSION`, and repeat `--previous OLD_DIST` for supported installed versions. The key stays on the server. Patches are advertised only when at least 10% smaller than a full file.
3. Publish both JARs, the signed manifest, and generated patch files in `/updates/VERSION/`. Verify reconstruction against each supported base with `PublishedUpdateTest` before activating the channel. Preserve these immutable version directories.
4. Atomically replace `/updates/launcher-channel.json` with the tested signed channel file. Mirror that pointer into the legacy latest GitHub release's `launcher-channel.json` until old clients have migrated. Older clients use a full download for their first upgrade; subsequent updates can use patches.

The first candidate remains development work while Minecraft Services approval and licensed server-join verification are pending. Offline launch allows local pack testing and disables multiplayer and chat.
