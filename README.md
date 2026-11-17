# FileHub AIO PC for Windows

Windows 10/11용 NAS·클라우드 드라이브 마운트 프로그램입니다.

## 버전과 업데이트

- PC 버전: v1.3.86, 이후 v1.3.87 / v1.3.88...
- PC 전용 GitHub 릴리즈 태그: pc-v1.3.86 / pc-v1.3.87 ...
- Android 버전: v0.1.x — Windows 업데이트와 별개로 운영
- 프로그램 실행 시 트레이 업데이트 알림 / 환경 설정 → PC 업데이트 확인
- 새 PC 버전을 받으면 GitHub 릴리즈 ZIP SHA-256 검증 → 사용자의 설치 승인 → 원격 드라이브 안전 해제 → 프로그램 업데이트 후 재실행
- 업데이트를 강제로 설치하거나 사용자의 원격 데이터를 임의 삭제하지 않습니다.

## 설치

GitHub Releases에서 FileHub-AIO-PC-1.3.86-win-x64.zip 같은 Windows 전용 ZIP을 받습니다. 압축을 별도 폴더에 풀고 FileHubAIO.exe를 실행합니다. 기존 v1.3.83 / v1.3.84 / v1.3.85에서 PC 업데이트 확인으로 v1.3.86을 설치할 수 있습니다.

업데이트 설치 전에는 Excel/Word 등 열린 문서를 모두 저장하고 닫아야 합니다. 안전하게 해제하지 못한 NAS 드라이브가 있으면 프로그램 교체를 중단합니다. 이전 버전은 사용자 백업 폴더에 보관합니다.

## 포함 엔진 및 출처

- .NET 10 (Windows x64 자체 포함 빌드)
- rclone v1.76.0: https://github.com/rclone/rclone
- rclone-extra v1.76.0-extra (TeraBox): https://github.com/gulp79/rclone-extra
- WinFsp v2.2.26215: https://github.com/winfsp/winfsp

GitHub Actions는 공식 릴리즈 파일과 컴파일된 실행 파일의 SHA-256을 검사한 후 Windows 전체 패키지를 작성합니다. 제3자 엔진의 저작권/라이선스는 각 원본 프로젝트에 따르며, WinFsp에서 파생된 예제의 원 저작권 고지를 유지합니다.

## 소스 빌드

PowerShell에서 dotnet restore NASMount.csproj, dotnet publish NASMount.csproj -c Release -r win-x64 --self-contained true -o publish, dotnet publish LockBridge/LockBridge.csproj -c Release -r win-x64 --self-contained true -o bridge 를 순서대로 실행합니다.

두 프로젝트를 컴파일하는 것만으로 전체 배포본이 완성되는 것은 아닙니다. rclone, 확장 엔진, WinFsp 배포 파일은 GitHub Actions에서 공식 릴리즈로부터 수집합니다.

## 검증 범위

업데이트 무결성, 버전 필터링, 로컬 WinFsp 저장, 백업/롤백에 대한 자동 검사를 포함합니다. 실제 사용자 NAS의 다중 PC Excel 동시 편집 및 서버별 잠금 호환성은 별도 시험이 필요합니다.

## v1.3.85 시놀로지 공유 링크

