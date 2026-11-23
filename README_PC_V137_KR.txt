FileHub AIO PC v1.3.137 - WebDAV bulk-copy performance update

New-file copy and move use one uniform fast-copy path regardless of file
extension or size:
- Windows writes to a local operating-system write cache.
- Files are queued for upload when the Windows file handle closes.
- Up to 64 background WebDAV PUT operations may run concurrently.
- No separate HEAD, PROPFIND, re-download or content hash check is made for
  an ordinarily created file after the WebDAV server accepts the PUT.
- In an accepted PUT lacking ETag the server is still considered to have
  acknowledged the upload. No synthetic ETag is invented.
- Bulk copy does not repeat progressive upload negotiation per file.
- Buffered staging avoids an initial durable recovery manifest for incomplete
  copies. A queued-file recovery manifest is written before Explorer closes.
- Files that the server has not yet received remain queued. Disconnect/exit
  readiness remains blocked while writes are pending. Pending failures are
  logged and local recovery data retained.
- The Windows copy dialog finishing is a LOCAL CACHE acknowledgement, NOT
  evidence that the entire NAS batch has been transmitted. Do not power off
  the PC or terminate the FileHub process while uploads are pending.
- For a forced power loss before the uploader durably flushes its local
  stage, the newest files may be lost. This is an explicit trade-off of
  buffering for throughput. Upload failures are not reported as successes.

Existing file edits, server-side moves, and Excel's ongoing editing
transactions remain separate Windows filesystem operations.

Repeatable local WinFsp/WebDAV performance and regression tests
-------------------------------------------------------------
NAS response delay simulated: 250 ms per response, with per-path concurrency.
2,780 files, 305,800,000 bytes:
  Windows local-copy window: 43.174 s
  All files received by simulated NAS: 45.336 s
  Upload errors: 0, journals remaining: 0, sample content hashes: PASS

3,000 files x 1 KiB with buffered per-file journal:
  Windows local-copy window: 24.418 s
  All files received: ~44.845 s
  Upload errors: 0, pending journals: 0

3,000 files x 1 KiB, final fast-copy code with initial journal omitted:
  Windows local-copy window: 15.682 s
  All files received: 46.359 s (with 250 ms test response latency)
  Upload failures: 0, pending manifests after completion: 0
  Sample content hashes: 5/5 PASS

The 3,000-file local-copy window improved, but the 10-second goal was
not reached. All-file NAS delivery time depends on network and server.

Strict WebDAV conditional-overwrite, Excel repeated-save, conflict, backup,
crash-replay and integrity tests: passed. Real Synology WAN speed depends
on NAS hardware, RTT and server concurrency; local measurements are not
proof of matching RaiDrive on a specific connection.
