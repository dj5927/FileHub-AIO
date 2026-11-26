# FileHub AIO Play — release preparation (2026-11-25)

Flutter phase 9 internal candidate: v1.0.15 (code 16), application ID
com.filehubaio.mobile. The v1.0.15 AAB is independently upload-signed;
its measured uncompressed DEX is 10,775,440 bytes with R8 scores 82.55%
obfuscation, 93.14% optimization and 82.53% shrinking. All three ABI
payloads are retained. Production configuration remains blocked.
See FLUTTER_TEST_RELEASE_HANDOFF.md and PLAY_STORE_LISTING_DRAFT.md.
Device validation, publication and license/provider review are not complete.

This checklist is for the Play edition, not the separately signed Direct APK.
The current internal candidate has no live advertising IDs or licensing public
key. It must not be promoted to production as-is.

## Verified technical quality

Google Play's February 2027 requirement applies to non-game bundles above
10 MB of uncompressed DEX. Each of the three scores must be at least 25%.
The app's v1.0.5 R8 metadata reports 82.47% obfuscation, 93.44% optimization,
82.48% shrinking, and 10,421,524 bytes of DEX (from 33,474,264 bytes,
68.87% reduction). The four manual UI languages are packaged together:
language splitting is disabled so manual language switching does not require
downloading a separate language resource split from Play.
These scores are from the actual r8.json, not the DEX size reduction.

- Run `python scripts/audit_localization.py --check --all`.
- Run `python scripts/test_verify_play_bundle_quality.py`.
- Run both edition unit tests and release lint.
- Run `:app:verifyPlayReleaseBundleQuality` with internal-test signing flags.
- Production `:app:bundlePlayRelease` automatically finalizes with the quality gate.
- Retain the private mapping.txt and configanalyzer report for each release.
- Check the real App Bundle Explorer after upload.

References:
https://support.google.com/googleplay/android-developer/answer/17492799
https://developer.android.com/google/play/vitals/code-optimization

## Permission declarations (draft, not submitted)

File management is the app's core purpose: browsing, moving, copying,
renaming, deleting and organizing arbitrary local and remote files and folders.
The Storage Access Framework supports selected folders but does not provide
equivalent arbitrary local filesystem navigation for all user-accessible
directories. MANAGE_EXTERNAL_STORAGE is therefore used for the full file
manager mode, with explicit user-granted special access and the SAF fallback.
Google approval is still required. Document the actual behavior in the listing
and demonstrate the permission request and file manager operation in the review
video.

READ_MEDIA_IMAGES and READ_MEDIA_VIDEO are currently declared for the dedicated
local media library and browsing workflow; confirm persistent access is
necessary and document why a single-item picker does not meet that purpose.
READ_MEDIA_AUDIO is used for the music library. CAMERA is for user-initiated QR
scanning. SYSTEM_ALERT_WINDOW is optional for the video Home-to-PiP choice,
not advertising. Foreground service types are mediaPlayback and dataSync for
user-requested playback and file transfers respectively. Review the actual
merged manifest, including Google Ads/Billing SDK permissions and declarations,
on each production build. No REQUEST_INSTALL_PACKAGES in Play; no Direct OTA.

References:
https://support.google.com/googleplay/android-developer/answer/10467955
https://support.google.com/googleplay/android-developer/answer/16558241

## Data safety / privacy worksheet (owner must confirm before publication)

- User-supplied NAS addresses, usernames, passwords, cloud sessions and tokens:
  used for user-requested connections to selected servers/providers. SecureStore
  encrypts secret fields using Android Keystore AES/GCM. Connection metadata and
  preferences are retained locally. Explicit backup exports and LAN/TV pairing
  are additional flows. Do not state that every file or metadata field is
  encrypted at rest.
- Files, folder names, documents, images, video/audio, reading and playback
  position: used for user-directed file operations, previews and playback.
  Remote transfer occurs to/from user-selected endpoints, and selected content
  can be shared with an external app via Android URI permissions.
- Optional Google Play purchase: Google processes the transaction. The app
  verifies and caches entitlement receipts; no card information is handled by
  the app. Production server-side receipt verification, if deployed, must be
  added to the disclosures and retention/deletion rules.
- Google Mobile Ads SDK Legacy 25.5.0 in Play only: Google's official disclosure
  includes IP address (approximate location), app interactions, diagnostics,
  and device/account identifiers, for advertising, analytics and fraud
  prevention. UMP manages applicable privacy choices. Actual production ad
  configuration, mediation, regional consent and SDK behavior must be reviewed.
  Do not answer "no data collected" just because the file manager itself uses
  a direct connection.
- Transport: HTTPS, SFTP and FTPS are available where configured; plain HTTP/FTP
  may be unencrypted. Do not claim all app traffic is encrypted.
- Local data removal: delete saved connections, clear app data or uninstall;
  separately delete any exported backups and remote files if desired. External
  providers and Google have their own retention/deletion controls.

See PRIVACY_POLICY_DRAFT_KR.md. Play v1.0.5 includes a privacy policy entry
under Settings > General, an in-app data handling summary in all four
supported languages, and an optional full policy link. Production preflight
requires filehubPrivacyPolicyUrl, an HTTPS URL without embedded credentials.
The URL format check does not verify that the page exists or is legally complete.
The draft still needs the actual publisher name, privacy contact, effective
date and public HTTPS URL; publish and confirm the same policy in Play Console.
The worksheet is not a final Data Safety submission or legal clearance.

References:
https://developers.google.com/admob/android/privacy/play-data-disclosure
https://support.google.com/googleplay/android-developer/answer/10144311

## External codecs and archive apps

Play uses Android's built-in media decoders and can hand a read-only file/stream
URI to an installed player or archive app. It does not load user-provided native
executables or bundle NextLib/Junrar. Direct retains the integrated codecs and
RAR implementation. Its import UI only archives external codec files, not
loads them. Do not market either as supporting arbitrary in-process codec
installation. Google prohibits external downloads of executable code in
Play-distributed apps; the exact policy implications of user-selected native
code require review, and arbitrary native loading is not implemented.

https://support.google.com/googleplay/android-developer/answer/16559646

## Still required before release

1. Test the R8-optimized APK on a real phone/tablet/TV, including all manual
   languages, fold/unfold, office JS bridge, SSH/SFTP/JCA, SMB, WebDAV, FTP,
   ZIP/7z, remote media seeking, external player/archive intents, PiP and
   backup/restore. R8 mapping proves method preservation, not device behavior.
2. Review the dependency inventory and actual upstream licenses and complete
   corresponding-source obligations; in particular review Direct NextLib,
   TeraBox/MyBox provider terms and bundled office assets. Do not assume the
   separate Play edition resolves all commercial license obligations.
3. Complete developer identity, Play Console listing, privacy URL, sensitive
   permission declarations, AdMob configuration, and product
   filehub_full_version at KRW 9,900. Upload test AAB and test real purchases
   (including restore/revoke/refund), consent, and ad placement.
4. Current internal signing uses the independent upload certificate, not the
   Play-managed distribution signing key. Do not deploy the local test APK as
   the public store build. Do not publish the R8 mapping publicly.

Current premium plan details and console configuration: PREMIUM_PLAN_V142.md.
