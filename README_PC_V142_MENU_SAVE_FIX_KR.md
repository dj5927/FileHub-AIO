# FileHub AIO PC v1.3.142

2026-11-24. Based on published v1.3.141.

## Office SaveAs repair

The production log shows successful temporary-file PUT followed by HTTP 412
from Rename after the guarded-MOVE probe passed. An isolated real Excel test
reproduced the same v141 failure by rejecting If-Match on unversioned MOVE.
The new version uses a verified Overwrite:F MOVE for an unversioned Office
temporary file published by this mount, with source SHA-256 verification and
destination SHA-256 readback. It no longer sends If-Match:* for this path.
Existing destination overwrite guards and Excel owner locking are unchanged.
Without a source ETag, content verification is not an atomic revision check;
the destination non-overwrite condition remains mandatory.

Lab results: v141 repeated 412 on SaveAs (test stopped); v142 SaveAs and
five repeat saves passed, exactly one owner marker per save, server workbook
content verified. Saving an existing local workbook to a new Korean filename
on the mount also passed. Guarded-write/recovery, two-client lease and bridge
self-tests passed. Actual production NAS retest remains required.

## Menu cleanup

Settings: remove connection diagnosis, retry-all, ghost report and Synology
sharing buttons. Retain startup, settings export/import, logs, WinFsp,
update, component inventory and close; use grouped line-icon buttons.
Profile menu: remove connection diagnosis, manual network ghost cleanup,
remote lock test, manual Google Explorer check and previous OAuth restore.
Retain Synology sharing only in NAS profile menu; keep reauthorization,
file-lock status, upload status, logs, edit, open and delete as applicable.
Remove unused old settings page and manual action handlers. Automatic
connection, shell recovery and credential transaction rollback remain intact.

Focused UI test checked all eight provider menus, exact settings buttons
and non-overlap. The broader existing UI smoke test failed on an unrelated
profile-editor Excel read-only checkbox clipping check; no full UI-suite
pass is claimed.

## Local test evidence

- v141: %TEMP%\filehub-office-new-save-d2161bb9
- v142: %TEMP%\filehub-office-new-save-7c66ab5c
- v142 extended SaveAs: %TEMP%\filehub-office-new-save-fca9345a
- v142 guarded write: %TEMP%\FileHubWriteStore_18b7c4e96bef4b70ade88d002a9e2393
- Candidate: D:\FileHubAIO-PublisherBuildCache\PC-1.3.142-20261124

RaiDrive transfer timing investigation remains separate.
