"""Mirror and verify the signed launcher update pointer on a legacy GitHub release.

This replaces the inline PowerShell release check. GitHub authentication is read
by the GitHub CLI, while downloads and all release validation stay in Python.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

REPOSITORY = "TheStonedGamer/aeromon-launcher"
PUBLIC_KEY = bytes.fromhex("1015bf1ab708746279cdf9b5c6d0ad700365db1c8ff8da62d70276c7656bea4d")
ALLOWED_HOSTS = {"aeromon.cc", "github.com", "objects.githubusercontent.com"}


def download(url: str) -> bytes:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS:
        raise ValueError(f"Unexpected signed release URL: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "AeromonReleaseVerifier/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 200:
            raise RuntimeError(f"Download failed (HTTP {response.status}): {parsed.hostname}")
        return response.read()


def run_gh(*arguments: str) -> None:
    try:
        subprocess.run(["gh", *arguments], check=True)
    except FileNotFoundError as error:
        raise RuntimeError("GitHub CLI (gh) is required and must be on PATH") from error
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f"GitHub CLI command failed with exit code {error.returncode}") from error


def verify_channel(raw_pointer: bytes, expected_version: str | None) -> tuple[dict, dict]:
    pointer = json.loads(raw_pointer)
    version = pointer.get("version", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Channel has an invalid launcher version")
    if expected_version and version != expected_version:
        raise ValueError(f"Expected launcher {expected_version}, received {version}")

    raw_manifest = download(pointer["manifestUrl"])
    digest = hashlib.sha256(raw_manifest).hexdigest()
    if digest != pointer.get("sha256"):
        raise ValueError("Signed launcher manifest SHA-256 does not match the channel")
    Ed25519PublicKey.from_public_bytes(PUBLIC_KEY).verify(bytes.fromhex(pointer["signature"]), raw_manifest)
    manifest = json.loads(raw_manifest)
    if manifest.get("schema") != 1 or manifest.get("version") != version:
        raise ValueError("Signed launcher manifest version/schema mismatch")
    return pointer, manifest


def verify_payloads(manifest: dict, output: pathlib.Path) -> pathlib.Path:
    files = {item.get("path"): item for item in manifest.get("files", [])}
    if set(files) != {"aeromon-launcher.jar", "gson.jar"}:
        raise ValueError("Signed launcher manifest has unexpected files")
    output.mkdir(parents=True, exist_ok=True)
    jar_path = output / "public-aeromon-launcher.jar"
    for name, item in files.items():
        data = download(item["url"])
        if len(data) != item["size"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError(f"Signed launcher payload failed size/hash verification: {name}")
        target = jar_path if name == "aeromon-launcher.jar" else output / name
        target.write_bytes(data)

    with zipfile.ZipFile(jar_path) as archive:
        class_file = archive.read("cc/aeromon/launcher/Minecraft.class")
        if b"127.0.0.1" not in class_file:
            raise ValueError("Launcher Minecraft class is missing its loopback OAuth callback")
        manifest_text = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
        if f"Implementation-Version: {manifest['version']}" not in manifest_text:
            raise ValueError("Launcher JAR embedded version does not match its signed manifest")
    return jar_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="Existing GitHub release tag to update, e.g. v1.0.12")
    parser.add_argument("--channel", required=True, type=pathlib.Path, help="Locally signed launcher-channel.json")
    parser.add_argument("--expected-version", help="Require this signed launcher version, e.g. 1.0.23")
    parser.add_argument("--repository", default=REPOSITORY)
    parser.add_argument("--output", type=pathlib.Path, help="Directory for verified public JARs")
    args = parser.parse_args()

    if not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag):
        parser.error("--tag must be a version tag such as v1.0.12")
    source = args.channel.resolve(strict=True)
    source_bytes = source.read_bytes()
    pointer, manifest = verify_channel(source_bytes, args.expected_version)

    # Publish the exact signed pointer and read it back through GitHub's release API.
    run_gh("release", "upload", args.tag, str(source), "--repo", args.repository, "--clobber")
    with tempfile.TemporaryDirectory(prefix="aeromon-release-mirror-") as temporary:
        mirror_dir = pathlib.Path(temporary)
        run_gh("release", "download", args.tag, "--repo", args.repository,
               "--pattern", "launcher-channel.json", "--dir", str(mirror_dir), "--clobber")
        mirrored = (mirror_dir / "launcher-channel.json").read_bytes()
        if hashlib.sha256(mirrored).digest() != hashlib.sha256(source_bytes).digest():
            raise ValueError("GitHub release mirror differs from the locally signed channel")
        _, mirrored_manifest = verify_channel(mirrored, args.expected_version)

    output = args.output or source.parent
    jar_path = verify_payloads(mirrored_manifest, output)
    print(f"Verified GitHub mirror {args.repository}@{args.tag}: launcher {pointer['version']}; signed manifest, JARs, and loopback callback OK")
    print(f"Verified launcher JAR: {jar_path}")


if __name__ == "__main__":
    main()
