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

The release notes must contain a line `versionCode: 86` (or the next Android version code), and the release must have a signed APK named `FileHub-AIO-v0.1.85.apk` (or the matching tag version). The updater checks SHA-256, package ID, Android versionCode, and signing certificate before opening the Android installer.

## Build

Requirements: JDK 17, Android SDK (compileSdk 36), and network access for Gradle dependency resolution.

The checked-in `app/office-assets/*.gz.partXX` files are lossless, split copies
of the bundled HWP viewer's WebAssembly runtime. Gradle reconstructs the original
WASM assets and verifies their SHA-256 digests before packaging. There is no
need to download binary assets from an untrusted site.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Release builds require **your own private signing configuration**, kept outside this source repository (`../signing/signing.properties`). Never commit `.jks`, passwords, API credentials or a user's app settings. A debug-signed APK cannot update a release-signed installation.

## Licensing

This project is distributed under GNU GPL version 3.0; see [LICENSE](LICENSE). Third-party packages have their own notices in [THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES). In particular, the NextLib/FFmpeg extension requires careful GPL-3.0 compliance; review licenses of other bundled dependencies and media assets before redistributing modified builds.

## Privacy and backup

Your NAS/cloud credentials are stored in Android's private encrypted app storage and are **never** included in the source repository. Device playback/reading history is local and intentionally excluded from transferred settings. The application itself does not require an account on this GitHub repository.

## Support

This is an actively developed community release. Features may be incomplete, and compatibility depends on the NAS provider and Android device. Report reproducible problems using GitHub Issues; do not include account passwords or tokens in screenshots or logs.
