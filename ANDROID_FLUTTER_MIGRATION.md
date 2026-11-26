# FileHub AIO Android Flutter migration

Branch: android/flutter-migration
Baseline: d68a11a51641d43d59a6a4313aad1b778cac6997

## Scope

Both Android distribution variants share the same Flutter module. The
Direct package com.nasmount.mobile retains its original signing identity,
OTA behavior, codecs and RAR implementation. The Play package
com.filehubaio.mobile retains its separate signing identity, entitlement,
consent, ads, Play Billing, and restrictions. No production publication
from this migration branch. The existing release sources remain separate.

Windows, iOS, macOS, Linux, web and TV UI migration are out of scope for
this first phase. Android TV continues using the established native UI.

## Phase 1 implementation

- Flutter connection list on the Android phone/tablet home remote-storage
  entry, including upgrade, add, manage, backup, native-list fallback and
  existing-profile navigation.
- Flutter language/theme settings using existing native preferences.
- Versioned method channel com.filehubaio/ui.v1; only allowlisted actions
  and non-secret profile metadata cross the boundary. The Java host
  resolves the saved profile again and rechecks entitlement.
- The existing Java filesystem, transfers, locking, Office save integrity,
  players, readers, native settings and other functionality remain intact.
- The existing native Play banner host wraps the Flutter Activity;
  verify the real Activity lifecycle, ad sizing and inset behavior on device.

## Build environment

Use the official Flutter 3.50.1 stable / Dart 3.14.0 SDK. Keep its official
release archive checksum and the Dart dependency lockfile. The Flutter
module is an add-to-app AAR, not a separately installed Android app.
The generated flutter_ui/.android and .ios directories and build output
are not versioned. The root Gradle project consumes the local AAR repository.

On a clean checkout, set ANDROID_HOME to the installed Android SDK, add
Flutter's bin folder to PATH, and use JDK 17. From flutter_ui:

```powershell
flutter create --template=module --org com.filehubaio --project-name filehub_flutter .
flutter pub get
flutter analyze
flutter test
flutter build aar --no-profile
```

From the repository root, configure the normal Android SDK location.
Use the existing, private publisher signing configurations by reference:

```powershell
.\gradlew.bat :app:assembleDirectRelease :app:assemblePlayRelease :app:verifyPlayReleaseBundleQuality :app:testDirectDebugUnitTest :app:testPlayDebugUnitTest --no-daemon -PfilehubPlayInternalTest=true "-PfilehubPlayReleaseSigningConfig=<private play signing properties>" "-PfilehubReleaseSigningConfig=<private direct signing properties>"
```

Never commit a signing file, password, actual customer connection profile,
or production ad credential. Check the APK/AAB contents, signature,
version and R8 scores before any subsequent distribution decision.

## Phase 2 implementation

- Phone/tablet home storage tiles and assigned media/book shelves now render
  in Flutter within the existing MainActivity. Keep the native toolbar,
  transfer chip, mini music player, OTA and Play banner host in place.
- Side navigation uses a Flutter surface with the original logo assets,
  active remote connections, favorite open/edit/reorder and persistent
  collapsed/expanded sections. Existing native actions remain authoritative.
- Home and drawer surfaces have independent, bounded engines and a native
  fallback. Show Flutter only after its snapshot is rendered and the first
  frame is available; restore the native UI on initialization failure or
  timeout. Native TV navigation is unchanged.
- Favorite IDs are opaque hashes; only display metadata crosses the
  presentation boundary. Resolve the live favorite set and validate the
  complete permutation before persisting order. Resolve live remote IDs
  and enforce entitlement when opening them.
- For Flutter-origin remote browsing, Back at the remote root returns to
  the Flutter connection list. Native-origin browsing retains native Back.
- Direct test version 0.1.124 (code 125); Play test version 1.0.9 (code 10).
  Both remain local test candidates. Real-device lifecycle, focus, rotation,
  banner insets, native fallback, startup latency and memory are not yet
  verified.

## Phase 3 implementation

- Add/edit for SMB, FTP/FTPS, SFTP, WebDAV, TeraBox and MYBOX in a shared
  Flutter screen on phones/tablets. Protocol-specific fields, ports,
  charset selection, TLS/PASV, read-only and root validation are retained.
