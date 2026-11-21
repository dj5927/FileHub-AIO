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

## 2026-11-21 follow-up: remove next-Explorer-operation OAuth cold stalls

- `GoogleDirectOAuthSession.KeepTokenWarmAsync` periodically inspects the
  existing authenticated grant while the WinFsp volume is mounted. Valid
  cached tokens return immediately with **zero Google network requests**.
- Only the last two minutes of access-token lifetime trigger an actual
  refresh (using the existing _refreshGate and encrypted token persistence),
  even when no Explorer I/O has occurred for an hour. The new task is
  canceled and awaited during clean WinFsp disconnect; errors do not
  terminate the mounted provider. It never opens Chrome or requests a
  new OAuth consent.
- Fake localhost OAuth regression checks: fresh token remains untouched
  after multiple timer ticks; an expiring token refreshes before any file
  operation, exactly once, and the refreshed token is reused afterward.
  Test marker `GOOGLE_NATIVE_IDLE_PREEMPTIVE_OAUTH_REFRESH_NO_SECOND_LOGIN_PASS`.
- Windows local main/WinFsp builds and native fake-Drive filesystem tests
  pass. The new behavior helps eliminate *idle expiry* first-open latency,
  not the separate first-account registration gate. Live Google sign-in and
  real user Office saves have not been verified.

## 2026-11-21 native Drive folder pagination protection

- The direct Google API directory reader now detects repeated page tokens
  before making any redundant request, with a generous upper bound of
  10,000 listing pages. This prevents a stuck or malformed remote listing
  from monopolizing an Explorer callback indefinitely.
- An isolated fake server deliberately returns the same pagination token
  repeatedly; the verified result is exactly two GET requests followed by
  a categorized I/O error, never an apparently empty directory. Marker:
  `GOOGLE_NATIVE_REPEATED_PAGINATION_LOOP_PREVENTION_PASS`.

## 2026-11-21 Chrome-only OpenList public broker EXPERIMENTAL PILOT

- The user explicitly wants **Google Drive add -> Chrome Google account
  selection/consent -> automatic WinFsp drive**, without obtaining any JSON,
  configuring Cloud Console, copying tokens or using rclone for Google.
- Official OpenList public OAuth helper supports broker-issued Google
  consent without a separate user-created OAuth client. URL:
  https://api.oplist.org/googleui/requests?server_use=true&driver_txt=googleui_go
  It uses its own (OpenList-branded) Google OAuth app and sends the
  authorization code and refresh token through the **third-party service**.
  This distinction MUST be visible and clearly disclosed to the tester;
  don't claim this is FileHub's own Google OAuth identity or hide the broker.
- Added `GoogleOpenListChromeAuth.cs`: Chrome isolated temporary profile,
  random localhost DevTools port, strict HTTPS Google consent origin/scope
  checks, required callback state match, one-time callback fragment parsing,
  no manual token copy/paste. The disposable Chrome profile is closed after
  the grant. Redirect URL/token material is NEVER logged.
- Added fully isolated **real Chrome** test against a local-only fake OAuth
  callback server: it launches Chrome in headless mode, follows the fake
  approval redirects, verifies callback state, retrieves the token fragment
  through localhost debugging, then terminates Chrome. Marker:
  `GOOGLE_OPENLIST_CHROME_SINGLE_LOGIN_NO_JSON_LOOPBACK_PASS`.
- `GoogleDirectCredentialStore` now records an optional `Provider=OpenList`
  with Windows DPAPI; `GoogleDirectOAuthSession` renews broker-issued grants
  **only through the provider that issued them**, never by impersonating
  FileHub's OAuth app. The documented public renew endpoint requires a GET
  query (the provider can observe refresh tokens); keep that privacy warning
  visible. Mock renew test marker:
  `GOOGLE_OPENLIST_EXPLICIT_BROKER_REFRESH_NO_RCLONE_PASS`.
- `GoogleDriveLoginDialog` now offers the documented OpenList browser flow
  if FileHub publisher OAuth is not bundled; text clearly says **OpenList**
  is the authorizing service. Chrome approval is validated by an actual
  read-only Google Drive About request before saving to DPAPI. No rclone
  process or Google JSON is used for this native Google WinFsp path.
- `tools/Build-GoogleDirectLocalPilot.ps1 -OpenListBroker` builds an actual
  no-JSON Windows ZIP with the Google direct WinFsp engine and inherited
  pinned ancillary backends. It includes an explicit public-broker pilot
  notice and rejects a bundled private OAuth JSON. First live local ZIP:
  SHA256 3845932FEDB972C4320ACBDBCF9F1C66116AD59C4A4155FD4AB2A8B3B867EEA1
  (~157 MiB), 6 mandatory entries verified. CI uploads a separate TEST
  artifact. New Google profiles start in folder/new-file write mode; existing
  Office file updates remain guarded until verified on a real account.
- All current tests use synthetic tokens or broker authorization URL
  discovery ONLY. This is a **testable experimental build** once delivered,
  not a production-complete or real-user-tested Google connection. The
  tester must independently approve OpenList in Chrome and try disposable
  folder/file operations before trusting important documents.

## 2026-11-21 resumed session: repair actual Connect and reauthorization

