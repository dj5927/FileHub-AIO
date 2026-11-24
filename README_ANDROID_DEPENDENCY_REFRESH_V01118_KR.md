# FileHub AIO Android v0.1.118 dependency refresh

Date: 2026-11-24. Baseline: official Android v0.1.117, commit
084e658a622fa9b05572e941211d5f3c21cf7f95; source branch starts from
main 523ff24bfe778b02a833808e5de2a1e1415db74c.
Separate worktree: D:\FileHubAIO-Android-v118-Dependencies.
Existing unrelated Android WebDAV working trees and all PC sources are untouched.

## Updated stable components

| Component | Previous | Candidate |
| --- | --- | --- |
| rhwp Studio, WASM and SDK | 0.9.0 | 0.9.1 |
| NextLib FFmpeg | 1.11.1-0.18.0 | 1.11.1-0.19.0 |
| Android Gradle Plugin | 9.5.0 | 9.5.2 |
| Gradle | 9.9.1 | 9.9.2 |
| AndroidX Lifecycle runtime | 2.11.0 | 2.11.1 |
| SSHJ Bouncy Castle provider, pkix and util | 1.84 | 1.87 |

Media3 remains 1.11.1, compatible with the new NextLib. Existing decoding
preference, external subtitle selection, playback resume, remote streaming,
network protocol and locking implementations are unchanged. Bouncy Castle
license files are merged, not discarded. Android app ID, minSdk 26,
targetSdk 35 and existing publisher certificate remain unchanged.
Already-current stable dependencies were not replaced with previews.
Next Player is a reference, not a packaged dependency. Its unshipped HW+
and decoder/gesture/thumbnail changes are not blindly copied; they require
separate real-device testing. Custom PPT and Excel assets remain unchanged.

rhwp source tag v0.9.1:
https://github.com/edwardkim/rhwp/tree/981ce66b4183b13c1a3b5907e04a8969c8c42034
Official npm packages (download SHA-512 verified):
- @rhwp/core 0.9.1 SHA256 1a4717863545d77ac86ada2afe5530fea4c26cf6dab842eb8b909c7e9c3e1a91
- @rhwp/editor 0.9.1 SHA256 27bd37112c7a12b99d068e360fb7ea8ffc08da875f38c25b9ce97459ae596b24
Studio built using its official Chrome viewer Vite config and npm lockfiles.
SDK JavaScript was already byte-equivalent after line-ending normalization
between 0.9.0 and 0.9.1. All viewer assets and the WASM are from one release.
WASM is reconstructed from six gzip chunks and hash checked before packaging.
New WASM SHA256 E2E56DFD387FFEE5B0995D23E4A0086001407F12A045820EB61BD6A506C9ABC1.
No debug globals, remote AI integrations or online document fetches added.
NextLib upstream tag: https://github.com/anilbeesetti/nextlib/tree/539411ccb4740251252a2cc8896c6579d0c43c3d

## Regression results

- Original official v117 source baseline: 587 JUnit cases, 0 failures,
  0 errors, 1 existing skipped case. Final v118: same counts.
- Final debug and publisher-signed release build including vital lint passed.
- Four Excel Chromium regression pages passed: fills, fonts, row/column size,
  borders, merged cells, scroll, pinch zoom, images, clipping/wrapping, tabs,
  original OOXML style/image preservation. JSZip/docx-preview hashes and
  Korean ZIP roundtrip passed.
- Production offline rhwp viewer (nonlocal network blocked): HWP 1-page
  sample and HWPX 10-page sample opened, rendered, typed through the real
  editor input, exported and reopened with changed document state and page
  count preserved. FileHub compact host and mocked native save bridge passed.
- Old and new signed APKs: 31 custom Excel/Word/PPT assets exactly identical.
  No native library removed; 24 old native entries, 28 new entries (adds
  libavformat on all four ABIs). ARM64, ARM32, x86, x86_64 preserved.
- Resolved release graph confirms NextLib 0.19.0, Media3 1.11.1,
  Lifecycle 2.11.1 and all three Bouncy Castle artifacts 1.87.
- APK versionCode 119, versionName 0.1.118, signature v2 valid,
  publisher cert SHA256 e2b6fece09666dbfb26044c381227953f93a1eb1685ea6890139bbda89efeeb8.
- Candidate bytes 84064229; SHA256 D4F454974EA6EF83048A75563BD8B9F13AD60AEAE433EA823D2F4622F554BA66.
- No physical Android device connected to the test host. Real hardware
  playback (DTS/TrueHD/E-AC3), SFTP/SMB/NAS connection and Android TV/Fold
  visual tests remain pending. Keep v117 as latest stable OTA until
  real-device acceptance; use an isolated manual test candidate instead.

## Evidence and reproducibility

Build logs: baseline-v117.log, dependency-v118.log, final-build-v118.log,
office-v118-edits.log, resolved-release-v118.log.
Browser regression: tests/office_dependency_refresh.mjs.
Offline import: scripts/install_rhwp_091_assets.py.
Publisher candidate:
D:\FileHubAIO-PublisherBuildCache\Android-0.1.118-20261124\FileHub-AIO-v0.1.118.apk
Baseline signed APK preserved in its original publisher cache.
