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

## 21:34 KST observed rclone regression and direct-only correction

- Real user log from 2026-11-20 21:31 showed `mode=shared`, then
  `rclone-filehub-goog has been started`, then 35,333ms until Windows
  mount visible. This proves the previous experimental ZIP *did not*
  choose the newly implemented native engine for that existing Google
  profile. Root cause: GUI and mount dispatcher silently fell back to
  historical rclone when no FileHub-native DPAPI grant existed.
- **Corrected Google mount policy:** `GoogleDrive` routes only to
  `ConnectDirectGoogleAsync`. There is now NO legacy Google rclone
  fallback, whether credentials are missing or the old shared OAuth
  remains present. On first explicit connect a browser PKCE consent is
  requested. Auto-connect without this grant waits without opening Chrome.
- Removed the direct engine's interim reading of `google-auth/*.conf`.
  The old rclone OAuth and files are preserved as recovery/backups, but
  can no longer be misinterpreted as authorization for the FileHub app.
- Browser consent requires a **publisher-owned Google Desktop OAuth
  client**, via the packaged `tools/google-oauth-desktop.json` or one-time
  Desktop JSON import. The publisher file is still NOT in this tree.
  An OAuth client cannot be generated or borrowed from another app
  automatically; the connection is intentionally blocked until issued.
  This is a prerequisite, not evidence of a successful live connection.
- Google REST file operations use a 12-second HTTP timeout, no HTTP
  retry on the real WinFsp host and an 8-request concurrency cap with
  2-second queue admission, preventing unlimited WinFsp request buildup.
- Google direct experimental CI ZIP explicitly omits
  `tools/rclone-filehub-google.exe`; regular rclone/terabox stay because
  **non-Google** providers still require them. CI requires
  `GOOGLE_NATIVE_ONLY_NO_RCLONE_FALLBACK_POLICY_PASS` and the original
  fake-Google real Windows WinFsp regression suite.
- Mandatory future live gates: actual browser grant, first-directory
  latency, one-hour read/reconnect stability, and safe native Office
  overwrite. Never describe these as completed on a fake API test alone.

## Browser-only OAuth UI correction (2026-11-20)

- The user-facing `GoogleDriveLoginDialog` was rewritten: it contains only
  the Google account-connection action, an explanatory label, a status label,
  and Cancel. No Client ID, Client Secret, OAuth JSON import, Google Cloud
  project creation link, or shared-rclone consent appears in this dialog.
- The app loads publisher-owned Desktop OAuth configuration from its packaged
  `tools/google-oauth-desktop.json`, or from an existing encrypted native
  FileHub authorization for reauthorization. User data does not supply the
  developer identity; missing publisher configuration is explicitly shown
  as a **packaging error**, with no user action to generate credentials.
- Browser approval continues to use PKCE and a localhost callback, and
  direct Google Drive API + WinFsp; no Google rclone fallback.
- The experimental ZIP must not be published without a real publisher OAuth
  client. GitHub Actions now consumes a publisher-managed credential secret
  and fails packaging if absent or malformed. This is an intentional fail-
  closed gate: no more experimental builds that visibly ask ordinary users
  to issue Google credentials.
- `--google-login-ui-policy-test` checks the no-developer-controls contract.
  `--google-publisher-oauth-policy-test` checks presence/validity of the
  packaged Desktop OAuth client and refuses release when missing.
- Google official docs explicitly require an application OAuth identity;
  OpenList's easy Chrome UX uses its own app/server credentials. Their
  OAuth client or public broker MUST NOT be silently reused as FileHub.
- **Outstanding external gate:** a publisher-owned Google OAuth client was
  not found in this environment or the repository. Therefore no valid live
  Chrome authentication can be claimed until that credential is supplied
  through the publisher's deployment channel and tested against Google.

## RaiDrive-like single-path cleanup and official OpenList broker review

User-facing contract: **Add Google Drive -> Chrome account picker -> approve -> ready**.
No developer credentials, JSON pickers, manual token copy, rclone fallback,
or surprise browser windows at startup. Regular WebDAV/TeraBox providers
are unchanged; Google runs through its own direct API and WinFsp mount only.

