# FileHub AIO PC v1.3.143 - Excel SaveAs and icon alignment

2026-11-24. Based on published v1.3.142 (cb0dc31).

## SaveAs repair

Production v142 log: the temporary-file MOVE succeeded, but the subsequent
Open failed with PROVIDER_ERROR. The previous test only stripped ETags on
the owner and temporary files; this version also tests the final workbook
without an ETag.

Actual Excel v142 reproduced the post-MOVE write-open failure
("Cannot safely edit without strong server ETag") and lost its owner marker.
v143 permits Excel to download and stage an unversioned existing workbook
after the filesystem checks the local editor lease and foreign owner.
Opening alone cannot overwrite the server. Remote updates require the
verified actual Excel owner marker (or exact same-mount temporary ownership)
and comparison of the prior bytes, with a conditional existence PUT and
readback for unversioned Office writes. Ordinary files retain their original
strong-ETag requirements. Excel's original-workbook-to-backup MOVE and
temporary-to-final MOVE use verified Overwrite:F and SHA-256 readback.
Office temporary files no longer enter progressive upload staging.

The fallback is advisory: SHA-256 comparison plus If-Match:* is not an
atomic content-version CAS against a noncooperating external client.
No generic unconditional overwrite or native WebDAV lock option is enabled.
Strong ETag protection remains active when available.

## Verified isolated WinFsp / WebDAV / Microsoft Excel tests

- v142 failure: %TEMP%\filehub-office-new-save-9f1c8fe6.
- v143 missing owner, temp and final workbook ETags: initial save, five
  consecutive saves, real NAS-to-NAS SaveAs from an open workbook, reopen
  and save, original content unchanged, Korean local-to-NAS SaveAs passed.
  %TEMP%\filehub-office-new-save-e11d94a1.
- 3.9 MB workbook and existing zero-byte SaveAs target: same entire
  sequence passed. %TEMP%\filehub-office-new-save-e1c57121.
- Independent second WinFsp mount: read allowed, write-open denied by
  the first mount's real Excel owner marker, with no ETags.
  %TEMP%\filehub-office-new-save-78c9040f.
- Prior strong-document-ETag scenario: full sequence passed.
  %TEMP%\filehub-office-new-save-a51c9f10.
- Write-store guarded update, conflict retention, crash recovery,
  independent client lease and bridge self-tests passed.
  %TEMP%\FileHubWriteStore_8b6bae9f392d470b8077974a313694e3.

## Alignment

Center each line icon by its actual visible bounds rather than its canvas;
center Korean caption ink on the same vertical row axis. Apply to settings
and connection context menus. Focused eight-provider menu and settings
layout tests passed. Actual 100% DPI settings screenshot: the six buttons
had measured icon-to-caption vertical center offsets of 0 or 0.5 pixel.

Production NAS retest remains required. RaiDrive transfer timing work is
separate and not represented as completed.
