"""Create Aeromon signed update assets. Keep the private key in a protected CI environment or signing host, never in Git."""
import argparse
import hashlib
import json
from pathlib import Path
from cryptography.hazmat.primitives import serialization

parser = argparse.ArgumentParser()
parser.add_argument("--version", required=True)
parser.add_argument("--key", type=Path, required=True)
parser.add_argument("--dist", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--previous", type=Path, action="append", default=[])
parser.add_argument("--base-url", help="Public URL for this version's update files")
args = parser.parse_args()
import re
if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
    parser.error("version must contain three numeric components")
base = args.base_url or f"https://github.com/TheStonedGamer/aeromon-launcher/releases/download/v{args.version}"
args.output.mkdir(parents=True, exist_ok=True)
manifest = {"schema": 1, "version": args.version, "files": []}
for name in ("aeromon-launcher.jar", "gson.jar"):
    data = (args.dist / name).read_bytes()
    checksum=hashlib.sha256(data).hexdigest()
    entry={"path": name, "size": len(data), "sha256": checksum, "url": f"{base}/{name}?sha256={checksum}", "patches": []}
    from delta import make_patch
    for previous in args.previous:
        old=previous/name
        if not old.is_file():continue
        old_hash=hashlib.sha256(old.read_bytes()).hexdigest()
        if old_hash==entry['sha256']:continue
        patch_name=f'{name}.{old_hash[:16]}.delta.gz';patch=args.output/patch_name
        make_patch(old,args.dist/name,patch)
        if patch.stat().st_size < len(data)*0.9:
            patch_hash=hashlib.sha256(patch.read_bytes()).hexdigest()
            entry['patches'].append({'format':'aeromon-copy-add-v1','baseSha256':old_hash,'size':patch.stat().st_size,'sha256':patch_hash,'url':f'{base}/{patch_name}?sha256={patch_hash}'})
        else:patch.unlink()
    manifest["files"].append(entry)
raw = json.dumps(manifest, separators=(",", ":"), sort_keys=True).encode()
key = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()
if public != "1015bf1ab708746279cdf9b5c6d0ad700365db1c8ff8da62d70276c7656bea4d":
    raise ValueError("Signing key does not match the launcher's pinned public key")
pointer = {"schema": 1, "version": args.version, "sha256": hashlib.sha256(raw).hexdigest(), "signature": key.sign(raw).hex(), "manifestUrl": f"{base}/launcher-manifest.json?sha256={hashlib.sha256(raw).hexdigest()}"}
args.output.mkdir(parents=True, exist_ok=True)
(args.output / "launcher-manifest.json").write_bytes(raw)
(args.output / "launcher-channel.json").write_text(json.dumps(pointer, indent=2), encoding="utf-8")
print(f"Signed launcher {args.version}; private key remains on this host")
