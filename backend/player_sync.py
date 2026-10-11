"""Standalone player storage; never shares the Minecraft process owner."""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import secrets
import time
import zipfile
from pathlib import Path, PurePosixPath

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

ROOT = Path(os.environ.get("AEROMON_PLAYER_SYNC_ROOT", "/var/lib/aeromon-player-sync"))
MAX_BYTES = 1024 * 1024 * 1024
MAX_EXPANDED = 2 * 1024 * 1024 * 1024
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
locks: dict[str, asyncio.Lock] = {}
tile_lock = asyncio.Lock()
identities: dict[str, tuple[float, str]] = {}
auth_gate = asyncio.Semaphore(8)


def allowed(name: str) -> bool:
    p = PurePosixPath(name)
    if not name or "\\" in name or ":" in name or p.is_absolute() or any(x in {"", ".", ".."} for x in name.split("/")):
        return False
    return name in {"options.txt", "optionsof.txt", "optionsshaders.txt"} or name.startswith(("journeymap/", "config/journeymap/"))


async def identity(request: Request) -> str:
    header = request.headers.get("authorization", "")
    if not header.startswith("Bearer ") or not 20 <= len(header) <= 8192:
        raise HTTPException(401, "Minecraft sign-in required")
    key = hashlib.sha256(header.encode()).hexdigest()
    cached = identities.get(key)
    if cached and cached[0] > time.monotonic():
        return cached[1]
    async with auth_gate:
        try:
            async with httpx.AsyncClient(timeout=15, follow_redirects=False) as client:
                response = await client.get("https://api.minecraftservices.com/minecraft/profile", headers={"Authorization": header})
            if response.status_code != 200:
                raise HTTPException(401, "Minecraft sign-in expired; sign in again")
            uuid = response.json().get("id", "")
            if not re.fullmatch(r"[0-9a-fA-F]{32}", uuid):
                raise HTTPException(401, "Invalid Minecraft identity")
        except (httpx.HTTPError, ValueError):
            raise HTTPException(503, "Minecraft identity service unavailable") from None
    if len(identities) >= 1024:
        identities.clear()
    identities[key] = (time.monotonic() + 60, uuid.lower())
    return uuid.lower()


def directory(uuid: str, branch: str) -> Path:
    if branch not in {"stable", "test"}:
        raise HTTPException(400, "Choose Stable or Test")
    return ROOT / uuid / branch


def shared_tile(name: str) -> bool:
    return allowed(name) and name.lower().startswith("journeymap/data/mp/aeromon/") and name.lower().endswith(".png")


def shared_tiles_root() -> Path:
    return ROOT / ".shared-map-tiles"


def canonical_manifest(tiles: dict[str, str]) -> bytes:
    return json.dumps(tiles, sort_keys=True, separators=(",", ":")).encode()


def snapshot_revision(archive: Path, manifest: bytes) -> str:
    digest = hashlib.sha256()
    with archive.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    digest.update(b"\0")
    digest.update(manifest)
    return digest.hexdigest()


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def metadata(folder: Path) -> dict:
    path = folder / "current.json"
    return json.loads(path.read_text()) if path.exists() else {"revision": "", "size": 0, "updated": None}


