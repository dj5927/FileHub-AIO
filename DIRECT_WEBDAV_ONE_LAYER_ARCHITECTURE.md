# FileHub AIO PC — RaiDrive-style single-WinFsp architecture

Verified on 2026-11-20. Development branch only; **not a production writable mount**.

## RaiDrive 2026.10.0 evidence (user-provided installation sample)

- Package: `D:\GPT\NAS_MOUNT\새 폴더\RaiDrive.Mount_2026.10.0_x64`.
- Installed dedicated mount service: `RaiDrive.Mount.Service.x64.exe`.
- Observed service modules: `winfsp-x64.dll`, `e_sqlite3.dll`. Package also
  contains shell extension and its own UI/service. Static service metadata
  references HTTP/FTP/SFTP provider libraries and cache/read-ahead APIs.
- The included WinFsp MSI matched FileHub's WinFsp MSI by SHA-256 in the
  earlier static analysis. WinFsp driver version is not the root cause.
- No `rclone.exe` was found in the RaiDrive installer package.
- RaiDrive documents a local read/write content cache, default 30-minute
  lifetime for each direction. This does not disclose proprietary algorithms.

These are evidence for a **dedicated provider service using WinFsp and a
local cache**. The exact proprietary internal call graph, SQLite schema,
locking algorithm, and cache implementation cannot be determined from the
installer alone. Do not claim code-level equivalence.

Official references:

- https://www.raidrive.com/release-notes/2026.10.0
- https://docs.raidrive.com/ko/gui/settings/cache/

## Current FileHub bottleneck

`Explorer -> public WinFsp LockBridge -> private rclone WinFsp mount ->
rclone VFS -> WebDAV -> NAS`.

Real user traces on 2026-11-20 show `DirectDownloadTests.exe` lower FileStream
open taking approximately 5.55-5.75 seconds repeatedly, while subsequent
already-cached opens are 0.2 ms. The same user's RaiDrive drive opened the
same named NAS EXE in 0-6 ms. This cannot be solved by shell notifications,
minor WinFsp metadata caches or rclone read chunk flag changes alone.

## New direct-provider architecture — implemented prototype

`Explorer -> one WinFsp -> FileHub direct WebDAV provider -> HTTP -> NAS`

- `LockBridge/DirectWebDavReadOnlyBackend.cs` implements WebDAV `PROPFIND`,
  `GET`, HTTP byte range reading, ETag-aware EXE/DLL local content cache,
  metadata/negative lookup cache, bounded size, safe path normalization.
- `LockBridge/DirectWebDavReadOnlyFileSystem.cs` implements WinFsp callbacks
  for file/directory metadata, security, opening, reading, enumeration and
  Windows change notifications from a 2-second watched-folder monitor.
- `NASMountLockBridge.exe --direct-webdav-readonly-lab
  http://127.0.0.1:18766/ V:` is intentionally **loopback-only and read-only**.
  It does not expose a normal user profile, does not accept credentials, and
  does not replace FileHub's production WebDAV connection path.
- `tests/direct_webdav_one_layer_lab.ps1` runs a loopback WebDAV server,
  mounts the new one-layer WinFsp driver, compares EXE hashes to local files,
  checks version-resource query latency, directory reads, remote file
  creation and executable revision changes, and rejects writes.

## Verified test result

The full read-only lab passed:

- First EXE version metadata reads approximately **58ms and 38ms**.
- Subsequent reads approximately **0-1ms**.
- Original EXE SHA-256 matched bytes read from the mounted drive.
- Nested directory reading passed.
- Creation via the second client became visible without remounting.
- EXE data revision on the remote was observed and re-downloaded with a
  matching SHA-256. Polling uses native WinFsp directory notifications.
- File creation through the read-only mount was rejected.

Measurements are from a local loopback WebDAV test server, not the user's
actual Synology NAS. Do not advertise equivalent end-user performance yet.

## Remaining blockers before any production switch

1. **Write lifecycle:** implement WinFsp `Create`, `Write`, `Flush`, `Cleanup`,
   `Rename`, `Delete`, `SetFileSize` and atomic WebDAV `PUT`/`MOVE`.
2. **Office:** preserve case and real Excel filename; repeated-save semantics,
   safe temporary rename, server commit proof, visible owner files, and
   cross-PC lease behavior without reintroducing a second WinFsp layer.
3. **Recovery:** durable local upload journal, re-open after crash, writeback
   retries and no data loss on network outage.
4. **Cache coherency:** validate concurrent edits, ETag/mtime change, file
   deletion and same-name replacement while handles remain open; no invented
   permissions, ACLs, file content or stale data.
5. **Production NAS field test:** compare the same WebDAV endpoint and EXEs
   against RaiDrive with cold/warm folder entry, icon extraction, EXE launch,
   running-app DLL access, and Excel multi-save plus a second PC.
6. **Provider abstraction:** use a single Windows WinFsp layer for SFTP/FTP
   and cloud providers only when their dedicated backends reach the same
   safety level. Do not equate a one-off WebDAV test with complete parity.

**Release policy:** keep `pc/windows` unchanged until all writable regression
tests pass on a real NAS. This prototype lives on its own development branch.
