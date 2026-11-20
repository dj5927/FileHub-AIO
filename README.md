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

The release notes must contain the exact matching integer versionCode and a correctly named signed APK. For v0.1.103 the metadata is versionCode: 104 and FileHub-AIO-v0.1.103.apk. The updater checks SHA-256, package ID, Android versionCode, and signing certificate before opening the Android installer.

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
