# OneDrive Android — Direct 0.1.150 / Play 1.0.33
Both editions use the system browser and Microsoft identity platform OAuth authorization code flow with PKCE (AppAuth). No relay, password field, copied cookies, or client secret. The publisher must register its own application first.

## Required publisher setup
1. Open https://entra.microsoft.com/ and go to App registrations > New registration.
2. Choose a name (FileHub AIO) and supported account types: accounts in any organizational directory and personal Microsoft accounts. Organizational policy may require administrator consent.
3. Authentication > Add a platform > Mobile and desktop applications (AppAuth, no broker). Register both exact custom redirect URIs:
   - com.nasmount.mobile://oauth2redirect
   - com.filehubaio.mobile://oauth2redirect
4. Add Microsoft Graph delegated permissions Files.ReadWrite and User.Read. The app requests offline_access to refresh access tokens. Do not create a client secret. Configure public client flows if required by the registration.
5. The publisher registered FileHub AIO's own public client ID: 3f9925ba-c774-4f21-9ebc-6fa836770de5. The default Gradle configuration uses this non-secret ID. A self-hosted build may override it with -PfilehubOneDriveClientId=GUID.
6. On 2026-11-26 the portal screenshots confirmed both native redirect URIs, support for all organizational directories and personal Microsoft accounts, and delegated permissions Files.ReadWrite, User.Read and offline_access. No tenant-wide consent or client secret is necessary for ordinary personal-account testing. Actual token exchange and file operations still need physical device validation. The unverified publisher warning may restrict organizational accounts.


## Implemented / limitations
My Files root browsing, paginated folder lists, ordinary files, ranged download, folders, rename/move within the drive, recycle-bin deletion, upload with verified response and upload-session chunks, and overwrite revision checks. Signed download and upload URLs receive no Graph bearer token. OAuth state is encrypted with Android Keystore, outside portable backups. Shortcuts, OneNote packages, shared drive browsing and persistent interrupted-upload resume are not implemented. Work/school accounts depend on tenant policy. Real Microsoft sign-in and cloud operations require the publisher registration and physical device validation. The Graph tests use synthetic loopback responses only.
Official cloud artwork: flutter_ui/assets/cloud/NOTICE.md. The trademarks remain owned by the respective providers; brand-use requirements should be checked before public release.


## v0.1.151 / Play v1.0.34 connection reliability
After browser authorization the app explicitly validates the token, stable Graph
user/drive identity, encrypted credential readback and persisted profile visibility.
Retrying the same identity reuses the original profile. A failed save restores
prior encrypted state. An external-browser success refreshes the remote list
independently of the old Flutter editor. Token refresh runs on the main looper
with bounded worker wait; no global authentication monitor is held during the wait.
The settings page now shows sanitized recent runtime errors in both editions.
OneDrive stage codes OD01–OD30 contain only stage, exception class and numeric status,
not OAuth tokens, emails, response bodies or URLs. Actual device crash trace and
live Microsoft login have not been reproduced on the build host.


## v0.1.152 / Play v1.0.35 ANR and callback recovery
Device SDK 37 report showed Android exit reason 4 (ANR), OD01_START and
OD07_BROWSER only. AppAuth 0.11.1 browser service has a synchronous
CountDownLatch wait and may make browser Binder calls. This is a plausible
source of UI blocking, not proof of the exact device ANR stack.
The browser binding/management activity is replaced by an ordinary HTTPS
ACTION_VIEW and a narrowly scoped OneDriveRedirectActivity. AppAuth remains
only for OAuth request/PKCE and token state models. The encrypted pending
transaction persists the request and draft for 15 minutes; the callback
validates exact URI and constant-time state, consumes it before exchange,
and works if the original process was killed. Token exchange and refresh
use bounded HTTPS worker requests with no redirect and no UI wait.
Registered Microsoft redirect URIs, client ID and permissions are unchanged.
Both editions collect only sanitized ANR main-thread Java frames, when Android
provides them. Real SDK 37 Microsoft login still requires device testing.


## v0.1.153 / Play v1.0.36 consent and callback compatibility
User reported the browser returned to FileHub but the authorization waiting
screen stayed open; a previous Microsoft grant might already exist.
Explicit add/reconnect now requests prompt=consent and response_mode=query.
Routine token refresh is unchanged, and no Microsoft grant is revoked.
The callback accepts a host-only URI with or without its trailing slash and
an empty fragment, consistent with Microsoft documented redirect normalization
and the Graph query response example. Other paths, ports, schemes, nonempty
fragments and duplicate state/code remain rejected. The original registered
redirect URI remains unchanged for the token exchange.
If the browser returns without a callback, a retry/cancel UI replaces the
spinner; a retry generates fresh PKCE and state. Valid endpoint callbacks are
forwarded to the connection activity before parsing the query, so malformed
payloads produce a visible failure instead of being silently discarded.
Refs: https://learn.microsoft.com/en-us/entra/identity-platform/reply-url
https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow
https://learn.microsoft.com/en-us/graph/auth-v2-user