- Native RemoteEditorPolicy validates all incoming draft fields before
  updating SecureStore. Keep existing encrypted credentials without
  returning their values to Dart. A changed endpoint/account/security mode
  requires explicit replacement; clearing an existing nonempty password
  requires the explicit passwordless option. Existing NAS sharing and
  other non-editor metadata are preserved. Resolve the profile again
  and reject stale editable revisions and unauthorized free-edition saves.
- Native RemoteCloudUi is extracted from MainActivity; both editors share
  the established TeraBox WebView login navigation policy and PAT/PC help.
  Native-acquired session values stay in the editor's Java memory until
  explicit Save. Only the acquisition status crosses to Flutter. User
  typed replacements traverse the method channel only during save; no
  credentials are written to logs, Intent extras, or UI state persistence.
- Keep classic editor fallback and TV native. Phone editor window has
  FLAG_SECURE. The existing Java storage, connection, lock, transfer
  and document engines are not rewritten in this phase.
- Direct local test version 0.1.125 (code 126); Play local test version
  1.0.10 (code 11). Actual Android UI/device, upgrade and six live provider
  connections remain unverified until a device is available.

## Phase 4 implementation

- Shared phone/tablet Flutter explorer for native local, SAF/SD, remote
  and media listings. Native enumeration, sorting, permission requests,
  folder cache, entitlement checks, opening files and transfer engine
  remain authoritative. Native toolbar retains refresh, recursive search,
  sort and folder settings.
- Immutable directory revision and opaque entry/ancestor IDs; requests
  cannot specify arbitrary file paths. Bounded 60-row pages with lazy
  list/grid rendering, continuation, empty state and retry. Existing
  native first page remains available as initialization/fallback UI.
- Existing native selection and operation chrome, select-all across
  the filtered directory, selection retained while loading more pages.
  Native readers and episode queues retained. Preserve size/date,
  assigned folder badges, reading progress and subtitle indicators.
- Reuse native thumbnail loader and folder count cache, limiting remote
  image reads to the existing cap and bridge images to 192px / 160KiB.
  Limit preview requests to six in flight; discard stale results after
  leaving the directory. Flutter caches a bounded number of previews.
- Direct test 0.1.126 (code 127), Play test 1.0.11 (code 12). TV and
  manual classic explorer remain native. Actual device rendering,
  engine start latency, very large NAS directory performance, SAF
  permission flows, thumbnail performance and transfer parity still
  require device/live-server verification.

## Phase 5 implementation

- Flutter selection header, select-all/clear and copy/move/rename/delete/
  more operation bar, staged clipboard and paste/cancel controls. Native
  selection and clipboard remain authoritative across directory revisions.
  Typed commands accept only the current opaque directory revision, not
  arbitrary paths or replacement selections; validate writable storage
  and active remote entitlement before committing operations.
- Native-owned, one-shot Flutter confirmation/filename/error prompts for
  explorer operations. Delete and overwrite retain explicit confirmation;
  native filename validation and file I/O remain authoritative. Native
  classic dialog is available before Flutter readiness and as fallback.
- Transfer progress uses native FileTransferService state, updates on
  changes, shows bytes, item counts and cancellation state. Unknown totals
  stay indeterminate, and 100% requires genuine successful completion.
  Hide/reopen through the existing native transfer chip; cancellation,
  background transfer and notifications retain native service ownership.
- Existing native additional-actions menu, waiting dialogs, permissions,
  toolbar, TV, transfer engine, Office save protection and locking remain.
  Direct test 0.1.127 (code 128), Play test 1.0.12 (code 13).
  Device keyboard/focus/insets, confirmation lifecycle and live transfer
  parity still require actual device/server verification.

## Phase 6 implementation

- Shared FlutterViewerChrome and ViewerPanel for the image, video,
  comic, TXT-book, EPUB and PDF readers. Exactly one bounded embedded
  Flutter surface per viewer; native bar remains as startup/timeout
  and manual fallback. Preserve the parent bar's original dimensions,
  visibility, alpha, Media3 controller and PiP behavior.
