# Device feedback retest — November 25, 2026

User observed: Play candidate 1.0.15 does not run; Direct 0.1.130
runs but whole-screen white flashes are visible on menu transitions.
The user prefers the new home design and requested removing the
old-home action.

## Retest

1. Install Direct v0.1.131 over the previous same-signer Direct APK.
   Confirm saved connections and file settings are intact.
2. Repeatedly navigate home, drawer, local storage, remote folders,
   assigned shelves, connection list/editor and settings; test both
   themes. A slow NAS may still show its loading view after the bounded
   handoff. Check the old home button is absent.
3. Install Play v1.0.16 optimized test APK over the previous internal
   Play APK. Check launch, menus, one-per-protocol limit and Office gate.
   No live payment key is configured. Test ads use the existing test IDs.
4. If Play still fails, use the separately named no-R8 diagnostic APK
   of the same internal version/signing identity. A difference between
   builds helps isolate R8 but does not establish a cause by itself.
   Never install an APK by uninstalling to resolve a signature mismatch.
5. With a USB-debugging device connected and authorized, inspect:

   ```powershell
   adb logcat -b crash -d
   adb logcat -d -s FileHubPlay FileHubFlutter AndroidRuntime
   ```

   Share only the crash block for com.filehubaio.mobile or relevant
   FileHub diagnostic messages. Remove unrelated logs, personal file
   names, URLs and credentials. Do not clear existing device logs.

These are retest steps, not device PASS results. Existing remote I/O,
Office save integrity, file locks, codecs and RAR edition separation
are outside this presentation/startup hotfix. The no-R8 APK is a
local diagnostic only, not an upload bundle or a public OTA asset.
