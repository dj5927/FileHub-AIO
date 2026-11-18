# RaiDrive 2026.10.0 live Google Drive / FileHub AIO comparison

Date: 2026-11-19 (KST)
Scope: read-only inspection of the user's active Windows environment. No RaiDrive configuration, files, or drive mappings were changed.

## Active software evidence

- RaiDrive installed from the user-specified `D:\GPT\NAS_MOUNT\새 폴더\RaiDrive.Mount_2026.10.0_x64.exe`.
- The actually running app is `C:\Program Files\OpenBoxLab\RaiDrive Mount\RaiDrive.Mount.exe`, version 2026.10.0; `RaiDriveMountService` was running.
- Both running `RaiDrive.Mount.Service.x64.exe` processes loaded `winfsp-x64.dll` **and** `e_sqlite3.dll`. This establishes actual WinFsp and SQLite library use, not any proprietary cache algorithm.
- RaiDrive installed components include a Shell Extension and NotifyHelper. Its cache directory is present under `%ProgramData%\OpenBoxLab\RaiDrive Mount\Cache`.
- The RaiDrive Google mapped drive was `Y:` (network drive). The FileHub rclone Google drive was `W:`. This comparison used these live mappings.
- FileHub was running **v1.3.100**, not the newly built v1.3.101. Its log reported `dir-cache=20s`, `write-back=0s`, `pacer=250ms burst2`, disabled Google About quota calls.

## Non-mutating latency checks (one sample per operation)

| Path | `DriveInfo` type/ready/size | Enumerate up to 30 root entries |
|---|---:|---:|
| RaiDrive Google `Y:` | 58 ms | 11 ms, completed 30 entries |
| FileHub Google `W:` | 57 ms | **greater than 9 seconds**, timed out |

The test enumerated names in memory only, did not print names or read file contents. A separate read-only local rclone RC `operations/list` request for the *same FileHub Google backend*, bypassing Explorer/WinFsp, **timed out after 12 seconds**. `core/pid`, `core/stats`, and `vfs/stats` on that RC server responded rapidly (about 1-76 ms).

**Interpretation:** The slow path is not limited to Windows Explorer or the WinFsp volume-info callback. Google directory listing inside the rclone backend/request chain can stall independently. A single comparison does not establish a statistical average or prove whether Google API latency, an rclone backend lock, or throttling is the immediate cause.

## Public documentation corroboration

- RaiDrive 2026.10.0 release notes explicitly introduce WinFsp and mention stronger local cache/database security. Source: https://www.raidrive.com/release-notes/2026.10.0
- RaiDrive documentation says downloads/uploads flow through local disk cache and its **content-cache retention defaults** are 30 minutes for each direction. This is not a direct statement about directory-metadata caching. Source: https://docs.raidrive.com/ko/gui/settings/cache/
- rclone Google Drive docs warn that the built-in shared OAuth client ID is being retired during 2026; custom client IDs are required and API requests have per-client rate limits. Source: https://rclone.org/drive/
- The FileHub Google rclone config was checked for **presence of fields only**, with no token values exposed. Its Google remote had a token but **no custom `client_id`**. This is an important service-continuity and rate-limiting risk, but does not independently prove every observed timeout was caused by quota.

## Relevant FileHub v1.3.101 changes already prepared

- `--dir-cache-time 30m` rather than 20s for Google only.
- `--poll-interval 1m` for asynchronous Google change notifications rather than disabling polling.
- Google pacer adjusted from 250ms / burst 2 to 100ms / burst 8.
- Google write-back changed from 0s to 5s. Normal shutdown still waits for VFS uploads.
- Google label repair waits for the provider-created MountPoints2 key and sets `_LabelFromReg` only, without synchronous Explorer COM rename.
- v1.3.100's `--disable About` remains to prevent remote quota lookups during WinFsp volume queries.
- Version 1.3.101 ZIP is at `C:\Users\UserK\Downloads\FileHub-AIO-PC-1.3.101-win-x64.zip`, built and checked, **but not yet installed or benchmarked**. Code commit `607b7f2`.

## Follow-up implementation priorities

1. Fix the OAuth service identity: register a dedicated FileHub Google OAuth client (requires actual Google developer configuration and credentials; **do not fabricate credentials**). Migration must preserve existing user tokens until a deliberate reauthorization, avoid premature revocation, and never log client secrets/tokens.
2. Measure cold and warm `operations/list` vs WinFsp root enumeration after v1.3.101, preserving user files. Repeat under the same root/account when possible and log timings, not filenames.
3. Consider persistent offline directory-metadata caching / async prefetch independent from Explorer callbacks if API latency persists. Do not pretend RaiDrive's proprietary internal algorithm is known from the presence of SQLite DLLs.
4. Verify stale entry handling, file create/open/rename/read and write-back correctness, directory change coherence and no unexpected error popups, then publish only after real-world validation.

Status: **RaiDrive 2026 live process/driver inspection and one-pass timing comparison completed; FileHub equivalence NOT verified**.
