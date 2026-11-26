"""Verify and stage local, unpublished Android test candidates.

APK/AAB signer and package identity are checked with the Android SDK tools
by the caller. This script checks byte-level payload separation, ABIs,
native notice assets and the existing Play optimization policy. It never
uploads a release or opens a network connection.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
from verify_play_bundle_quality import check, verify_notices, direct_native, DIRECT_NATIVE_LIBS

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"armeabi-v7a", "arm64-v8a", "x86_64"}
BRAND = ("filehub_aio_icon.png", "filehub_aio_wordmark.png")


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def versions():
    text = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    result = {}
    for flavor in ("direct", "play"):
        m = re.search(r"(?m)^\s{8}" + flavor + r" \{([\s\S]*?)(?=^\s{8}\})", text)
        if not m:
            raise ValueError("Missing flavor " + flavor)
        result[flavor] = (
            re.search(r"versionName '([0-9.]+)'", m[1])[1],
            int(re.search(r"versionCode (\d+)", m[1])[1]),
        )
    return result


def inspect(path, edition, expected_abis, bundle=False):
    prefix = "base/" if bundle else ""
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        actual = {
            n[len(prefix):].split("/")[1]
            for n in names if n.startswith(prefix + "lib/") and n.endswith(".so")
        }
        if actual != expected_abis:
            raise ValueError(f"{path.name}: wrong ABI set {actual}")
        for abi in expected_abis:
            for lib in ("libflutter.so", "libapp.so"):
                if prefix + f"lib/{abi}/{lib}" not in names:
                    raise ValueError(f"{path.name}: missing {abi}/{lib}")
        if z.read(prefix + "assets/locale/ui.tsv") != (ROOT / "app/src/main/assets/locale/ui.tsv").read_bytes():
            raise ValueError("Localization payload differs from source")
        for name in BRAND:
            if z.read(prefix + "assets/flutter_assets/assets/" + name) != (ROOT / "flutter_ui/assets" / name).read_bytes():
                raise ValueError("Branding payload differs from source")
        for name in ("canvaskit-SCAxUyCS.wasm", "rhwp_bg-CjDYAjgF.wasm"):
            if prefix + "assets/office_hwp/assets/" + name not in names:
                raise ValueError("Office renderer missing")
        if prefix + "assets/flutter_assets/NOTICES.Z" not in names:
            raise ValueError("Flutter notices missing")
        codecs = [n for n in names if n.startswith(prefix + "lib/") and direct_native(n)]
        if edition == "play" and codecs:
            raise ValueError("Direct-only codecs in Play")
        if edition == "direct":
            for abi in expected_abis:
                if any(prefix + f"lib/{abi}/{lib}" not in names for lib in DIRECT_NATIVE_LIBS):
                    raise ValueError("Direct codecs unexpectedly missing for " + abi)
            verify_notices(z, "direct")
    return {"filename": path.name, "bytes": path.stat().st_size,
            "sha256": digest(path), "abis": sorted(actual)}


def prepare(directory):
    ver = versions()
    dv, dc = ver["direct"]
    pv, pc = ver["play"]
    apk = directory / f"FileHub-AIO-v{dv}.apk"
    arm = directory / f"FileHub-AIO-Direct-v{dv}-arm64-test.apk"
    play = directory / f"FileHub-AIO-Play-v{pv}-test.apk"
    aab = directory / f"FileHub-AIO-Play-v{pv}-internal.aab"
    data = {
        "status": "LOCAL_TEST_CANDIDATE_NOT_PUBLISHED",
        "direct": {"version": dv, "versionCode": dc, "package": "com.nasmount.mobile",
                   "universal": inspect(apk, "direct", ABIS),
                   "arm64": inspect(arm, "direct", {"arm64-v8a"})},
        "play": {"version": pv, "versionCode": pc, "package": "com.filehubaio.mobile",
                 "apk": inspect(play, "play", ABIS), "aab": inspect(aab, "play", ABIS, True),
                 "quality": check(aab)},
        "publicDistribution": "not approved; device and license review pending",
    }
    tag = f"flutter-test-v{dv}"
    notes = f"versionCode: {dc}\n\nFlutter internal device test candidate. Not a stable release.\n"
    # An offline fixture to check the same metadata shape the app accepts.
    # This is deliberately not an assertion that any remote release exists.
    preview = {"tag_name": tag, "draft": False, "prerelease": True, "body": notes,
               "assets": [{"name": apk.name, "size": apk.stat().st_size,
                           "digest": "sha256:" + digest(apk),
                           "browser_download_url":
                               f"https://github.com/dj5927/FileHub-AIO/releases/download/{tag}/{apk.name}"}]}
    (directory / "CANDIDATE_REPORT.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    (directory / "PREVIEW_METADATA_FIXTURE.json").write_text(json.dumps(preview, indent=2) + "\n", encoding="utf-8")
    (directory / "RELEASE_NOTES.md").write_text(notes, encoding="utf-8")
    return data


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("directory", type=Path)
    args = p.parse_args()
    print(json.dumps(prepare(args.directory), indent=2))
    print("FLUTTER_CANDIDATE_CONTENT_PASS")
