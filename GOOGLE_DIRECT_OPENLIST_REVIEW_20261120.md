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
