# FileHub AIO Android v0.1.68 - Synology / QNAP NAS 파일 공유 링크

## 사용법

1. **원격 저장소 목록 > 해당 NAS 길게 누르기 > NAS 공유 링크 설정**을 선택합니다. 네이버 MYBOX·TeraBox 등 클라우드에서는 메뉴가 없습니다.
2. 제조사 **Synology DSM** 또는 **QNAP QTS / QuTS**를 선택합니다. DSM/QTS 관리 HTTPS 주소, File Station 기준 공유 폴더(예: `/video`, `/Public`), 관리 계정과 비밀번호를 입력합니다. 원격 파일 접근에 사용하는 SMB/WebDAV 주소와 File Station 주소는 서로 다를 수 있습니다.
3. **인증 확인 후 저장**을 누르면 관리 API에 실제로 로그인하고 File Station 호출까지 성공한 경우에만 기능을 활성화합니다. 관리 비밀번호는 기존 Android Keystore 암호화 저장소에 저장합니다.
4. NAS 연결에서 파일/폴더 **하나를 선택 > 더 보기 > 공유 링크 생성**을 선택합니다. 만료 1·3·7·30일·무제한·직접 날짜, 선택적 링크 암호를 지정합니다.
5. NAS에서 링크 발급 성공 시 URL을 복사하거나 Android 공유 메뉴로 전달할 수 있습니다. **공유 링크 관리**에서 현재 공유 폴더에 속하고 현재 계정이 발급한 링크 목록을 조회하고 공유 해제합니다.

## 구현

- Synology: `SYNO.API.Auth` 로그인, `SYNO.FileStation.Sharing` v3 `list/create/delete`. 응답의 개별 링크 생성 오류도 검사합니다.
- QNAP: `authLogin.cgi` 인증, File Station HTTP API v5 `check_sid/get_share_link/get_share_list/delete_share`. 서버 응답에서 공유 URL을 가져옵니다.
- 공유 API 통신은 HTTPS 인증서 검증, HTTP 리디렉션 비허용, 고정된 엔드포인트와 POST 폼 사용. 인증 비밀번호와 공유 암호를 URL 쿼리에 넣지 않습니다.
- 별도 FileHub 다운로드 서버, 파일 중계 업로드, 외부 링크 생성 대행 서버는 없습니다. NAS 자체가 다운로드를 처리합니다.

## 제한 및 검증 요청

- NAS 제조사 API는 DSM/QTS 버전 및 관리계정·File Station 권한·2FA 정책에 따라 일부 조합에서 인증 실패할 수 있습니다. 기본 SMB 연결의 성공과 별개입니다.
- 인증서는 신뢰되는 인증기관 또는 장치에 신뢰 등록된 인증서여야 합니다. 자체 서명 인증서를 무조건 허용하지 않습니다.
- `File Station 기준 공유 폴더`의 경로 매핑은 NAS 관리자에게 확인해야 합니다. 예를 들어 SMB 공유 `Movies`와 DSM 공유 폴더 `/video`가 같을 수도 다를 수도 있습니다.
- 링크의 **외부 인터넷 접근**은 NAS 제조사의 QuickConnect/공유 서비스, myQNAPcloud 또는 사용자가 안전하게 구성한 HTTPS 네트워크 설정에 따라 달라집니다. 관리 콘솔 포트를 외부에 무조건 공개하지 마세요.
- 이 버전은 공식 API 호출을 구현했지만 실기기 NAS 계정이 없어 DSM/QTS와 통신하는 통합 시험은 실행하지 못했습니다. 발급된 링크는 외부 네트워크에서도 열리는지 직접 확인해 주세요.
- QNAP API는 펌웨어별 응답 및 공유 경로 표현이 다를 수 있어 장비에서 생성·조회·해제 순서대로 확인해야 합니다. 링크 공유 목적의 외부 URL은 필요 시 별도 HTTPS 주소로 지정합니다.