The official OpenList-APIPages Google flow was inspected directly from
`OpenListTeam/OpenList-APIPages/src/driver/googleui_oa.ts` and its frontend
`frontend/src/pages/TokenPage.tsx` (read-only checkout outside the repo).
It exchanges Google codes server-side and redirects to the OpenList **own**
web page with `/#<base64-json>` containing the tokens. The page displays
the tokens. No documented, authenticated return URI or secure local app
handoff was implemented in these sources. Consequently launching the
public web page from FileHub does **not** automatically complete native
login, and requiring the customer to copy tokens would violate the UX.
We did not automate Chrome DevTools, scrape other tabs, reuse private
third-party OAuth keys, or leak tokens through a redirect workaround.
OpenList public service permission and cross-client quotas remain unproven.
Its source is AGPL-3.0, so incorporating its code may carry license duties.

Runtime improvements in this continuation:

- Google REST transport now holds its max-8 request semaphore **until the
  response body is fully buffered**, not just headers, and normalizes timeout
  and transport failures into recoverable I/O errors.
- After 3 consecutive network transport errors, a 5-second fail-fast window
  prevents an offline Google session from starving Windows Explorer; no
  uncapped reattempts are made by the WinFsp host.
- `GOOGLE_NATIVE_EXPLORER_REQUEST_CAP_AND_FAST_FAIL_PASS` exercises 24
  simultaneous fake metadata reads and a transport outage with no network
  access or user data; actual Windows WinFsp integration remains tested.
- The Google mount command now honors the profile's read-only/new-file-write
  selection; previously it launched the writable WinFsp command regardless
  of what was selected in the connection editor. The editor describes the
  actual supported scope: folder/new-file write, not Office overwrite.
- The editor's Google text is concise and uses a single Chrome-account
  connection description; all NAS server/password fields remain hidden for
  the Google profile.
- If a clean browser grant is unavailable because publisher OAuth was not
  packaged, the GUI does not display a disabled developer dialog. It shows
  one short app-update message instead. No end-user setup can solve a
  missing publisher OAuth identity.
- GitHub native tests always run. Experimental release packaging is skipped
  (not falsely reported as a runnable Google ZIP) until the publisher
  `FILEHUB_GOOGLE_DESKTOP_OAUTH_JSON_BASE64` secret is configured. When
  set, invalid credentials still fail the package check.

External completion gates remain actual publisher OAuth app identity or an
explicitly supported/approved OpenList desktop callback contract, live
Chrome login, repeated large directory and file I/O benchmarks, and
multi-PC Office conflict recovery. Do not ship/claim a finished Google
production feature without these gates.

## Latest direct-engine reliability pass (2026-11-21)

1. Verified RaiDrive's **documented user experience** is Add Google Drive,
   browser Google login, approve, Explorer mount. The RaiDrive engine is
   proprietary; its internal client cannot simply be copied or packaged.
2. Inspected OpenList APIPages implementation and public-service docs. Its
   shared public OAuth requires obtaining the tokens from an OpenList web
   page. There is no documented supported desktop app callback. No Chrome
   debugging, browser-token scraping, copied OAuth identity, invisible
   clipboard watching or hidden OpenList proxy was added.
3. Native Google folder metadata now retains previously verified listing
   for a maximum of **five minutes** if the Google network is temporarily
   unavailable or rate limited. Permanent auth/permission 403 is *not*
   disguised as a stale cached directory, and explicit mutations invalidate
   the previous list. Regression marker:
   `GOOGLE_NATIVE_STALE_DIRECTORY_OFFLINE_VISIBLE_PASS`.
4. Native ranged reads stream with an upper bound rather than loading the
   response body before checking its size. If Google or a proxy ignores a
   Range request on a large file, the client stops before allocating an
   unbounded download. Regression marker:
   `GOOGLE_NATIVE_BOUNDED_RANGE_READ_NO_FULL_FILE_BUFFER_PASS`.
