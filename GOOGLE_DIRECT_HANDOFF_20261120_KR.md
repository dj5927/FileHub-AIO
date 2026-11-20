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
