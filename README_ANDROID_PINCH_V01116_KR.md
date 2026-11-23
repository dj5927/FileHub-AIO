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
