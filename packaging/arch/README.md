# Arch Linux

Download the x86_64 `.pkg.tar.zst` from https://aeromon.cc/launcher.html, then install it:

```sh
sudo pacman -U ./aeromon-launcher-bin-1.0.4-1-x86_64.pkg.tar.zst
```

Launch **Aeromon Launcher** from your desktop menu, or run `aeromon`.
Java is bundled. The package installs the application in `/opt/aeromon`.
Minecraft, the modpack, and signed application updates live in `$XDG_DATA_HOME/Aeromon`
(normally `~/.local/share/Aeromon`).
Installing the pacman package needs administrator access; subsequent launcher updates do not.

To build the package yourself, download this directory and run `makepkg -si`.
The PKGBUILD downloads the published Linux bundle and verifies its SHA-256.
This package is distributed directly by Aeromon; it has not been submitted to the AUR.
