# FileHub AIO PC v1.3.144 — stable dependency refresh

2026-11-24. Based on published PC v1.3.143 (3cbc5c4).
Worktree: D:\FileHubAIO-PC-v144-Dependencies.

## Updates
- WinFsp official MSI and managed binding: 2.1.25156 -> 2.2.26323.
  Official MSI SHA256 36CE01F9EEDBBE6842B7FB4BEDC79D7D4F71983E8ACEB6877B46A07B331C7E19;
  Authenticode status Valid, NAVIMATICS LLC.
- .NET self-contained runtime: 10.0.11 -> 10.0.15 (explicit runtime pin
  in both projects; the locally built CoreLib version confirms 10.0.15).
- WebView2 SDK/loader: 1.0.4314.35 -> 1.0.4370.26.
- Future ordinary PC CI SDK/toolchain pins: .NET SDK 10.0.404, Go 1.27.4.
  The local compiler remains SDK 10.0.400; the bundled runtime is 10.0.15.
  The official standard and TeraBox binaries remain unchanged, including
  their original Go 1.27.3. Optional Google rclone source build was not
  executed; the actual v143 package uses the direct Google engine.
- rclone 1.76.0 and rclone-extra 1.76.0-extra remain current stable releases.
  rclone master has 82 commits beyond the tag (c0cef5e872f9), including
  config/debug log secret-redaction, VFS/download and dependency changes.
  Do not blindly replace tested stable engines with untagged development
  code. Review any backports in a separate engine build and regression lab.
- FluentFTP 55.1.0, SSH.NET 2026.0.1, other current stable dependencies
  unchanged. No major transitive dependency upgrades forced.

## Existing-driver compatibility
WinFsp native and managed versions must be compatible: upstream 2.2
binding rejects native 2.1. To avoid breaking existing connections,
the bridge selects the pinned 2.1.25156 managed DLL in an isolated load
context when native 2.1 is installed. The new default remains 2.2.26323.
Both packages share assembly identity 2.0.0.0. The compatibility DLL
SHA256 is 6BB1C36F899A5DE0CED020A026120CE9D0B88B140B3C7BD6CD079175F76529BC.
User-approved driver installation keeps the existing mounted-drive check;
there is no automatic shared-driver upgrade or downgrade. Beta versions
below 2.2.26323 are now offered the newer stable MSI, not treated as a
mount error. The installed test-host driver remains 2.2.26215 Beta4.

## Local verified results
- Both projects build and publish. Packaged FileHubAIO.exe and bridge
  report 1.3.144; WinFsp DLLs 2.2.26323 and 2.1.25156, WebView2 loader
  1.0.4370.26, CoreLib 10.0.15.
- Actual Excel on isolated WebDAV/WinFsp: missing owner/temp/document ETags,
  rejected unversioned conditional MOVE, 3.9 MB workbook, existing empty
  destination, NAS-to-NAS SaveAs, five repeat saves, reopened save,
  original preservation, Korean filename, independent second-mount owner
  conflict. PASS. Log: %TEMP%\filehub-office-new-save-3e389767.
- Guarded PUT/rename/delete, write recovery and independent lease tests:
  PASS. %TEMP%\FileHubWriteStore_249f19235b8a48ad825599848e6cad2d.
- Real native WinFsp fake-Google-API read/write/rename/recovery policy lab
  passed with the new managed binding and with the forced legacy binding
  in its isolated load context (both on installed Beta4).
- Packaged updater, driver policy, EXE execute, compact menu, routing,
  Synology sharing, OAuth, Google cold start, embedded engine manifest
  and bridge self-tests all passed.
- Current production NAS/cloud accounts, installed native 2.2 stable and
  native 2.1 need separate verification; do not claim the local Beta4 lab
  proves the other native-driver versions. Hosted test workflow checks
  both versions before publishing.

No document-save, owner-lock, transfer queue or connection routing logic
changed. No Android release changes. Production retest remains required.
