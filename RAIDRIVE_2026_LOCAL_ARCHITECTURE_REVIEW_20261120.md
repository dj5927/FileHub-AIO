# RaiDrive 2026.10.0 설치본 구조 조사 (2026-11-20 KST)

## 범위와 안전

- 대상: 현재 사용자 PC에 설치되어 실행 중인 **RaiDrive Mount 2026.10.0 x64**.
- 모든 조사는 읽기 전용. 프로그램 종료, 연결 해제, 설정 변경, 토큰/계정/파일 본문 열람, 캐시 삭제는 하지 않음.
- 설치 파일 자체에서 확인한 정보와 가능한 구조적 추론을 구분함. 제3자 비공개 소스 코드를 확보하거나 내부 알고리즘을 완전히 분석한 것은 아님.

## 현장 확인: 설치 및 프로세스

- 설치: `C:\Program Files\OpenBoxLab\RaiDrive Mount`
- 실행: `RaiDrive.Mount.exe`, `RaiDrive.Mount.Service.x64.exe`(실행 중 2개), `RaiDrive.NotifyHelper.exe`(2개).
- Windows 서비스 `RaiDriveMountService`: Running, Auto Start, LocalSystem; 서버 실행 파일은 `RaiDrive.Mount.Service.x64.exe`.
- 두 서비스 프로세스가 실제로 `winfsp-x64.dll`과 `e_sqlite3.dll`을 로드함.
- 설치 구성품에 `RaiDrive.ShellExtension.x64.dll`, `RaiDrive.ShellExtension.x64.comhost.dll`, `WebView2Loader.dll`, WinFsp 설치용 MSI가 존재함.
- 설치 폴더 및 현 실행 프로세스에 별도 `rclone.exe` 없음. 서비스/GUI EXE의 ASCII·UTF16 정적 마커 검사에서도 `rclone` 또는 `github.com/rclone` 문자열 미발견. 이는 *독립적인 rclone 실행 프로그램을 사용한다는 증거가 없다는 뜻*이지, 내부에서 어떤 오픈소스도 절대로 사용하지 않는다는 법적·기술적 증명은 아님.
- 서비스 실행 파일의 정적 구성 마커: `FspFileSystem`, `GoogleDrive`, `HttpClient`, `FileCache`, `DriveCache`, `SQLitePCL`, `Microsoft.Data.Sqlite`. GUI 실행 파일에는 `GoogleDrive`, `DiskCache`, `DriveCache`, `HttpClient` 등 존재. 이는 코드 경로/클래스 명칭을 암시하지만 구현 알고리즘을 증명하지는 않음.

## 로컬 캐시 관찰 및 공식 문서

- 기본 경로 `%ProgramData%\OpenBoxLab\RaiDrive Mount\Cache` 존재. 현장 조회 시 직계 하위 디렉터리 2개, 그 하위에서 일반 파일 0개로 관찰. 당시 디스크 캐시가 비어 있었을 수 있으므로 이를 근거로 메타데이터 캐시를 쓰지 않는다고 주장하면 안 됨.
- 프로세스에서 SQLite 라이브러리 로딩 확인. 사용자 AppData에서 관찰된 SQLite DB는 브라우저 WebView2 경로의 DB이므로 RaiDrive의 내부 메타데이터 DB임을 단정할 수 없음.
- 공식 RaiDrive 2026.10.0 릴리스 노트는 WinFsp 추가, 캐시 파일/로컬 DB 보안 강화, TPM을 통한 연결 정보 보호를 명시.
- 공식 캐시 문서에 따르면 다운로드/업로드는 로컬 파일 캐시를 거치며, 기본 다운로드·업로드 유지시간은 각각 30분. 이는 **파일 콘텐츠 캐시** 설명이며 폴더 메타데이터의 정확한 지속화 방식에 대한 직접 증거는 아님.
- 출처: https://www.raidrive.com/release-notes/2026.10.0 , https://docs.raidrive.com/ko/gui/settings/cache/

## 현장 응답 비교

- 현재 RaiDrive Google Drive는 `Y:` 네트워크 드라이브로 연결됨. 해당 드라이브의 루트 항목 최대 30개를 이름/내용 출력 없이 읽기 전용 조회했을 때 3회 각각 **8 ms / 0 ms / 0 ms**.
- 이는 이미 연결된 RaiDrive에서의 **웜(warm) 폴더 목록 반응**이며, 연결 버튼 클릭부터 완료되는 콜드(cold) 마운트 시간이나 API 호출 시간을 측정한 것이 아님.
- 2026-11-19 기존 비교(당시 FileHub v1.3.100)에서는 RaiDrive Y: 루트 30항목 11ms, FileHub Google W: 9초 초과 및 FileHub 자체 rclone RC `operations/list` 12초 초과. 테스트 당시 환경/버전 한정.
- 현재 RaiDrive를 끊었다 다시 연결하는 실험은 **기존 사용자 연결 상태가 달라지므로 실시하지 않음**.

