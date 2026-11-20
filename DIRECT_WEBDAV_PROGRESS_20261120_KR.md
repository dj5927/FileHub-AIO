# FileHub AIO PC — 단일 WinFsp 전용 WebDAV 엔진 작업 기록

## 2026-11-20 v1.3.118 긴급 WebDAV 연결 호환성 수정

사용자 실NAS에서 두 WebDAV 프로필이 `Direct WebDAV server did not authorize a safe mount`
오류로 연결되지 않았다. 로그는 `RequireVerifiedConditionalWrites()`의
`Conditional PUT not supported: create failed`에서 발생했다. FTP는 사용자가
재확인한 결과 정상 연결됐으므로 FTP 코드는 변경하지 않았다.

분석: Synology WebDAV `/` 경로는 공유폴더를 표시하는 가상 루트이기 때문에
하위 공유폴더에 쓰기 권한이 있어도 `/`에 직접 `PUT/MKCOL` 생성이 거부될 수 있다.
기존 v1.3.117은 최상위 `/`만 검사해 서버가 조건부 저장을 지원하지 않는다고
잘못 판정했고, 쓰기 가능한 다른 공유폴더를 검사하지 않고 연결 자체를 거부했다.
Synology 공식 문서도 공유폴더별 권한 설정을 안내한다.

v1.3.118 수정:

- `LockBridge/DirectWebDavWriteStore.cs`: 조건부 `PUT` 및 배타적 `MKCOL`
  임시 검사 위치를 전달받도록 변경; 결과 HTTP 코드 포함. 임시 검사 파일은
  난수 이름만 사용하고 cleanup에서 삭제하며 사용자 파일을 덮어쓰지 않는다.
- `LockBridge/ManagedDirectWebDavHost.cs`: 기존 공유폴더 최대 8곳에서
  조건부 쓰기/배타적 잠금을 우선 시험하고 마지막에 `/` 시험. 서버가 명시적으로
  `If-Match/If-None-Match`를 무시하면 다른 폴더까지 반복하지 않고 즉시 호환 전환.
- 강제 덮어쓰기는 허용하지 않음. 서버에서 안전한 조건부 저장을 입증할 수 없으면
  **마운트를 시작하기 전에** 코드 24와 `DIRECT_WEBDAV_COMPATIBILITY_REQUIRED`를
  반환하여 기본 FileHub가 기존 rclone+LockBridge 호환 경로로 자동 연결한다.
- `RemoteMount.cs`: 해당 코드 24에서만 호환 경로로 전환. 네트워크 연결 오류,
  로그인 오류나 별도 이상 종료는 임의로 안전검사를 건너뛰지 않는다.
- `MainForm.cs`: 실제 단일 WinFsp로 연결했을 때만 해당 방식이라고 표시한다.
  호환 경로로 연결되면 기존 방식의 연결/잠금 상태를 표시한다.
- `tests/strict_webdav_conditional_proxy.py`: `/`의 파일 생성만 HTTP 403으로
  차단하고 공유폴더 안의 쓰기는 허용하는 Synology 가상 루트 환경 재현.
- `tests/direct_webdav_managed_lifecycle_lab.ps1`: 이 가상 루트에서 실제
  단일 WinFsp 파일 저장·열린 핸들 종료 거부·정상 해제 시험 성공.
- `tests/direct_webdav_compatibility_gui_lab.ps1`: 서버가 조건부 PUT을
  무시하는 상황을 재현하고, FileHub GUI가 자동으로 기존 rclone 호환 드라이브로
  연결해 파일을 읽고 안전하게 연결 해제하는 시험 성공.
- `.github/workflows/publish-pc.yml`: 실제 WinFsp 드라이버 설치 및 위 두
  호환성 통합 검사를 PC 릴리스 이전 필수 단계로 추가.

**중요:** 사용자 실제 Synology 계정으로 쓰기 권한을 확인한 것은 아니다.
단일 WinFsp가 적용될지는 실제 NAS가 요청 조건을 충족하는지에 달려 있다.
지원하지 않으면 v1.3.116과 동일한 구형 호환 드라이브를 사용하며,
그 경우 EXE 폴더 지연·Excel 재저장 문제가 재현될 가능성은 남는다.
검증되지 않은 향상 효과를 완료라고 표시하지 말 것.

기록일: 2026-11-20 / 브랜치: `pc/direct-webdav-engine`

## 1. 요청 목적