def validate_archive(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > 100000 or sum(x.file_size for x in entries) > MAX_EXPANDED:
                raise HTTPException(413, "JourneyMap snapshot is too large")
            seen: set[str] = set()
            for item in entries:
                if item.is_dir() or not allowed(item.filename) or item.filename.lower() in seen or (item.external_attr >> 16) & 0o170000 == 0o120000:
                    raise HTTPException(400, "Snapshot contains an unsupported path")
                seen.add(item.filename.lower())
            if archive.testzip() is not None:
                raise HTTPException(400, "Damaged snapshot")
    except (zipfile.BadZipFile, RuntimeError, OSError):
        raise HTTPException(400, "Invalid snapshot archive") from None


@app.get("/health")
async def health():
    return {"ok": True}


@app.get("/v1/{branch}")
async def status(branch: str, request: Request):
    folder = directory(await identity(request), branch)
    async with locks.setdefault(str(folder), asyncio.Lock()):
        return metadata(folder)


@app.post("/v1/{branch}/tiles/missing")
async def missing_tiles(branch: str, request: Request):
    directory(await identity(request), branch)
    try:
        payload = await request.json()
        hashes = payload.get("hashes", [])
        if not isinstance(hashes, list) or len(hashes) > 100000 or any(not isinstance(x, str) or not re.fullmatch(r"[0-9a-f]{64}", x) for x in hashes):
            raise ValueError
    except (ValueError, AttributeError):
        raise HTTPException(400, "Invalid tile hash list") from None
    root = shared_tiles_root()
    return {"missing": [value for value in set(hashes) if not (root / value).is_file()]}


@app.put("/v1/{branch}/deduplicated")
async def upload_deduplicated(branch: str, request: Request):
    uuid = await identity(request)
    folder = directory(uuid, branch)
    expected = request.headers.get("if-match")
    if expected is None or len(expected) > 64 or expected and not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise HTTPException(428 if expected is None else 400, "Cloud revision required or invalid")
    async with locks.setdefault(str(folder), asyncio.Lock()):
        current = metadata(folder)
        if current["revision"] != expected:
            raise HTTPException(409, "Cloud data changed on another computer; download it before uploading")
        folder.mkdir(parents=True, exist_ok=True)
        revisions = folder / "revisions"
        revisions.mkdir(exist_ok=True)
        ROOT.mkdir(parents=True, exist_ok=True)
        temporary = folder / ("upload-" + secrets.token_hex(16) + ".part")
        personal = folder / ("personal-" + secrets.token_hex(16) + ".part")
        try:
            size = 0
            with temporary.open("xb") as output:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise HTTPException(413, "Compressed snapshot exceeds 1 GiB")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            try:
                with zipfile.ZipFile(temporary) as source:
                    entries = source.infolist()
                    if len(entries) > 100001 or sum(x.file_size for x in entries) > MAX_EXPANDED:
                        raise HTTPException(413, "JourneyMap snapshot is too large")
                    tile_item = next((x for x in entries if x.filename == "META-INF/aeromon-tiles.json"), None)
                    if tile_item is None or tile_item.is_dir() or tile_item.file_size > 32 * 1024 * 1024:
                        raise HTTPException(400, "Snapshot tile manifest is missing")
                    try:
                        supplied_tiles = json.loads(source.read(tile_item))
                    except (ValueError, UnicodeDecodeError):
                        raise HTTPException(400, "Invalid tile manifest") from None
                    if not isinstance(supplied_tiles, dict) or len(supplied_tiles) > 100000:
                        raise HTTPException(400, "Invalid tile manifest")
                    tiles: dict[str, str] = {}
                    tile_names: set[str] = set()
                    for name, digest in supplied_tiles.items():
                        if not isinstance(name, str) or not shared_tile(name) or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                            raise HTTPException(400, "Invalid shared map tile manifest")
                        if name.lower() in tile_names:
                            raise HTTPException(400, "Duplicate shared map tile path")
                        tile_names.add(name.lower())
                        tiles[name] = digest
                    if len(tiles) > 100000:
                        raise HTTPException(413, "JourneyMap snapshot is too large")
                    seen: set[str] = set()
                    uploaded_hashes: set[str] = set()
                    async with tile_lock:
                        blob_root = shared_tiles_root()
                        blob_root.mkdir(parents=True, exist_ok=True)
                        for item in entries:
                            if item.filename == tile_item.filename:
                                continue
                            if item.is_dir() or item.filename.lower() in seen or (item.external_attr >> 16) & 0o170000 == 0o120000:
                                raise HTTPException(400, "Snapshot contains an unsupported path")
                            seen.add(item.filename.lower())
                            if shared_tile(item.filename):
                                expected_hash = tiles.get(item.filename)
                                if expected_hash is None:
                                    raise HTTPException(400, "Unlisted map tile in snapshot")
                                digest = hashlib.sha256()
                                destination = blob_root / (expected_hash + ".part-" + secrets.token_hex(6))
                                with source.open(item) as data, destination.open("xb") as out:
                                    while chunk := data.read(1024 * 1024):
                                        digest.update(chunk)
                                        out.write(chunk)
                                    out.flush()
                                    os.fsync(out.fileno())
                                if digest.hexdigest() != expected_hash:
                                    destination.unlink(missing_ok=True)
                                    raise HTTPException(400, "Map tile checksum mismatch")
                                final = blob_root / expected_hash
                                if not final.exists():
                                    destination.replace(final)
                                else:
                                    destination.unlink(missing_ok=True)
                                uploaded_hashes.add(expected_hash)
                            elif not allowed(item.filename):
                                raise HTTPException(400, "Snapshot contains an unsupported path")
                        for digest in set(tiles.values()):
                            blob = blob_root / digest
                            if not blob.is_file():
                                raise HTTPException(409, "A shared map tile is missing; retry the upload")
                            if digest in uploaded_hashes and await asyncio.to_thread(file_hash, blob) != digest:
                                raise HTTPException(500, "Stored map tile checksum mismatch")
                        logical_size = sum(item.file_size for item in entries if item.filename != tile_item.filename and not shared_tile(item.filename))
                        logical_size += sum((blob_root / digest).stat().st_size for digest in tiles.values())
                        personal_count = sum(1 for item in entries if item.filename != tile_item.filename and not shared_tile(item.filename))
                        if logical_size > MAX_EXPANDED or personal_count + len(tiles) > 100000:
                            raise HTTPException(413, "JourneyMap snapshot is too large")
                        with zipfile.ZipFile(personal, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
                            for item in entries:
                                if item.filename == tile_item.filename or shared_tile(item.filename):
                                    continue
                                info = zipfile.ZipInfo(item.filename, date_time=(1980, 1, 1, 0, 0, 0))
                                info.external_attr = item.external_attr
                                with source.open(item) as inp, output.open(info, "w") as out:
                                    while chunk := inp.read(1024 * 1024):
                                        out.write(chunk)
                    validate_archive(personal)
            except (zipfile.BadZipFile, RuntimeError, OSError):
                raise HTTPException(400, "Invalid snapshot archive") from None
            manifest_bytes = canonical_manifest(tiles)
            revision = snapshot_revision(personal, manifest_bytes)
            destination = revisions / (revision + ".zip")
            manifest_path = revisions / (revision + ".tiles.json")
            personal.replace(destination)
            manifest_path.write_bytes(manifest_bytes)
            result = {"revision": revision, "size": destination.stat().st_size, "updated": int(time.time()), "format": 2}
            pending = folder / "current.json.part"
            pending.write_text(json.dumps(result))
            pending.replace(folder / "current.json")
            snapshots = sorted(revisions.glob("*.zip"), key=lambda p: p.stat().st_mtime, reverse=True)
            for old in snapshots[5:]:
                old.unlink(missing_ok=True)
                old.with_suffix(".tiles.json").unlink(missing_ok=True)
            return result
        finally:
            temporary.unlink(missing_ok=True)
            personal.unlink(missing_ok=True)


@app.get("/v1/{branch}/snapshot/{revision}")
async def download(branch: str, revision: str, request: Request):
    folder = directory(await identity(request), branch)
    if not re.fullmatch(r"[0-9a-f]{64}", revision):
        raise HTTPException(400, "Invalid revision")
    path = folder / "revisions" / (revision + ".zip")
    format_version = "2"
    if not path.is_file():
        path = folder / (revision + ".zip")
        format_version = "1"
    if not path.is_file():
        raise HTTPException(404, "Snapshot is no longer available")
    return FileResponse(path, media_type="application/zip", headers={"Cache-Control": "no-store", "X-Aeromon-Snapshot-Format": format_version})


@app.get("/v1/{branch}/snapshot/{revision}/manifest")
async def download_manifest(branch: str, revision: str, request: Request):
    folder = directory(await identity(request), branch)
    if not re.fullmatch(r"[0-9a-f]{64}", revision):
        raise HTTPException(400, "Invalid revision")
    path = folder / "revisions" / (revision + ".tiles.json")
    if not path.is_file():
        raise HTTPException(404, "Snapshot manifest is unavailable")
    return FileResponse(path, media_type="application/json", headers={"Cache-Control": "no-store"})


@app.get("/v1/{branch}/snapshot/{revision}/tiles")
async def download_tiles(branch: str, revision: str, request: Request):
    folder = directory(await identity(request), branch)
    if not re.fullmatch(r"[0-9a-f]{64}", revision):
        raise HTTPException(400, "Invalid revision")
    manifest_path = folder / "revisions" / (revision + ".tiles.json")
    if not manifest_path.is_file():
        raise HTTPException(404, "Snapshot tile manifest is unavailable")
    try:
        tiles = json.loads(manifest_path.read_text())
    except (ValueError, OSError):
        raise HTTPException(500, "Snapshot tile manifest is damaged") from None
    root = shared_tiles_root()
    temporary = ROOT / ("tiles-" + secrets.token_hex(16) + ".zip")
    async with tile_lock:
        try:
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as archive:
                for digest in sorted(set(tiles.values())):
                    if not re.fullmatch(r"[0-9a-f]{64}", digest):
                        raise HTTPException(500, "Snapshot tile manifest is invalid")
                    blob = root / digest
                    if not blob.is_file():
                        raise HTTPException(503, "A shared map tile is temporarily unavailable")
                    archive.write(blob, digest)
        except Exception:
            temporary.unlink(missing_ok=True)
            raise
    return FileResponse(temporary, media_type="application/zip", headers={"Cache-Control": "no-store"}, background=BackgroundTask(temporary.unlink, missing_ok=True))


@app.put("/v1/{branch}")
async def upload(branch: str, request: Request):
    folder = directory(await identity(request), branch)
    # Compare-and-swap prevents one computer silently replacing another's upload.
    expected = request.headers.get("if-match")
    if expected is None:
        raise HTTPException(428, "Cloud revision required")
    if len(expected) > 64 or expected and not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise HTTPException(400, "Invalid cloud revision")
    async with locks.setdefault(str(folder), asyncio.Lock()):
        current = metadata(folder)
        if current["revision"] != expected:
            raise HTTPException(409, "Cloud data changed on another computer; download it before uploading")
        folder.mkdir(parents=True, exist_ok=True)
        temporary = folder / ("upload-" + secrets.token_hex(16) + ".part")
        try:
            size = 0
            digest = hashlib.sha256()
            with temporary.open("xb") as output:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise HTTPException(413, "Compressed snapshot exceeds 1 GiB")
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            await asyncio.to_thread(validate_archive, temporary)
            revision = digest.hexdigest()
            destination = folder / (revision + ".zip")
            temporary.replace(destination)
            result = {"revision": revision, "size": size, "updated": int(time.time())}
            pending = folder / "current.json.part"
            pending.write_text(json.dumps(result))
            pending.replace(folder / "current.json")
            # Five versions bound disk use and allow recovery from accidental uploads.
            snapshots = sorted(folder.glob("*.zip"), key=lambda p: p.stat().st_mtime, reverse=True)
            for old in snapshots[5:]:
                if old != destination:
                    old.unlink()
            return result
        finally:
            temporary.unlink(missing_ok=True)
