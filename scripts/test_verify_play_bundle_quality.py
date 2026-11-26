import json
import tempfile
import unittest
import zipfile
from pathlib import Path
import verify_play_bundle_quality as gate


class BundleQualityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.bundle = Path(self.directory.name) / "test.aab"

    def tearDown(self):
        self.directory.cleanup()

    def create(self, no_obfuscation=20, metadata=True, mapping=True,
               missing_asset=False, forbidden=False, tampered_notice=False,
               direct_notice=False):
        with zipfile.ZipFile(self.bundle, "w") as z:
            z.writestr("base/dex/classes.dex", b"dex fixture")
            for index, name in enumerate(gate.REQUIRED_ASSETS):
                if index or not missing_asset:
                    z.writestr(name, b"fixture")
            for row in json.loads((gate.PINNED_DIR / "manifest.json").read_text(encoding="utf-8")):
                if row["coordinate"].startswith(("io.github.anilbeesetti:", "com.github.junrar:")) and not direct_notice:
                    continue
                for entry in row["files"]:
                    data = (gate.PINNED_DIR / entry["filename"]).read_bytes()
                    z.writestr("base/assets/licenses/upstream-" + entry["filename"],
                               b"tampered" if tampered_notice else data)
            if metadata:
                z.writestr(gate.METADATA, json.dumps({"stats": {
                    "noObfuscationPercentage": no_obfuscation,
                    "noOptimizationPercentage": 10,
                    "noShrinkingPercentage": 25,
                }}))
            if mapping:
                z.writestr(gate.MAPPING, "com.github.junrar.Archive -> x:\n"
                           if forbidden else "example.Main -> x:\n")

    def test_healthy_bundle(self):
        self.create()
        self.assertEqual(gate.check(self.bundle)["scores"]["obfuscation"], 80)
        self.assertEqual(gate.dex_bytes(self.bundle), 11)

    def test_threshold_and_bad_values(self):
        self.create(no_obfuscation=75)
        self.assertEqual(gate.check(self.bundle)["scores"]["obfuscation"], 25)
        for invalid in (75.01, 100, -1, float("nan"), "20", True):
            self.create(no_obfuscation=invalid)
            with self.assertRaises(ValueError):
                gate.check(self.bundle)

    def test_missing_metadata_mapping_assets(self):
        for args in ({"metadata": False}, {"mapping": False}, {"missing_asset": True}):
            self.create(**args)
            with self.assertRaises(ValueError):
                gate.check(self.bundle)

    def test_direct_class_rejected(self):
        self.create(forbidden=True)
        with self.assertRaises(ValueError):
            gate.check(self.bundle)

    def test_actual_direct_native_names_rejected(self):
        for lib in gate.DIRECT_NATIVE_LIBS | {"libffmpeg.so", "libnextlib.so"}:
            self.create()
            with zipfile.ZipFile(self.bundle, "a") as z:
                z.writestr("base/lib/arm64-v8a/" + lib, b"fixture")
            with self.assertRaisesRegex(ValueError, "Direct-only native decoder"):
                gate.check(self.bundle)

    def test_notice_integrity_and_edition_separation(self):
        for args in ({"tampered_notice": True}, {"direct_notice": True}):
            self.create(**args)
            with self.assertRaises(ValueError):
                gate.check(self.bundle)


if __name__ == "__main__":
    unittest.main()
