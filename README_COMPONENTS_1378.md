# FileHub AIO PC v1.3.119 — 현재 포함 엔진·라이브러리 (2026-11-20)

이 표가 현재 v1.3.119 배포본 기준입니다. 아래의 NAS Mount v1.3.78
및 후속 기록은 변경 이력을 보존한 것이며 최신 배포 구성 표가 아닙니다.

| 종류 | 배포/빌드 버전 | 상태 |
|---|---|---|
| 일반 rclone | v1.76.0 | 공식 릴리즈 ZIP·EXE SHA-256 고정, 최신 유지 |
| Google 전용 비동기 용량 rclone | v1.76.0 FileHub 패치 | 공식 태그·패치를 고정해 소스 빌드, WinFsp 포함. 빌드별 SHA-256 매니페스트와 패치 마커를 함께 검증. 네트워크 용량 요청에서 Google API 동기 호출 제거 |
| TeraBox rclone-extra | v1.76.0-extra | 확장 ZIP·EXE SHA-256 고정, 최신 유지 |
| WinFsp MSI | **2.1.25156 (2025 정식판)** | 공식 서명 MSI, SHA-256 고정, RaiDrive와 공유하는 시스템 드라이버 |
| WinFsp .NET 바인딩 | **2.1.25156** | 포함된 정식 드라이버와 버전 일치 |
| SSH.NET | **2026.0.1** | 메인 및 LockBridge 동일 |
| Microsoft Edge WebView2 SDK | **1.0.4314.35** | TeraBox 계정 로그인 창 |
| FluentFTP | 55.1.0 | 공식 최신 유지 |
| Microsoft .NET | net10.0-windows | 자체 포함 최신 .NET 10 패치 SDK 빌드 |
| GitHub Actions | checkout v7 / setup-dotnet v6 | 새 빌드 Action 버전 |

NuGet 버전은 루트의 Directory.Packages.props로 중앙 관리합니다.
Dependabot은 매주 PC 전용 의존성 업데이트 PR/커밋을 자동으로 생성합니다.
WinFsp .NET과 설치 MSI는 반드시 함께 검증해야 하므로 자동 병합 대상에서
제외했습니다. rclone·확장 엔진 역시 해시 검증 없는 자동 교체를 허용하지 않습니다.
WinFsp 2.2 Beta가 설치되어도 정상 동작한다면 오류가 아닙니다.
FileHub에서 강제 다운그레이드하지 않으며 베타 설치 자체를
RaiDrive 실패로 오판하지 않습니다. 드라이버 제거/재부팅/재설치는
실제 장애가 확인된 경우에만 로그를 확인한 후 검토해야 합니다.
WinFsp 2025 정식판에는 Beta에서 수정된 보안 문제(CVE-2026-3006 및
CVE-2026-7162)가 남아 있을 수 있으니 신뢰할 수 없는 환경에서는 주의하세요.
GitHub Dependabot 설정은 기본 브랜치 main의 .github/dependabot.yml에
등록해야 PC용 pc/windows 브랜치 PR이 실제로 생성됩니다.

## 이전 변경 이력 (당시 배포 기준)

# NAS Mount v1.3.78 — 사용 엔진 / 라이브러리 / 확장 백엔드

기준일: 2026-11-11
프로그램: NAS Mount v1.3.78 (Windows x64, .NET 10 WinForms)

## 핵심 엔진과 프로그램

