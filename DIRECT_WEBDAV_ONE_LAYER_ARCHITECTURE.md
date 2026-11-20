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

## Writable single-WinFsp development progress — 2026-11-20

- `LockBridge/DirectWebDavWriteStore.cs`: uses a durable, local, fsynced
  JSON+data journal per write handle; writes only via WebDAV with strong
  `If-Match` / `If-None-Match` preconditions. Before any document writes, the
  client proves that its server actually rejects duplicate creations and
  mismatched ETags. Fails closed otherwise. Upload verification checks the
  remote length/ETag and file checksum for the tested small files.
- `LockBridge/DirectWebDavWritableFileSystem.cs`: Windows WinFsp Create/Open,
  Read/Write/Flush/SetFileSize, Cleanup/Close, server MOVE with `Overwrite:F`,
  guarded DELETE, directory create, local dirty-file overlay, hide internal
  `.nasmount.lock` markers. Single WinFsp layer; no rclone mount underneath.
- Existing `CooperativeLocks` / `LeaseCoordinator` is reused for Office
  ownership on WinFsp document Open/Create, and document rename edges.
  Server must also prove that duplicate `MKCOL` is rejected; otherwise it
  cannot safely arbitrate between two FileHub writers.
- In an actual Excel COM application on a mounted loopback WinFsp WebDAV
  drive, SaveAs + two later Save calls completed and the third saved workbook
  was reopened from the server with the exact last cell value.
- While the Excel workbook was still open, an independent FileHub instance
  attempting the same document lock was rejected. Independent clients could
  reacquire a cleanly released lease in a separate two-client test.
- Copy-to-drive EXE contents matched the source SHA256; its version info
  loaded in ~36-65ms in loopback tests. File create, three text saves,
  safe MOVE, conditional DELETE passed.
- `DirectWebDavWriteStore.RecoverPending`: local manifest is parsed after
  restart and a write is retried only if the server's current strong ETag
  still matches the previously edited revision. If the PUT committed just
  before a lost response, remote SHA256 matching can confirm the commit.
  The recovery file is retained on conflict, not overwritten. Documents
  require an actual document lease before automatic recovery.
- The development branch includes opt-in pilot modes in the LockBridge EXE:
  `--direct-webdav-profile-readonly-pilot <saved-profile-guid> Z:` and
  `--direct-webdav-profile-writable-pilot <saved-profile-guid> Z:`. Only a
  previously saved WebDAV profile may authorize the remote endpoint. Both
  modes use its exact hostname, port, share path and existing TLS validation.
  The password comes from Windows Credential Manager or an inherited
  `NASMOUNT_BRIDGE_PASSWORD` field, never the command line. A free drive
  letter is required; existing FileHub mounts and GUI connections are not
  redirected. No production profile was modified.
- The actual saved WebDAV profile on the development PC has no remembered
  password; a read-only live-NAS pilot was rejected before mounting. This is
  an authorization blocker, not a measured failure of the direct engine.
- A local, interactive **read-only** tester is included at
  `tests/start_direct_webdav_readonly_pilot.ps1`: it prompts for a missing
  password using Windows SecureString and inherits it only into a new child
  process. This test does not write to NAS, replace an existing drive or store
  the plaintext secret. The user can explicitly run it when ready; it was not
  run automatically because no credentials were supplied to this session.
- `tests/direct_webdav_write_store_lab.ps1` and
  `tests/direct_webdav_writable_winfsp_lab.ps1` use a disposable loopback
  WebDAV server. `tests/strict_webdav_conditional_proxy.py` is test-only:
  it enforces conditional PUT/MOVE/DELETE and exclusive MKCOL semantics,
  which the stock rclone test WebDAV server **did not** enforce. Never
  assume a production NAS supports these features without the same probe.

## Remaining blockers before any production switch

1. **Production UI and lifecycle:** authenticated HTTPS WebDAV exists in the
   opt-in pilot CLI, but normal GUI Connect, status/OTA, graceful reconnect,
   pending-write-safe disconnect and real-NAS field verification are NOT yet
   integrated. The current saved WebDAV profile has no remembered credential,
   so the pilot cannot authenticate without an interactive credential.
2. **Office cross-client field tests:** verify real Synology/QNAP/WebDAV
   servers actually enforce conditional writes and exclusive `MKCOL`, then
   test two real Windows PCs with Excel, third-party readers and clients.
3. **Recovery UX:** local journal and guarded replay work in a controlled
   failure lab; manual conflict-copy selection, abandoned lease cleanup,
   outage timeouts, and complete crash/kill/reboot scenarios still require
   real-device proof. There is no safe user-facing recovery dialog yet.
4. **Cache coherency:** expand tests for concurrent edits, ETag/mtime change, file
   deletion and same-name replacement while handles remain open; no invented
   permissions, ACLs, file content or stale data. ACLs and time-set behavior
   must reflect actual NAS capabilities, not simulated local ACLs.
5. **Production NAS field test:** compare the same WebDAV endpoint and EXEs
   against RaiDrive with cold/warm folder entry, icon extraction, EXE launch,
   running-app DLL access, and Excel multi-save plus a second PC.
6. **Provider abstraction:** use a single Windows WinFsp layer for SFTP/FTP
   and cloud providers only when their dedicated backends reach the same
   safety level. Do not equate a one-off WebDAV test with complete parity.

**Release policy:** keep `pc/windows` unchanged until all writable regression
tests pass on a real NAS. This prototype lives on its own development branch.