5. Added a real-account test command (explicit only):
   `FileHubAIO.exe --google-direct-live-smoke-test PROFILE_GUID`.
   It requires a valid native FileHub Chrome OAuth grant. It creates ONE
   unique temporary folder, tests root listing, upload, SHA256 read-back,
   rename and always attempts to trash ONLY its own test folder. Logs
   contain operation timing and result categories, never token/file IDs.
   This was compiled and reviewed, but could **not** be executed on the
   real Google Drive because the publisher OAuth identity is not provided.
6. The accessible PC has one old shared-rclone credential file and zero
   native FileHub grants. Its shared-client refresh token was **not**
   silently reused for native impersonation, since Google 403 rate limits
   and the user's app consent would remain mismatched.
7. Current official Google documentation classifies full Drive scope as
   **restricted**. FileHub needs the publisher app's OAuth configuration
   and appropriate testing/verification; no code change can issue a real
   Google app identity on behalf of a missing authorized Cloud project.

Do not claim 100% product completion, publish a working-download link,
or switch stable `pc/windows` until real Chrome consent, sustained live
read/write, disconnect/reconnect and Office save tests all pass.

## 2026-11-21 continuation: strict ranged-file integrity and truthful OAuth callback

- Google's ranged read implementation now rejects a `416` response, a
  partial response with an incomplete/unexpected Content-Range, or a body
  shorter than the requested bytes. These previously risked looking like
  a normal Windows EOF even while the file was changing remotely.
  The affected range is NOT returned to WinFsp as valid data.
- New isolated fake-server test cases: `range_truncated`,
  `range_shortbody`, `range_stale`. Marker:
  `GOOGLE_NATIVE_SHORT_RANGE_AND_STALE_FILE_FAIL_CLOSED_PASS`.
- Chrome loopback callback no longer shows "Google login complete" merely
  on receiving a code. FileHub exchanges the code for a valid access AND
  refresh token first, and only then sends the browser's HTTP 200 success
  confirmation. Invalid-grant exchanges show a failure response.
- Added fully isolated real-127.0.0.1 callback tests with a fake Google
  token endpoint: one successful grant and one rejected grant, checking
  browser status before/after the controlled exchange. Marker:
  `GOOGLE_BROWSER_LOOPBACK_TOKEN_EXCHANGE_BEFORE_SUCCESS_PASS`.
- Each loopback callback connection gets a five-second request-line timeout
  so an unrelated local connection cannot block the Chrome sign-in window.
- Both main .NET Windows app and native WinFsp bridge pass offline tests;
  Google live OAuth was not executed because a publisher-owned OAuth Client
  ID is still absent. The user's existing legacy Google OAuth credentials
  must NOT be copied, rewritten or treated as a native FileHub login.

## Native Chrome login persistence semantics (continuation)

- Chrome's localhost success page now means more than an OAuth code being
  accepted: FileHub has exchanged the code, made its read-only Drive About
  permission check and durably saved the native OAuth grant via DPAPI.
- `AuthorizeAsync` accepts a publisher-controlled completion callback for
  Drive verification and encrypted persistence. The GUI uses it before the
  local browser's HTTP 200 success response. Failed refresh-grant storage
  returns an error page rather than falsely reporting a connected account.
- Isolated fake browser/Google token tests cover success, rejected consent
  and an injected failure during final grant persistence. Marker:
  `GOOGLE_BROWSER_GRANT_DPAPI_SAVE_BEFORE_SUCCESS_PASS`.
- No publisher OAuth identity was invented or copied. The final real-account
  Chrome login, sustained I/O, Office saves and working download remain
  blocked and MUST NOT be described as production-ready.

## 2026-11-21 goal continuation: unexpected Google API/proxy replies

- The native metadata transport now buffers at most 8 MiB of response data
  while maintaining the eight-request concurrency lease. Unlike the prior
  unbounded `ResponseContentRead`, this avoids runaway memory pressure if
  a gateway answers metadata calls with a huge HTML page or binary body.