| 용도 | 구성 요소 | 현재 배포 버전 | 출처/참고 |
|---|---|---|---|
| NAS/WebDAV/FTP/SFTP/Google Drive/OneDrive의 기본 연결 및 마운트 | rclone (공식) | **v1.76.0** | https://github.com/rclone/rclone |
| TeraBox 드라이브 전용 연결 | rclone-extra (비공식 확장 빌드) | **v1.76.0-extra** | https://github.com/gulp79/rclone-extra |
| TeraBox 확장 백엔드 원본 | rclone용 terabox 백엔드 | 위 rclone-extra에 포함 (별도 버전 없음) | https://github.com/x1arch/rclone/tree/backend/terabox |
| Windows 파일시스템 드라이버 | WinFsp 설치 프로그램 | 2.2.26215 (배포 포함) | https://github.com/winfsp/winfsp |
| NAS Mount 파일잠금 및 드라이브 중재 | NASMountLockBridge.exe | 1.3.76 (자체 구성 요소) | NAS Mount 자체 소스 |
| FTP 레거시 문자셋 (CP949 등) | FluentFTP | 55.1.0 (NuGet) | https://github.com/robinrodricks/FluentFTP |
| SFTP 레거시 문자셋 및 SSH 연결 | SSH.NET | 2026.0.0 (NuGet) | https://github.com/sshnet/SSH.NET |
| TeraBox ndus 로그인 창 | Microsoft Edge WebView2 SDK/Loader | 1.0.3800.47 (NuGet) | https://github.com/MicrosoftEdge/WebView2Samples |
| 네이버 MYBOX PAT 연결 | NAS Mount 자체 MYBOX Open API 게이트웨이 | v1.3.73 (별도 외부 엔진 없음) | NAS Mount 소스 MyBoxBetaGateway.cs |
| SMB 네트워크 공유 | Windows 네이티브 SMB 연결 | Windows 운영체제 버전 기준 | NAS Mount WindowsShare.cs |
| NAS Mount 앱 실행 환경 | Microsoft .NET | .NET 10, Windows x64 자체 포함 빌드 | https://github.com/dotnet/runtime |

## TeraBox 백엔드 적용 내역

- **이전:** rclone-extra v1.75.2-extra (NAS Mount v1.3.70까지)
- **업데이트:** rclone-extra v1.76.0-extra (NAS Mount v1.3.78부터)
- 공식 GitHub 릴리스: https://github.com/gulp79/rclone-extra/releases/tag/v1.76.0-extra
- GitHub 게시: 2026-11-06 (UTC)
- 배포 ZIP 파일명: rclone-windows-amd64.zip
- ZIP SHA-256 (GitHub 게시값/실제 다운로드 확인): `A9BB4C6F30E7C6F73ABA05D9EA3355C0C540E7ADD2E10480A7C976516DCD2D37`
- ZIP에서 추출한 rclone.exe의 SHA-256: `A72B8D123517696C4CC996FD17BD7509DDD7850B5E13893EE970F8C8A2B8390C`
- NAS Mount 패키지에서는 해당 파일을 `tools/rclone-terabox.exe`라는 이름으로 배치함.
- TeraBox 파일명 변환·접속 프로필·인증 형식은 변경하지 않음. 실제 TeraBox 계정 ndus는 Windows 자격 증명 저장소에서 처리함.
- 구버전과 신버전의 `backend/terabox` 파일은 GitHub 기준 내용 해시가 동일함. 이번 업데이트는 공통 rclone 엔진 업그레이드이며, TeraBox API 전용 기능 추가는 확인되지 않음.

## 공식 일반 rclone 적용 내역

- **이전:** 공식 rclone v1.75.2 (NAS Mount v1.3.70까지)
- **업데이트:** 공식 rclone **v1.76.0** (NAS Mount v1.3.78부터)
- 공식 GitHub 릴리스: https://github.com/rclone/rclone/releases/tag/v1.76.0
- GitHub 게시: 2026-11-06 (UTC)
- 배포 ZIP 파일명: rclone-v1.76.0-windows-amd64.zip
- ZIP SHA-256 (GitHub 게시값/실제 다운로드 확인): 8B4FD2737ADE4E70C057EF4A49DEB7CFFE94B0FB9D7587C189D149823F21A1DC
- ZIP에서 추출한 rclone.exe SHA-256: ACD80683E23151E93BBE0D6FFA74D26E542EC510332F630BEECB9944F8D1A623
- NAS Mount 패키지 배치: tools/rclone.exe
- 일반 엔진 무결성 검사 파일: tools/rclone.sha256. 확장 백엔드는 tools/rclone-terabox.sha256를 사용.
- NAS/WebDAV/FTP/SFTP, Google Drive, OneDrive 연결에 해당 실행 파일을 사용하고, TeraBox는 별도 rclone-extra로 연결.

