FileHub AIO PC 1.3.136

WebDAV (Synology) real-world 405 / 412 and slow-copy follow-up.

1. A Windows DELETE-only open (e.g. 0x10080) no longer triggers a full remote
   download and recovery journal as though the file were being edited.
2. User-initiated file/folder deletion retrieves fresh remote metadata and
   sends ordinary WebDAV DELETE without an outdated If-Match ETag.
   An empty-folder check and root protection remain in place.
3. Office's internal conditional replacement deletion stays protected with
   a strong If-Match precondition. Existing workbook conflict/recovery tests pass.
4. Existing-directory MKCOL 405 is translated to an expected name collision
   rather than a generic I/O error.
5. If a NAS rejects progressive stage upload, FileHub switches to direct PUT
   for the rest of the mount instead of repeating progressive failure per file.
6. Added privacy-safe per-copy and per-delete timing diagnostics to identify
   network PUT / PROPFIND / journal bottlenecks on real NAS connections.

Lab: Windows WinFsp, strict conditional WebDAV proxy configured to return
HTTP 412 on If-Match DELETE, and reject progressive staging:
23 x 1 MiB + 4 x 3 MiB copied; all 27 removed with containing directory;
no folder reappearance after 2 seconds. Only one progressive disable event.
Independent conditional-write/Office repeat-save/crash recovery lab passed.

Important: Loopback timings are NOT a measured Synology / internet result.
If real-network copy latency persists, compare new COPY_TIMING_MS lines.
No Android, Google Drive, SMB, FTP or SFTP behavior changed.