- Recovered successful CI run 44843167683 at remote commit 06a59f2018fa9d7a6470596af59652a7e48a7444.
  Downloaded artifact 14682190406 and verified its outer and inner SHA-256.
  That ZIP is superseded: MainForm still rejected missing publisher JSON
  before the otherwise working OpenList dialog, blocking both new and old
  Google profiles. Do not recommend the earlier ZIP as the repaired build.
- Removed that obsolete precondition. MainForm's explicit Connect and the
  packaged UI regression use the same GoogleBrowserLoginFor entry point.
  Automatic startup still never creates a Google approval window.
- Native readiness now requires a readable DPAPI grant, not merely a token
  filename. An unreadable grant can reach browser reapproval without deleting
  or changing its original bytes. A bundled publisher identity still wins;
  otherwise the dialog clearly offers the OpenList public broker.
- Extended both CI and final ZIP checks to require the actual Connect entry
  and unreadable-grant recovery markers, closing the prior test coverage gap.
- Local main and WinFsp bridge builds succeeded. Waited child-process runs
  passed 3 UI, 24 OAuth/API, and 12 real Windows WinFsp markers (39 total).
  Chrome itself ran against the isolated fake callback server. No live Google
  account files were accessed. Logs are on the Windows host under
  D:\FileHubAIO-PublisherBuildCache\OpenListResumeQA-20261121.
- The first QA launcher read a null ExitCode after WaitForExit; it was a
  launcher accounting issue, not a product assertion failure. Reran with
  Start-Process -Wait -PassThru and verified ExitCode=0 for all three suites.
- At this checkpoint: final remote CI and rebuilt ZIP delivery are pending.
  Native Google grants present on this PC: zero. The tester's actual Google
  consent, live latency and Office overwrite verification remain outstanding.
  The enabled pilot scope remains browsing, folders and new-file upload;
  existing binary writes are not promoted by these synthetic tests.

## 2026-11-21 delivery checkpoint: local repaired ZIP ready, GitHub blocked

- Product/source fix commit on the Windows worktree: ab3acd5d6df7f16fc81d73f0ed39f748fd72bb1b.
- Rebuilt OpenList ZIP successfully with all new packaged Connect/reapproval
  gates. Final ZIP SHA-256:
  FFC591CF7639E3D93C7E82710DCBA6EE66AC138AA5EB24F106772A532BC3A38A.
- Ready-to-run extracted app:
  D:\FileHubAIO-GoogleOpenList-TEST-ab3acd5\FileHubAIO.exe
- Desktop shortcut:
  C:\Users\UserK\Desktop\FileHub AIO Google TEST.lnk
- User download ZIP:
  C:\Users\UserK\Downloads\FileHub-GoogleOpenList-FIX-20261121-ab3acd5.zip
- Verified 294 archive entries: no duplicates, traversal paths, account
  credentials, publisher JSON or Google-specific rclone executable. Retested
  the final extracted binary: all 3 UI and 12 WinFsp markers, ExitCode=0.
  The packaging stage had also passed native API and Chrome callback tests.
- Both existing saved Google profiles are already in new-file writable
  mode. Zero native Google grants exist. The old FileHub GUI is still
  running; it was not forcibly terminated. Exit it through its tray menu
  before opening the new desktop shortcut. First Connect requires actual
  Google account approval in Chrome; no JSON or token copying.
- GitHub create_blob was rejected by automatic approval review, twice.
  First reason: unverified public destination and source-upload authority.
  We then verified connected user dj5927 owns the PUBLIC repository with
  admin/push access, fetched the already-public matching credential-store
  source, and recovered prior user-authored all-source/PC update instructions.
  Review still required a new explicit user approval for this exact source
  payload and destination. No alternative upload method was attempted.
- Remote pc/google-direct-stage-recovery-20261120 therefore remains at
  06a59f2018fa9d7a6470596af59652a7e48a7444. The current repaired source has
  NOT been uploaded and no new remote CI has run. Next external action needs
  explicit approval to upload the eight changed source/workflow/history files
  to dj5927/FileHub-AIO on that branch. Do not replace the stable release.
- All background build/extraction/test jobs started in this session finished.
  Honest remaining scope: live Google consent/I/O latency and existing Office
  overwrite are not verified; the pilot still guards existing-file writes.

## 2026-11-21 user runtime failure: OpenList OAuth client is deleted

- User tested the ab3acd5 ZIP at 09:48 KST. Screenshot shows Google's
  accounts.google.com/signin/oauth/error: 401 deleted_client / The OAuth
  client was deleted. App log records two WebSocketException categories.
- Incognito was caused by our own explicit --incognito Chrome argument;
  it is separate from the deleted Google OAuth application problem.
- Root reproduced anonymously on the Windows host: OpenList /requests
  returned HTTP 200 but following its returned authorization URL reached
  Google's deleted_client error page. No Google login/session was used.
- Official mirror api.oplist.org.cn uses the exact same public client and
  also reaches deleted_client. Legacy api-cn.oplist.org was unavailable.
  A different official hostname cannot repair the deleted application.
- Official OpenList-APIPages issue #99 reports the same failure. Maintainer
  PIKACHUIM (MEMBER) replied on 2026-10-18 that the official Google app is
  currently unusable because it did not pass verification, and closed the
  issue not_planned. Reference:
  https://github.com/OpenListTeam/OpenList-APIPages/issues/99#issuecomment-6248884779
- Prior public-broker reachability and synthetic Chrome tests did not prove
  the provider's Google identity was usable. Do not repeat the prior claim
  that another no-JSON OpenList ZIP is ready for successful account login.