## FileHub AIO PC 코드와 다른 점

- FileHub Google 연결은 `RemoteMount.Connect`가 프로필별 `rclone-filehub-google.exe`를 연결 시 시작하여 WinFsp로 마운트함. 이 앱은 rclone 프로세스를 종료하면 해당 프로세스의 디렉터리 캐시도 함께 버려짐.
- Google VFS 디렉터리 캐시 설정은 30분, Changes API 폴링 1분, pacer 100ms/burst8. **메모리 캐시 지속 시간은 프로세스가 살아 있는 동안의 설정**이므로, 연결 해제 후 다음 실행까지 캐시가 살아남는다는 뜻은 아님.
- Google 시작 때 폴더 조회와 WinFsp 루트 검증을 기다리고, 원격 API 재시도/토큰 갱신 지연이 초기 연결 완료까지 전파될 수 있음.
- 2026-11-19 FileHub 로그에서 연결 시간 1.4~34.7초, 반면 성공 후 루트 열거 0.16~0.35초 사례. 2026-11-11/12/17 로그에 `rate_limit`과 `api_retry_or_pacer` 진단 기록.
- 현재 FileHub Google OAuth 설정은 rclone 공용 클라이언트 모드로 관찰됐으며, 별도 전용 OAuth Client ID 사용 시 공유 쿼터 제한 문제를 줄일 가능성이 있음. 단, 전용 OAuth도 Google 사용자 단위/API 한도와 네트워크 지연을 완전히 없애지는 못함.

## 적용 설계 제안 (검증 전, 구현 안 함)

1. **연결 성능 계측부터 통일:** 동일 PC, 동일 Google 계정과 디렉터리, 같은 콜드/웜 조건에서 `버튼 클릭 → 프로세스 시작 → OAuth 갱신 여부 → API 첫 응답 → 드라이브 문자 → 루트 목록 확인` 단계별 계측. 자격 증명·경로·파일명은 기록하지 않음.
2. **Google 인증 개선:** 별도 FileHub Desktop OAuth 클라이언트 공식 등록, 명시적 재인증/토큰 보존 경로. 공용 rclone OAuth 의존성 제거. Google 이용 약관 준수.
3. **상주 Google 전용 백엔드:** UI보다 먼저 기동하는 프로세스/서비스가 토큰과 캐시를 보존하고, 연결 버튼은 기존 백엔드의 마운트 객체만 연결. UI 종료의 사용자가 승인한 드라이브 해제 정책은 유지. 백엔드가 상주해도 마운트가 남도록 강제하지 않음.
4. **폴더 메타데이터의 지속 가능한 스냅샷:** 자체 SQLite 기반 cache + Google Changes API 비동기 무효화/갱신. 오프라인 또는 API 지연 시에는 캐시 데이터가 오래되었음을 구별해야 하며, 허위로 정상 온라인 연결이라고 표시하지 않음. 단순 rclone VFS 콘텐츠 캐시와 별개.
5. **원격 파일 안전성:** 파일 읽기, 수정, 크기, 이름 변경, 삭제, Excel 잠금, 다른 PC와 충돌, 업로드 재시도와 미완료 전송 저널을 검증한 후 활성화. 빠른 연결만을 위해 무조건 즉시 연결 성공 표시 금지.
6. **구현 선택지:** (A) rclone 유지 + 상주 엔진 및 자체 디렉터리 스냅샷으로 점진 개선 / (B) Google Drive API 직접 통신 + 자체 WinFsp 파일시스템 개발. 현재 기능 보존과 위험 관리 측면에서 A 먼저 측정 후 결정.

## 결론의 확신 수준

- **높음(관찰):** Windows 자동 시작 RaiDrive 서비스 + WinFsp DLL 사용 + SQLite 라이브러리 사용 + 셸 확장 + 실행 중 별도 rclone 프로세스 없음.
- **중간(정적 바이너리 분석):** 자체 Google/HTTP 연결 코드를 포함하는 것으로 보임. rclone 경로 의존도는 낮아 보임.
- **추정:** 서비스 상주와 내부 캐시가 1초 체감 연결의 핵심. 실제 RaiDrive 연결 핸드셰이크, SQLite 스키마, Google API 캐시 무효화 로직, 정확한 영속성 구조는 아직 확인되지 않음.
