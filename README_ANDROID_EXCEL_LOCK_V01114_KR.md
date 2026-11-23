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

## 정식 Android 빌드 및 OTA 인수인계 (2026-11-23)

- 최신 Android 버전: v0.1.114, versionCode 115, Android applicationId com.nasmount.mobile.
- 기존 퍼블리셔 서명 v0.1.113과 새 v0.1.114 서명 인증서 SHA-256 일치: E2B6FECE09666DBFB26044C381227953F93A1EB1685EA6890139BBDA89EFEEB8.
- 로컬 Gradle testDebugUnitTest 총 586개 테스트 통과, 실패 0, WebDavExcelLeaseTest 8개 통과. GitHub Android 37 JavaScript 및 Android 테스트/디버그 APK Actions run 45133864314 success.
- Publisher 서명 Release APK: FileHub-AIO-v0.1.114.apk 72,631,889 bytes, SHA-256 A27758AD2EBF517C4FCE91245731915E2FAAA34838814EBCEEB3EFF45A48F975.
- Gradle assembleRelease 성공, Android apksigner 검증 성공, aapt2 package/version 확인, APK 342개 엔트리 압축 검증 및 Office 자산 검증 PASS.
- 공식 최신 Android OTA: https://github.com/dj5927/FileHub-AIO/releases/tag/v0.1.114
- 구버전 staged OTA workflow는 0.1.113/0.1.114 태그에서는 실행하지 않고, 체크섬 고정 배포 workflow에서 발행한다.
- 실제 사용자 NAS에서 RaiDrive와 안드로이드 Excel 동시 열기 최종 현장 테스트는 미수행. PC 버전, 다른 Android 스토리지 공급자 및 사용자 NAS 데이터는 변경하지 않음.

## 공식 릴리즈 최종 완료 (2026-11-23)

- GitHub Actions run 45134612630: success. 배포 APK 다운로드 후 고정 크기 및 전체 SHA-256, ZIP 구성 무결성 검증하고 정식 Android OTA `v0.1.114` 게시 완료.
- GitHub 릴리즈 APK 72,631,889 bytes 및 `sha256:a27758ad2ebf517c4fce91245731915e2faaa34838814ebceeb3eff45a48f975`와 빌드 원본 SHA-256 일치.
- 공식 다운로드 https://github.com/dj5927/FileHub-AIO/releases/download/v0.1.114/FileHub-AIO-v0.1.114.apk
- 기존 v0.1.113과 apk signer 인증서 동일 확인. 전체 Java 테스트 586개 중 실패 0, WebDAV Excel 신규 8개 포함. GitHub Actions 테스트/디버그 빌드 run 45133864314 success.
- 임시 파일 배포 HTTP 서버 및 Cloudflare Tunnel 종료. 기존 NAS 데이터와 PC판 배포에는 변경 없음.
- 미검증: 사용자 Android 기기와 실 NAS에서 RaiDrive/Excel 교차 편집 화면 동작은 직접 확인 필요.
