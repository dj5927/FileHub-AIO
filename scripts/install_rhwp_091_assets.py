"""Import a reviewed v0.9.1 offline viewer build and matching SDK.

The input is the official rhwp tag 981ce66b4183b13c1a3b5907e04a8969c8c42034
built with its checked-in Chrome Vite config and locked npm dependencies,
using the SHA-512-verified @rhwp/core 0.9.1 WASM package.
Only rhwp assets are replaced; Excel, Word, PowerPoint and host code are not.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("upstream", type=Path)
parser.add_argument("sdk", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
target = root / "app/src/main/assets/office_hwp"
chunks = root / "app/office-assets"
upstream = args.upstream.resolve()
dist = upstream / "rhwp-chrome/dist"
assert subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"],
                               text=True).strip() == "981ce66b4183b13c1a3b5907e04a8969c8c42034"
assert json.loads((args.sdk / "package.json").read_text(encoding="utf-8"))["version"] == "0.9.1"
wasm = dist / "assets/rhwp_bg-CjDYAjgF.wasm"
assert hashlib.sha256(wasm.read_bytes()).hexdigest() == "e2e56dfd387ffee5b0995d23e4a0086001407f12a045820eb61bd6a506c9abc1"
assert wasm.read_bytes() == (upstream / "pkg/rhwp_bg.wasm").read_bytes()
assert (dist / "assets/viewer-Dwh9aKgC.js").is_file()
assert (dist / "assets/viewer-C3LMT7O_.css").is_file()
before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in target.glob("*")
          if p.is_file() and p.name.startswith(("office-", "excel-", "pptx-", "xlsx.", "jszip.", "docx-preview."))}
for path in (target / "assets").iterdir():
    if path.is_file():
        path.unlink()
shutil.copytree(dist / "assets", target / "assets", dirs_exist_ok=True)
shutil.copyfile(dist / "index.html", target / "viewer.html")
public = upstream / "rhwp-studio/public"
for name in ("theme-init.js", "locale-init.js", "print.html", "favicon.ico",
             "images/icon_small_ko.svg", "images/icon_small_ko_dark.svg", "icons/icon-256.png"):
    dest = target / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(public / name, dest)
for name in ("index.js", "transport.js", "document-agent-contract.js"):
    shutil.copyfile(args.sdk / name, target / "sdk" / name)
shutil.copyfile(upstream / "THIRD_PARTY_LICENSES.md", target / "THIRD_PARTY_LICENSES.md")
notices = upstream / "pkg/notices"
shutil.copytree(notices, target / "rhwp-notices", dirs_exist_ok=True)
shutil.copyfile(upstream / "pkg/LICENSE", target / "rhwp-notices/LICENSE")
shutil.copyfile(args.sdk / "LICENSE", target / "rhwp-notices/SDK-LICENSE")
for p in chunks.glob("rhwp_bg-*.wasm.gz.part*"):
    p.unlink()
compressed = gzip.compress(wasm.read_bytes(), compresslevel=9, mtime=0)
count = 0
for i in range(0, len(compressed), 1900000):
    (chunks / f"{wasm.name}.gz.part{count:02}").write_bytes(compressed[i:i+1900000])
    count += 1
assert gzip.decompress(compressed) == wasm.read_bytes()
after = {n: hashlib.sha256((target / n).read_bytes()).hexdigest() for n in before}
assert before == after, "Non-HWP Office assets unexpectedly changed"
print("RHWP_ASSET_IMPORT_PASS", wasm.name, "chunks", count, "bytes", len(compressed))
print("UNCHANGED_CUSTOM_OFFICE_ASSETS", len(before))