RaiDrive에서 즉시 열리는 NAS의 EXE 폴더와 프로그램이 기존 FileHub에서는
아이콘 로딩, 파일 열기, 실행까지 수십 초 이상 걸리는 구조 문제 해결.
기존 경로 `탐색기 -> WinFsp 잠금 브리지 -> rclone WinFsp -> WebDAV -> NAS`를
`탐색기 -> 단일 WinFsp -> FileHub 전용 WebDAV 클라이언트 -> NAS`로 교체.

## 2. 이번 세션에 구현한 코드

- `LockBridge/DirectWebDavReadOnlyBackend.cs`: 단일 계층 HTTP WebDAV
  PROPFIND, GET/Range, ETag·메타데이터 캐시, EXE/DLL 로컬 읽기 캐시,
  사용자에 저장된 WebDAV 프로필의 정확한 주소·포트·경로와 TLS 정책 사용
- `LockBridge/DirectWebDavReadOnlyFileSystem.cs`: WinFsp 읽기·탐색·속성,
  폴더 변경 감시와 탐색기 이벤트 통지
- `LockBridge/DirectWebDavWriteStore.cs`: 파일별 안전한 로컬 저널,
  조건부 PUT, 안전한 MOVE/DELETE, 충돌 감지, 전체 해시 검증 기반 복구,
  다른 클라이언트가 원격 파일을 수정했으면 자동 덮어쓰기 거부
- `LockBridge/DirectWebDavWritableFileSystem.cs`: 실제 WinFsp 생성·쓰기·
  Flush·Close·이름 변경·삭제·폴더 처리, Excel 문서 잠금 유지
- `LockBridge/DirectWebDavLeaseLab.cs`: 두 클라이언트의 잠금 경합과
  실제 열려 있는 Excel 문서에 대한 다른 클라이언트 편집 차단 검증
- `LockBridge/Program.cs`: 개발용 loopback 시험 모드와 저장된 WebDAV
  프로필로만 실행 가능한 독립 읽기 전용/쓰기 파일시스템 파일럿 모드
- `tests/start_direct_webdav_readonly_pilot.ps1`: 비밀번호가 저장되지
  않았을 때 채팅이나 명령줄이 아닌 Windows PowerShell 보안 입력으로만
  인증 정보를 일시적으로 전달하는 **읽기 전용** 실제 NAS 시험 스크립트

## 3. 검증 결과 (격리된 테스트 NAS 역할의 WebDAV 서버)

- 읽기용 단일 WinFsp: 처음 EXE 정보 읽기 약 37~60ms, 재조회 0~1ms
- 실행 파일 SHA-256: 원본 일치, 캐시 변조 방지 및 변경 버전 반영
- Windows 파일 API: 파일 생성, 2·3차 저장, 이동, 삭제 모두 성공
- **실제 Microsoft Excel**: `SaveAs` + 두 번의 `Save` 정상 반환,
  NAS 역할의 서버에서 XLSX를 다시 열어 최종 셀 값 확인 성공
- Excel이 열린 상태에서 두 번째 FileHub 클라이언트의 편집 잠금 차단
- 두 FileHub 클라이언트의 잠금 충돌 차단과 정상 해제 후 재취득 검증
- 별도 클라이언트의 동시 수정 발생 시 덮어쓰기 차단 + 로컬 복구본 보존
- 비정상 종료 모의 이후 다른 인스턴스에서 로컬 저널 다시 읽어 복구;
  다른 클라이언트가 그 사이 파일을 변경했으면 복구본만 보존
- WebDAV 서버가 `If-Match`/`If-None-Match` 또는 배타적 `MKCOL`을
  실제로 보장하지 않으면 위험한 쓰기를 시작하지 않도록 차단
- 기존 PC 앱의 주요 정책 회귀 검사 및 LockBridge 자체 테스트 통과

최종 테스트 실행 결과: `ALL_NATIVE_WIN_FSP_DIRECT_DEVELOPMENT_END_TO_END_TESTS_PASS`.
상세 로그는 개발 PC 저장소의 `.direct-final-readonly-lab.log`,
`.direct-final-recovery-lab.log`, `.direct-final-excel-lab.log` 등에 기록.

## 4. 실제 NAS 현장 검증을 못한 이유

현재 PC에 저장된 WebDAV 프로필의 **RememberPassword=false**로 인해,
별도 읽기 전용 시험 엔진이 저장된 인증 정보를 찾지 못해 실제 NAS 연결 전에
종료함. 비밀번호 우회·추출은 하지 않았고, 사용자 NAS는 변경하지 않았음.
사용자가 안전한 **읽기 전용** 파일럿 스크립트를 직접 실행하면 로컬
Windows 보안 입력을 통해 인증할 수 있음.

## 5. 정식 배포 미전환 이유 및 남은 사항

