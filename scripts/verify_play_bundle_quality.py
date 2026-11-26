"""Fail-closed local check for Play's February 2027 DEX quality requirement.

Use the R8 stats shipped in the AAB, not APK compression or DEX size reduction
as a proxy for the three percentages. Never rewrite the generated metadata.
"""
import argparse
import hashlib
import json
import math
import re
import zipfile
from pathlib import Path

METADATA = "BUNDLE-METADATA/com.android.tools/r8.json"
MAPPING = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
DEX = re.compile(r"[^/]+/dex/classes(?:\d+)?\.dex$")
SCORES = {
    "obfuscation": "noObfuscationPercentage",
    "optimization": "noOptimizationPercentage",
    "shrinking": "noShrinkingPercentage",
}
REQUIRED_ASSETS = (
    "base/assets/locale/ui.tsv",
    "base/assets/licenses/00-FileHub-GPL-3.0.txt",
    "base/assets/licenses/02-Dependency-inventory.txt",
    "base/assets/licenses/03-Upstream-license-review.txt",
    "base/assets/office_hwp/assets/canvaskit-SCAxUyCS.wasm",
    "base/assets/office_hwp/assets/rhwp_bg-CjDYAjgF.wasm",
)
FORBIDDEN_CLASSES = (
    "com.github.junrar.",
    "io.github.anilbeesetti.nextlib.",
    "com.nasmount.mobile.CodecAddonManager",
    "com.nasmount.mobile.ApkInstallStaging",
)


DIRECT_NATIVE_LIBS = frozenset({
    "libass.so", "libavcodec.so", "libavformat.so", "libavutil.so",
    "libmedia3ext.so", "libswresample.so", "libswscale.so",
})


def direct_native(name):
    leaf = name.rsplit("/", 1)[-1].lower()
    return leaf in DIRECT_NATIVE_LIBS or "ffmpeg" in leaf or "nextlib" in leaf


PINNED_DIR = Path(__file__).resolve().parents[1] / "THIRD_PARTY_LICENSES/pinned"


def verify_notices(archive, edition="play"):
    prefix = "base/assets/licenses/" if edition == "play" else "assets/licenses/"
    entries = set(archive.namelist())
    manifest = json.loads((PINNED_DIR / "manifest.json").read_text(encoding="utf-8"))
    expected_count = 0
    for row in manifest:
        direct_only = row["coordinate"].startswith(("io.github.anilbeesetti:", "com.github.junrar:"))
        for entry in row["files"]:
            name = prefix + "upstream-" + entry["filename"]
            if direct_only and edition == "play":
                if name in entries:
                    raise ValueError("Direct-only notice in Play bundle: " + name)
                continue
            if name not in entries:
                raise ValueError("Missing pinned upstream notice: " + name)
            if hashlib.sha256(archive.read(name)).hexdigest() != entry["sha256"]:
                raise ValueError("Pinned upstream notice mismatch: " + name)
            expected_count += 1
    return expected_count


def dex_bytes(bundle):
    with zipfile.ZipFile(bundle) as archive:
        return sum(i.file_size for i in archive.infolist() if DEX.fullmatch(i.filename))


def check(bundle):
    with zipfile.ZipFile(bundle) as archive:
        entries = archive.namelist()
        size = sum(i.file_size for i in archive.infolist() if DEX.fullmatch(i.filename))
        if not size:
            raise ValueError("Missing DEX")
        missing = [name for name in REQUIRED_ASSETS if name not in entries]
        if missing:
            raise ValueError("Required assets missing: " + ", ".join(missing))
        notices = verify_notices(archive)
        if METADATA not in entries:
            raise ValueError("Missing R8 metadata: even small bundles must use R8")
        stats = json.loads(archive.read(METADATA))["stats"]
        scores = {}
        for label, key in SCORES.items():
            val = stats[key]
            if (type(val) not in (int, float) or not math.isfinite(val)
                    or not 0 <= val <= 100):
                raise ValueError("Invalid R8 percentage: " + key)
            scores[label] = 100 - val
        # Apply the 25% floor even below Google's size threshold so future
        # feature growth cannot accidentally regress the production policy.
        if any(value < 25 for value in scores.values()):
            raise ValueError("R8 score below 25%: " + repr(scores))
        if MAPPING not in entries:
            raise ValueError("Missing R8 mapping")
        mapping = archive.read(MAPPING).decode("utf-8")
        for line in mapping.splitlines():
            if line.startswith(FORBIDDEN_CLASSES):
                raise ValueError("Direct-only class in Play mapping: " + line)
        if any(name.startswith("base/lib/") and direct_native(name)
               for name in entries):
            raise ValueError("Direct-only native decoder in Play bundle")
        return {"dex_bytes": size, "scores": scores, "mapping_bytes": len(mapping.encode("utf-8")), "pinned_notices_verified": notices}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    try:
        result = check(args.bundle)
        if args.baseline:
            before = dex_bytes(args.baseline)
            if before:
                result["baseline_dex_bytes"] = before
                result["dex_size_reduction_percent"] = round(
                    100 * (before - result["dex_bytes"]) / before, 2)
        print(json.dumps(result, indent=2))
        print("PLAY_BUNDLE_QUALITY_PASS")
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as problem:
        parser.exit(1, f"PLAY_BUNDLE_QUALITY_FAIL: {problem}\n")


if __name__ == "__main__":
    main()
