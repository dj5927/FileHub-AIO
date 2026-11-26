# Flutter compact UI and verified Play R8 startup fix

## Installable local candidates
- Direct v0.1.134 (135): FileHub-AIO-v0.1.134.apk.
- Play v1.0.19 (20), recommended R8-enabled test: FileHub-AIO-Play-v1.0.19-optimized-test.apk.
- Same-version fallback with R8 disabled: FileHub-AIO-Play-v1.0.19-test.apk.
- Signed Play internal-test bundle: FileHub-AIO-Play-v1.0.19-internal.aab.
- DO NOT INSTALL the older failing trial APK in diagnostics. It is retained solely for regression evidence.
- Use matching signer/package; install as update, do not clear existing application data. No production upload, live ad identifiers, real billing keys, or real purchases are enabled.

## Implemented
- Assigned video/comic/book folder panels use flat file-explorer-style rows with folder icon, name, metadata and right overflow; open/remove commands unchanged.
- Removed user-facing classic navigation/editor/viewer/transfer/prompt/settings switches, including old connection manager. Phone/tablet uses Flutter by default regardless of previous preference; native TV and automatic startup/failure fallback remain to protect functionality.
- Persistent independent 61dp music overlay, animated translation up/down without changing body viewport. Hidden when drawer or file-selection/clipboard controls would conflict. Fixed bottom scroll clearance avoids inaccessible last items.
- Compact drawer home/section/favorite/network/settings rows; drag/reorder and manage controls retained.
- Android 16 API 36 x86_64 emulator reproduced the former R8 startup crash. AndroidX Startup -> WorkManager 2.7.0 -> Room 2.2.5 could not instantiate WorkDatabase; the earlier mapping retained WorkDatabase_Impl class but no constructor. Explicit no-arg constructor keep for RoomDatabase subclasses; added explicit internal optimized-test Gradle mode. Normal internal APK defaults to no R8, diagnostic/trial AABs remain blocked, production guard unchanged.
- Built optimized APK and signed AAB with R8 plus resource shrinking. AAB R8 metadata: obfuscation 74.13%, optimization 83.75%, shrinking 74.10%; uncompressed DEX 11,562,108 bytes. All three exceed 25%. No-R8 Play DEX 34,583,816 bytes. This is not a Google Play Console acceptance claim.

## Verification
Flutter analyze no issues; 31 widget tests PASS; direct/play Java unit tests 1242 total, 1240 pass, 2 skipped, 0 failed/errors. Localization audit 1091 catalog / 903 UI keys / 0 missing/pending. Release lint, APK signer/package/version, 16KB ZIP alignment, ABI, shared Flutter AOT, branding/localization/Office assets, pinned notices and Direct/Play codec separation PASS. AAB jarsigner verified with standard self-signed/no timestamp and JarInputStream ordering warnings; bundle quality gate PASS.
Emulator: signed normal Direct and Play installed and cold-launched; old R8 trial reproduced fatal startup; corrected optimized APK updated over trial, cold-launched and remained resumed after 10 seconds with no fatal crash. Home, test ad banner, drawer and Settings General/Display language visible. Direct local explorer music overlay stop removed bar with identical file row bounds; compact drawer visible. This was an isolated emulator, not a user's physical ARM phone or live NAS. Actual hardware transitions/Fold/TV, storage/cloud/Office operations, all language layouts, purchase/restore/refund, real ads/privacy declarations and provider/license review still require verification. Production guard remains closed.
Archive: D:/FileHubAIO-PublisherBuildCache/Android-FlutterCompactUI-20261125.
