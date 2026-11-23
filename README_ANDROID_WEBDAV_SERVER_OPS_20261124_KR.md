# FileHub AIO Android - WebDAV server operation optimization

Date: 2026-11-24. Branch: android/webdav-server-ops-20261124.
Based on the published 0.1.116 source. This branch is an isolated source
patch for integration with the in-progress v0.1.117 Excel changes.
It is **not a signed APK or OTA release**; no version is bumped.

## Compared with PC FileHub AIO v1.3.138

Android's foreground transfer service is already synchronous with the
network operations it performs: WebDAV PUT must receive HTTP success and
the final MOVE must receive HTTP success before the operation is counted
complete. The Android byte-progress formatter caps at 99% until the entire
transfer succeeds. PC's WinFsp local write-behind / Explorer-completion race
therefore must NOT be copied into Android.

## Changes to Android WebDAV

1. Delete a WebDAV collection with one server-side DELETE of the directory,
   rather than recursively enumerating thousands of descendants and
   sending one DELETE per child. Files outside WebDAV retain their existing
   provider-specific deletion policy. The server's HTTP acknowledgment
   is still required before reporting success.
2. WebDAV DELETE HTTP 207 Multi-Status is treated as an incomplete/uncertain
   deletion rather than an unconditional success; partial failures must not
   be displayed as completed.
3. Same-account WebDAV COPY of a file or directory uses the NAS's native
   COPY command with Overwrite: F. Directory copies use Depth: infinity.
   This avoids re-downloading/re-uploading content through the phone.
4. A WebDAV server that explicitly returns HTTP 405 or 501 to COPY
   falls back to the existing streaming copy. Unsupported COPY is remembered
   per account for the rest of the batch, to avoid a failed request per file.
5. HTTP 207, 409, 412, 423, network timeouts, and other uncertain responses
   do **not** trigger blind COPY fallback/retry. Completion is counted only
   for accepted HTTP 201/204 responses.
6. Existing WebDAV upload with staging PUT -> server-side MOVE, Android
   progress 99%-until-success, and cancellation semantics remain intact.

## Verification

- Android Debug Java compiler: PASS
- FileTransferEngineWebDavTest: PASS (local MockWebServer)
- TransferProgressFormatTest: PASS
- Entire Android debug unit-test suite on the isolated branch after
  directory COPY addition: **593 tests, 0 failures, 0 errors, 1 skipped**,
  in 123 test suites, Gradle BUILD SUCCESSFUL.
- A disposable local WebDAV server accepted file COPY and recursive
  directory DELETE. Real Synology and Android device speed remain untested.

## Release coordination

Do not replace the v0.1.117 Office/Excel staged work with this branch.
Merge or cherry-pick its commit after the Office work is ready, then build
and sign an incremented Android version using the existing trusted publisher
signing process. A debug APK is not a drop-in OTA replacement for the user's
signed installed app.