**아직 전체 FileHub 완성이 아님.** 새 엔진은 현재 개발 브랜치의
시험·파일럿에만 적용하며, 기존 `pc/windows` 정식 릴리스 v1.3.116은
변경하지 않았음. 아래 항목이 실제 장비로 검증되지 않았음:

1. 실제 Synology/QNAP WebDAV에서 조건부 PUT과 배타적 MKCOL 지원 여부
2. 실제 NAS와 두 PC에서 파일명·Excel 연속 저장·동시 잠금·충돌 복구
3. 실제 NAS의 EXE/DLL 실행·아이콘 속도 및 장시간 실행 중 성능
4. GUI Connect/Disconnect, 자동연결, 업데이트, 비정상 종료 후 복구 안내
5. 네트워크 장애/PC 강제 종료/대용량 파일/다른 앱과의 파일잠금 처리
6. SFTP, FTP, Google Drive, TeraBox 등 다른 프로토콜의 전용 엔진 통합

안전하지 않은 서버에서 무조건 저장되도록 파일잠금이나 ETag 검증을
끄는 우회는 하지 말 것. 사용자가 테스트하기 전 정식 릴리스에 혼합 금지.

## 6. 재시작 시 확인

1. GitHub 브랜치 `pc/direct-webdav-engine` 및 최신 커밋 확인
2. `DIRECT_WEBDAV_ONE_LAYER_ARCHITECTURE.md` 확인
3. 개발 PC의 위 3개 QA 로그와 Git 작업 트리 상태 확인
4. 실제 NAS 계정 인증이 가능해지면 읽기 전용 파일럿부터 테스트
5. 전체 정식 기능과 안전성 검증 전 `pc/windows`에는 병합하지 않음

## 7. PC v1.3.117 일반 FileHub 연결 통합 (추가 구현)

- WebDAV 기본 연결은 `RemoteMount.Connect` -> `ConnectDirectWebDavAsync` -> `NASMountLockBridge.exe --direct-webdav-managed` 경로로 접속한다. Windows GUI 사용자는 별도 PowerShell 또는 시험 프로그램을 실행하지 않는다.
- 단일 WinFsp 엔진은 기존 설정 파일·자격 증명을 사용한다. 비밀번호는 자식 프로세스 환경으로 일시 전달하고 프로세스 인수·로그에는 쓰지 않는다. 루트 주소는 사용자가 설정한 WebDAV URL 그대로 사용한다.
- `RemoteMount.VerifyMountedBackendHealth`, `GetTransferStatus`, `CheckPersistentMount`, `Disconnect`가 기존 rclone RC 대신 엔진 이름 지정 파이프 `PING/STATUS/QUIT`를 사용한다.
- 활성 편집 파일, 문서 잠금, 미전송 복구본이 있으면 `QUIT` 거부. 읽기 전용 메타데이터 핸들만 남은 경우는 안전하게 종료한다.
- 기존 WebDAV 중 서버 기본 RFC4918 LOCK 기능을 명시한 프로필은 기존 경로를 유지한다. SFTP/FTP/Google/TeraBox/OneDrive/MyBox 역시 원래 백엔드를 사용한다.
- 실제 FileHubAIO.exe 프로세스의 `RemoteMount.Connect` 경로에서 별도 임시 서버 드라이브로 Microsoft Excel `SaveAs` 후 두 차례 추가 `Save` 실행과 NAS 측 최종 XLSX XML `SAVE_3` 검증 성공.
- 실제 Windows EXE 파일을 WebDAV 드라이브에 복사한 후 원본 SHA-256 검사, Win32 CreateProcess를 통한 직접 실행, 버전 리소스 조회까지 성공.
- UI 수준의 `Connect`/상태 확인/`Disconnect`, 파일 열린 상태의 안전 종료 거부, 파일 닫힌 후 연결 해제, 기존 PC 업데이트·고스트 정리·Google 정책 회귀 검사 통과.
- 패키지 생성 대상 버전은 **1.3.117**. 일반 FileHub 앱/전용 WinFsp 엔진을 모두 self-contained publish 후 Google/TeraBox/rclone 엔진과 함께 ZIP 구성한다.

**주의:** 개발 PC의 실제 Synology NAS 프로필에는 기억된 인증 정보가 없어 현장 측정은 불가능했다. 버전 번호가 올라갔다고 실제 NAS 호환성을 증명한 것으로 간주하지 말 것. 파일 안전 정책을 우회해 저장을 강제하지 말 것. 기존 1.3.116 정식 버전은 반드시 복원 가능하도록 보존한다.