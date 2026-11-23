# FileHub AIO PC 1.3.135 — WebDAV small-file copy and folder deletion

## User-reported symptoms

- Twenty-three roughly 1 MB pictures took 40–50 seconds with 5–6 second
  inter-file stalls, compared with 2–3 seconds using RaiDrive.
- After copying, deleting the containing folder sometimes removed its
  contents but left the folder behind, or items reappeared on refresh.

## Verified causes in FileHub's direct WebDAV WinFsp path

- Each copy's local staged data and recovery JSON was forced to disk very
  frequently, and each successful small non-Office upload incurred a second
  full-body GET + SHA-256 verification before Explorer's copy completed.
- WebDAV collections can lack a strong ETag; treating a folder like a file
  made legitimate folder DELETE fail even when the server could delete it.
- PowerShell Remove-Item -Recurse -Force first issues SetBasicInfo to
  clear the containing directory's attributes. Previously the writable
  provider returned ACCESS_DENIED for folders at this stage, BEFORE the
  actual delete callback. This was reproduced consistently with WinFsp.

## Changes

- Store the first dirty recovery checkpoint durably; checkpoint sequential
  subsequent writes in 1 MiB batches rather than forcing a full JSON
  rewrite after every write block. Keep final pre-upload fsync.
- Use ordinary random-access staging for new files; durable checkpoint
  Flush(true) is retained, and unsent changes are never declared committed.
- For a NEW, non-Office small file: require conditional If-None-Match PUT,
  success response, confirmed exact remote size and strong ETag. Skip
  redundant full remote GET. Existing overwrites and Office files still
  require the original full hash verification.
- Permit deletion of an empty WebDAV collection without an ETag, after
  retrieving its current child list. Never delete the mount root. A strong
  collection ETag, if supplied, is still honored.
- Protect nonempty directories and keep conditional ETag protection for
  normal file DELETE. Invalidate the folder and its parent cache after
  confirmed successful DELETE.
- Allow harmless Windows directory SetBasicInfo callbacks for deletion,
  while keeping folder data and the server-side deletion checks unchanged.

## Reproduction and verification (local WebDAV server, NOT the user NAS)

- Windows WinFsp mounted at V: via strict conditional WebDAV proxy.
- 23 x 1 MiB mock JPG files copied in 1,723 ms; max one-file delay 129 ms.
- All 23 remote files confirmed before deletion.
- Recursive deletion of 23 files and folder: 923 ms, all removed.
- 2 seconds later neither the actual remote folder nor V: contained it.
- Five repeated 6-file recursive folder deletion checks passed after the
  directory SetBasicInfo fix; all folders also absent on server.
- LockBridge self-test and strict WebDAV write-store lab (concurrent
  modification, interrupted upload recovery and Excel repeated saves)
  passed. Actual latency on Synology/QNAP/remote WAN needs field QA.

## Scope and limitations

- Applies only to direct WebDAV/WinFsp connection path. SMB, SFTP, FTP and
  cloud-provider adapters need separate diagnosis if the same symptom occurs.
- Windows File Explorer and NAS network latency can exceed loopback results.
- We do NOT remove conditional ETag protection from concurrent overwrites
  or Office documents, and an unsuccessful upload is not reported as saved.