- Implemented locally: normal isolated Chrome window; sanitized immediate OAuth
  error classification; anonymous Google-entry preflight; package gate that
  refuses an OpenList login ZIP when the real provider is unavailable.
- Actual connection restoration requires an active publisher-owned FileHub
  OAuth registration or OpenList operator restoration. No publisher OAuth
  identity is available here. End users must still only use browser consent;
  do not add user JSON selection/token copy or silently use another app ID.
- GitHub source-upload approval remains blocked from the preceding turn.
  This user message reports a runtime error and is not a new upload approval.

### Verified local correction and exact remaining blocker

- Removed --incognito from production Chrome arguments; retained an
  app-owned temporary profile and loopback-only browser control. This is
  source/build work, not an update to the user's already-extracted ab3acd5 ZIP.
- GoogleDriveAuth now presents fixed Korean messages with an allowlisted
  OAuth error code. Logs contain only the category, not page text, account
  details, full callback URLs, tokens, or the decoded Google error payload.
- Added an anonymous provider-entry check before opening production Chrome.
  It follows the returned authorization URL and detects Google's rejection;
  the check never reuses personal Chrome cookies or grants account access.
- Poll and pending CDP receive are canceled and fully awaited before HTTP
  and WebSocket disposal. The app-created Chrome process is awaited before
  temporary-profile cleanup. Independent review confirmed the lifecycle fix.
- Windows final build: 0 errors, 12 existing warnings. UI policy suite:
  3 PASS markers. API/OAuth policy suite: 28 PASS markers. Final narrow rerun
  after cancellation cleanup passed all five Chrome markers, including
  normal-window arguments, successful synthetic consent, deleted-client URL,
  deleted-client HTTP 401 document, and denied consent.
- Live public-provider CLI exited 1 with exactly:
  GOOGLE_OPENLIST_LIVE_AUTH_UNAVAILABLE code=deleted_client
  This is a confirmed blocked provider, NOT a successful Google login.
- Ran the actual local publisher script with -OpenListBroker. Main app and
  WinFsp bridge published, then the live check rejected the package with
  deleted_client before any ZIP was created. Verification markers:
  FINAL_CHROME_CLEANUP_AND_OUTCOMES_PASS
  DELETED_CLIENT_PACKAGE_BLOCK_NO_ARCHIVE_PASS
  FINAL_LOCAL_AUTH_ERROR_VERIFICATION_COMPLETE
- Final background job 4ead1a5764044f6d99047a5fc5abbc9f completed with exit 0;
  all build/test jobs launched for this correction are finished. Test logs:
  D:\FileHubAIO-PublisherBuildCache\OpenListDeletedClientQA-20261121
- CI uses the same builder and has no continue-on-error before artifact
  upload, so this correction will also block the broken-provider ZIP once
  uploaded. The remote branch is still unchanged; this is not retroactive
  protection for old remote code or already-uploaded archives.
- Do not tell the user to retry the old ZIP or claim Google auth restored.
  Next actual integration prerequisite: an authorized, active FileHub
  publisher Google OAuth registration, or restoration by OpenList's owner.
  Keep ordinary users' flow as Chrome consent only, with no JSON selection,
  token copying, or borrowed third-party OAuth client. Real Google Drive
  account access, mount and Office saves remain unverified.

## 2026-11-21 user direction: RaiDrive-style FileHub-owned browser sign-in

- User explicitly requested the clean RaiDrive-style connection flow.
  Selected path: Connect -> ordinary Chrome account selection/consent ->
  local callback -> direct Google token exchange -> DPAPI -> native WinFsp.
  No end-user JSON/Client ID/token copying, OpenList fallback, or Google
  rclone backend. RaiDrive's private implementation is not known; its
  documented user experience is the reference, using FileHub's own app.
- Primary sources verified: RaiDrive Google Drive guide requires only the
  user's Google account and browser approval; its access-removal guide
  names RaiDrive as the authorized application. Google's Desktop OAuth
  guide requires the publisher to create its own client and supports a
  system browser with a loopback callback and PKCE.
  https://docs.raidrive.com/gui/storage/personal/google-drive/
  https://docs.raidrive.com/gui/account/remove-access/storage/
  https://developers.google.com/identity/protocols/oauth2/native-app
- Native publisher OAuth was already substantially implemented. Now completed locally:
  remove automatic broker selection; reject historical broker grants for
  live sessions while preserving their bytes for recovery; auto-start normal
  Chrome on the explicit Connect dialog; propagate browser startup failure;
  terminate verified consent denials promptly; cap token response bodies;
  validate the real publisher authorization entry before ZIP creation.
- Root owns Program.cs, tests there, and this history. oauth_review owns
  GoogleDriveAuth.cs, GoogleDirectOAuthSession.cs,
  GoogleDirectCredentialStore.cs, RemoteMount.GoogleDirect.cs and
  GoogleDirectApiPolicyTest.cs. ci_state owns GoogleDirectBrowserAuth.cs and
  GoogleDirectBrowserOAuthPolicyLab.cs. publisher_packaging owns the local
  publisher build script and direct-google-pilot.yml.
- The actual active publisher registration is still absent. It must be
  created under the authorized developer's Google Cloud project; neither
  modifying callback code nor a public broker can invent this registration.
  Do not claim a live login test or working ZIP until that dependency and
  actual account consent are verified. Developer configuration is bundled
  once; ordinary users must not perform developer onboarding.
