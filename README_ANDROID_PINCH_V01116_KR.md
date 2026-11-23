# FileHub AIO Android v0.1.116 - Smooth spreadsheet pinch zoom

## User report

On v0.1.115, two-finger pinch-in/out could change sheet zoom by only
about 2% and then seem to freeze or respond erratically. One-finger
horizontal and vertical scrolling was otherwise acceptable.

## Root cause

- The touchmove callback called grid.setZoom() after every movement.
- That forced the virtualized worksheet to replace its visible cell DOM
  nodes on every frame. Android WebView could cancel the original touch
  target when a touched cell was removed, ending the pinch prematurely.
- Zoom was anchored to the screen center, not the two-finger midpoint.

## Fix

- A spreadsheet-specific two-finger handler starts with the initial finger
  distance and initial zoom. Each move sets zoom to initialZoom *
  currentDistance / initialDistance, clamped to 55%-300%.
- The grid keeps visible cells mounted throughout the gesture. CSS zoom and
  scroll offsets update continuously; cells are rebuilt only after both
  fingers lift.
- Zoom follows the two-finger midpoint so content stays under the gesture.
- A remaining finger cannot cause an early virtualized DOM replacement.
- One-finger horizontal/vertical native scrolling is never intercepted.
- The older HWP and slide pinch helpers are unchanged.

## Embedded XLSX pictures, clipped text, and sheet geometry

- Read worksheet drawing relationships and standard OOXML oneCellAnchor,
  twoCellAnchor and absoluteAnchor picture coordinates. Resolve supported
  PNG, JPEG, GIF, WebP and BMP images inside the original XLSX.
- Place images over original cells with row/column offsets and dimensions,
  including pictures farther down the sheet, rendered when scrolled into view.
- Ordinary unwrapped strings spill across adjacent empty cells until a
  nonempty or merged cell blocks them. Only text flows; neighboring fills,
  borders and gridlines remain intact.
- Apply sheetFormatPr defaultRowHeight/defaultColWidth instead of hardcoded
  27px rows and 89px columns when the workbook omits specific overrides.
- Existing wrapText, explicit row heights and merged cell boundaries remain.
  Edits still patch only the selected cell XML, preserving the image media,
  drawings, relations and other worksheets byte-for-byte.
- Vector, WMF/EMF, OLE and modern Excel in-cell rich images are not
  guaranteed to render, though they remain preserved on save.

## Tests

- Headless Chromium dispatches TouchEvent objects and verifies 100% ->
  180% -> 130% -> 65% zoom, midpoint anchoring, stable touched cell DOM,
  and no prevention of single-finger touchstart.
- Existing OOXML style, worksheet scrolling, tabs and lossless-save
  regression tests must continue to pass.
- An OOXML fixture with two embedded PNG drawings, custom row/column
  geometry and Korean long-text spill tests actual image decode, on-sheet
  coordinates, lazy image rendering, text overflow into only empty cells,
  wrapped cells and byte-identical drawing/image content after edited save.
- Gradle Android unit tests and release APK signing/version checks.

## Limits

- Chromium simulated touch input does not guarantee identical behavior on
  every Android System WebView or Samsung firmware version. Physical
  on-device touch testing remains the last field check.
- This is a spreadsheet UI fix: WebDAV locks, file storage and OTA
  behavior are unchanged.

## Verified Android v0.1.116 publisher APK handoff (2026-11-23)

- GitHub official OTA release: https://github.com/dj5927/FileHub-AIO/releases/tag/v0.1.116
- Signed APK FileHub-AIO-v0.1.116.apk: 72,642,748 bytes; SHA256 5438C7E979A7360728433E28C046FAAC452677B0D277503958B96874ABDE4BCC.
- Android applicationId com.nasmount.mobile; versionCode 117, versionName 0.1.116; publisher certificate SHA256 E2B6FECE09666DBFB26044C381227953F93A1EB1685EA6890139BBDA89EFEEB8 identical to signed v0.1.115.
- Android Gradle testDebugUnitTest/assembleRelease: success; 587 tests, 0 failures, 1 skipped.
- Headless Chromium browser regressions: pinch 100% -> 180% -> 130% -> 65%, stable DOM, native one-finger touch, source OOXML fill/width/merge, two PNG drawing anchors with scrolling, Korean overflow into only empty cells, default geometry, and byte-identical embedded media after edited-cell save all passed.
- GitHub release notes MUST contain standalone `versionCode: 117` line; SHA256-guarded publishing workflow checks this before reporting success. Previous staged OTA workflow excludes v0.1.116.
- Actual Android Samsung touch and user workbook rendering not tested on physical device; device confirmation still necessary. No live NAS files changed.
