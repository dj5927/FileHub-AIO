# FileHub AIO · Android / Android TV

An open-source file manager and media/reading app designed for NAS, WebDAV, SMB, FTP, SFTP, MYBOX, and TeraBox. The Android and Android TV apps share one package.

## Features

- NAS and cloud file browsing, copying, moving, and background transfers.
- Video playback with subtitles, episode navigation and gesture controls.
- Comic, text novel and EPUB readers with device-local reading progress.
- Favorites, remote configuration, encrypted backup and same-LAN device-to-device settings transfer.
- Synology / QNAP share-link tools (subject to the NAS APIs and server configuration).
- GitHub Releases-based in-app updater (v0.1.85+).

## Download / updates

Get the latest signed APK from [GitHub Releases](https://github.com/dj5927/FileHub-AIO/releases/latest). On app launch, FileHub checks the latest published stable release, showing only a short notification when an update exists. You can download and install it manually from **⋮ → 업데이트 확인 (Check for updates)**. Installation always requires Android's confirmation and may require permission to install APKs from this app.

The release notes must contain the exact matching integer versionCode and a correctly named signed APK. For v0.1.112 the metadata is versionCode: 113 and FileHub-AIO-v0.1.112.apk. The updater checks SHA-256, package ID, Android versionCode, and signing certificate before opening the Android installer.

## Android v0.1.112: Steam Deck SFTP symlinked directories

- A real Steam Deck `/home/deck/.steam` listing contains several symbolic
  links pointing to directories (e.g., `root`, `steam`, `bin64`).
  Earlier FileHub Android versions treated them as ordinary files because
  SSHJ SFTP `RemoteResourceInfo.isDirectory()` only describes the link
  itself, not its target. Trying to open the link as a normal file raised
  a generic IOException.
- SFTP directory listing now resolves each symbolic link with a
  **single SFTP STAT** to classify the target, retaining the original
  alias path. Directory links show the folder icon and open with normal
  folder navigation; file links can be read as files. Other providers
  and ordinary file/directory listings are unchanged. The UI labels
  link items explicitly as `심볼릭 링크 → 폴더` or `심볼릭 링크 → 파일`.
- Dangling links and targets with insufficient permissions remain visible
  but display a link-specific error rather than aborting the entire
  directory listing. An inaccessible link is not silently misrepresented
  as a regular file.
- Link identity propagates through the selection/transfer model even when
  the target is a directory. For deletion, FileHub **never recursively
  traverses a symbolic-link directory**. The SFTP provider performs LSTAT
  at deletion time and unlinks the link using RM, without deleting the
  directory it points to. Cross-storage copy and compression of symlinks
  are deliberately refused to prevent loops and unexpected target data
  duplication. A same-profile non-overwriting move may rename the link
  itself without traversing it. The chmod editor is not offered on links.
- Added regression tests for directory links, file links, broken links,
  linked folder loops, ordinary folders, retained aliases and correct
  non-recursive deletion/copy protection. All features from v0.1.111
  (automatic SFTP login home and manual Linux chmod of normal files)
  remain in place. As before, a connected Android device is needed to
  confirm the actual remote Steam Deck behavior; publisher tests do
  not use the user's SSH credentials.

## Android v0.1.111: SFTP login home and Linux chmod permissions

- **Default SFTP opening location is the user's actual home directory**, not
  the server filesystem root. If the configured SFTP start/root path is
  blank, FileHub authenticates and asks SFTP `REALPATH .` for the actual
  login home (e.g. Steam Deck account `deck` returns `/home/deck`).
  Nothing is hardcoded: chrooted SFTP users and NAS home directories work
  as well. Once resolved, the home path is cached only for the app session.
  Existing explicit start/root path settings are preserved: the literal
  `/` starts from root, and a custom path keeps its previous semantics.
- Navigating from the home directory to `/home` and `/` via breadcrumbs and
  upward navigation remains possible. Remote favorites continue to open
  their exact saved paths, not the newly chosen default start directory.
  The SFTP settings field explains blank = account home, '/' = root.
- **File or folder -> select one -> More -> Linux file permissions (chmod)**:
  view the current server-side Unix mode and edit owner/group/others'
  read/write/execute bits with nine checkboxes or 3/4-digit octal such as
  `755`, `644`, `0755`. Shows an rwx preview and preserves existing
  setuid/setgid/sticky bits when using the checkboxes. It is one-item-only
  and does not recurse into a directory; it does not use sudo/SSH commands.
- Uses SSHJ SFTP `LSTAT` to read current 12-bit permission mode and
  `SETSTAT chmod` to apply it. Disallows chmod of symlinks (which could
  change the target), read-only profiles, and concurrent mode changes;
  re-reads server-side mode after mutation to confirm the update.
  An explicit warning is required before removing owner execute access
  from a directory. Server permissions and ownership still determine
  whether chmod is allowed.
- Tests cover octal parsing, rwx/special-bit display and preservation,
  home-vs-explicit-root profile settings, read-only chmod rejection,
  and root/parent navigation. PC version is unchanged. The exact Steam
  Deck's live chmod and home directory must be confirmed on the user's
  Android device; no device credentials were used during the build.

## Android v0.1.110: fix missing cryptographic algorithm during SFTP handshake

- Android v0.1.109 reported `SFTP-HANDSHAKE-NoSuchAlgorithmException` on a
  Steam Deck SFTP connection. The failure is during SSH cryptographic setup,
  not username/password authentication or directory permissions.
- SSHJ 0.41.x can bind its JCA operations to Android's deprecated system
  Bouncy Castle (BC) provider, where SHA-256 and other implementations are
  restricted on Android P and later. We now explicitly reset that binding
  and use SSHJ's upstream `DefaultSecurityProviderConfig`, which selects
  the platform-provided JCA algorithms without pinning deprecated BC.
- The app prioritizes `ecdh-sha2-nistp256` and other secure platform ECDH
  key exchanges before provider-dependent Curve25519/X25519, plus standard
  AES-CTR ciphers before optional ChaCha20; ECDSA host key verification and
  explicit first-use fingerprint approval remain mandatory.
- A preflight checks SHA-256, EC key generation/factory, ECDH, ECDSA
  signature, AES-CTR and HMAC-SHA256 availability **on Android** before
  attempting SSH. If a device still lacks an algorithm, sanitized
  `SFTP-CRYPTO-JCA-*` diagnostics identify the error without exposing
  user IPs, private paths or passwords.
- Verified the new crypto provider setup and algorithm preferences by
  completing a real **unauthenticated** SSH key exchange to the Steam
  Deck's OpenSSH 10.4 using pinned ECDSA host-key fingerprint. No actual
  password was used. This test ran from the publisher's Windows JVM;
  a physical Android device was not connected, so final on-device SFTP
  authentication still requires user field testing.
- Automated regression tests cover recovery from an explicitly poisoned
  provider, valid secure ECDH/AES setup, and sanitized crypto error codes.
  The temporary LAN/IP-specific test was removed before publication.

## Android v0.1.109: Steam Deck SSH transport compatibility & diagnostic codes

- The Steam Deck at the user-provided private address was independently
  verified to respond on SSH port 22 with OpenSSH 10.4; a deliberately
  unauthenticated SSHJ v0.41.2 test successfully reached the host-key
  verification stage. Its OpenSSH server offered ECDSA P-256 and
  Ed25519. No real device password was used in the test.
- SSHJ's default key-algorithm order prioritizes ssh-ed25519, which is
  affected by a documented Android Conscrypt key-encoding failure in
  SSHJ (upstream issue #1032). On Android, FileHub now advertises modern
  ECDSA P-256/P-384/P-521 and RSA-SHA2 host key algorithms before Ed25519.
  Ed25519 remains supported for hosts that have no alternative. This
  does not disable server-key verification, add permissive key acceptance,
  or enable legacy insecure crypto.
- Confirmed against that same SSH server, with a fake password and a
  locally discarded test-only probe: the selected SSHJ server host-key
  fingerprint changed from Ed25519 to the secure ECDSA P-256 key expected
  from OpenSSH's independent ECDSA negotiation. The diagnostic test
  contained the private IP and was removed before committing sources.
- FileHub now reports structured error codes for Android TCP route/refused/
  timeout, SSH handshake and key-exchange, password authentication, and
  SFTP directory listing. Raw SSH exception text, passwords, hostnames and
  sensitive remote file paths are not copied into error dialogs.
- 100% connection success is NOT claimed until the user tests the signed
  build on the actual Android device, where Wi-Fi isolation/Conscrypt
  behavior can differ from Windows.

## Android v0.1.108: fix Steam Deck SFTP host-key/SSH TransportException

- The Android SSHJ verifier previously threw an unchecked exception for
  **first-use host keys** and **mismatched host keys**. Because the callback
  runs on an SSH transport thread, this could be delivered as a generic
  TransportException rather than asking for the fingerprint. The verifier
  now returns false and saves the observed key, so the connection wrapper
  can emit the app's existing explicit first-connect fingerprint dialog.
- For Android Conscrypt Ed25519 keys, the host fingerprint is computed
  from strict RFC 8410 Ed25519 X.509 SPKI bytes converted into the OpenSSH
  ssh-ed25519 key blob. This addresses a documented SSHJ Android key-encoding
  failure without disabling SSH signature validation or host key pinning.
- A changed server key is still rejected and must not be accepted
  automatically. RSA/ECDSA remain under SSHJ's existing encoding.
- Added targeted SFTP error explanations (authentication, SSH algorithm
  negotiation, Ed25519, changed or unknown host key), replacing a generic
  TransportException-only popup. Corrected error dialog branding to
  FileHub AIO.
- New unit tests cover first-connection approval, previously trusted key,
  changed-key rejection, provider-specific EdDSA wrapper, malformed keys
  and RSA compatibility. FileZilla may connect with a differently
  configured SSH key algorithm, so real Steam Deck connection is the
  final device-specific validation and is not claimed from unit tests.

## Android v0.1.107: TeraBox sign-in cleanup, MYBOX help, SFTP charsets and WebDAV locks

- Removed the TeraBox **Chrome Google account login** button: Android's
  Chrome cannot return its TeraBox ndus session to FileHub. TeraBox's own
  in-app web login and the manual PC ndus instructions remain available.
- Added a compact **PAT 발급 방법** action to the bottom of the MYBOX
  connection dialog. Instructions match the Windows FileHub help: sign in
  to Naver MYBOX, Settings, account/personal access token management,
  create token, copy into FileHub. Never publish or screenshot the PAT.
- SFTP now exposes its own filename encoding selector; the chosen charset
  is passed to SSHJ via setRemoteCharset() for actual filename path packet
  encoding/decoding. Includes Korean CP949, EUC-KR and other commonly
  deployed legacy NAS codepages. Unsupported encodings fail explicitly
  rather than silently reverting to UTF-8.
- Android WebDAV has a server-native file lock toggle. For existing-file
  overwrites it sends exclusive RFC 4918 LOCK, validates the Lock-Token,
  submits the destination-tagged token (and strong ETag when present) in
  MOVE, and attempts UNLOCK on completion or error. Unsupported LOCK
  (HTTP 405/501) falls back to existing strong-ETag checks. Lock conflicts
  fail safely. Without either a strong ETag or a native lock, replacement
  is blocked. This protects **during file save/upload only**, not for
  an entire mobile editing session; it is not Windows WinFsp Office locks.
- Mock WebDAV server unit tests cover lock acquisition, conditional
  replacement, unlock, unsupported-server fallback, missing ETag and 423
  contention; SFTP codepage tests cover actual byte encodings.

## Android v0.1.106: Google Drive removed; NAS features and PC backups aligned

- Google Drive and OneDrive are **not supported** on Android. The experimental
  Google Drive system folder picker added in v0.1.105 was removed. Legacy
  Google connection data is hidden, not deleted from the user's device.
- SMB, FTP, SFTP, WebDAV, TeraBox, and MYBOX remain supported. FTP now
  supports explicit FTPS and implicit FTPS (hostname/certificate verified),
  PASV/active selection, and UTF-8/CP949/EUC-KR filename encodings.
- Read-only remote profiles block upload, rename, folder creation and delete.
  WebDAV reads RFC 4331 actual server space without inventing local disk
  capacity; concurrent overwrite checks strong ETags before and after
  staging and uses RFC 4918 tagged conditional MOVE. Servers without
  strong ETags cannot safely overwrite an existing document.
- Android .fhbackup uses the same AES-GCM/PBKDF2 encrypted envelope and
  common NAS account schema as Windows v1.3.128. Both directions passed
  ciphertext interoperability tests. Windows-only Google Drive and
  OneDrive login credentials never enter a shared backup.
- Platform limits remain: Android is not a Windows WinFsp drive, and
  Windows Office open-handle/Explorer lock semantics do not apply on Android.

## Android v0.1.105: Google Drive system picker and verification notice

- The remote storage page now clearly marks the publisher's Google Drive
  restricted-scope API approval as under review and directs interested users
  to contact the publisher. This notice does not block other remote storage
  connections or FileHub's existing functions.
- Google Drive folders may be linked via the Android system's
  **DocumentsProvider folder picker**, using an already signed-in Google
  Drive app. The user chooses a folder and grants persisted read/write
  permissions. FileHub stores only its URI (not OAuth JSON or passwords).
- This folder-only Android SAF integration is **not** the Windows app's
  full Google Drive API mount. It depends on the device's Google Drive
  provider supporting tree selection and does not expose Drive folders
  outside the selected tree. If no compatible provider is available,
  no Google Drive connection can be registered on that device.
- Existing Android NAS, MYBOX and TeraBox connections and local files are
  untouched. Android publisher OAuth client approval remains separate.

## Android v0.1.104: gallery-style photo swiping

- Normal folder photos now use a finger-tracking horizontal filmstrip. The
  previous/current/next images move together with the finger, then snap
  into place with a short decelerating animation.
- The current photo is never cleared when navigating to the next one. Both
  neighboring photos pre-load in parallel, including remote NAS folders.
- If a remote image is still downloading, the current photo stays visible
  instead of revealing a black frame. On completion it slides in normally.
- Pinch-to-zoom, panning a zoomed image, and double-tap zoom remain separate
  from gallery navigation. Edge swipes do not switch folders or wrap around.
- Motion tests verify adjacent photos maintain exact viewport spacing,
  finger tracking, quick flings, pinch/pan exclusion and no blank-page drag.

## Build

Requirements: JDK 17, Android SDK (compileSdk 37 / Build Tools 36.0.0), Gradle 9.9.1 with verified distribution SHA-256, and network access for dependency resolution. The app continues targeting Android 35 until runtime behavior migrations are explicitly tested.

The checked-in `app/office-assets/*.gz.partXX` files are lossless, split copies
of the bundled HWP viewer's WebAssembly runtime. Gradle reconstructs the original
WASM assets and verifies their SHA-256 digests before packaging. There is no
need to download binary assets from an untrusted site.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Release builds require **your own private signing configuration**, kept outside this source repository (`../signing/signing.properties`). Never commit `.jks`, passwords, API credentials or a user's app settings. A debug-signed APK cannot update a release-signed installation.

## Android v0.1.101 dependency refresh (November 2026)

- Android Gradle Plugin 9.5.0 and Gradle 9.9.1 with pinned SHA-256.
- AndroidX Core 1.19.1, DocumentFile 1.1.0, OkHttp/MockWebServer 5.5.1, SSHJ 0.41.2, Commons Net 3.13.0 and test-only org.json 20260814.
- ZXing Core 3.5.4 now uses Maven Central instead of the obsolete local 3.5.3 JAR.
- Embedded JSZip updated from official npm to 3.10.2. The bundled docx-preview was verified byte-for-byte against official npm 0.4.1. SheetJS 0.20.3 and rhwp 0.9.0 remain at their checked latest releases.
- Media3 1.11.1 and NextLib FFmpeg 1.11.1-0.18.0 remain paired to avoid codec and ABI regressions.
- 528 JVM tests ran during the upgrade. MockWebServer 5 changed the default loopback hostname; test-only fixtures now pin 127.0.0.1, preserving strict production upload-host validation.
- Gradle dependency updates are checked weekly by Dependabot. It creates PRs with automated debug builds/tests, never directly signs or publishes an APK.

## Licensing

This project is distributed under GNU GPL version 3.0; see [LICENSE](LICENSE). Third-party packages have their own notices in [THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES). In particular, the NextLib/FFmpeg extension requires careful GPL-3.0 compliance; review licenses of other bundled dependencies and media assets before redistributing modified builds.

## Privacy and backup

Your NAS/cloud credentials are stored in Android's private encrypted app storage and are **never** included in the source repository. Device playback/reading history is local and intentionally excluded from transferred settings. The application itself does not require an account on this GitHub repository.

## Support

This is an actively developed community release. Features may be incomplete, and compatibility depends on the NAS provider and Android device. Report reproducible problems using GitHub Issues; do not include account passwords or tokens in screenshots or logs.

### Android v0.1.113: Steam Deck SFTP folder browsing speed

The Android SFTP view no longer opens an additional SSH connection for each visible subfolder solely to count items. Symlink directory navigation and file metadata remain available. See the v0.1.113 OTA release for the signed APK.
