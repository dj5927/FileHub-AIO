# NAS Mount v1.3.74 — 연결 속도 및 탐색기 깜빡임 분석/최적화

## RaiDrive와 비교한 확인 결과

- 사용자 관측: RaiDrive에서는 연결 목록이 보인 뒤 2~3초가량, NAS Mount v1.3.73에서는 전체 연결에 10~16초가량 소요되며 Google Drive는 대체로 8~15초. 같은 PC에서 초 단위로 동시 계측한 비교 기록은 아직 없습니다.
- RaiDrive 공식 2026.10.0 릴리스: WinFsp 추가, WebDAV/SFTP/FTP의 다중 IP 서버 접속 개선 등을 명시합니다. 내부 전용 캐시 구조나 탐색기 알림 횟수는 공개 자료만으로는 확인할 수 없습니다.
  - https://www.raidrive.com/release-notes/2026.10.0
  - https://docs.raidrive.com/gui/settings/cache/

## 우리 소스에서 확인한 중복 작업

1. NAS Mount는 연결을 프로필별로 병렬 시작하므로 6개 드라이브가 처음부터 하나씩 순차 연결되는 구조는 아닙니다.
2. 기존에는 매 연결마다 아이콘 재등록, 전체 Windows Shell 아이콘 연관 캐시 초기화(SHCNE_ASSOCCHANGED), 드라이브 추가 알림, 모든 열린 '내 PC' 창의 F5가 반복되었습니다. 일반 연결도 420ms 후 추가 전역 알림/F5가 발생했고 MYBOX/OneDrive는 별도 800ms 재시도가 있었습니다.
3. 시작 시 어떤 드라이브에도 이전 연결 기록이 남지 않은 경우에도 빈 기록 정리 경로에서 드라이브별 Shell 알림을 생성할 수 있었습니다.
4. Google Drive는 rclone 실행 뒤 WinFsp 드라이브 생성 → 폴더 첫 목록 조회 → 용량 조회를 완료해야 녹색으로 표시합니다. 이것은 실제 API 지연에 따라 달라집니다. 공개 자료만으로 RaiDrive가 동일한 검증 시점에 연결됨을 표시하는지는 알 수 없습니다.
5. 일반 rclone 파일 해시는 실행 중 처음 한 번 확인되고 이후 성공한 검증 결과를 캐시합니다. 정상 연결마다 전체 SHA-256 검사를 반복하지 않습니다. 별도 원격 `rclone lsf` 사전조회도 일반 연결에서는 이미 비활성화되어 있습니다.

## v1.3.74에서 변경된 항목

- **각 드라이브 WinFsp 연결/해제, Shell 드라이브 추가 알림 및 사용자 지정 아이콘은 유지.** 전역 Shell 연관 캐시 변경 및 열린 '내 PC' 창의 F5 요청만 공유 900ms 간격으로 합칩니다.
- 기존 WebDAV/SFTP/NAS/MyBox/OneDrive 라벨 적용 동작은 유지하되 중복 Shell COM 이름 조회를 줄이고, 신규 MyBox/OneDrive의 늦게 나타나는 표시 이름은 한 번만 재확인합니다.
- **정상적으로 해제되어 잔여 연결/프로필별 기록이 없는 경우** 시작 시 무조건 실행되던 불필요한 Shell 정리 및 F5를 건너뜁니다. GUID에 실제로 속하는 잔여 네트워크 위치/아이콘 임대가 있으면 기존 안전 정리 동작을 유지합니다.
- 연결 시작 단계의 시간 측정: `STARTUP_RESIDUE_CHECK_TIMING`, `STARTUP_DEPENDENCY_GATE_TIMING`, 프로필의 `PROFILE_OPERATION_TIMING`, `CONNECT_FAST_LOCAL_PREFLIGHT_TIMING`.
- 실제 드라이브 접근 검증을 `CONNECT_ROOT_PHASE_TIMING`으로 분해하여 `existsMs` / `enumerateMs` / `capacityMs`를 기록합니다. 특히 Google Drive 지연이 폴더 열거인지 용량 조회인지 구분하는 데 사용합니다.
- 서버 인증, 기본 연결 안전성 검사, Google OAuth 토큰, 잠금 브리지, 엑셀 저장·원격 업로드, WinFsp 프로세스, 해제 정책은 변경하지 않았습니다. 연결 검증 전에 녹색 표시를 강제하지 않습니다.
- **일반 rclone v1.76.0, TeraBox v1.76.0-extra, NASMountLockBridge v1.3.73 유지.** 이번에는 주 프로그램만 업데이트하였습니다.

## 테스트 및 현실적 제한

- 내부 전역 알림 묶기 로직 자체 시험: 3회 연속 갱신 요청이 1회 전체 갱신으로 합쳐지는 것을 확인.
- 새로운 프로필/기록이 없는 경우 불필요한 Shell 정리를 피하는 정책 검사와 기존 파일 관련/Google/OneDrive/NAS 기능 회귀검사 포함.
- 실제 사용자 PC에서 RaiDrive와 NAS Mount의 6개 연결을 같은 네트워크·동일 캐시 조건으로 실측한 것은 아닙니다. 전체 2~3초 달성은 아직 보장되지 않습니다.
- 만약 v1.3.74에서도 Google Drive가 8~15초면, 위 세부 타이밍 로그로 로컬 작업과 원격 API 지연을 구분할 수 있습니다. 실제 원격 API의 시간은 네트워크·서비스 상태에 따라 변합니다.

## 설치

기존 NAS Mount에서 열린 Excel 문서와 업로드를 완료하고 드라이브를 안전하게 연결 해제하세요. ZIP 전체를 **새 폴더**에 압축 해제하여 NASMount.exe를 실행하세요. `tools` 폴더를 그대로 유지하세요. 실제 클라우드 또는 NAS 데이터에는 개발/검증 도중 접속하지 않았습니다.

