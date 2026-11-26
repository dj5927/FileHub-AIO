# Optimized Android device regression — Play v1.0.6

Status: not executed. No device is attached to ADB, and the current SDK
does not contain an emulator/system image. The local APK uses the Play
upload signer, not the eventual Google-managed app signing certificate.
Install only on a disposable test profile/device. Do not uninstall an
existing app or clear its data merely to resolve a signature mismatch.
Use synthetic files and test accounts rather than production NAS data.

## Installation and UI

1. Install the optimized Play test APK alongside the Direct app (different
   package IDs). Launch, background, resume and relaunch. Check logcat
   for fatal errors, VerifyError, NoClassDefFoundError and security failures.
2. On Settings > General > Interface switch System, Korean, English,
   Japanese and Simplified Chinese. Check after process restart, and return
   from settings into a remote folder. Test narrow, wide, landscape, Fold
   and TV layouts; verify system language fallback.
3. Exercise new dynamic messages: conflicting names, upload replacement,
   remote delete, favorite removal, assigned folder removal, subtitle
   folder rules, bulk share revocation, resume video and next comic.
   Use a filename containing Korean, Japanese, spaces, apostrophes and
   a literal percent sign. Verify the filename is never translated,
   interpreted as a format string or altered.
4. Open privacy and open-source notices in each language. Confirm the
   full policy link is absent in the unconfigured internal build; verify
   actual upstream notices are present in the license dialog.

## Storage and media

5. Test WebDAV HTTPS, FTP/FTPS, SFTP (known host key), SMB and authorized
   cloud connections with disposable test folders. List, upload, download,
   move, overwrite, rename, delete and reconnect. Test error messages,
   cancellation, partial transfers and restored settings.
6. Open JPEG/PNG, TXT, EPUB, PDF, ZIP/CBZ and 7z. Test zoom, orientation,
   next page/volume, seek, read/write and book position restore.
7. Check Media3 hardware playback, audio/subtitles, resume, next video,
   full screen, volume, PiP and the user-approved overlay confirmation.
   Play has no bundled FFmpeg decoder and no RAR extraction engine.
8. Hand off a test local and remote video to an installed external player,
   and a RAR/CBR to an external archive app. Verify read-only URI access,
   seeking, missing-app handling, and that no NAS credential is forwarded.
9. Test DOCX/XLSX/PPTX/HWP/HWPX viewing and allowed editing with a legitimate
   full-version test entitlement where necessary. Verify JS bridge and
   native storage saving, lock handling, original file integrity, and
   Korean/English upstream editor behavior. Japanese/Chinese upstream
   editor UI may fall back to English.
10. Confirm free-tier connection limit, Office restriction, banner location
    and safe interstitial placement in the internal build with test ads.
    Actual purchase/restore/revocation requires Play Console license
    testers and configured product/public key; defer to the final stage.

Record device model, Android API, app version, language, action,
expected/observed result, and a redacted screenshot or log for failures.
Do not report PASS for an unexecuted step.
