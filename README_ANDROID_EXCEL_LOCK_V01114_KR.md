# FileHub AIO Android v0.1.114 - WebDAV Excel owner interoperability

## Existing problem

PC v1.3.133 and RaiDrive recognize Excel's remote ~$ owner file. Android's
built-in and external Office editors instead download a private local copy
and upload it after editing. Previously neither Android editor checked the
owner file when opening nor protected the original workbook revision captured
at the beginning of the editing session.

## Changes

- WebDAV Excel .xlsx/.xls remote edit checks both owner-name conventions:
  ~$11월매출.xlsx and ~$월매출.xlsx. Existing owner => read-only; no overwrite.
- With strong original ETag and write access, Android conditionally creates
  a remote Microsoft-Excel-shaped 165-byte ~$ owner file using If-None-Match:*.
  The owner label is generic ("FileHub Android") without user details.
  Another Android FileHub editor or PC client can recognize this marker.
- The staged original ETag is checked both before save and again at the
  beginning of RemoteProvider.upload; the final WebDAV MOVE already uses
  conditional remote ETag safety. After successful upload the baseline ETag
  is updated for further saves in the same editing session.
- The owner marker must have a strong ETag. On normal editor close Android
  deletes only the still-matching owner via If-Match. A marker that changed
  ownership is never deleted. After crash/process kill, the remote marker
  is left for manual recovery; no auto-expiry or garbage collection.
- Built-in WebView editor exposes readOnly to JavaScript. Save and cell edits
  are disabled and visible read-only messaging is presented.
- External Office bridge permits read access to the staged file but does
  not grant a write URI or allow commit back to NAS in read-only mode.
- Safe editing is limited to WebDAV Excel. Non-Excel formats, local/SAF,
  SFTP, FTP, SMB, MYBOX and TeraBox flows remain unchanged.

## Tests

- Mock WebDAV owner from independent RaiDrive-like client forces read-only.
- Alternate Korean owner name also forces read-only.
- Conditional owner PUT, exact content, ownership HEAD, If-Match DELETE,
  repeated save baseline updates, and idempotent cleanup.
- Other client changing owner prevents unsafe cleanup.
- Other client changing workbook ETag blocks save and overwrite.
- Missing strong ETag falls back to read-only, never unsafe editing.
- Stale baseline during upload prevents any temporary PUT or MOVE.
- Existing app-wide Android JVM tests and Gradle APK assembly.

## Limitations

- Microsoft Office owner files are a cooperative editing convention and not
  a distributed atomic filesystem lock. The app also requires WebDAV strong
  ETags and conditional write behavior. A NAS that lacks them is treated as
  read-only for shared Excel editing.
- Full two-way RaiDrive/PC/Android Excel behavior on the user's actual NAS
  requires device-side field testing after installation.
- In Android, the Office editor is locally staged; it is not the native
  Windows Excel process. Android's generated owner is binary-compatible in
  shape and designed for existence-based interop; its displayed owner text
  in every proprietary Excel version is not guaranteed.
- An abandoned remote ~$ file should only be removed manually after all
  editors are closed.
