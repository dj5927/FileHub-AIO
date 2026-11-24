"""Reconstruct a full verified ZIP from the previous official PC release.

The handoff contains only changed public package files, not configuration
or user files. Per-file SHA-256 verifies identical candidate contents.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
import zlib

BASE_SHA = "155f33ce3d5fa55fcf68a872b9d0bb275fa9aa3c79ea4007b4d698c07484c5fc"

ALLOWED_CHANGES = {"FileHubAIO.exe", "FileHubAIO.pdb", "README_COMPONENTS_1378.md",
    "README_PC_V146_CACHE_CLEANUP_KR.md",
    "tools/NASMountLockBridge.deps.json", "tools/NASMountLockBridge.dll",
    "tools/NASMountLockBridge.exe", "tools/NASMountLockBridge.pdb"}


def digest(data):
    return hashlib.sha256(data).hexdigest()

def file_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def files(root):
    return {p.relative_to(root).as_posix(): p for p in root.rglob("*") if p.is_file()}

def safe(name):
    p = Path(name)
    assert not p.is_absolute() and ".." not in p.parts and "\\" not in name
    assert not ":" in name and name and not name.endswith("/")

def prepare(old, new, output):
    a, b = files(old), files(new)
    for name in b: safe(name)
    ah = {n: file_digest(p) for n,p in a.items()}
    bh = {n: file_digest(p) for n,p in b.items()}
    changed = [n for n in sorted(b) if ah.get(n) != bh[n]]
    assert set(changed) == ALLOWED_CHANGES, changed
    assert set(a).issubset(b), "Unexpected package file removal"
    assert all(not any(x in n.lower() for x in
        ("oauth-desktop.json", "credentials.json", ".rclone.conf", "keystore",
         "profiles.json", "settings.json", "tokens.json")) for n in changed)
    entries = {n: {"sha256": bh[n], "kind": "base"} for n in sorted(b)}
    def change(pair):
        index, name = pair
        data = b[name].read_bytes()
        kind = "full"
        payload = data
        if name in a and len(data) >= 65536:
            with tempfile.TemporaryDirectory() as td:
                patch = Path(td) / "delta.zst"
                subprocess.run(["zstd", "-q", "-f", "-3", "--long=28",
                    "--patch-from="+str(a[name]), str(b[name]), "-o", str(patch)],
                    check=True)
                delta = patch.read_bytes()
            if len(delta) < len(zlib.compress(data, 3)):
                kind, payload = "patch", delta
        key = f"payload/{index:04d}"
        entry = {"sha256":bh[name], "kind":kind, "payload":key,
                 "payload_sha256":digest(payload)}
        if kind == "patch": entry["base_sha256"] = ah[name]
        return name, entry, payload
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=5) as out:
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for name, entry, payload in pool.map(change, enumerate(changed)):
                out.writestr(entry["payload"], payload)
                entries[name] = entry
        out.writestr("manifest.json", json.dumps({"base_sha256":BASE_SHA,
            "files":entries}, sort_keys=True, indent=2))
    print("HANDOFF_FILES", len(b), "CHANGED", len(changed),
          "BYTES", output.stat().st_size, "SHA256", file_digest(output), flush=True)

def reconstruct(base, handoff, output):
    assert file_digest(base) == BASE_SHA, "Published base archive mismatch"
    with zipfile.ZipFile(base) as a, zipfile.ZipFile(handoff) as b:
        manifest = json.loads(b.read("manifest.json"))
        assert manifest["base_sha256"] == BASE_SHA
        entries = manifest["files"]
        assert {n for n,e in entries.items() if e["kind"] != "base"} == ALLOWED_CHANGES
        # Earlier release inadvertently included internal UI smoke-test images.
        # Remove only that exact generated QA subtree, not application files.
        assert {n for n in a.namelist() if not n.endswith("/") and
                not n.startswith("ui-qa/")} <= set(entries)
        assert not any(n.startswith("ui-qa/") for n in entries)
        assert "tools/winfsp-2.2.26323.msi" in entries
        assert "tools/winfsp-2.1.25156.msi" not in entries
        assert "tools/winfsp-legacy/winfsp-msil.dll" in entries
        with tempfile.TemporaryDirectory() as td, zipfile.ZipFile(
            output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as out:
            scratch = Path(td)
            for name, entry in sorted(entries.items()):
                safe(name)
                kind = entry["kind"]
                if kind == "base":
                    data = a.read(name)
                else:
                    payload = b.read(entry["payload"])
                    assert digest(payload) == entry["payload_sha256"], name
                    if kind == "full":
                        data = payload
                    elif kind == "patch":
                        original = a.read(name)
                        assert digest(original) == entry["base_sha256"], name
                        prev, patch, result = (scratch / n for n in
                                               ("base", "patch", "result"))
                        prev.write_bytes(original)
                        patch.write_bytes(payload)
                        subprocess.run(["zstd", "-q", "-d", "-f", "--long=28",
                            "--patch-from="+str(prev), str(patch),
                            "-o", str(result)], check=True)
                        data = result.read_bytes()
                    else:
                        raise ValueError(kind)
                assert digest(data) == entry["sha256"], name
                info = zipfile.ZipInfo(name, (2026,11,25,0,0,0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 0
                info.external_attr = 0x20
                out.writestr(info, data)
    verify(handoff, output)

def verify(handoff, output):
    with zipfile.ZipFile(handoff) as b, zipfile.ZipFile(output) as a:
        entries = json.loads(b.read("manifest.json"))["files"]
        assert len(a.namelist()) == len(entries)
        assert set(a.namelist()) == set(entries)
        assert a.testzip() is None
        for n, e in entries.items():
            with a.open(n) as f:
                assert hashlib.file_digest(f, "sha256").hexdigest() == e["sha256"], n
        print("PC146_ALL_FILE_DIGESTS_PASS",len(entries),flush=True)
    print("ZIP_SHA256",file_digest(output),"BYTES",output.stat().st_size,flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare","reconstruct","verify"])
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    {"prepare":prepare,"reconstruct":reconstruct,"verify":verify}[args.mode](*args.paths)
