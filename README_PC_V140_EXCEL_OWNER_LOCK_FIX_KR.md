# FileHub AIO PC v1.3.140 - Excel owner files on NAS without ETags

2026-11-24. Direct WebDAV/WinFsp only.

## Actual regression evidence

The running v1.3.139 application and bridge were checked on the user's
Synology connection. The server accepted the first 55-byte owner PUT.
Even the subsequent metadata lookups did not produce a strong ETag.
The next Write failed, and Excel deleted the owner. The previous v139
test removed ETags from PUT only, leaving GET and PROPFIND ETags intact;
that model was insufficient.

## Changes

- The real Office-created owner payload is committed on its Write callback,
  with an exact remote GET to establish server-visible bytes. No fake owner
  is generated and no additional lock sidecar is introduced.
- The first creation remains exclusive (If-None-Match: *). A live handle
  updating its own previously confirmed tiny owner may use If-Match: *
  only when no strong revision validator exists and the existing server
  bytes match that handle's prior bytes. If the owner has disappeared or
  changed, reject the write rather than recreate or overwrite it.
- If a strong ETag is available, retain that stronger precondition.
  Wildcard If-Match is an existence precondition, NOT atomic byte-level
  compare-and-swap; non-cooperating external deletion/replacement between
  GET and PUT remains a limitation of advisory Office locks on servers
  lacking strong validators.
- Use an exact GET as a fallback if PROPFIND omits an owner resource.
  Recognize ETags returned in separate successful DAV propstat blocks.
- Ordinary file transfer and workbook overwrite guards are unchanged.
  No generic post-upload reads or serialized bulk-copy queue were added.
- Log server-confirmed owner creation/update, byte length, conditional
  mode and strong-ETag availability without printing owner contents.

## Verification

- Real Synology v139: failure reproduced and diagnosed from production log;
  only disposable test files and directories were created.
- Strict local WebDAV regression adapter removed owner ETags from PUT,
  GET, HEAD and PROPFIND (including directory listings).
- Microsoft Excel created the owner in two writes, 55 then 165 bytes.
  Both were confirmed on server; second write used owned-existing mode
  with strongEtag=False.
- Independent backend blocked a new editor; second Excel opened read-only.
- Five consecutive Excel saves retained one 165-byte server owner.
- Closing Excel removed the owner; bridge reported SAFE, no pending upload,
  failure or recovery journal.
- Actual updated Synology authentication/verification is still pending:
  the existing profile does not save its password. The active R: mount,
  existing workbook contents and credentials were not modified or extracted.
