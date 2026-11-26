# Google Drive Android integration — Direct 0.1.147 / Play 1.0.30

## Account linking
Remote storage > Add > Google Drive > optional display name > Connect Google account.
Android's official account picker selects a Google account on the device.
Google Play Services AuthorizationClient requests the Drive scope for that exact account.
The returned token is verified against Drive about.user.emailAddress/permissionId before
saving a profile. Access tokens stay in memory and are refreshed by Play Services; no
client secret, password, JSON file, embedded third-party OAuth identity, or relay server.
Existing links reauthorize the same account to preserve favorite and file identity.
Deleting a connection clears its local token/lookup cache; revoke granted access from
Google Account security if desired. OAuth connections are device-specific and must
be re-added after settings restore. Android devices without Google Play services cannot
use this provider.

## Required Google Cloud setup (not completed by the build)
Use the project's authorized Google Cloud Console account:
1. Enable Google Drive API.
2. Configure Google Auth Platform branding, audience, and data access:
   https://www.googleapis.com/auth/drive (restricted; needed for full file management).
3. For local testing, set External/Testing and add tester accounts in Audience.
4. Create Android OAuth clients with these package and certificate pairs:
   - Direct signed APK: com.nasmount.mobile
     SHA-1: 74:09:2A:44:EB:4C:0E:4C:B7:DE:A5:B4:DF:13:A9:21:5B:6A:4A:94
   - Play sideload/internal test APK: com.filehubaio.mobile
     SHA-1: 06:46:B6:77:B0:01:27:EB:8C:EB:71:64:98:E7:4E:00:98:67:79:75
   - Play-delivered APK: com.filehubaio.mobile
     Register the actual **App signing key certificate** SHA-1 from Play Console > App integrity.
     It can differ from the upload signing certificate above.
5. Test on a physical device with a registered account; confirm consent screen is FileHub AIO.
6. Before production, complete Google's applicable brand and restricted-scope verification.
   Testing audience grants generally expire after seven days; production is separate.
   Google determines whether any additional assessment is required. The implementation
   sends Drive API traffic directly from the device to Google. No authorization data is
   transmitted to the NAS OTA server. Do not use another app's OAuth client.

## Initial feature scope
My Drive hierarchy, pagination, folder creation, ordinary file download/upload,
overwrite, rename/move, trash (not permanent deletion), and ranged reads.
Existing file transfer and viewer paths integrate the provider. Native Docs/Sheets/Slides
are exported as Office files; drawings as PDF. Native document overwrites/rename and
lossy copy-then-delete moves are blocked. Unsupported native types/shortcuts cannot
be downloaded. Shared drives, Shared with me browsing, shortcuts resolution, quota UI,
and persistent interrupted-upload resume are not implemented in this first version.
Duplicate or path-incompatible names receive ID-based aliases. The application still
uses its existing path-based file transfer layer; operations can fail if files are renamed
concurrently. The short-lived name/ID cache is read-only; mutation lookup uses fresh lists.
Unknown-size uploads spool to app-private temporary storage and are cleaned after transfer.
No real account login or Google API end-to-end test is claimed until OAuth setup and device
test. The loopback API tests do not use any real Google account or access token.

References:
https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient
https://developers.google.com/android/guides/client-auth
https://developers.google.com/workspace/drive/api/guides/manage-uploads
https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification


## Official consent replay (Direct 0.1.149 / Play 1.0.32)
On explicit add/reconnect, Google Play Services supplies the official authorization UI
if consent is required. If the selected account already granted the requested scope,
the app offers **Use existing access** or **Approve again with Google**. Only the
second action revokes the selected account's Drive grant through the official
AuthorizationClient.revokeAccess API, then requests authorization again. It can
affect other clients using the same Google Cloud project. If Google does not return
a consent resolution, the app reports failure rather than claiming consent was shown.
Routine token renewal never revokes access and never opens this dialog.
For an OAuth verification recording, prefer a fresh registered test account, or remove
FileHub AIO access at https://myaccount.google.com/connections before reauthorization.
Show Google's actual consent screen in English, the correct application identity,
and actual scope usage on each applicable client. Do not substitute the app's
own explanatory dialog for the Google consent screen. The exact layout is controlled
by Google. Follow the review team's requirements for native Android clients.
Physical device verification of the new revoke/re-consent flow is still required.
