# FileHub AIO PC - Google Direct API continuation (2026-11-20)

## Branch and scope

- Working branch: `pc/fix-upload-google-13119` (separate from stable `pc/windows`).
- Google native OAuth + direct API/WinFsp pilot remains opt-in. Existing rclone Google profiles are not migrated without an app-owned OAuth client and authorization.
- No real Google account files were altered by this work; all API/WinFsp tests used an isolated fake Google server.

## Implemented and verified in this continuation

1. Preserved in-progress existing-binary Drive revision API helper with a pinned previous revision and MD5/revision preflight. **Not wired into existing-file WinFsp writes**: Drive metadata preflight is not atomic compare-and-swap and concurrent-client clobber remains possible.
2. Fixed new Google file lifecycle: WinFsp `Flush` now persists the local stage only, instead of uploading incomplete bytes and rendering subsequent `Write` invalid.
3. Added per-handle Cleanup/Close tracking and only commits new content when the final Cleanup arrives. Cleans a successfully committed local stage immediately at final Cleanup because Windows may delay Close.
4. Extended real Windows WinFsp fake-Google integration lab with repeated Flush and partial writes before final commit.

## Verification

- `dotnet build NASMount.csproj -c Release --no-restore`: PASS (prior nullable/assembly warnings).
- `dotnet build LockBridge/LockBridge.csproj -c Release --no-restore`: PASS (prior warnings).
- `FileHubAIO.exe --google-direct-api-policy-test`: PASS (browser OAuth PKCE, list cache, create/rename, ranged read, new upload, revision pinning/conflict, 403 classification, DPAPI backup).
- `NASMountLockBridge.exe --direct-google-winfsp-policy-test`: PASS (native Windows mount, cached reads, Unicode folder create/rename, new file upload, repeated Flush-before-commit, rename/trash, failed 403 recovery).
- `git diff --check`: PASS.

## Remaining mandatory gates before shipping rclone-free Google as normal profile

1. Publisher must issue an actual Google Desktop OAuth client configuration and bundle `tools/google-oauth-desktop.json` (currently missing); approval/verification for requested Drive scope may be required.
2. Test live, disposable Drive account and real Chrome consent. Compare first-operation latency against RaiDrive with the same account.
3. Implement safe existing Office/Excel repeat-save policy. An API version check plus pinned revision is **not** a cross-PC atomic write lock. Existing content writes remain denied in WinFsp until verified conflict handling and recovery are complete.
4. Google-native Docs export, Drive Changes API invalidation, multi-client conflicts, crash/restart recovery/replay and locked Excel temp/rename behavior require live integration.
5. Do not publish a general-release ZIP based solely on this offline pilot; stable `pc/windows` remains unaffected.

## Continuation: existing file save laboratory and persistent recovery

- The native WinFsp driver now has an **explicitly gated** existing binary
  write mode (`allowExistingBinaryWrites`). It downloads and verifies the
  previous bytes (up to 128 MiB for this pilot), stages all writes locally,
  pins the old binary revision, updates the original Drive file ID, and
  verifies size plus MD5 before removing the local recovery journal.
- Real Windows WinFsp + isolated fake Google API tests now prove **two
  consecutive saves to the same existing file** and a stale-write rejection
  when another client changes the revision first. The second client's data
  remains intact; the uncommitted local edit remains in the recovery folder.
- This test **does not establish atomic cross-client compare-and-swap**.
  A different Google client could still race between revision preflight and
  the last upload bytes. Therefore the real-account host keeps existing
  binary writes **OFF by default** until production conflict contracts and
  live tests are established. Do not advertise Excel overwrite parity yet.
- Existing native file read/write stages expose incomplete journals after
  restart via `RemoteMount.GetIntegrityAlert` and the dashboard's
  `업로드 상태 확인`. Users can export local recovery copies to an explicitly
  selected folder without deleting the original journal or altering Drive.
  `FileHubAIO.exe --google-direct-recovery-export PROFILE_GUID DEST_FOLDER`
  also supports a manual offline rescue.
- Repeated Flush and delayed Close are handled with per-handle staging.
  Old read-cache blocks are dropped after confirmed edits.
- The publisher OAuth JSON remains **absent**: regular one-click Google
  login cannot become functional for all users without the publisher's own
  Google Cloud Desktop OAuth application. The app already supports browser
  account-picker/PKCE; do not bundle placeholder or third-party OAuth IDs.
- New validation markers:
  `GOOGLE_DIRECT_WINFSP_EXISTING_BINARY_REPEAT_SAVE_PASS`,
  `GOOGLE_DIRECT_TWO_CLIENT_STALE_WRITE_RECOVERY_PASS`,
  `GOOGLE_DIRECT_CRASH_JOURNAL_EXPORT_NO_OVERWRITE_PASS`.

## Final isolated pilot integration and network presentation

- The original Windows CI run `44730985257` on the Google direct experiment
  branch succeeded end-to-end, including a real WinFsp fake-API drive and
  uploading the separate EXPERIMENTAL ZIP artifact. This is NOT a signed or
  production-ready release.
- The native Google host now mounts as a **Windows network drive** using
  `FileSystemHost.Prefix`. The main GUI supplies a per-connection, GUID-owned
  random `NASMount-<id8>-<session>` network server and the configured friendly
  share name to its child bridge. The child validates ownership before
  mounting. This ensures Explorer icon and MountPoints2 label handling see
  the same server name as the GUI and avoids recycling a stale UNC server.
  Verified through actual Windows WinFsp mock integration.
- Added revision-pinning HTTP 403 failure test: the older Google object and
  unsaved local edit must both survive; no update PUT can run if pinning fails.
- An active native WinFsp writer's live staging JSON is not confused with
  a crash record; the manual recovery dialog refuses to export an active
  writer's stage until that writer closes.
- Main/bridge native policy suite pass; additionally run the full Windows CI
  on each pilot branch commit before recommending experimental binaries.
- Unresolved for production: publisher-issued Google Desktop OAuth client,
  live Google Drive consent, read/write latency benchmarks, cross-PC atomic
  overwrite/collision design, and true Excel Office lock/rename verification.
  Do not claim general-release completion or switch `pc/windows` to this
  experimental native client until those external gates pass.
