# RaiDrive 2026.10.0 vs FileHub AIO: read-only compatibility investigation

Date: 2026-11-24. Scope: Windows / WinFsp / WebDAV, with the user's
installed RaiDrive version. This is an architectural comparison, not a
RaiDrive source-code port. No proprietary binaries are redistributed.

## What was verified on the installed Windows machine

- RaiDrive Mount version 2026.10.0, installed under OpenBoxLab.
- Its running per-user mount service loads **winfsp-x64.dll** and
  **e_sqlite3.dll**. This establishes actual WinFsp usage, not merely
  an installer dependency.
- The local Windows Installer cache contains the RaiDrive MSI package.
  Read-only MSI inspection enumerated the UI executable, mount-service
  executable, Shell extension, WinFsp MSI and other bundled components.
- RaiDrive.Mount.Service.x64.exe is a .NET 10 single-file bundle.
  The manifest describes 52 embedded files, including an approximately
  8 MB managed service assembly, Rebex protocol libraries, Microsoft.Data.Sqlite
  and standard .NET components.
- Internal identifiers include CleanupFile, FlushFile, FinalUpload,
  MergeUpload, UploadWriteFile, UploadAsyncDelayTime,
  FlushAndPurgeOnCleanup, PostCleanupWhenModifiedOnly and IrpTimeout.
  These are **binary strings**, NOT proof of invocation sequence.
- The managed service assembly has obfuscated private type/method names.
  Its exact Cleanup/Flush/HTTP acknowledgment call sequence is **not verified**.
  Do not claim RaiDrive always blocks every Cleanup on each HTTP 201/204.
- Only read-only inspection of installed application and cached MSI was done.
  No settings, production NAS profiles, user data, or running RaiDrive drive
  were changed. No private configurations or authentication tokens included.

## Vendor-published behavior

- https://docs.raidrive.com/gui/settings/cache/
  RaiDrive stages read and write transfers through a local disk cache;
  its default cache lifetime is 30 minutes, separately for reads/writes.
- https://www.raidrive.com/release-notes/2026.10.0
  The 2026.10.0 release added WinFsp as a filesystem driver.
- https://docs.raidrive.com/gui/options/advanced/
  An optional *quick copy/move/delete on storage* feature intercepts some
  same-storage Explorer operations and shows RaiDrive's own progress.
  Do not mistake these server-side operations for local-PC-to-NAS PUT.

## Controlled FileHub WinFsp A/B test

Lab-only test, **not a measurement of RaiDrive's internal callback behavior**.
WebDAV proxy adds 250 ms per response. Source: 60 x 4 KiB test files.

| FileHub cleanup policy | Explorer/local-copy completion | Server accepted all 60 |
| --- | ---: | ---: |
| Block Cleanup until HTTP PUT response | 17.246 s | 17.246 s |
| Queue and allow parallel PUTs | 0.230 s | about 1.085 s |

The first policy makes completion line up but serializes sequential Explorer
files. The second preserves throughput but lets Explorer finish before
the final server acknowledgment. This is a real architectural tradeoff, not
something that can be solved by changing ProgressBar to 99%.

## FileHub-specific interoperability correction

- The v1.3.137 Explorer progress window acknowledges local staging, not
  successful remote uploads; an external WebDAV client deleting the
  destination while uploads are pending can race with remaining PUTs.
- Pending/active/acknowledged/failed/canceled counters must be reported
  independently, from the bridge's local STATUS pipe, without per-file
  NAS reads. The FileHub UI must not claim success while any remain.
- Do not blindly replay indeterminate/crashed queued copies: another client
  may have removed their remote path, and replay would resurrect deleted data.
  Preserve a local recovery copy for manual disposition instead.
- HTTP 2xx is a **server acceptance acknowledgment**, not proof of physical
  spindle/SSD fsync on a NAS backend. Do not claim greater durability
  than the server protocol actually provides.
- Any future mode that blocks all Explorer Cleanup calls must be benchmarked
  against 2,780+ small files, because it can undo the performance gains.

## Outstanding verification

- RaiDrive's proprietary precise callback-to-HTTP call order remains unknown.
  A definitive black-box comparison needs a separate disposable RaiDrive
  profile connected to a controlled WebDAV endpoint with timestamped server
  traces. Avoid disrupting the user's existing production connections.
- Real Synology LAN/WAN FileHub performance and cross-client deletion races
  require field QA after each release; local loopback numbers are not proof
  of real-NAS parity.
