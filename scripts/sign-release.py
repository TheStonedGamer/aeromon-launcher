"""Create update assets on the signing host. Never copy its private key into Git."""
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
args = parser.parse_args()
import re
if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
    parser.error("version must contain three numeric components")
base = f"https://github.com/TheStonedGamer/aeromon-launcher/releases/download/v{args.version}"
manifest = {"schema": 1, "version": args.version, "files": []}
for name in ("aeromon-launcher.jar", "gson.jar"):
    data = (args.dist / name).read_bytes()
    manifest["files"].append({"path": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "url": f"{base}/{name}"})
raw = json.dumps(manifest, separators=(",", ":"), sort_keys=True).encode()
key = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()
if public != "1015bf1ab708746279cdf9b5c6d0ad700365db1c8ff8da62d70276c7656bea4d":
    raise ValueError("Signing key does not match the launcher's pinned public key")
pointer = {"schema": 1, "version": args.version, "sha256": hashlib.sha256(raw).hexdigest(), "signature": key.sign(raw).hex(), "manifestUrl": f"{base}/launcher-manifest.json"}
args.output.mkdir(parents=True, exist_ok=True)
(args.output / "launcher-manifest.json").write_bytes(raw)
(args.output / "launcher-channel.json").write_text(json.dumps(pointer, indent=2), encoding="utf-8")
print(f"Signed launcher {args.version}; private key remains on this host")
