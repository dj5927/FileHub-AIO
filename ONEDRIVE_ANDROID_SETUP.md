# OneDrive Android — Direct 0.1.148 / Play 1.0.31
Both editions use the system browser and Microsoft identity platform OAuth authorization code flow with PKCE (AppAuth). No relay, password field, copied cookies, or client secret. The publisher must register its own application first.

## Required publisher setup
1. Open https://entra.microsoft.com/ and go to App registrations > New registration.
2. Choose a name (FileHub AIO) and supported account types: accounts in any organizational directory and personal Microsoft accounts. Organizational policy may require administrator consent.
3. Authentication > Add a platform > Mobile and desktop applications (AppAuth, no broker). Register both exact custom redirect URIs:
   - com.nasmount.mobile:/oauth2redirect
   - com.filehubaio.mobile:/oauth2redirect
4. Add Microsoft Graph delegated permissions Files.ReadWrite and User.Read. The app requests offline_access to refresh access tokens. Do not create a client secret. Configure public client flows if required by the registration.
5. Provide the **Application (client) ID** (public GUID) to the publisher build using -PfilehubOneDriveClientId=GUID. The same registration can cover both redirect URIs; do not reuse another company's OAuth client ID.
6. Rebuild Direct and Play with this property, install and test on a real device. This initial build without a client ID intentionally displays a publisher registration message. Google Drive registration and TeraBox NDUS remain unchanged.

## Implemented / limitations
My Files root browsing, paginated folder lists, ordinary files, ranged download, folders, rename/move within the drive, recycle-bin deletion, upload with verified response and upload-session chunks, and overwrite revision checks. Signed download and upload URLs receive no Graph bearer token. OAuth state is encrypted with Android Keystore, outside portable backups. Shortcuts, OneNote packages, shared drive browsing and persistent interrupted-upload resume are not implemented. Work/school accounts depend on tenant policy. Real Microsoft sign-in and cloud operations require the publisher registration and physical device validation. The Graph tests use synthetic loopback responses only.
Official cloud artwork: flutter_ui/assets/cloud/NOTICE.md. The trademarks remain owned by the respective providers; brand-use requirements should be checked before public release.
