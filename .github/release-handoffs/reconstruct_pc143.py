from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile

base, handoff, output = map(Path, sys.argv[1:4])
def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

assert digest(handoff) == "9a8a01bd1d37a6512ab775af27f4ecd323f114c45f0dd11113bae8f8640cbf34"
with tempfile.TemporaryDirectory() as td, zipfile.ZipFile(handoff) as bundle:
    root = Path(td)
    manifest = json.loads(bundle.read("manifest.json"))
    assert manifest["base_sha256"] == "fd9b3724fad56cb5d7a5d6886d5cca01380bd8415256a739153ce7da6302566a"
    assert digest(base) == manifest["base_sha256"]
    expected = manifest["files"]
    assert len(expected) == 323
    target = root / "candidate"
    target.mkdir()
    def safe(path):
        p = Path(path)
        assert not p.is_absolute() and ".." not in p.parts
        return target / p
    with zipfile.ZipFile(base) as original:
        for item in original.infolist():
            if item.is_dir():
                continue
            assert item.filename in expected
            dst = safe(item.filename)
            dst.parent.mkdir(parents=True, exist_ok=True)
            with original.open(item) as inp, dst.open("wb") as out:
                shutil.copyfileobj(inp, out)
    app = target / "FileHubAIO.exe"
    assert digest(app) == manifest["app_base_sha256"]
    patch = root / "app.zst"
    patch.write_bytes(bundle.read("app.zst"))
    assert digest(patch) == manifest["app_patch_sha256"]
    rebuilt = root / "FileHubAIO.exe"
    subprocess.run(["zstd", "-q", "-d", "-f", "--long=27", f"--patch-from={app}",
                    str(patch), "-o", str(rebuilt)], check=True)
    shutil.move(str(rebuilt), str(app))
    for name in manifest["changed"]:
        if name == "FileHubAIO.exe":
            continue
        dst = safe(name)
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(bundle.read("replacements/" + name))
    actual = {p.relative_to(target).as_posix(): digest(p)
              for p in target.rglob("*") if p.is_file()}
    assert actual == expected, "Reconstructed files differ from tested publisher candidate"
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name in sorted(expected):
            info = zipfile.ZipInfo(name, (2026, 11, 24, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 0
            info.external_attr = 0x20
            with z.open(info, "w") as dst, safe(name).open("rb") as inp:
                shutil.copyfileobj(inp, dst)
    with zipfile.ZipFile(output) as z:
        assert z.testzip() is None
        assert set(z.namelist()) == set(expected)
        for name, sha in expected.items():
            with z.open(name) as f:
                assert hashlib.file_digest(f, "sha256").hexdigest() == sha, name
    print("ALL_323_PACKAGED_FILE_DIGESTS_MATCH_VERIFIED_CANDIDATE")
    print("PUBLISHED_ZIP_SHA256", digest(output), "BYTES", output.stat().st_size)