- Image title/back and adjacent-photo controls call the existing native
  slide/prefetch path. Video top controls expose play/pause, back and
  existing settings, and preserve optional episode buttons. Media3's
  native seek bar, side controls, touch lock, subtitle/audio settings,
  rendering, codecs, playback and resume decisions remain native.
- Comic and TXT page controls share the original native seek calculations,
  with a Flutter slider committing only on release; EPUB/PDF page controls
  and quick-navigation invoke the existing native actions. Reader headers,
  bookmarks, style/settings dialogs, body renderers, transitions, pinch,
  scrolling, WebView security, progress/history and ads remain native.
- Commands are scoped to an opaque per-viewer nonce, checked against the
  enabled command set and current lifecycle/readiness; seeks accept only
  integer values 0..1000. Poll presentation state on the main thread while
  visible and resumed, push changed values only, stop when paused/destroyed.
  No file contents, paths or credentials cross the new bridge.
- Direct test 0.1.128 (code 129), Play test 1.0.13 (code 14).
  Native TV behavior unchanged. Actual device gestures, rendering,
  seek parity, PiP, rotation, controller hiding and latency still require
  device/live-media testing. These are partial UI migrations, not a
  replacement of the native viewer engines.

## Phase 7 implementation

- Shared Flutter document toolbar for built-in Office/HWP and text editor.
  Existing WebView/WASM rendering and native text editor body remain
  authoritative. Spreadsheet save calls the original JavaScript request
  and existing native staging/integrity/lease pipeline; the button is
  disabled while loading, busy or read-only. No document payload crosses
  the new Flutter UI channel. Existing native close confirmation retained.
- Flutter external Office bridge workflow with native staging, compatible
  editor launch, explicit commit and status. Save eligibility follows the
  original prepared file, active save and lease/remote read-only state.
- Settings list and four category tabs use NativePanelModel and a bounded
  Flutter surface. Choices commit only after Apply, invoke the same
  existing preference setters, and reject stale revision/invalid indices.
  Existing language/theme screen, advanced native picker dialogs,
  edition-specific codecs/updates/billing controls, native reader preview
  and font preview remain accessible. Preview opens a native dialog rather
  than occupying the inline list. Native TV and manual classic fallback
  remain.
- Direct test 0.1.129 (code 130), Play test 1.0.14 (code 15).
  Actual device rendering, keyboard, back/rotation and live Office
  save/lock/parity are not verified by unit/widget tests.

## Phase 8/9 candidate

- Start embedded Flutter only while the host is resumed and defer
  initialization timeouts while paused. Warm the loader asynchronously
  on the phone/tablet MainActivity path. Add a persisted classic UI
  switch without modifying storage, reader or connection settings.
- Preserve universal ABI compatibility. Optional filehubTestAbi selects
  a single ABI for a manually installed test package; never use it as
  the universal OTA payload. Play keeps all three ABIs in its AAB.
- Direct opt-in preview updates recognize only matching flutter-test-v
  GitHub prereleases and validate SHA256, package and signing identity.
  Stable OTA and configured private OTA are unchanged. Local release
  notes, metadata fixture, build/checksum report and device-test handoff
  are prepared separately from production.
- Direct 0.1.130 (code 131), Play 1.0.15 (code 16).
  No device parity or measured startup/memory gains are claimed.
- Actual device verification, GitHub publication and production Play
  acceptance remain separate gates; see FLUTTER_TEST_RELEASE_HANDOFF.md.
  Ads/billing configuration and other platforms remain deferred.

## Device feedback hotfix

- First device test reported Play launch failure and visible white
  flashing while navigating Direct. No device/crash trace was attached
  to the build host, so the exact Play failure is not established.
- Keep the previous rendered home/explorer root attached and input-blocked
  for a bounded 1.8-second handoff. Immediately revoke its Flutter host
  commands and pending explorer actions. Remove it after the new surface
  is ready, on native destination, timeout, pause or destroy. No screenshot
  or content file is generated. Defer the Dart bootstrap raster frame
  until the first snapshot resolves or fails; match native/Flutter
  window colors and suppress redundant Flutter Activity transitions.
- Remove the user-visible classic-home action and bridge command.
  Preserve the internal initialization-failure fallback, native TV UI
  and overall classic interface recovery preference.
