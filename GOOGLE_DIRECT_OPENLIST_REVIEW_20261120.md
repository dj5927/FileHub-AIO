# FileHub AIO Google Direct Pilot / OpenList evaluation
Date: 2026-11-20

## User requirements
- No rclone in the Google read/write/mount/auth path.
- Windows Explorer drive with Chrome Google account-picker and consent. No end-user API key or OAuth JSON.
- Fast and predictable first real file operations, not a mounted-but-unusable drive that blocks.
- No regressions to other NAS/cloud providers, spreadsheet save or lock handling.

## OpenList evaluation
- https://github.com/OpenListTeam/OpenList is a Go/AList-derived cloud storage server supporting Google Drive, TeraBox, FTP/SFTP and WebDAV, including a WebDAV server.
- OpenList core is NOT itself a FileHub Windows WinFsp callback provider. Using it here requires an extra localhost WebDAV layer between WinFsp and Google.
- OpenList Desktop's published Windows mounting example relies on rclone. Substituting OpenList Desktop does not meet the no-rclone requirement.
- OpenList's Google browser authorization documentation includes a process to retrieve tokens for storage setup. This is not automatically the requested one-click FileHub Chrome login.
- OpenList core uses AGPL-3.0; embedding or modifying it for distribution requires an explicit open-source licensing strategy.
- OpenList is useful as an optional adapter or reference for other providers, but cannot be assumed faster without comparison on first operation and Office writes.

Official docs:
- https://github.com/OpenListTeam/OpenList
- https://doc.oplist.org/guide/drivers/google_drive
- https://openlistteam.github.io/docs/guide/webdav.html
- https://github.com/OpenListTeam/openlist-desktop/blob/main/README_en.md

## Direct Google pilot implemented — no rclone in these components
- Native browser OAuth Authorization Code + PKCE, loopback callback, anti-CSRF state, direct token exchange and direct Drive API token verification.
- An app publisher can bundle tools/google-oauth-desktop.json (Desktop OAuth client) to remove the credential fields for all ordinary users. No valid app-owned Google OAuth client is currently bundled. It must be registered by the publisher; it cannot be fabricated.
- Direct Google REST client: pooled HttpClient, metadata caching, single-flight folder list, folder creation/rename, ranged reads, new-file-only resumable upload, direct OAuth refresh.
- Separate real Windows WinFsp pilot: file/folder metadata, list, read, optional new folder and rename, via direct Google API. No synthetic NASMount UNC. Real production Google mounts are not silently switched.
- Offline REAL WinFsp integration mounts a mock Google API and proves file list/read + Hangul folder creation/rename without rclone.
- Dedicated API policy tests prove ranged read, new-only resumable upload protocol, token refresh and error classification.

## Production migration NOT completed
- Publisher-owned Desktop OAuth credentials not yet registered/bundled; ordinary users cannot yet receive a universal one-click login without them.
- Native OAuth is integrated for custom-client login; legacy shared-client option remains separate.
- Normal RemoteMount.Connect still uses rclone for Google. Pilot command is isolated, without automatic switchover.
- Direct WinFsp still needs create/write/flush, durable upload/recovery, existing-file conflict safeguards, Google Changes API invalidation, Google-native Docs export, permissions and load tests.
- No real user Google data was modified by mock integration tests. The existing user's W: drive must not be interrupted.
- No legitimate claim of stable 1-second first operations until real dedicated-client benchmarks pass.

## Commands / regression
- FileHubAIO.exe --google-direct-api-policy-test
- NASMountLockBridge.exe --direct-google-winfsp-policy-test
- Isolated read-only pilot with custom-client OAuth profile: NASMountLockBridge.exe --direct-google-profile-readonly-pilot PROFILE_GUID X:

## Decision
Continue FileHub-native direct Google WinFsp as primary. Do not copy OpenList AGPL code or use OpenList Desktop as a rclone replacement. Reassess OpenList separately for additional storage providers.

## Continuation: direct WinFsp file operations / browser authentication

- Direct Google WinFsp is now routed through FileHub's normal profile
  Connect / Disconnect / health / transfer safety paths ONLY after the profile
  has completed FileHub's native browser OAuth authorization. The legacy
  shared-client account remains on the last working engine; there is no
  silent migration or file modification on startup.
- Browser-based Google OAuth stores token/refresh credentials in a separate
  Windows per-user DPAPI encrypted file. Previous native account grants can
  be restored; routine OAuth refreshes do not overwrite the previous account
  backup. The old rclone profile config is left untouched for rollback.
- The native Google backend can create durable local write stages for NEW
  ordinary binary files, do resumable upload using a pre-generated Drive ID,
  and verify the final ID, length and MD5 before clearing local recovery.
  HTTP 403 or an uncertain response preserves the local .data and .json
  record, and a safe QUIT is refused while recovery remains.
- Existing files can be read, renamed, moved, and moved to reversible Google
  Trash with metadata version preflight. Permanent DELETE is not issued.
- Existing file **content overwrite is still deliberately prohibited**.
  Google Drive v3 does not document a demonstrated atomic compare-and-swap
  for files.update binary content; enabling blind overwrite would recreate
  the Excel concurrent-edit and unsaved-file problems previously fixed on
  NAS. A safe Office write policy and real-account integration are required.
- The Google file metadata cache now expires its WinFsp name-to-ID lookup
  within 30 seconds instead of retaining stale names indefinitely.
- New real Windows WinFsp integration tests cover reading, Korean folder
  creation/rename, new-file upload, rename, reversible Trash and an injected
  Google 403 error that must preserve local recovery bytes.
- The pilot branch has a dedicated Windows CI workflow:
  .github/workflows/direct-google-pilot.yml. Production publish workflow
  and Android remain untouched.

## Mandatory publisher-controlled integration before general release

1. Create/register a FileHub-owned Google OAuth **Desktop** client under the
   publisher's Google Cloud project, enabling Google Drive API. Configure
   OAuth consent screen scopes, testing/verification and authorized user
   access according to Google's policies. The full drive scope is restricted
   and may require app verification/security assessment.
2. Bundle the officially issued Desktop OAuth configuration at
   tools/google-oauth-desktop.json. FileHub automatically hides all developer
   fields and lets an end user click Google connect -> Chrome account picker
   -> consent, and persists encrypted tokens for subsequent logins.
3. Validate against a disposable folder in a REAL Google Drive account:
   first list latency, new folder, rename, upload, download, renaming and
   Trash, reconnect and expired token refresh. Simulated API tests alone
   cannot establish live Google response behavior.
4. Implement/policy-test protected repeat-save of an EXISTING binary file,
   including two-client edits, Excel tmp/lock files, conflict recovery,
   crash during upload, Google Docs native MIME types and Google changes.
   Until then do not claim complete RaiDrive file editing parity.
