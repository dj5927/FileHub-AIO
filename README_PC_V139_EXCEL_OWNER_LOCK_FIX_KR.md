# FileHub AIO PC v1.3.139 - Excel ~$ owner-lock repair

2026-11-24. Scope: direct WebDAV/WinFsp on Windows.

## Reproduced regression on real Synology (v1.3.138)

Using a **unique disposable QA folder only**, an isolated Microsoft Excel
automation instance opened an XLSX on the existing Synology R: drive.
The first workbook opened writable, but no ~$ owner file remained in the
NAS folder. Another local Excel instance opened read-only, which alone
does not establish safe locking from a different computer.

The production bridge log showed the precise cause:

1. Excel wrote its first 55-byte ~$ owner chunk.
2. FileHub committed it immediately via conditional WebDAV PUT.
3. Synology replied 2xx but **without a strong ETag in the PUT response**.
4. Excel wrote a second, 165-byte owner payload to the same handle.
5. A second PUT could not send a safe If-Match based on the first response;
   FileHub returned an IOException in Write.
6. Excel removed the temporary owner file, so external Office instances
   could not see it.

## Correction

- Keep the owner file **visible to remote clients while the workbook is open**.
  Waiting for WinFsp Cleanup is insufficient, because Excel can keep the
  owner handle open for the whole editing session.
- After the first owner PUT, if the reply has no strong ETag, issue
  **a fresh WebDAV metadata lookup for THIS owner only**. Save the
  actual server ETag for subsequent guarded If-Match updates.
- Continue to acknowledge each owner Write only after the NAS accepts it.
- Keep an in-memory fingerprint of the real Excel-created owner payload.
  If its first PUT did not provide an ETag, the same FileHub mount may
  recognize its own owner via an explicit short GET/byte comparison.
  A different mount does **not** inherit that trust.
- Do not synthesize arbitrary Excel owners or create user-visible
  *.nasmount.lock sidecars.
- Generic files retain the original high-throughput parallel staging path;
  only tiny Office ~$ owner files use the focused extra lookup.
- No other providers, Android files, or OAuth connection flows changed.

## Verification

- v1.3.138 against real Synology: missing ~$ reproduced; initial
  successful 55-byte upload followed by Write IOException established.
- v1.3.139 against a disposable WebDAV server configured to remove strong
  ETags from **every PUT reply**: Excel first writable, remote ~$ visible,
  two real owner writes (55 then 165 bytes) succeeded; second Excel opened
  read-only.
- Independent read-only WebDAV client using a **separate backend instance**
  correctly refused editing while the first Excel was open:
  DIRECT_WEBDAV_LIVE_EXCEL_SECOND_PC_EDIT_BLOCKED_PASS.
- After Excel closed: owner file removed, WinFsp bridge STATUS SAFE,
  recovery=0, writers=0, queued=0, uploading=0, failed=0.
- Parallel small-file smoke: 500 files successfully uploaded,
  local-copy acknowledgment 5.447s, remote count=500.
- Test server timings cannot assert actual cross-PC Synology behavior;
  next acceptance requires opening the same workbook simultaneously on
  two real Windows machines using the deployed v1.3.139 and another
  NAS client (e.g. RaiDrive).

## QA isolation

No existing user workbook was modified. The disposable real-Synology
test folder was deleted. The original mounted R: drive and its running
FileHub v1.3.138 processes were not stopped or changed.
