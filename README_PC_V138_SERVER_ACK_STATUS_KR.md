# FileHub AIO PC 1.3.138 - NAS acknowledged upload status

## Reported interoperability problem

After an Explorer copy of thousands of files into FileHub's WebDAV drive
finished, another WebDAV client (RaiDrive) sometimes deleted the files, but
some later reappeared. FileHub 1.3.137's Explorer copy completion represented
the Windows local staging cache, NOT the NAS completing every upload. Uploads
could still be queued, which makes cross-client early deletion race with PUT.

## Changes

- Retain 64-way concurrent WebDAV upload, with one successful conditional
  PUT as the new-file NAS acknowledgment. Do not introduce per-file HEAD,
  PROPFIND, or re-download.
- Extend the bridge local STATUS command with separate queued, uploading,
  failed, accepted, confirmed, and canceled counters.
- The PC parses those counters rather than treating every pending upload
  as an unknown office lock or recovery warning.
- Show a separate FileHub NAS progress bar and textual server-accepted counts,
  instead of implying that Explorer's own progress means NAS complete.
- Send a system-tray notification when the last outstanding upload receives
  the server acknowledgement, or retain an explicit failure/recovery warning.
- Never display success after losing the local status pipe during an active
  transfer, and do not turn normal queued uploads into corrupted recovery.
- Stop blindly retrying HTTP 412/423, detected conflicts or indeterminate PUT
  outcomes, which might otherwise recreate a file deleted by another client.
  Retain the failed upload's local recovery data instead.
- Count files intentionally canceled by FileHub's own DELETE separately
  from files that the NAS acknowledged as stored.

## Verified in a local Windows WinFsp / WebDAV lab

- 500 files copied to a WinFsp drive with 250ms added response latency.
  After Explorer copy was finished, the local bridge reported
  accepted=500 confirmed=281 queued=203 uploading=16, i.e. NAS transfer
  was NOT yet complete, as expected.
- All 500 files ultimately reached the WebDAV server; the status then became
  accepted=500 confirmed=500 queued=0 uploading=0 failed=0 SAFE.
- An EXTERNAL client issued WebDAV DELETE on the containing directory
  after the SAFE signal. Server returned HTTP 204; the directory was absent
  on both remote disk and mounted FileHub drive two seconds later.
- The actual Synology LAN/WAN has NOT been measured with this build.
- A queued write abandoned by a crash is retained for manual inspection;
  it is **not** automatically replayed and allowed to resurrect a path that
  another WebDAV client removed. The dedicated regression passed.

## RaiDrive 2026.10 comparison (read-only investigation)

- See docs/RAIDRIVE_2026_10_COMPARATIVE_RESEARCH.md.
- RaiDrive loads WinFsp and uses a .NET 10 mount service with local cache.
- Its private managed implementation is obfuscated. Exact Cleanup-to-PUT
  ordering cannot be asserted from binary strings alone.
- In the controlled FileHub WinFsp test with 250ms response latency:
  60 x 4KiB files took **17.246s** with blocking Cleanup; the same files
  took **0.230s** to stage and about **1.085s** for all remote acknowledgments
  with parallel PUTs. This rules out naive synchronous-per-file Cleanup as
  a default without sacrificing many-small-file throughput.

## Technical limitation

The Windows Explorer copy bar is not controlled by FileHub. This release
intentionally preserves the high-throughput write-behind model: Explorer
may finish before the actual NAS uploads. Only the FileHub NAS progress bar,
the pending/confirmed numbers, and the end-of-transfer notification reflect
the real HTTP responses. Users must wait for NAS completion before issuing
cross-client DELETEs. Full Explorer close-time blocking would serialize
ordinary sequential Explorer copies and can seriously reduce small-file
throughput; it is not falsely advertised as RaiDrive's private behavior.

Do not kill FileHub or shut down the PC while uploads are pending. Existing
Office save/ETag conflict handling, safe disconnect checks, and WebDAV
deletion remain. Android, SFTP, SMB, FTP and cloud provider paths unchanged.
