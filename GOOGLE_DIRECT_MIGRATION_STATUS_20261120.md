# FileHub AIO PC: Google direct-API migration / network display root cause

Date: 2026-11-20 KST

## What the live investigation proved

- The running Google Drive profile is still mounted through `rclone-filehub-google.exe`. The existing Google rclone config uses **shared** OAuth credentials (`client_id` absent).
- A READ-ONLY authenticated call to the official Drive v3 `files.list` endpoint, bypassing rclone and not requesting names or content, returned **HTTP 403 / rateLimitExceeded** on three attempts. These completed after ~319, 266 and 304 ms, so slow folder creation cannot safely be attributed to rclone alone: the currently shared Google OAuth quota is demonstrably throttled.
- The actual FileHub-owned Windows MountPoints2 entry for the mounted Google drive existed but had a **one-character share suffix** and **no `_LabelFromReg`**. `ExplorerDriveIcon.IsCurrentMountPointKey` erroneously rejected abbreviations shorter than 3 characters, even when they matched the exact GUID/session-owned server. Thus Explorer fell back to displaying `\\NASMount-...`.

## Changes implemented (v1.3.126)

1. The exact live GUID/session-owned UNC share is now matched even with a 1-character prefix of the saved profile name; foreign vendor or other server keys are still rejected.
2. A lightweight 10-second registry-only label lease repairs re-created MountPoints2 entries while the exact FileHub-owned mount is active. It stops on verified disconnect. No blocking Shell COM rename or Google API calls are involved.
3. `GoogleDirectApiClient` is a **new rclone-free** C# HTTP layer for Google Drive v3: shared/reused `HttpClient`, coalesced and cached folder listing, direct folder `files.create`, direct `files.update` rename, cache invalidation and bounded error-specific 403/429 retry.
4. `GoogleDirectOAuthSession` supports direct refresh for a **dedicated Google Desktop OAuth client** without starting rclone. Import from existing custom per-profile OAuth config is possible; the current shared-client profile intentionally fails closed instead of reusing throttled credentials.
5. An offline loopback mocked-API policy test validates the direct folder/query/rename path, one in-flight list request for concurrent callers, explicit 403 classification, and single-flight OAuth token refresh. CI fails if this regresses.

## Not yet completed / do NOT claim otherwise

- The normal user's Google Drive mount is **still rclone-based**. The new direct API code is not selected by normal profiles, and the user has NOT yet migrated the account to a dedicated OAuth app.
- No production direct WinFsp Google filesystem exists yet. Required: safe native data reads/writes, resumable uploads, file revision/cross-PC conflict semantics, Google's native Docs formats, directories, permissions, Drive Changes API invalidation, persistent cache/DB, full error/timeout recovery, native OAuth onboarding, UI settings and end-to-end Windows tests.
- In particular, Google's Drive v3 docs do not prove that HTTP `If-Match` protects binary uploads atomically; do not allow destructive existing-file writes without a verified concurrency contract.
- Speed target is fast and predictable *usable* directory access. Always ~1-second real connection cannot be guaranteed by cloud APIs even with a direct client.
- The 403 live probe did not create, edit or delete any Drive files. No existing OAuth files were modified.

## Next implementation gates

1. Register/provide a **FileHub-specific Google OAuth Desktop client** and add native authorization + protected token storage (without rclone); this needs account owner action in Google Cloud Console. Google scope approval/restrictions may apply.
2. Add a separate direct WinFsp pilot to prove root browse and folder creation on a throwaway Drive directory under the dedicated OAuth client; do not switch existing profiles automatically.
3. Implement tested content upload/read, journaling and cross-client conflicts; compare cold/warm latencies against RaiDrive with the same Google account. Promote only after no-data-loss regression passes.

Sources:
- https://developers.google.com/workspace/drive/api/guides/folder
- https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update
- https://developers.google.com/workspace/drive/api/guides/manage-changes
