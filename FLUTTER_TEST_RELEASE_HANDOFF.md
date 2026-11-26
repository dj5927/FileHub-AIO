# FileHub AIO Flutter device-test handoff

## Candidate

- Direct v0.1.130, code 131, com.nasmount.mobile. Universal signed APK
  keeps the three established ABIs. The separate ARM64 APK is for manual
  installation on ARM64 devices only; never make it the universal OTA asset.
- Play v1.0.15, code 16, com.filehubaio.mobile. Optimized signed test APK
  and upload-signed internal-test AAB. No production Play configuration.
- Use synthetic documents and disposable NAS test directories. Back up
  connection settings before upgrading. Install in place when the signer
  matches; do not uninstall or clear data to force a signature mismatch.

## Stable versus test OTA

The existing public latest release and production branches must not be
changed before testing. The optional Direct test-update setting defaults
to stable. A configured private OTA server takes precedence. Test releases
must be GitHub prereleases with tag flutter-test-v0.1.130 (or a newer
matching version), notes containing a standalone versionCode line, and
the universal FileHub-AIO-v0.1.130.apk with GitHub's SHA256 asset digest.
The updater rejects drafts, unrelated tags, incorrect URLs, missing
digests, wrong package/signature and non-increasing version codes.

After device and corresponding-source/license review, an authorized
publisher can push the verified migration commit to a separate branch,
create the test tag at that commit, and use:

```powershell
gh release create flutter-test-v0.1.130 FileHub-AIO-v0.1.130.apk --repo dj5927/FileHub-AIO --verify-tag --prerelease --latest=false --notes-file RELEASE_NOTES.md
```

Attach the completed corresponding-source materials as required before
any external redistribution. Verify the downloaded release asset digest,
size and signing identity, and check that releases/latest is unchanged.
Do not use the old stable tag workflow for a prerelease. The local fixture
is not evidence of a published GitHub release.

## Device checklist

1. Upgrade Direct over the existing installed version. Confirm saved
   profiles, bookmarks, history, per-folder sort, appearance and settings.
2. Settings > General > Interface mode: switch Flutter/classic and back.
   Confirm no settings are reset; check startup, background/resume,
   rotate/fold, native TV, keyboard and back navigation. If Flutter
   fails, use the classic mode and retain diagnostic details.
3. Use small synthetic XLSX documents on each supported writable protocol.
   Open/save repeatedly, inspect server file size and content, open
   concurrently in Office, check lock creation/removal, simulate a
   dropped connection and confirm no original becomes zero bytes.
4. Check all explorer operations, large directory scrolling, transfer
   completion and cancellation, image zoom/swipe, video subtitles/PiP,
   reader position, and Office/HWP rendering against classic mode.
5. Install Play test APK alongside Direct, verify free limits, language
   switching and test ads; do not attempt a real purchase until configured.
6. Measure cold/warm launch and memory on the same device in both modes:
   adb shell am start -W -n com.nasmount.mobile/com.nasmount.mobile.MainActivity
   and adb shell dumpsys meminfo com.nasmount.mobile. Use the same files and
   repeat at least three times. Unit tests do not measure device speed.

## Before Play publication

Keep the private upload signing key and R8 mapping local. Use the internal
AAB for the registered Play app after configuring the developer account
and Play App Signing. Review merged permissions, actual Data Safety,
privacy URL, listing/screenshots, license/provider terms and Google Play
console checks. Live AdMob IDs, billing public key, one-time product
filehub_full_version (KRW 9,900 target), consent and real purchase/restore
tests belong to the final stage. Production build guard stays closed
until explicit device parity and release review.

Current premium plan details and console configuration: PREMIUM_PLAN_V142.md.