- No new source upload approval was given; prior automatic review block
  still applies. Continue local implementation and verification only.

### Publisher-only Windows verification completed

- Root took over GoogleDirectApiPolicyTest.cs after the runtime agent's
  launcher patch response timed out. Readback confirmed that patch had
  actually applied; no duplicate mutation was attempted.
- New normal UI uses publisher settings or an existing FileHub native grant
  only, auto-starts on the explicit dialog's Shown event, and opens ordinary
  Chrome (system browser fallback). No CDP/incognito/profile extraction is
  part of that path. No extra user JSON/Client ID/token fields were added.
- All normal routing and token import require TryLoadPublisher. Historical
  broker grant bytes remain intact until a verified own-client grant saves
  an encrypted backup. OAuth renewal now posts only to Google's endpoint.
- Native browser code now handles valid-state denials immediately, ignores
  malformed/duplicate/wrong-state callbacks, bounds HTTP bodies, and reports
  sanitized failures. Code exchange plus Drive verification/DPAPI storage
  precede browser success. Anonymous publisher readiness uses a fresh cookie
  jar, Google-only redirects, and does not send the client secret.
- Main and WinFsp bridge Windows Release builds both passed with 0 errors
  (12 and 39 warnings respectively). Login UI policy: 4 PASS markers.
  Native OAuth/API policy: 28 PASS markers, including all five new callback,
  response-bound and anonymous-readiness scenarios plus rejected broker
  import with zero HTTP requests and preserved encrypted backup bytes.
- Ran actual Build-GoogleDirectLocalPilot.ps1 with synthetic publisher
  configuration and -DryRun. Published app/bridge and packaged binary
  checks passed; no downloadable archive was created from fake credentials.
  GOOGLE_PUBLISHER_LOCAL_PILOT_PACKAGE_DRY_RUN_PASS
- Retired -OpenListBroker mode rejected before any publish/download:
  OPENLIST_PACKAGE_MODE_RETIRED_BEFORE_BUILD_PASS
- Live publisher CLI exited 1 with
  GOOGLE_PUBLISHER_LIVE_AUTH_UNAVAILABLE code=publisher_not_configured
  This is the real remaining blocker, not a passed live Google account test.
- Background verification ed1d4257e6e84d64aaf9e98943fd6ca9 completed exit 0:
  PUBLISHER_ONLY_LOCAL_VERIFICATION_COMPLETE
  Logs: D:\FileHubAIO-PublisherBuildCache\PublisherOnlyOAuthQA-20261121
- Automatic approval review rejected a proposed local workflow refactor
  because its future artifact upload would distribute bundled OAuth config
  derived from a GitHub secret without fresh explicit sharing approval.
  That rejected workflow payload was not applied or retried through another
  tool. A materially safer tests-only workflow was applied instead: no
  publisher secret use, no artifact upload, no OpenList package step.
  The local publisher builder remains available and requires an actual
  Google-entry check for real ZIP creation. PowerShell parser/diff checks
  passed for the builder and all six workflow run blocks.
- No remote push, secret upload, CI dispatch or public release occurred.
  Stable pc/windows and the remote pilot branch were not modified.
- Next step requires an authorized developer Google Cloud account/project
  to create FileHub's active Desktop OAuth registration. Regular users only
  approve FileHub in their browser; this one-time publisher setup must not
  be shifted into a per-user JSON/token-copy flow. Actual Google account
  consent, Drive mount/latency and Office save tests still require that grant.

## 2026-11-21 clarified requirement: public distribution, each user's Google account

- User explicitly clarified that FileHub is for distribution, not just the
  publisher's personal account. Every recipient must connect their own
  Google account through ordinary Chrome login and consent only.
- Explain "Google Cloud project" as the publisher's one-time registration
  of the FileHub application. It is not an onboarding task for each user
  and does not tie every installation to the publisher's Drive account.
  Each user's separate grant remains tied to their selected Google account.
- Public-distribution setup must use an External audience and a production
  app, with required brand/scope verification. Do not choose Personal mode,
  Internal-only mode, or a test-user allowlist as the final distribution
  solution. Do not describe Publish alone as removing verification limits.
- Current code requests full Drive access for existing-file mounting;
  Google's Drive scope is restricted. Public availability without the
  unverified warning/user cap requires the applicable Google review.
  Publisher registration/verification has not been performed here and must
  not be confused with passed synthetic Windows OAuth/API tests.
- Keep the user-facing workflow: FileHub Connect -> normal Chrome -> own
  Google account -> approve FileHub Drive access -> automatic connection.
  No developer-project creation, JSON selection, IDs or token copy/paste
  appears in regular users' setup.
- Official requirements rechecked:
  https://developers.google.com/identity/protocols/oauth2/production-readiness/overview
  https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
  https://developers.google.com/identity/protocols/oauth2/native-app
- No runtime code change, credential access, remote write, account creation
  or deployment was performed for this clarification.

## 2026-11-21 publisher registration started: console access blocker

- User said to proceed with public Google publisher registration. Re-read
  this history before continuing. App code remains locally committed at
  5516c6b; public-audience clarification is committed at 512d77c.
- Searched available integrations for Google Cloud Console registration.
  No returned plugin exposed ordinary Desktop OAuth client creation.
  Public docs require the Google Cloud Console for this registration.
