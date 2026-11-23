# FileHub AIO Android v0.1.115 - WebDAV Excel locking and spreadsheet UX

## Scope

User reports after Android 0.1.114:

- WebDAV settings still showed the obsolete experimental "server file lock"
  toggle. It is unrelated to Microsoft Excel owner-file interoperability.
- Some Synology WebDAV HEAD responses omit a strong ETag. Android used to
  create its Excel ~$ owner and then throw during owner HEAD validation,
  leaving a stale marker that blocked the next open.
- The built-in editor used several permanent title/status bars, paginated
  55 rows x 12 columns and hid sheets inside a dropdown.
- The original workbook's cell fills, fonts, widths, heights and styles
  were not rendered or faithfully preserved on export.

## Excel/WebDAV changes

1. Removed native RFC4918 LOCK/UNLOCK mode UI and engine paths. Existing
   stored/backup profiles with useNativeWebDavLocks=true are safely imported
   as false. Every writable Excel session uses the genuine ~$ owner file.
2. Strong file ETags may come from HEAD or depth-zero PROPFIND DAV:getetag.
   A NAS without either still permits reading the document: it must not
   create a marker or fail document staging solely because of missing ETag.
   Saving is disabled if the original document cannot be protected with
   a strong validator.
3. Android's Excel owner is a unique 165-byte Office-style binary marker
   created conditionally with If-None-Match:*. Its bytes are verified via
   GET, even when the NAS omits the owner file's ETag header.
4. Normal close removes only the verified, locally owned marker. Use
   If-Match when the server provides a strong ETag; otherwise verify the
   unique marker bytes before deletion. Up to three short cleanup attempts
   handle transient NAS errors. Do not automatically remove markers
   after an abnormal device shutdown.
5. Read-only mode shows a single small lock icon next to the filename,
   not two extra sticky status bars. The Save action is unavailable.
6. Verify the original workbook's ETag at the start of each save and again
   before a conditional MOVE. A changed remote revision is never silently
   overwritten.

## Spreadsheet screen

- Full used worksheet dimensions are represented in a continuous scrollable
  surface, not pages. Rendering is virtualized so even very large row
  ranges do not create millions of DOM elements.
- One finger pans vertically and horizontally; two fingers pinch to zoom
  just the spreadsheet. The previous row/column paging buttons are gone.
- Worksheet tabs appear along the bottom and can scroll horizontally.
- Row heights, column widths, solid fill colors, font colors, weights,
  sizes, supported alignments, borders and merged cells are obtained from
  the original OOXML worksheet/styles metadata.
- XLSX saves patch ONLY changed cell XML into the original ZIP document
  and preserve untouched original styles.xml, other sheets, drawings,
  images, and unknown workbook components. No-edit saves reuse the
  original archive bytes. After a confirmed save, the source baseline is
  updated for subsequent saves. Legacy XLS still uses the earlier editor.

## Validation

- Android Gradle testDebugUnitTest: 587 tests total, 0 failures, 1 skipped.
  WebDavExcelLeaseTest: 10 tests, 0 failures. Includes HEAD/PROPFIND,
  missing owner ETags, conditional owner creation, read-only fallback,
  altered workbook/owner and normal deletion.
- Headless Chromium OOXML test fixture generated using openpyxl:
  yellow fill, red bold text, centered alignment, row height, column
  width, merged cell, virtual scroll, zoom, alternate sheet, lossless
  editing and untouched styles.xml all passed.
- JavaScript syntax checks of office-host.js, excel-grid.js and
  excel-ooxml.js passed.
- Release APK must be signed with the same Android publisher certificate
  as v0.1.114 and its SHA-256 checked after publishing.

## Important limitations

- This custom viewer is not Microsoft Excel itself. Dynamic conditional
  formatting, advanced charts, formula recalculation and some installed
  desktop fonts may still look different. Original OOXML content is
  preserved on cell edits rather than destroying unsupported features.
- No strong document ETag in both HEAD and PROPFIND means read-only. This
  protects financial workbooks from unsafe cross-device overwrites.
- The Excel ~$ convention is cooperative and only blocks applications
  that honor it. Server-side atomic file locking is not enabled.
- Real Android + RaiDrive on the user's NAS and long-file GUI performance
  require final installation testing.

## Signed Android v0.1.115 distribution (2026-11-23)

- GitHub release: https://github.com/dj5927/FileHub-AIO/releases/tag/v0.1.115
- FileHub-AIO-v0.1.115.apk 72,639,184 bytes, SHA-256 4B98D025E2BA28B267AC1BDC8B3DB6DF2C1D0E34B59EE644761F9AF44EDA47A9.
- Android applicationId com.nasmount.mobile, versionCode 116, versionName 0.1.115, signed with existing v0.1.114 publisher certificate SHA-256 E2B6FECE09666DBFB26044C381227953F93A1EB1685EA6890139BBDA89EFEEB8.
- Local Gradle unit tests 587 cases total (586 passed, 1 skipped, 0 failed); WebDavExcelLeaseTest 10 passed. Headless Chromium synthetic OOXML fixture tested styles/fills/widths/merges, scrolling, zoom, worksheet tabs, and preserving styles.xml after edited cell saved; PASS.
- Uploaded GitHub source commit b95069482c7ce9aca75d6f5bd3e8ebfa907f16c5, official Android main branch. Publishing workflow verifies immutable signed APK SHA-256 and package contents before release.
- Real user NAS and Android+RaiDrive field confirmation still needed. No change to PC branch or user NAS data.

## Official OTA publication confirmation (2026-11-23)

- GitHub Actions run 45143672133 succeeded; SHA-256, APK ZIP validity and required application/assets all verified by CI.
- Official Android release v0.1.115: https://github.com/dj5927/FileHub-AIO/releases/tag/v0.1.115
- Published signed APK FileHub-AIO-v0.1.115.apk: 72,639,184 bytes, GitHub asset SHA256 4B98D025E2BA28B267AC1BDC8B3DB6DF2C1D0E34B59EE644761F9AF44EDA47A9. Local signed APK checksum identical.
- Existing Android publisher certificate unchanged; app version 0.1.115 / code 116; Gradle 587 tests (586 passed, 1 skipped, 0 failure), OOXML browser rendering/export tests passed.
- Temporary signed APK HTTP host, HTTPS tunnel and browser test server stopped after official release publication.
- Real NAS and device usability still requires end-user confirmation; exact pixel equivalence with proprietary Excel is not asserted.

## OTA parser compatibility repair (2026-11-23)

- Root cause: Android OtaReleasePolicy.parse requires a standalone `versionCode: 116` line in the release body, while v0.1.115 originally shipped release notes with only `(versionCode 116)` inline. This caused the manual update check to report it could not read update information even though the signed APK existed.
- Fix: Repair v0.1.115 GitHub release notes in place, preserving the already verified signed APK and its checksum; existing installed clients can re-check OTA without upgrading or reinstalling.
- Triggered via a one-time checksum-guarded GitHub Actions workflow; no Android package change.