## 내부 연결 구조

- WebDAV/일반 FTP/SFTP/Google Drive/OneDrive → `tools/rclone.exe` (공식 v1.76.0) + WinFsp, 필요시 NASMountLockBridge.
- TeraBox → `tools/rclone-terabox.exe` (rclone-extra 1.76.0-extra) + WinFsp.
- FTP/SFTP에서 비UTF-8 문자셋 선택 시 → NAS Mount 로컬 문자셋 게이트웨이 + FluentFTP/SSH.NET + WinFsp.
- MYBOX → NAS Mount의 자체 MYBOX Open API 로컬 게이트웨이 + rclone WebDAV + WinFsp.
- SMB → Windows가 제공하는 네이티브 SMB 네트워크 공유.
- TeraBox 로그인 보조 → 격리된 WebView2 브라우저에서 사용자가 직접 로그인한 뒤 `ndus`만 가져옴.

## 유지보수 메모

1. 백엔드 버전 확인 시 NAS Mount 환경 설정의 **엔진 · 버전 · README** 버튼을 누른다.
2. 이 README는 배포 ZIP과 소스에 함께 보관하며, 실행 파일 내부에도 같은 내용이 포함되어 있다.
3. 정보 화면 하단에는 실제 배포 폴더의 두 rclone 실행 파일에서 읽은 버전이 별도로 표시된다. README에 적힌 기준 버전과 다르면 새로 검증해야 한다.
4. 일반 rclone 및 확장 백엔드 업데이트 전, 구버전을 보존하고 각 공식 GitHub 릴리스 SHA-256 비교 후 격리된 전체 배포본에서 회귀검사를 수행한다. `tools/*.sha256` 파일도 반드시 실행 파일과 함께 갱신한다.
5. WinFsp, 기타 드라이버의 **설치된 버전**은 PC에 따라 다를 수 있다. 위 숫자는 패키지에 포함된 설치 프로그램의 버전이다.
6. TeraBox는 공식 인증 API가 아닌 비공식 확장 백엔드이며 서비스 변경에 따라 동작이 달라질 수 있다. 실제 장시간 업로드·엑셀 직접 편집·다중 PC 잠금은 별도 검증 필요.
7. 모든 수정 사항과 배포 파일 해시는 `D:\GPT\NAS_MOUNT\작업일지.md`에도 기록한다.


## v1.3.72 시작 연결 안정화

- 자동 연결 전에 로컬 rclone 일반/확장 엔진 무결성·실행 상태, WinFsp, 잠금 브리지를 확인한 후에만 연결 시도.
- 초기 자동 연결 도중 30초 타이머 중복 실행을 방지하며, 타이머는 초기 일괄 연결 완료 뒤 가동.
- Google Drive/OneDrive 초기 마운트 준비 제한 20초 → 75초 (실제 NAS Mount 로그에서 Google Drive 첫 준비 35초 이상 지연 확인).
- TeraBox/MYBOX 55초, 기타 25초. 연결 확인 전에는 오류 대신 파란색 '연결 준비 중 · 마운트 확인 중' 표시.
- 75초 뒤에도 준비 중이면 rclone VFS 데이터를 강제로 종료하지 않고 추후 안전한 재확인. 실제 디렉터리·용량 검증 뒤에만 정상 연결 상태.
- 실제 계정 로그인·실서비스 장시간 업로드·Excel 직접 편집은 사용자 PC에서 별도 검증 필요.

## v1.3.73 Office filename case preservation

