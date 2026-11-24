# FileHub AIO PC — Google Drive 전용 OAuth 연결

## 현재 공개 배포판 v1.3.145 이용자 안내

1. FileHub에서 Google Drive를 선택하고 연결 이름을 입력해 저장합니다.
2. 연결 목록의 연결 버튼을 누릅니다.
3. Chrome에서 본인의 Google 계정을 선택하고 FileHub AIO의 Drive 접근 권한을 선택하여 승인합니다.
4. 계정 이메일 확인, Drive 권한 검사 및 인증 정보 저장이 성공하면 브라우저에 인증 완료 화면이 나타납니다.
5. Windows 탐색기에 연결된 드라이브가 나타납니다. JSON이나 API Client ID는 입력하지 않습니다.

**Google Drive 전체 접근 권한은 Google 검토 중입니다.** 미승인 계정은 Google
정책에 따라 접근이 차단될 수 있습니다. 앱 배포는 Google 인증 완료를 의미하지
않습니다. 현재의 Desktop Client ID를 유지하며, 새 승인에 요청하는 범위는
`https://www.googleapis.com/auth/drive`와
`https://www.googleapis.com/auth/userinfo.email`입니다.
이메일은 승인한 Google 계정 확인 및 완료 화면에만 표시하며 별도로 저장하거나
로그에 기록하지 않습니다. Drive 권한 미승인 시 새 연결 및 토큰 저장을 중단합니다.

게시자 설정은 GitHub Actions의 암호화 Secret에 저장하고 공식 ZIP에
데스크톱용 클라이언트 구성으로 포함합니다. 개인 로그인 토큰은 포함하지 않습니다.

아래 내용은 **v1.3.126 이전의 구형 사용자별 전용 OAuth 설정 안내**이므로,
현재 버전 사용자에게 추가 작업으로 요구하지 마세요.

Google Drive API의 403 rateLimitExceeded가 발생하는 경우
여러 사람이 공유하는 rclone 공용 Client ID 대신 별도로 발급한
OAuth Client ID를 사용하는 것이 좋습니다. 전용 인증이
모든 환경에서 1~3초 연결을 보장하는 것은 아닙니다.

## 1. Google Cloud에서 데스크톱 OAuth 앱 만들기

1. https://console.cloud.google.com/ 에 접속해 본인 프로젝트를
   선택하거나 새 프로젝트를 만듭니다.
2. 해당 프로젝트에서 Google Drive API를 활성화합니다.
3. Google Auth Platform에서 앱 정보 및 OAuth 동의 화면을 설정합니다.
4. 테스트 모드라면 사용할 Google 계정을 테스트 사용자에 등록합니다.
   테스트 모드에서 갱신 토큰은 7일 만료 제한이 적용될 수 있습니다.
5. https://console.cloud.google.com/auth/clients 에서
   클라이언트 만들기 → 애플리케이션 유형: 데스크톱 앱을 선택합니다.
6. 생성된 OAuth 클라이언트의 JSON 파일을 다운로드합니다.
   JSON의 최상위에 installed 항목이 있어야 합니다.
   web 또는 service_account JSON은 사용할 수 없습니다.

공식 rclone 안내: https://rclone.org/drive/#making-your-own-client-id

## 2. 구형 버전의 사용자별 JSON 적용 방법 (v1.3.127에서는 사용하지 않음)

1. Google Drive 마운트를 정상 연결 해제합니다.
   업로드 중이라면 완료한 뒤 해제해야 합니다.
2. 연결 목록에서 Google Drive 항목 선택 → ⋯ → Google 계정 재인증.
3. OAuth JSON 가져오기 → Google Cloud에서 받은 JSON을 선택합니다.
   Client ID와 Client Secret을 직접 입력해도 됩니다.
4. Google 계정 연결을 누르고 브라우저에서 계정 사용을 승인합니다.
5. 새 토큰의 Drive API 검증이 완료되면 기존 인증을 백업한 뒤
   새 인증으로 교체하고 Google Drive를 다시 연결합니다.
6. 로그의 GOOGLE_OAUTH_CLIENT_MODE mode=custom을 확인하고
   PROFILE_OPERATION_TIMING에서 전후 연결 시간을 비교합니다.

검증에 실패하면 이전 설정이 유지됩니다. 기존 인증 복원 메뉴를
통해 이전 Google 인증으로 되돌리는 기능도 지원합니다.

## 주의

- 발급받은 JSON, Client Secret, Google 인증 토큰은 GitHub,
  채팅, 로그, 공용 폴더 등에 올리지 마세요.
- 테스트 모드에서는 7일 후 재인증이 필요할 수 있습니다.
  공개 배포 시에는 Google OAuth 정책·게시·심사 요건을
  별도로 확인해야 합니다.
- FileHub 전용 Google OAuth 프로젝트의 등록은 프로젝트
  소유자가 실제 Google Cloud에서 수행해야 합니다.
  타사 RaiDrive의 비공개 Client ID를 대신 사용하지 않습니다.
- WebDAV, TeraBox, SMB, FTP, SFTP 연결에는 이 변경이
  적용되지 않습니다.
