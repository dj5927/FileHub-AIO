# FileHub AIO v0.1.74 - QNAP myQNAPcloud Link 공식 웹 연결

## 구현한 기능

- QNAP NAS의 **NAS 공유 링크 설정**에서 `QNAP CloudLink로 포트 개방 없이 접속 (공식 웹)` 버튼을 표시합니다.
- 인증 완료된 QNAP 원격 저장소의 길게 누르기 메뉴에도 `QNAP CloudLink (포트 개방 없이 웹 접속)`을 표시합니다.
- QNAP 공식 포털 `https://www.myqnapcloud.com/`을 기기의 시스템 웹 브라우저로 실행합니다.
- 공식 myQNAPcloud에서 받은 SmartURL(예: `https://qlink.to/장치명`)을 해당 원격 연결에 저장하고 다시 실행할 수 있습니다.
- SmartURL은 HTTPS 및 QNAP 공식 도메인만 허용합니다. 계정 비밀번호, 브라우저 세션, CloudLink 토큰을 FileHub가 수집하거나 가져오지 않습니다.
- 기존 QNAP HTTPS API 직접 연결, 공유 폴더 자동 조회, 만료기간·암호·공유 링크 관리, DSM 2FA 등은 변경하지 않았습니다.

## 사용 방법

1. QNAP 원격 저장소 → NAS 공유 링크 설정 → QNAP QTS / QuTS 선택
2. `QNAP CloudLink로 포트 개방 없이 접속 (공식 웹)` 선택
3. `공식 포털 열기` → QNAP ID로 로그인 → 내 장치 → SmartURL → File Station에서 공유 링크 생성
4. 자주 사용하는 SmartURL은 링크를 복사해서 FileHub의 SmartURL 칸에 입력하면 다음부터 바로 웹 연결을 열 수 있습니다.

## 제약 및 주의

- **CloudLink 네이티브 릴레이 API 연동은 구현되지 않았습니다.** QNAP 공식 자료로 공개된 제3자용 CloudLink 세션·파일관리 API를 확인하지 못해 비공개 인증 구조를 추측해 사용하지 않았습니다.
- `공식 포털 열기`는 포트포워딩 없이 접속하는 QNAP 공식 웹 경로를 제공하는 기능이며, FileHub 자체의 NAS 연결이나 공유 링크 생성 API를 CloudLink로 우회시키는 기능은 아닙니다.
- QNAP 관리 API로 폴더를 직접 불러오려면 기존대로 직접 HTTPS 도달 가능한 네트워크 또는 VPN 연결이 필요합니다.
- QNAP 계정에 장치가 등록되고 myQNAPcloud Link가 활성화되어 있어야 원격 웹 접속이 작동합니다.
- APK 자동 테스트 및 Android Lint는 QNAP 클라우드 실제 계정에서의 동작을 대체하지 않습니다. QNAP 친구분의 기기 테스트가 필요합니다.