- Opened https://console.cloud.google.com/auth/overview in the dedicated
  task browser. Actual page showed "Site Unavailable / Unable to access
  this site". Browsing issue report returned document HTTP 403. A single
  reload returned the same state. This is a browser access error; no
  explicit bot-denial evidence and no claim that Google is globally down.
- No sign-in flow, account selection, project, OAuth app, client ID, terms
  acceptance, billing or consent submission was reached in that browser.
  Do not claim registration or authentication succeeded.
- Via WEBJ, sent a normal Chrome new-window launch on the Windows PC to the
  same verified Console URL. Launch command returned success. Targeted
  process-window title checks could not confirm the actual rendered page;
  do not claim the user is already on a working registration screen.
- Prepared and reviewed docs/GOOGLE_OAUTH_PUBLISHER_PREPARATION_KR.md as an internal,
  code-grounded submission draft. Publisher identity, support/contact email,
  domain ownership, public policy URLs and Google review remain unresolved.
  No public policy publication, source upload or app deployment authorized
  by prior rejected upload payloads has been attempted.
- Next concrete input: the actual Google Cloud screen on the user's normal
  Chrome, so registration can continue with the correct account and UI.
  A sign-in on the user's Chrome is not a sign-in of the separate task
  browser; do not imply that sessions are shared.
- The internal draft is 134 lines, checked against current source. It
  records full-Drive scope justification and accurately separates encrypted
  tokens from unencrypted local upload/recovery data. Profile deletion does
  not currently revoke Google access or delete the native token backups;
  public policy must not promise those actions until implemented.
- Documentation-only changes passed git diff --check. No background test,
  build, upload or Google registration job remains running for this step.

## 2026-11-21 KST user screenshot: existing publisher project visible

- Re-read the saved history before continuing. User supplied a screenshot
  from normal Windows Chrome showing the Google Auth Platform overview.
  Selected project name: nas mount; URL project ID: nas-mount-514420.
- The overview explicitly says Google Auth Platform is not configured yet
  and shows the central Get started button. Project selection and working
  access on the user's PC are observed; OAuth app/client creation, project
  ownership, Drive API enablement and public verification are not proven.
- Continue initial setup in that existing project: Get started -> app name
  FileHub AIO and an operator-monitored support email -> External audience
  -> monitored Google contact email -> review the user-data policy, agree
  if accepted, then Continue/Create. No new project or free-trial signup
  is needed for this initial OAuth configuration step.
- The user must choose the support/contact email shown in their account;
  no email address or legal publisher identity has been inferred from the
  avatar, bookmarks, project name or repository account.
- Public release still targets External + Production with applicable
  brand/scope verification. Initial setup is not a public-release approval.
- Updated docs/GOOGLE_OAUTH_PUBLISHER_PREPARATION_KR.md with this observed
  project and the distinction between the user's working Chrome page and
  the separate task browser's prior 403. No browser session is shared.
- Official onboarding guidance checked against:
  https://support.google.com/cloud/answer/15544987?hl=en
  https://developers.google.com/workspace/guides/configure-oauth-consent
- No OAuth configuration was changed by the assistant, and no client,
  credential, upload, build or background registration job was created.
  Next evidence is the user's page after initial setup or any intervening
  screen whose fields differ from the documented onboarding sequence.

## 2026-11-21 KST user screenshot: initial OAuth configuration created

- Re-read the current handoff before continuing. The user's next normal
  Chrome screenshot shows the same nas-mount-514420 project and a visible
  OAuth configuration created toast. Initial Google Auth setup is complete.
- The overview explicitly states no OAuth clients are configured and offers
  Create OAuth client. It does not expose the saved audience, support email,
  app name, Drive API status or public verification. Do not infer those
  fields, client creation or successful Google account consent from this.
- Next user action: Create OAuth client -> Desktop app -> internal client
  name FileHub AIO PC -> Create. Save the generated publisher JSON locally
  using the creation dialog's download action so the app builder can use
  the actual FileHub registration. This is one-time publisher configuration,
  never a per-recipient JSON or token-copy onboarding requirement.
- No credential values were requested as chat text or read from the user's
  PC. No OAuth settings were changed by the assistant, and no build, upload,
  public release or account-consent success is claimed at this stage.
- Current official native-app documentation reconfirmed Desktop app and
  system-browser/loopback/PKCE flow:
  https://developers.google.com/identity/protocols/oauth2/native-app
- Public release still requires confirming External, API/scopes, Production
  and the applicable Google verification. Next evidence is actual client
  creation and the user's saved publisher configuration file.

## 2026-11-21 KST Google Drive scope configuration verified from user screenshot

- User subsequently created FileHub AIO Windows Desktop OAuth client in
  nas-mount-514420 and confirmed Google Drive API displayed `API enabled`.
- Latest user screenshot at Google Auth Platform > Data access > scopes
  visibly lists one scope under Restricted: `.../auth/drive`, with the
  explanation that files can be viewed/edited/created/deleted. Save button
  is disabled, consistent with the scope configuration already saved.
- OAuth client creation and API/scope configuration are supported by user
  screenshots. Actual successful Google account consent, token exchange,
  WinFsp mount, Office file save, External audience status and production
  verification are not yet supported by screenshots or live tests.
- Next action: open Google Auth Platform > Audience (left menu) to inspect
  External / Testing / In Production state. If Testing, add only a chosen
  developer account as test user for real connection test; don't claim
  unrestricted public distribution yet. Google scope verification and
  accurate branding/privacy-policy/domain details are needed for broad
  production distribution using full `drive` restricted scope.
