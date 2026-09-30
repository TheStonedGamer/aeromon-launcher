# Launcher updates and Java

The native wrapper contains a Java 21 bootstrap runtime. Players can open the application without a system Java installation. Minecraft gets a separate private Temurin Java 21 JRE from Adoptium, selected for the host OS and CPU, verified against the official package SHA256, and tested before use.

At startup the launcher checks the latest public GitHub release for `launcher-channel.json`. A 404 means no launcher release has been published yet. Updates require an Ed25519 signature from the existing Aeromon release key, a matching manifest SHA256, and exact hashes for both application JARs. The JAR's embedded version must match the signed version.

Downloads go to user storage under `launcher/updates/VERSION`. A separate process waits for the launcher to exit, atomically selects the new release, and restarts it. The original native wrapper verifies and opens the selected updated JAR on subsequent starts. Previous releases remain on disk. The wrapper itself and its bundled bootstrap runtime are not replaced by this JAR update mechanism.

## Publishing

1. Set the application version in `LauncherUpdate.java` fallback and both build manifests/package commands. Build and verify packages on all four CI targets.
2. Transfer the two application JARs and `scripts/sign-release.py` to the signing host. Run that script with the existing release key path, version, dist, and output. The key stays on the server.
3. Upload both JARs, `launcher-manifest.json`, and `launcher-channel.json` to the matching GitHub `vVERSION` release, along with native packages.
4. Publish only after the candidate has been tested. GitHub's latest stable release becomes the automatic update source.

The first candidate remains development work while Minecraft Services approval and licensed server-join verification are pending. Offline launch allows local pack testing and disables multiplayer and chat.
