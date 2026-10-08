# Player cloud storage

Settings → Minecraft account cloud storage provides explicit Upload and Restore
actions for Stable and Test. Microsoft/Minecraft sign-in is required. Storage is
scoped to the Minecraft UUID verified by Minecraft Services; a caller cannot
choose another UUID. Stable and Test have separate collections.

Included: `options.txt`, `optionsof.txt`, `optionsshaders.txt`, `journeymap/**`,
and `config/journeymap/**`. This transfers Minecraft options/keybinds and
JourneyMap maps, waypoints and preferences from the Aeromon managed instance.
Launcher RAM settings, credentials, mods, local worlds, and shared resource
packs are excluded. Official-launcher game directories are not transferred.

Close Minecraft before either action. Upload remembers its cloud revision and
uses compare-and-swap: a different computer's newer upload causes a conflict
instead of overwriting it. Restore overlays matching files, keeps extra local
map files, and writes a recovery ZIP under the branch's
`launcher/player-sync/<uuid>` directory (use Open recovery copies for the
actual path). Local replacements roll back on a failed restore. There is no
background or automatic synchronization.

Cloud limits: 256 MiB compressed, 1 GiB expanded, 100,000 files per snapshot;
the newest five snapshots are retained per UUID and branch. Minecraft access
tokens are used transiently to verify the profile and never stored in snapshots
or on the service's disk. Authorization headers are not logged. Transfers use
HTTPS; server files use permissions restricted to the storage service user.

Backend: `webui/aeromon_control/player_sync.py`, running independently via
`webui/deploy/aeromon-player-sync.service`. The panel reverse proxy includes
`webui/deploy/player-sync-nginx.conf`. Production firewall permits port 8768
only from proxy 10.0.0.163. Run exactly one backend worker: compare-and-swap
locks are process-local. Do not mount the storage folder as public static files.

Build and route availability were checked. Real authenticated cross-computer
Upload/Restore requires verification with a signed-in licensed account.