- Official published state behavior verified 2026-11-21:
  https://developers.google.com/identity/protocols/oauth2/production-readiness/overview
  https://support.google.com/cloud/answer/15549945?hl=en
- Do not transmit OAuth client JSON/secret through conversation, public
  issues or publicly accessible source. A client secret appeared in a prior
  screenshot, so review and rotate before a public release where needed.

## 2026-11-21 KST actual publisher OAuth local test package ready

- User confirmed a test user was added to the project's External / Testing
  audience. We did not read or record its email address; account consent
  remains user-controlled.
- Located exactly one downloaded Desktop OAuth client JSON on the Windows
  host and validated the `installed` structure and credential field presence
  without printing IDs/secrets or writing them to source control.
- Existing WinFsp x64 installation and pinned v1.3.126 ancillary archive
  were available. Ran `tools/Build-GoogleDirectLocalPilot.ps1` locally with
  the real publisher JSON. The published app/bridge policy checks all passed,
  and real anonymous Google auth entry returned:
  `GOOGLE_PUBLISHER_LIVE_AUTH_ENTRY_READY`.
- This live entry check does not involve the user's Google account and is
  not a completed consent, token-exchange, Drive listing or upload test.
- Local ZIP: `C:\Users\UserK\Downloads\FileHub-GoogleDirect-PublisherTest-20261121-110528-4294d7b0.zip`
  SHA256: `383D4EA7E5FD41B1DE30DB8195100B9AA846E69424E4466B502AABFFED325C8E`.
  Never push this ZIP to public GitHub before the explicit distribution
  approval and required public verification. It contains publisher Desktop
  OAuth application metadata, not a user's personal refresh token.
- Independently checked ZIP hash and re-extracted to:
  `D:\FileHubAIO-GoogleDirect-TEST-20261121-4294d7b0\FileHubAIO.exe`.
  Verified necessary EXE, WinFsp bridge, direct Google settings and
  supporting backend/driver files. All 3 binary checks from the extracted
  package passed: publisher configuration, login UI, real anonymous entry.
- Created the separate desktop shortcut:
  `C:\Users\UserK\Desktop\FileHub AIO Google DIRECT TEST.lnk`.
  Do not use the old OpenList shortcut or previous ZIP for this test.
- Ran the extracted WinFsp bridge's real Windows I/O policy over an isolated
  in-process fake Google API; 12 PASS markers, including read, folder
  create/rename, new upload, two existing binary saves, and two-client
  conflict recovery. No user Google Drive access or production file writes.
- Verification logs (redacted status only):
  `D:\FileHubAIO-PublisherBuildCache\RealPublisher-20261121`.
- Both background jobs completed successfully: publisher ZIP build
  `1937a890fbaa4b899dcb0070a4f92ffe`; ZIP extraction/shortcut checks
  `0bd004aa25f84899a8d203797ff7c63e`; isolated WinFsp policy lab
  `787c9cebce594a2ab8a886b549f50909`. All exited zero; no job left
  running. No remote push, credentials upload or public release.
- Next required proof: user closes old FileHub instance, runs the new
  Direct Test desktop shortcut, picks the already-allowlisted Google test
  account in ordinary Chrome, and approves. Verify actual Google Drive
  mount and user-created disposable test file operations before claiming
  live success. Current direct pilot still restricts ordinary writes to
  existing files; the isolated existing-file save lab is opt-in and does
  not prove production Office overwrite safety.

## 2026-11-21 KST direct Google W: quota fix, tested locally

- User screenshots: FileHub Google W: had 465 GB total and ~79.5 MB free,
  matching local C: disk, while separate Google Drive Y: showed ~5 TB
  total and ~4.93 TB free, consistent with reported ~68 GB usage. User
  confirmed real OAuth/WinFsp connection. Do not confuse W: and Y:.
- Root cause: LockBridge/DirectGoogleWinFsp.cs GetVolumeInfo returned
  local C: DriveInfo instead of Google's quota.
- Fixed native API GetStorageQuotaAsync to call Drive v3 About with
  fields=storageQuota(limit,usage). Google's overall used bytes include
  other services charged to the same quota, not only Drive files.
- Native WinFsp GetVolumeInfo is now nonblocking: cached quota or unknown
  0/0, never local disk. First lookup is background; success cached 5
  minutes, failure/limit-absent retried after 1 minute. Unlimited accounts
  have unknown quota instead of a fabricated capacity.
- On quota update the bridge sends a fixed output message. The GUI
  notifies the specific drive and This PC, without new blocking remote
  lookups from the Explorer filesystem callback.
- Tests: main and bridge Windows Release builds succeeded. Actual Windows
  WinFsp mocked Drive API tests passed 14 markers, including 5 TB total,
  68 GB usage, remaining 4.932 TB, cached single About lookup, one quota
  update event, unlimited quota, new upload, repeated existing writes
  and conflict recovery. Real Windows free-space bytes are rounded down
  by up to 4095 bytes for sector boundaries (initial test differed by
  2048 bytes). Fixed test tolerance accordingly, not production quota.
- Publisher OAuth packaging checks, login UI, direct routing and a live
  anonymous Google authorization entry all passed with the existing
  locally stored Google Desktop client JSON. No new account tokens or
  user Drive content were accessed for the mock WinFsp tests.
