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
