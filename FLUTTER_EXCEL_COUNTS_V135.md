# Direct 0.1.135 / Play 1.0.20 - Excel, shelf navigation, folder counts
Date: 2026-11-25 (KST). Builds and verification finished in this session.
Archive: D:/FileHubAIO-PublisherBuildCache/Android-FlutterExcelCounts-20261125

## Changes
- Assigned Videos/Comics/Books routes show their loading panel immediately and enumerate storage on a separate executor; stale jobs canceled on handoff.
- Excel zoom minimum 30% (was 55%), maximum 300%; persistent sticky row/column/corner header layers separated from virtualized data cells. Existing pinch, drawing, format and lossless-save regressions preserved.
- Search icon beside spreadsheet save. Active sheet only, case-insensitive normalized text, displayed/underlying values and formulas; previous/next match and confirm jumps/selects exact cell. Works read-only. Search available once workbook is ready.
- Folder item counts use separate four-thread local and network pools, cached counts where available, cancel old screen work. Local names-only list; SAF child-name query; SMB names-only list; SFTP ls without per-link stat; other remote providers count direct children. Timeout 8s local / 20s remote shows unknown, not fake zero. Respect existing hidden-file setting.
- Runtime found an additional first-frame bug: explorerPreview calls from newly mounted Flutter tiles were rejected by ROUTE_NOT_READY. Only revision-validated read-only explorerPage/explorerPreview bypass first-frame and drawer gates; mutation/action gates unchanged.
- Play release always R8 with resource shrinking; obsolete Play no-R8/trial mode rejected. Direct retains its established independent build settings and signature. No public release or store upload.

## Verified
- Flutter analyze clean, 31 widget tests passed.
- Java full suite: 1244 tests, 1242 passed, 2 skipped, zero failures/errors; new targeted WebDAV count test passed on both flavors.
- Five Chromium regressions passed (including actual host search, 30% zoom, frozen headers, sheet isolation, old styles/drawings/save).
- Android 36 x86_64 QA emulator: Direct update and cold launch; all 12 test subfolder count labels displayed promptly; each 3 with hidden enabled, actual folder listing confirmed. First unsuccessful count run diagnosed and fixed, then retested. A test initially assumed hidden disabled and was corrected by verifying actual listing. Play R8 update cold launch and 8s resumed, home and test ad visible, no crash.
- Localization 1095 catalog / 906 used, no missing/pending. Release lint, signer continuity, APK 16KB alignment, ABI and asset verification, AAB signature and quality pass. R8 scores obfuscation 74.12%, optimization 83.73%, shrinking 74.09%, DEX 11,568,772 bytes.
- Remaining hardware checks: real NAS latency and actual SMB/SFTP/cloud accounts, physical phone/Fold/TV gestures, native Excel search UI on a physical device, and exact shelf transition time on real hardware. WebDAV transport counted against loopback test only. No Play Console acceptance claim.

## Install
FileHub-AIO-v0.1.135.apk
FileHub-AIO-Play-v1.0.20-optimized-test.apk
FileHub-AIO-Play-v1.0.20-internal.aab
Update matching edition; no data clearing. No new non-R8 Play APK.