- 환경 설정의 Synology 공유 링크 생성 / 관리, 또는 NAS 연결 카드의 추가 메뉴에서 엽니다.
- 최초 1회 DSM HTTPS 주소 (예: https://nas.example.com:5001), DSM 계정 및 최상위 공유 폴더 (예: /video)를 지정하세요. 각 FileHub AIO 드라이브에 별도로 설정됩니다.
- DSM 비밀번호는 Windows 자격 증명 관리자에 보관하며, 설정 JSON에는 비밀번호나 OTP를 넣지 않습니다.
- 2단계 인증이 필요하면 OTP 6자리를 입력합니다. Secure SignIn 푸시 승인 전용 계정은 별도 호환 확인이 필요합니다.
- 링크 생성 시 만료 1/3/7/30일·무제한, 선택적 암호를 지원합니다. 링크를 복사하거나 관리 목록에서 조회·선택 해제할 수 있습니다.
- 탐색기 메뉴 등록은 사용자가 버튼을 누를 때만 HKCU에 추가됩니다. Windows 10에서는 우클릭 메뉴, Windows 11에서는 우클릭 → 추가 옵션 표시(Shift+F10)에서 사용합니다. 기본 메뉴 강제 변경이나 Explorer 재시작은 없습니다.
- 선택된 경로는 FileHubAIO.exe의 --synology-share 명령행 인수로 전달합니다. 원본 파일을 외부 중계 서버로 업로드하지 않습니다.
- DSM 설정은 %APPDATA%/NASMount/synology-sharing.json에 저장됩니다. 기존 설정 내보내기/가져오기 파일에는 아직 포함되지 않아 다른 PC에서는 다시 설정해야 합니다.
- 실제 DSM 버전별 공유 발급, 외부 링크 다운로드 및 2FA 동작은 사용자 NAS에서 별도 검증이 필요합니다.

## v1.3.85 PC 업데이트 하단 알림

- 앱 시작 직후 NAS 연결 상태와 무관하게 GitHub PC 전용 릴리즈를 확인합니다.
- 새 버전이 있으면 메인 화면 오른쪽 하단에 노란색 PC 업데이트 있음 v... 링크를 표시합니다. 최신 버전이면 숨깁니다.
- 링크를 누르면 다운로드·SHA-256 검증을 즉시 시작하고, 원격 드라이브 안전 해제와 프로그램 교체 직전에 사용자 승인을 요청합니다.
- Android 릴리즈를 PC 업데이트로 취급하지 않습니다.

## v1.3.86 Synology 공유 폴더 다중 선택

- DSM 주소, 계정, 비밀번호를 입력한 뒤 **목록 불러오기 · 다중선택**을 누르면 DSM File Station의 SYNO.FileStation.List v2 list_share API로 현재 계정이 볼 수 있는 공유 폴더 목록을 읽습니다.
- 예: /01자료, /02자료, /video를 한 번에 체크해 저장할 수 있습니다. 저장된 폴더 목록은 NAS 연결별로 유지됩니다.
- **NAS 파일 / 폴더 탐색** 버튼으로 선택한 공유 폴더 내부를 탐색하여 파일·폴더를 선택할 수 있습니다. 탐색하는 동안 인증 세션을 재사용하므로 폴더를 열 때마다 로그인하지 않습니다.
- 공유 링크 생성·목록·해제는 체크된 공유 폴더 내부로 한정됩니다. Windows 드라이브 경로가 여러 DSM 공유 폴더 중 어디에 해당하는지 안전하게 판별할 수 없으면 추측하지 않고 DSM 파일 탐색을 안내합니다.
- 기존 v1.3.85의 단일 ShareRoot 설정은 새 ShareRoots 목록이 비어 있을 때 계속 읽습니다. 새로 선택하면 ShareRoots 목록으로 저장합니다.
- OTP 6자리 입력 방식을 유지합니다. 실제 DSM 2단계 인증 및 NAS 실기기 링크 생성은 별도 검증이 필요합니다.

## v1.3.86 시놀로지 공유 링크 오류 진단 및 신뢰 PC 인증

- 이전에는 공유 링크 생성 때마다 DSM에 새로 로그인하면서 매번 OTP를 요청했습니다.
- 이제 DSM 로그인에 첫 OTP와 함께 **이 PC 신뢰**를 선택하면 공식 enable_device_token / device_name 필드를 보내고, DSM이 반환한 did를 Windows 자격 증명 관리자에 보호 저장합니다.
- 이후 DSM 로그인에는 저장한 device_id와 device_name을 전송하므로, 서버가 신뢰를 유지하는 동안 링크 생성·목록·탐색을 반복해도 OTP를 다시 입력하지 않습니다.
- 인증이 만료·철회되거나 DSM 보안 설정이 변경되면 OTP 입력창을 다시 표시합니다. 신뢰를 해제하면 저장된 PC 기기 ID를 지웁니다. OTP 숫자는 저장하지 않습니다.
- 토큰을 NAS 주소, DSM 사용자, FileHub 연결 프로필별로 구분하므로 다른 계정이나 NAS에 전달하지 않습니다.
- 생성 대상이 비어 있으면 오류가 나기 전에 DSM 파일/폴더 탐색으로 대상을 선택하도록 안내합니다. 공유 링크 API가 거부하면 API 이름·메서드·오류 코드와 점검 항목을 표시합니다.
- 실제 DSM NAS에서 인증·기기 신뢰·링크 발급 결과는 실사용 검증이 필요합니다. Windows 컴파일 및 오프라인 정책/UI 테스트 성공은 서버 호환성의 증거가 아닙니다.