- Preserve Flutter Java/JNI embedding names and methods with R8 keep
  rules. Billing initialization is lazy and skipped when no purchase key
  is configured; ads/consent are initialized after initial layout and
  SDK failure is isolated from file management. No local full-version
  unlock. Keep production Play gated.
- Direct 0.1.131 (code 132), Play 1.0.16 (code 17). Provide the normal
  optimized test APK plus an optional unminified Play diagnostic APK
  from the same internal-test signing identity. The latter is prohibited
  from generating a Play bundle. Actual launch and visual transition
  fixes require confirmation on the reporting device.

## Second device feedback — persistent navigation and R8 isolation

- Device report: Direct 0.1.131 still flashed white on Flutter menu
  transitions; the native video/comic/text shelves did not flash.
  Optimized Play 1.0.16 still crashed on launch; its no-R8 counterpart ran.
- MainActivity keeps one attached body FlutterView/engine through home,
  explorer, and assigned-folder navigation. Rebind a native host and
  epoch instead of tearing down and restarting the renderer. Keep the
  native toolbar, operation chrome, permission handling and storage
  engines. A previous body is input-blocked for at most 1.5 seconds;
  slow directories retain the native loading view. The layer stays
  attached when native-only destinations are shown.
- Embedded Flutter views now use a non-opaque TextureView with the
  original Flutter surface listener preserved. Display requires the
  current snapshot acknowledgement plus an actual texture-buffer update.
  Old route actions are rejected by epoch, and explorer/shelf operations
  remain revision-bound. Dedicated activities use texture rendering;
  drawer, dialog and viewer chrome still have independent surfaces.
- Video/comic/TXT assigned shelves now use Flutter folder rows and
  60-row bounded pages. Native folder-role catalog, open/confirmation/
  removal callbacks and original fallback retained. Visible folder
  display paths are labels only; commands use opaque revision/row IDs.
- Direct 0.1.132 (133) and Play 1.0.17 (18). Default internal Play APK
  now disables R8/resource shrinking, based on the device comparison.
  Explicit filehubPlayR8Trial builds a separate APK retaining app,
  Flutter and AndroidX startup classes, with resource shrinking off.
  Both internal paths block bundle generation; production guard and
  the production code-quality policy are not waived.
- Play Java fatal reports are recorded locally after Application attach;
  only exception types, stack class/method names, line numbers, version,
  trial flag and SDK level. No exception messages, file names, URLs or
  account credentials. Read/copy in Settings > General if present.
  Native process faults or earlier failures may not be captured.
- Real device compositor behavior and exact R8 crash cause are still
  unverified. New test APKs are local only, not a published release.

## Third device feedback — unified main navigation

- User still observed white flashes entering network connections/folders,
  opening the left drawer and opening main settings. Favorites from the
  home overflow menu still used the old list. The second R8 trial also
  failed at launch and its Java crash report row was absent.
- Connections, settings and favorites now bind to the persistent main
  Flutter body; the connection list no longer launches a separate
  Flutter Activity. The drawer is an overlay inside the existing Dart
  navigator/viewport, retaining the underlying screen and native toolbar.
  TV/classic and unavailable-surface fallbacks remain native. Ordinary
  remote folder reads and SFTP home discovery use inline loading instead
  of a separate waiting-dialog window; errors and OTP handling remain.
- Shared SettingsContent retains the existing preference methods, four
  tabs, document/folder pickers and edition-specific settings. MainActivity
  owns its lifetime and forwards picker results; the native SettingsActivity
  remains a fallback using the same controller. Language/theme settings
  use revision-bound choice callbacks instead of another Flutter Activity.
- Play keeps the known-working no-R8 internal build strategy. The error
  report row is always present in Settings General, with Java crash
  details if captured and Android API 30+ own-app historical exit reason,
  status and time. Empty reports explicitly say so. No log upload.
- Direct 0.1.133 (134), Play 1.0.18 (19). Both local APK builds pass;
  Flutter 31 tests, Java 1240 passed and 2 skipped. R8 root cause is
  unresolved; no new trial or AAB. Real-device flicker and settings/picker
  lifecycle parity still require validation.
