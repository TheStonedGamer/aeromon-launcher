# Aeromon preview 1.0.0

Prepared September 30, 2026 for limited testing with pack 1.0.5, Minecraft 1.21.1 and NeoForge 21.1.248.

The local Windows preview is in `build/prerelease`: an MSI installer, a portable ZIP with Java included, community pack archives, and SHA256SUMS.txt. These packages have no Windows publisher signature. Compare their SHA-256 hashes before use. The public launcher remains labeled unavailable until a signed launcher release is prepared.

Use the official-launcher action with a licensed Minecraft account, or offline launch for local testing. Aeromon's own Microsoft Services app approval is pending. Offline mode disables multiplayer. The official profile and isolated offline NeoForge boot were verified; a licensed server join still requires a tester's account.

Updater checks passed for signatures, safe paths, staged installation, saves, recovery, custom mod import/toggle/remove, update preservation, conflicts, and the running-game guard. Windows app-image and MSI builds succeeded. Other native installers require the current GitHub Actions run to succeed; prior runs do not verify this revision.

The public download page and MultiMC/Prism archives are deployed at https://aeromon.cc/launcher.html. Each archive pins the published pack's exact files, supplies the Minecraft/NeoForge versions, and adds mc.aeromon.cc for newly imported instances. Import into a new instance to preserve existing launcher preferences and servers.

On the web host, aeromon-pack-downloads.path starts the isolated publishing service when Aeromon Control changes the stable client release pointer. The service verifies the signed release and file hashes before updating the Prism, MultiMC, and CurseForge client packs. Website backups are in /srv/pixsite/backups/prerelease-20260930. No Minecraft server restart is needed.

Private signing keys and account tokens are excluded from Git. OS signing/notarization and native gameplay checks on Linux/macOS are still pending. Tag builds create draft GitHub pre-releases for review.
