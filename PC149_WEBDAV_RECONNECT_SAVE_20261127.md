# FileHub AIO PC 1.3.149 release validation

Windows-only change, based on PC 1.3.148. Transient WebDAV transport and control-pipe failures no longer terminate the mounted filesystem. Read-only requests can retry; uncertain writes are not blindly replayed. Verified Office reservation replacement now covers XLSX as well as XLS, with source/destination byte checks, nonempty-document protection and retained recovery data.

Local real Excel/WinFsp/WebDAV tests: new SaveAs, five repeat saves for each format, SaveAs/reopen, Korean and space-containing filenames, formatted workbook content, empty reservation replacement, concurrent edit protection, owner lock conflict. Managed mount outage/reconnect, abandoned control-client timeout, write-store recovery and packaged self-tests passed. The original reported production NAS failure was not independently reproduced; servers without strong ETags retain optimistic concurrency limits.

Public OTA packaging uses the verified PC 1.3.148 archive plus a checksum-locked handoff. All 317 distributable file hashes were checked against the tested build; generated internal QA images excluded. Existing Android releases unchanged.