- First ZIP attempt failed when output targeting C: Downloads because C:
  is nearly full (~0.07 GiB free). No C: content was removed. Retried
  with D: (>200 GiB free) and successfully created:
  D:\FileHubAIO-PublisherBuildCache\QuotaFix-20261121\FileHub-GoogleDirect-PublisherTest-20261121-111341-ba39452e.zip
  SHA256: 62EB9CC258397FAF132CE06FFD7DCF5C3C308F8C7B9442A46BCE43F19B35B1B2
- Reopened and verified this ZIP. Extracted executable:
  D:\FileHubAIO-GoogleQuota-FIX-20261121-ba39452e\FileHubAIO.exe
  Unpacked app/bridge policy tests passed, including WinFsp quota I/O.
- Created desktop shortcut:
  C:\Users\UserK\Desktop\FileHub AIO Google QUOTA TEST.lnk
- Existing app/mount was NOT forcibly closed or replaced. User must
  safely exit old FileHub and run new shortcut, reconnect W: and
  confirm actual account capacity from Explorer. Real 5 TB quota value
  on this user's account and shell auto-refresh are still unverified.
- All build, package and mock-WinFsp jobs completed with exit 0 after
  moving ZIP output to D:. No remote push or public release.
  Verification logs in D:\FileHubAIO-PublisherBuildCache\QuotaFix-20261121.

## 2026-11-21 KST user verification of Google quota, public distribution steps

- User supplied an Explorer screenshot after reconnecting on the quota-fix
  build: FileHub '구글드라이브 (W:)' now shows 5.00 TB total and 4.93 TB
  free, matching a separate 'Google Drive (Y:)' 5.00 TB / 4.93 TB.
  User also reported that connection is fast. This closes the observed
  quota presentation bug; no new real-file read/write timing measurements
  or production Office overwrite tests were furnished.
- User next asks how other people authenticate with their own Google
  accounts when distributing FileHub. Their nas-mount-514420 Google Auth
  Platform project is External + Testing with a Desktop OAuth client and
  Drive full scope (restricted).
- Under Testing, operator adds each person's Google account to the Test
  users allowlist (maximum 100), then each uses the same FileHub Desktop
  OAuth client to consent in a normal Chrome browser. End users do not
  create Google Cloud projects, download OAuth JSON, or copy tokens.
  Testing refresh tokens expire after 7 days with Drive scope, so this
  is not a durable production distribution solution.
- For broad production distribution: complete accurate Branding, host a
  public homepage and privacy policy on a verified publisher-owned
  domain, provide support email, verify full Drive restricted scope and
  submit the appropriate demo/data-use review. If restricted user data
  flows through a third-party server, annual independent security
  assessment may apply. Recommend separate production project/client
  from testing and update FileHub's bundled Desktop client identity only
  after production approval. Avoid shipping the testing build to the
  general public before scope verification.
- Verified policy sources on 2026-11-21:
  https://developers.google.com/identity/protocols/oauth2/production-readiness/overview
  https://developers.google.com/identity/protocols/oauth2
  https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
  https://developers.google.com/identity/verification/authentication-policy-compliance

## 2026-11-21 KST Google OAuth branding screenshot and WebDAV quota fix

- Google Auth Platform > Branding screenshot for project nas-mount-514420:
  app name FileHub AIO, support email and developer email already set, app
  logo optional, homepage/privacy/terms URLs and authorized domains not
  yet supplied. Current 'verification not needed' badge is about Testing
  branding; public restricted Drive scope approval is NOT complete.
- Publisher must choose an actual verified owned domain and publish a
  publicly accessible FileHub-specific homepage and accurate Google user
  data privacy policy on that domain before entering URLs. Do NOT invent
  URLs or assume a third-party shared github.com domain qualifies.
  Official references:
  https://support.google.com/cloud/answer/15549049?hl=en
  https://developers.google.com/identity/verification/authentication-policy-compliance
- User screenshot has new NAS WebDAV (R:) showing 465 GB total, 7.43 GB
  free: exactly the Windows C: local staging disk; separately, Synology
  (X:) is the same NAS but shows 7.99 EB total/free as virtual unlimited.
  This is a capacity *presentation* bug in FileHub, not evidence of a
  cloud/NAS mount write error or an actual 7.99 EB NAS disk.
- Fixed DirectWebDavWritableFileSystem.GetVolumeInfo: it no longer
  reports local staging drive. The shared direct WebDAV WinFsp provider
  now reports (a) validated RFC 4331 quota-available-bytes and
  quota-used-bytes from an asynchronous Depth:0 PROPFIND to the configured
  root, or (b) the same virtual long.MaxValue/7.99 EB total/free as
  RaiDrive when the server does not provide usable quota information.
  Avoiding false local C: quota and not mislabeling virtual EB as physical
  NAS capacity is mandatory.
- Quota callbacks never block on WebDAV HTTP; background fetch is cached
  5 minutes and does not recursively enumerate folders. The WinFsp bridge
  emits a sanitized quota-ready event and FileHub refreshes its exact
  Explorer drive and This PC after real remote quota becomes available.
  Actual file upload, ETag checks, Office save and journal guard code
  was not otherwise changed.
