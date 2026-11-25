# Android edition separation — 2026-11-25

Base: Android v0.1.118, commit 3a7363a07b324ffb06efc209337f7c18fb4d6962.
Development branch: android/play. Existing public stable release and PC release are not modified by this work.

## Build matrix
| | Direct | Play |
|---|---|---|
| Application ID | com.nasmount.mobile | com.filehubaio.mobile |
| Version | 0.1.119 / 120 | 1.0.0 / 1 |
| targetSdk | 35 | 36 |
| Codec | existing NextLib + MediaCodec | Android MediaCodec |
| RAR/CBR | existing Junrar | external application |
| External video | Android content URI / read-only remote stream | same |
| OTA | existing public OTA or authenticated private channel | none; store updates |
| APK installer | existing functionality | excluded |
| Signing | existing Direct certificate | independent Play upload key required |

Build tasks: :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest :app:assembleDirectDebug :app:assemblePlayDebug :app:assembleDirectRelease :app:bundlePlayRelease. Direct release signing uses an external, untracked signing configuration. Play debug APK is for testing; Play release AAB is unsigned until the independent upload key is configured.

## Localization
Common offline catalogue app/src/main/assets/locale/ui.tsv; device locale chooses Korean, English, Japanese or Simplified Chinese. Other locales use English for translated entries. This is a first-pass migration, not a complete translation: remaining Korean literals, document WebView labels, dynamic error strings and layout QA require further work. User filenames and keys must not be translated.

## External codec scope
The Play artifact excludes FFmpeg/NextLib and Junrar. The implemented alternative delegates to installed external players and archive apps, with scoped read-only content URI access. This is not an in-process third-party codec loader. No downloadable executable plug-in is loaded. Any future external decoder interface must be reviewed against store policies and tested for signature/ABI compatibility. Direct retains existing features; personal distribution does not remove license obligations.

## Private Direct OTA
scripts/private_ota_server.py provides a loopback origin, intended behind an authenticated HTTPS reverse proxy. Generate a unique access code per authorized user with the token subcommand; store only the SHA-256 values in a private JSON array outside the web root and source repository. Do not put tokens, private GitHub credentials, signing keys or NAS passwords in the APK or repository.

Serve an authenticated manifest, signed APK and matching corresponding-source archive. Manifest fields: channel=direct, package=com.nasmount.mobile, versionName, versionCode, filename, size, sha256, sourceFilename, optional notes. Paths: manifest.json, download/<filename>, source/<sourceFilename>. The APK client checks the manifest, size, SHA-256, package, version and existing signer; private mode does not silently fall back to public GitHub.

Deploy the server and configure HTTPS, then distribute the migration release and provision each user's URL/access code in Settings > Direct updates. Confirm authorized access and denied anonymous access before removing existing public Direct APK assets. Keep public PC assets and public source available. Existing copies cannot be recalled. Source archive must meet applicable license obligations. No production server or access codes have been configured as part of this branch.

## Outstanding before Play production
Full locale coverage, actual-device playback/archive/TV tests, independent Play signing key, privacy/data safety/permission review, third-party notices and TeraBox API authorization review. No advertising, billing or production listing is enabled. Do not merge or create release tags until the edition-aware release pipeline is approved.
