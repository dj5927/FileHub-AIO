# FileHub AIO PC v1.3.133 - Excel owner-file interoperability

## Problem

- Previous FileHub versions created Excel's real owner file (~$11월매출.xlsx)
  and a private 11월매출.xlsx.nasmount.lock directory. RaiDrive does not
  recognize the private FileHub directory.
- Excel's owner file must be visible to independent NAS clients immediately.
  Delayed WinFsp Cleanup may postpone uploading the owner file.
- After abnormal shutdown an owner file can survive. Users may manually
  remove it only after confirming that no one is editing.

## Direct WebDAV WinFsp changes (Excel only)

1. Before a new Excel write-open, probe the remote owner-file path directly,
   after invalidating metadata caches. Both full-prefix and first-two-character
   replacement filename variants are supported.
2. New Excel sessions no longer create .nasmount.lock directories.
   Existing legacy sidecar directories are still respected as lock signals,
   so an older client cannot be silently overwritten.
3. Excel creates the original binary ~$ owner file. FileHub uploads the same
   bytes through WebDAV before the WinFsp Write succeeds. FileHub does not
   fabricate a Microsoft owner file.
4. A local Windows handle releases its exemption at WinFsp Cleanup, not at
   the potentially delayed final Close.
5. Excel SaveAs can reopen or rename a workbook while its own owner exists.
   A remote owner revision known to have been uploaded by THIS mount can be
   recognized via its strong ETag. Independently created owner files still
   block editing.
6. Crash recovery never recreates a stale owner file from a local journal.
   It never automatically deletes an existing owner file on the NAS.
7. Other Office document types keep the existing cooperative-lock mode.
   Legacy WebDAV compatibility mount behavior is unchanged.

## Validation

- Independent WebDAV owner presence blocks editing while read-only open
  still succeeds.
- Old .nasmount.lock is still respected but no new sidecar is created.
- New owner bytes become visible on the server while the file is open.
- Removing an owner file manually allows editing again.
- Korean 11월매출.xlsx name variants are detected.
- Real Excel creates a workbook, saves it, and saves further changes three
  times with verified server output.
- The usual file-copy, EXE, rename, delete, and Excel replacement regression
  tests are run.

## Limits

- A Microsoft Excel ~$ owner file is a cooperative convention, not an
  atomic universal lock. Conditional server ETags continue to protect
  content from overwriting a concurrently changed revision. A short initial
  gap may exist before Excel publishes its owner file.
- The local lab uses independent WebDAV clients and real Windows WinFsp.
  Real FileHub + RaiDrive Excel interaction on the user's NAS remains a
  separate field verification.
- Remote stale owner files are never removed without user action.

## Verified build and official release handoff (2026-11-23)

- Windows v1.3.133 release ZIP: FileHub-AIO-PC-1.3.133-win-x64.zip, 168,932,839 bytes, SHA-256 F67D257DE161ED24CCECAA8F4E1F55F717881D6D8F80709A60585E3C8D531E72.
- Base archive: checksum-verified official pc-v1.3.132; shipped app and WinFsp LockBridge replaced with v1.3.133, maintaining Google publisher OAuth, rclone, Naver MYBOX, TeraBox and all other dependencies.
- Real Windows WinFsp test: independent WebDAV ~$ owner blocks editing, allows reads; newly published owner bytes visible immediately; legacy .nasmount.lock still respected; no new Excel sidecar; Korean ~$ name variants; manual stale owner cleanup; 24MiB and odd-length SHA-256 copying; temp replacement 3x and file operations all passed.
- Microsoft Excel COM integration: new workbook SaveAs plus 3 saves, existing Korean-path workbook reopen plus 5 saves, and independent owner check while Excel is open all passed.
- Crash journal/ETag conflict and legacy two-FileHub client lock policy tests passed; published application updater, title-version, Google publisher OAuth, WebDAV routing, encrypted backup and bridge self-test passed.
- Official Windows-only release: https://github.com/dj5927/FileHub-AIO/releases/tag/pc-v1.3.133
- User NAS + real RaiDrive GUI cross-client final field verification still required; Microsoft owner-file locking is cooperative and not guaranteed atomic without a common server-side protocol.