- Local Excel Save() reproduction with WinFsp lock bridge: LabActual.xlsx changed to LABACTUAL.XLSX on remote server prior to fix, while direct rclone mount retained LabActual.xlsx. This is an ACTUAL server filename case mutation, not only Explorer display.
- On existing workbook backup-rename, the bridge captures the real on-disk filename spelling before the temporary rename; on the atomic temp-to-final step, it uses the saved original exact spelling. No extra remote writer or forced rename after upload is introduced.
- For brand-new Excel temp-to-document saves without a previous name, Office extensions are written in lowercase; unrelated file types are untouched. Intentionally uppercase extensions on existing workbooks are retained.
- Auto-refresh notification mapping is unchanged.
- Verified isolated loopback NAS with real Excel COM: prior failed (server LABACTUAL.XLSX); fixed Save() server LabActual.xlsx, SaveAs() NewCase.xlsx, legacy XLS LabActual.xls; workbook bytes confirmed; bridge safety tests 5/5. Actual two-PC concurrent Office editing requires separate field verification.

## v1.3.74 - Startup performance and Explorer flicker analysis
- Official RaiDrive 2026.10.0 publicly confirms WinFsp support and WebDAV/SFTP/FTP multi-IP connection speed improvements, but its internal connection scheduler/cache/notification calls are proprietary and cannot be verified.
- NAS Mount connection is already PARALLEL per profile, not serial. Actual startup can vary with remote endpoint, Google API, WinFsp process creation and verified root/volume calls. Do not mark connected until verified.
- Previous NAS Mount sent repeated SHCNE_ASSOCCHANGED + CSIDL_DRIVES + per-window Explorer F5 on each of 6 profiles. Each connected drive also had a delayed second set of F5, while startup history cleanup refreshed Explorer even when no residue existed. These redundant global Shell events can cause repeated desktop icon flickering and UI stalls.
- Current behavior: per-drive SHCNE_DRIVEADD / WM_DEVICECHANGE events remain immediate and bounded; global SHCNE_ASSOCCHANGED, This-PC refresh and F5 requests are coalesced into a short shared 900ms debounce. Cloud label creation is retried once after 650ms and coalesced. WinFsp processes, original user icons, file locks, VFS caches and safe disconnect unchanged.
- On normal startup without profile-owned disconnected history/icon lease, expensive unconditional stale-mount cleanup is skipped. Never delete unrelated vendor registry keys or an active drive.
- New log CONNECT_ROOT_PHASE_TIMING splits existsMs, enumerateMs, capacityMs; STARTUP_RESIDUE_CHECK_TIMING and STARTUP_DEPENDENCY_GATE_TIMING. An initial Google Drive remote folder listing and capacity verification may vary greatly; no premature green connected status.
- Main rclone v1.76.0, TeraBox v1.76.0-extra, LockBridge v1.3.73 binary unchanged. Performance field target 2-3 sec is aspirational, NOT a verified claim; actual RaiDrive speed comparison requires tests under identical network credentials/cache conditions.
## v1.3.75 — parallel mount Shell arrival batch + Google startup diagnostics