- Direct WebDAV WinFsp Bridge --self-test returned exit 0; 14 PASS
  markers including RFC 4331 real 5TB/68GB parser, async cache
  behavior, 7.99 EB virtual fallback when PROPFIND reports 404, and
  existing write ETag fail-closed policy. Main FileHub and Bridge Release
  builds succeeded with zero errors. One source compile error from an
  XML exception namespace reference was corrected before retesting.
- Local publisher OAuth test ZIP containing both Google and WebDAV
  quota fixes was generated after the first attempt tried to redownload
  stable ancillary components into C: and stalled at 0 bytes. The
  stalled job was stopped and retried using the existing SHA256-verified
  D: cache without touching the real NAS or mounting profile.
- Output ZIP:
  D:\FileHubAIO-PublisherBuildCache\WebDavQuotaFix-20261121\FileHub-GoogleDirect-PublisherTest-20261121-112518-0fe17e8c.zip
  SHA256: A5481568CD7A4F1E97D259A1E1F72FAB667F3B4596394A62D6627F37969D5CCD
  Verified hash, re-extracted and reran packaged app OAuth policy,
  login UI, live anonymous Google authorization entry, packaged bridge
  WebDAV selftest and isolated Google WinFsp volume test: all passed.
- Extracted local EXE:
  D:\FileHubAIO-WebDAVQuota-FIX-20261121-0fe17e8c\FileHubAIO.exe
  Desktop shortcut:
  C:\Users\UserK\Desktop\FileHub AIO Google WebDAV QUOTA TEST.lnk
- This is a LOCAL, non-public test build. No GitHub upload, credential
  publication, browser auth reapproval or real NAS connection was
  attempted in this test. User needs to exit existing FileHub safely,
  launch NEW shortcut, reconnect WebDAV R:, and compare R: with
  Synology X: capacity (expected 7.99 EB if quota unavailable).
  Verify actual remote file operations separately; mock tests do not
  prove live account or NAS compatibility.

## 2026-11-21 KST FileHub OAuth branding approved, scope verification next

- User supplied Google Auth Platform > Branding screenshot for project nas-mount-514420. Verification state has green check with Korean text '브랜딩이 인증되었으며 사용자에게 표시되고 있습니다' (brand verified and showing to users); homepage, privacy policy, terms and authorized bangtanrun.com domain are retained; Save button is disabled and earlier screenshot displayed branding changes saved.
- This is branding approval only, NOT full https://www.googleapis.com/auth/drive restricted scope approval. User's External audience was previously Testing with test user(s). No evidence of Production publication / unrestricted Google Drive access.
- NEXT: In the Google Auth Platform left sidebar open '인증 센터' / Verification Center. Review Data Access / Drive Restricted scope verification and Google-specified evidence (scope justification, exact demo video, policy). Scope verification must be completed before calling the app ready for unrestricted public distribution. Do not press '앱 게시' as a substitute for scope verification; publishing unverified restricted scope results in warnings and 100-user cap. Source: https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification ; https://developers.google.com/identity/protocols/oauth2/production-readiness/overview .
- FileHub page availability and brand verification are confirmed, while general user OAuth approval remains in progress. No app source or Google settings changed by the assistant this turn.
## 2026-11-21 KST PC v1.3.127 release prep and Android v0.1.105

- User requested GitHub releases for the current verified direct Google/OAuth + WebDAV quota Windows build and Android aligned update; yellow notice only for Google settings, registration/login not disabled.
- PC UI: ModernProfileDialog.cs Google write mode checkbox label fit fixed, static yellow '현재 Google 인증 심사 진행 중 · 이용을 원하시면 관리자에게 문의하세요.' on Google tab only; Program.cs screenshot-free WinForms policy test confirms label width, correct scope and Chrome OAuth UI. PC version bumped 1.3.127; official workflow now requires encrypted publisher JSON and refuses to release an unconfigured login, with post-publish binary checks and policy tests. Google actual OAuth grant for ordinary users still requires publisher Google Cloud test-user allowlisting until verification approved.
- Publisher OAuth ZIP local 164214679 bytes sha256 47398A53A9CE3BEA49F40D7BD49F74E9849A609218A4C6EB37992753AAD194A1 at D:\FileHubAIO-PublisherBuildCache\Release-1.3.127-20261121\FileHub-GoogleDirect-PublisherTest-20261121-125236-3b42d075.zip. Automated publisher OAuth and login/WinFsp policy gates passed.
- Android source independent main branch v0.1.105, versionCode 106; signed APK 72615361 bytes sha256 74AA110A7EA2A451374335BD39E41CCFD4333D96557A2269DEC2A67D1494C740 at D:\FileHubAIO-PublisherBuildCache\Android-0.1.105-20261121\FileHub-AIO-v0.1.105.apk. Existing official v0.1.104 signing certificate SHA256 matches after signing release. Android debug unit tests and build passed. This is Android system SAF selected-folder Google Drive connectivity only, NOT an Android native full-drive OAuth mount; device provider support unverified. Existing Android NAS/MYBOX/TeraBox remain unchanged.
- Github repo is dj5927/FileHub-AIO; origin PC pc/windows at 503905b, Android main at 090cbe0. GitHub connector authenticated to owner dj5927 but local git push lacks credentials and gh CLI absent. Need push source through GitHub connector and upload stable PC ZIP + Android signed APK through a controlled artifact transport; user public NAS mapped X: earlier now unavailable. Never commit publisher JSON or Android private signing configuration. Verified normal website https://bangtanrun.com/filehub/ returns HTTP 200.
