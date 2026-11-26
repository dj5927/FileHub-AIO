# Second device-feedback retest

Install over the matching existing package and signing identity; do not
uninstall or clear data. Use the archived APKs, not the last Gradle output.

1. Direct FileHub-AIO-v0.1.132.apk: navigate Home, local/remote folders
   (including nested folders), drawer, settings and connection editor.
   Check white flashes, back/home, light/dark, rotate/fold, fast switching,
   loading a slow NAS and transfer/music controls.
2. Open the video, comic and text assigned-folder lists. Check empty,
   populated and multi-page lists, folder open/back, long press/menu
   removal confirmation and no loss of existing assignments.
3. Play FileHub-AIO-Play-v1.0.17-test.apk is the normal no-R8 internal
   test build. Verify launch and existing free-edition restrictions.
4. Optional FileHub-AIO-Play-v1.0.17-r8-trial.apk is an isolated R8
   experiment, not the recommended daily test. If it crashes, reinstall
   the normal test APK over it, without clearing data, and check
   Settings > General > Recent crash report. Copy only the relevant report.
   If absent, use an authorized ADB connection and the crash log buffer.
   Native crashes may not be captured by the Java fatal handler.

No public GitHub OTA or production Play upload. The earlier optimized
1.0.16 AAB is not a known-working device build and must not be published.
The new internal APK-only modes keep bundle generation disabled pending
R8/device parity. The user has confirmed only the previous no-R8 APK,
not the new build; automated tests do not prove actual visual smoothness.
