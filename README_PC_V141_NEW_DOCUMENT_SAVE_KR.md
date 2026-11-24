# PC v1.3.141 — New Office document save repair

Date: 2026-11-24. Base: PC v1.3.140 (`7b13629`).

## Reproduced failure and fix

Actual Microsoft Excel SaveAs in an isolated WinFsp/WebDAV lab creates a
random eight-hex temporary file, writes and truncates it, then renames it
to the new workbook. v1.3.140 treated nonsequential temporary writes as
failed queued copies (`Incomplete local copy cannot be queued`). With all
temporary-file ETags omitted, its final rename also failed with
`Unsafe rename without strong source ETag` and Excel returned COMException.

Office document and temporary writes now commit synchronously. Random or
noncontiguous ordinary writes fall back to synchronous commit instead of
throwing when queued. Sequential ordinary-file writes retain the bounded
parallel write-behind path. Only a temporary file this mount successfully
uploaded, whose current bytes match the retained SHA-256, may be reopened
for rename without a strong ETag. That handle cannot overwrite or truncate
without a strong revision. Publishing the temporary file to an absent Office
destination uses a verified `MOVE Overwrite:F`; no unconditional target
overwrite or general no-ETag document editing has been enabled. If-Match:*
is only an existence guard, not an atomic content-version guard, so the
remaining race with a noncooperating external editor is documented.

## Evidence

- Old bridge baseline: `D:\FileHubAIO-PC-v138-Lab\new-save-24538bf6` (PUT ETag absent; six cleanup errors despite save success).
- Old bridge reproduced SaveAs COM failure: `D:\FileHubAIO-PC-v138-Lab\new-save-352424ae` (temporary and owner ETags absent).
- Final v141: `D:\FileHubAIO-PC-v138-Lab\new-save-3eeb6181` (same proxy conditions; SaveAs plus five subsequent saves passed, one owner per save, final server workbook reopened and cell verified).
- Guarded-write, crash recovery, independent-client lock, and bridge self-test: all passed in local isolated lab.
- No production drive was disconnected, no existing workbook edited, no user credentials read. Actual production Synology retest and OTA publishing are distinct from the local lab.

RaiDrive response-timing investigation remains independent. This change does
not claim that ordinary Explorer copy completion is a NAS response or that
RaiDrive's proprietary implementation has been reconstructed.
