# FileHub AIO PC v1.3.134 - Remove obsolete experimental WebDAV native-lock mode

## Request

The WebDAV profile settings still exposed the experimental server native
LOCK/UNLOCK checkbox after PC v1.3.133 switched Excel interoperability to
the real Microsoft Office ~$ owner file.

## Change

- Remove the experimental native LOCK/UNLOCK checkbox, help text, and warning
  dialog from WebDAV profile creation/editing.
- Remove the related WebDAV LOCK support test button from global settings.
  Ordinary connection diagnostics and all storage providers remain.
- Every WebDAV profile, including old settings/imports that selected native
  mode, uses the verified direct WinFsp WebDAV engine.
- The UseNativeWebDavLocks profile property is now an ignored compatibility
  shim: it always reads false, cannot be set true, and is omitted from JSON.
  Old saved JSON with true is accepted without changing other profile fields.
- Remove obsolete native-only status text. The regular connection and
  read-only statuses remain unchanged.
- Retain v1.3.133 Excel ~$ owner-file locking, safe conditional ETag writes,
  crash recovery and the MYBOX/TeraBox/Google/SFTP/FTP backends.

## Verification

- Direct-WebDAV routing policy includes legacy native=true JSON migration.
- Encrypted profile backup/import and Android compatible backup policy.
- PC running title and footer v1.3.134, updater and basic mount policy.
- LockBridge self-test covers Excel/file safety.

## Android review (no Android changes in this PC-only release)

Android BuiltInOfficeActivity and OfficeBridgeActivity download remote
documents into a temporary local file, then upload on explicit save.
They do not currently inspect Excel ~$ owner files on remote open/save.
WebDAV conditional ETag checks help protect final writes but do not guard
the entire editing session. A separate Android implementation should
check owner files on open/save, show read-only/conflict states, and verify
ETags before writing. Do not copy WinFsp callbacks to Android or create
synthetic Microsoft Office owner files without a compatibility test.

## Official release build handoff (2026-11-23)

- GitHub PC v1.3.134: https://github.com/dj5927/FileHub-AIO/releases/tag/pc-v1.3.134
- Archive FileHub-AIO-PC-1.3.134-win-x64.zip: 168,933,682 bytes, SHA-256 A8FC94E5D73EEA4CFC99BC36CA0B9CBDBD85ACFB1CB7E0080B697BF3D6D69DB8.
- Verified official PC v1.3.133 archive used as source; replaced v1.3.134 app/bridge binaries, preserving approved OAuth and third-party provider dependencies.
- Distribution policy PASS: old true native-lock JSON disabled and directly routed, encrypted backup/import, PC updater, version/header/footer, Google OAuth and login, and LockBridge self-test (Excel owner naming, ETag protection, file read/write) all passed.
- C: volume temporary exhaustion during build resolved by deleting only generated bin/obj directories from old PC worktrees; project sources and published release assets untouched.
- This version removes an obsolete PC experiment. Separate Android WebDAV Office owner-file conflict detection still requires implementation; no Android APK change or release is included.

## Official release verified (2026-11-23)

- GitHub Actions run 45132223519: success. The CI runner rebuilt the 11 transfer segments, verified ZIP integrity, complete SHA-256, required EXE/LockBridge/OAuth/rclone assets, and created the PC-only release.
- Official ZIP: FileHub-AIO-PC-1.3.134-win-x64.zip, 168,933,682 bytes, SHA-256 A8FC94E5D73EEA4CFC99BC36CA0B9CBDBD85ACFB1CB7E0080B697BF3D6D69DB8; GitHub asset digest exactly matches the locally tested ZIP.
- Download: https://github.com/dj5927/FileHub-AIO/releases/download/pc-v1.3.134/FileHub-AIO-PC-1.3.134-win-x64.zip
- Post-release cleanup: temporary HTTPS tunnel and ZIP part HTTP server stopped. No user NAS data or live FileHub connections were modified.
- Remaining separate work: Android WebDAV editing does not yet check Excel ~$ owner files. It was audited but NOT modified or released by this PC fix.