- NAS Mount 1.3.74 field logs show six-profile startup can take 7.58s or 35.28s on the same PC. The ~35-second case was Google rclone process/mount startup; the actual Google drive root listing/capacity probe took only 243 ms. Fast local engine SHA validation was NOT the bottleneck. The exact Google API/OAuth cause is not established.
- While each real WinFsp mount and its per-profile file locks are started/verified independently, auto-connect now COLLECTS the completed mounts for presentation to Explorer. It sends one combined WM_DEVICECHANGE volume arrival (bitwise OR of verified owned drive letters) plus one Shell computer-folder refresh when all requested profiles finish. A 9-second deadline publishes already-ready drives without making them wait for a 35-second Google initialization; a delayed cloud finishing later is notified separately. The batch settles for 650ms to allow late WinFsp UNC labels to register. Windows may still generate its own events when drivers mount; reducing NAS Mount-originated events is confirmed, not a guarantee of zero flicker.
- A profile not actually mounted (or owned by another provider) is EXCLUDED from the combined volume mask, and a disconnected drive is rechecked before notification. No mapped letter is removed to hide early mounts; no upload/VFS cache, file permissions, file locking, Explorer.exe restart or backend safety mechanisms are changed.
- UI Settings now includes opt-in Google startup DEBUG classification. When enabled, the next Google mount records only safe fixed categories (oauth_refresh, api_retry_or_pacer, rate_limit, backend_creation, mount_setup, root_discovery, backend_reported_error), per-category occurrence counts and elapsed milliseconds. NEVER stores raw Google DEBUG lines, remote file names/paths, URLs, tokens or passwords. The opt-in is OFF by default. This is not a new API authorization; existing GoogleDriveLoginDialog already accepts personal OAuth Client ID and Client Secret for explicit reauthorization.
- General rclone 1.76.0, TeraBox rclone-extra 1.76.0-extra, WinFsp and NASMountLockBridge v1.3.73 binaries unchanged. Runtime tests cannot prove RaiDrive's closed-source Shell notification strategy. A six-drive cold-start and Google timing comparison needs real-user validation.
## v1.3.76 — First Open Excel filename case-preservation fix

- User screenshot: opening a previously lowercase .xlsx through NAS Mount WinFsp lock bridge immediately showed .XLSX in Excel window title before Save.
- Reproduced with actual Excel COM on a disposable localhost WebDAV NAS: server LabActual.xlsx; bridge Directory listing LabActual.xlsx; Excel Workbooks.Open Name LABACTUAL.XLSX and FullName W:\LABACTUAL.XLSX. Direct rclone without bridge preserved LabActual.xlsx (including rclone --network-mode).
- Root cause: the WinFsp DotNet case-insensitive search configuration caused uppercase normalization of the OS open-path despite case-preserved backend filenames. Returning NormalizedName manually did NOT fix the Excel title. With WinFsp CaseSensitiveSearch=true and CasePreservedNames=true, the real Excel title and actual remote filename remain LabActual.xlsx at open and save.
- No extra remote filename enumeration on every open; existing lock coordinator and Excel staged atomic Save still used. The WinFsp remote change monitor now sends exact-case paths instead of previous uppercase Notify paths, matching the new volume policy. Verified remote root and nested create/change/delete events.
- CaseSensitiveSearch changes the Windows filesystem advertised semantics; test potential odd case-clashing remote names on user NAS before broad deployment. Exact file name already returned in enumerations; lower rclone mount remains case-insensitive. Never force-rename existing remote files.
- Engines unchanged: rclone 1.76.0 / TeraBox extra 1.76.0-extra. Only NAS Mount main and WinFsp LockBridge v1.3.76 newly rebuilt.


## v1.3.78 - full safe exit restored + cold Google mount API pacing

- Based on v1.3.76, NOT v1.3.77. Removes independent persistent engine, keep connected while closing, new settings toggle. UI ExitSafe always disconnects all owned remote volumes with pending upload / file lock guards and then exits. X still hides to tray.
- Actual user Google startup logs: 2s or 34-36s; one DEBUG trace confirmed rate_limit and api_retry_or_pacer. rclone drive defaults 100ms minSleep / burst100. Google mounts only: use --drive-pacer-min-sleep 250ms and --drive-pacer-burst 2, preserving rclone's official retry/backoff and the real root-browse verification.
- On startup/after root verified, log ONLY Boolean expiration of saved OAuth access token, NEVER token text or account/folder names.
- Real Google read-only A/B on ephemeral cloned user OAuth config with no file modifications showed default 19.09s and 1.31s, paced 1.23s, 1.20s and ALSO 9.91s. Hence improvements are variable and not an SLA or guaranteed RaiDrive parity. No Google credential/client ID changed. Check README_1378_GOOGLE_COLD_CONNECT_KR.md.
- Upstream rclone 1.76.0, TeraBox extra 1.76.0-extra, WinFsp driver and NASMountLockBridge v1.3.76 unchanged.