- HTTP non-2xx responses with missing, invalid or non-JSON bodies are now
  reported as categorized Google HTTP I/O errors, preserving the status
  without exposing the response body or crashing WinFsp JSON parsing.
- A malformed successful response, invalid file item, absent/non-array
  `files` listing, or invalid page token is no longer treated as an empty
  Google directory. It fails closed and can use only the prior *verified*
  stale directory cache under the existing transient-error policy.
- Fake HTTP regression cases include gateway 502 HTML, HTTP 200 non-JSON,
  8 MiB+1 oversized body, missing item name, missing `files`, non-array
  `files`, and numeric page token. Marker:
  `GOOGLE_NATIVE_INVALID_HTTP_JSON_AND_OVERSIZED_METADATA_FAIL_CLOSED_PASS`.
- Current local Windows main app and WinFsp bridge builds and all direct
  fake-API integration tests pass. Publisher OAuth still does not exist in
  this environment. A successful CI must NOT be described as successful
  live Google sign-in or an installable ZIP.

## Google OAuth refresh bounded-error pass

- Native refresh requests now use `ResponseHeadersRead`, check status first,
  and buffer a maximum of 256 KiB of successful OAuth JSON. This avoids
  buffering an unbounded error page on the Explorer callback path.
- Network failures, request timeouts, malformed refresh JSON, wrong JSON
  types and missing access tokens are normalized to recoverable `IOException`
  instead of allowing an unexpected exception to escape WinFsp callbacks.
- A separate fake token server exercises HTTP 502/HTML, malformed JSON,
  missing token, network failure, timeout and oversized response. Marker:
  `GOOGLE_NATIVE_OAUTH_REFRESH_INVALID_RESPONSE_FAIL_CLOSED_PASS`.
- Only the existing FileHub-native Desktop OAuth client can refresh its own
  token. There is still NO implicit rclone credential import or fallback.

## Publisher login + tested local ZIP path (2026-11-21)

- Added `tools/Setup-GooglePublisherOAuth.cmd`/`.ps1`: one-time **publisher**
  Google Cloud Desktop OAuth JSON selection with a Windows file picker,
  validation, direct local Windows test ZIP packaging, optional encrypted
  GitHub Actions secret upload and developer CI trigger. Ordinary FileHub
  users never see OAuth Client ID/JSON or a Google developer form.
- GitHub CLI and a publisher-issued OAuth JSON are both absent from the
  connected Windows PC. The publisher can now build a local test ZIP
  **without GitHub CLI** as soon as their Google Cloud Desktop client JSON
  is downloaded. The owner's initial Google Cloud registration and Chrome
  consent cannot be performed by code without the account's authorization.
- The GitHub pilot workflow is absent on default branch `main`. Onboarding
  has a safe fast-forward-only developer-branch push fallback if workflow
  dispatch is unavailable. Fake GitHub CLI tests verify both paths.
- Added `tools/Build-GoogleDirectLocalPilot.ps1`: pinned SHA-verified
  ancillary binaries, at-least-3GiB-free local fixed work drive, selective
  extraction of five fixed ZIP entries, native WinFsp bridge publishing,
  mandatory waited child-EXE success checks, forward-slash portable ZIP
  names, and pre-publish verification of six required package contents.
- The system C: drive was almost full (353MB) and full archive extraction
  initially failed. Only our own leftover temporary publisher folders were
  removed; our verified download cache was moved to D: (208GB free).
- After repairing PowerShell 5 compatibility and GUI process wait handling,
  a **real ~164MB Windows ZIP was generated using ONLY synthetic OAuth
  credentials**, six mandatory entries verified, then the synthetic ZIP
  deleted. No live Google login or user data was touched by this test.
- The developer CI includes an offline publisher bootstrap test and a
  synthetic local publisher packaging regression step; neither produces a
  publicly downloadable ZIP with the fake credentials.
- Stable `pc/windows` remains unaffected. Real Chrome owner consent,
  actual Google Drive performance, repeat Excel saves and long-running
  reconnect checks are still unverified. Never call this a finished product.
