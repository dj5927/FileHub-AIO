# FileHub AIO PC Google Drive - 배포사 최초 1회 인증 등록

**누가 하나요?** FileHub AIO 배포사/저장소 소유자만 **한 번** 진행합니다.
최종 프로그램의 일반 사용자는 Client ID, JSON, API 키, OpenList 토큰을
입력하지 않습니다. Google Drive 추가 버튼을 누르고 Chrome에서 자신의
계정을 골라 승인하면 됩니다.

## 왜 이 절차가 필요한가

Google Drive 전체를 Windows 네트워크 드라이브처럼 읽고 쓰려면
`https://www.googleapis.com/auth/drive` 범위의 Google OAuth 동의가
필요합니다. 로그인 화면은 Google이 제공하지만, **어느 앱이 접근 권한을
요청하는지** 지정하는 OAuth Desktop Client ID는 배포사가 등록해야
합니다. OpenList 공개 토큰 페이지는 FileHub PC의 자동 localhost 콜백을
지원하지 않으며, RaiDrive 비공개 앱 자격증명을 사용할 수 없습니다.

## Google Cloud에서 배포사 최초 1회

1. `https://console.cloud.google.com/`에서 본인의 Google Cloud 프로젝트를
   만들거나 선택합니다.
2. `https://console.cloud.google.com/apis/library/drive.googleapis.com`
   에서 **Google Drive API 사용 설정(Enable)**을 선택합니다.
3. Google Auth Platform에서 `FileHub AIO`를 등록합니다. **본인 Google
   계정 하나로 먼저 실사용 검증**하려면 새 프로젝트에 **Personal 모드**를
   선택할 수 있습니다. 이 모드는 검증·미확인 앱 경고·7일 갱신 토큰 제한이
   없지만 **만든 계정 외에는 누구도 로그인할 수 없고**, 추후 External로
   변환할 수도 없습니다. **지인 배포**는 별도 프로젝트에 **외부(External)**
   앱을 만들고 테스트 사용자를 등록하는 방식으로 준비합니다.
4. `https://console.cloud.google.com/auth/clients`에서
   **클라이언트 만들기 → 데스크톱 앱(Desktop app)**을 선택합니다.
   **웹 애플리케이션이 아닙니다.** Client ID/Secret을 수동으로 앱에
   입력할 필요는 없습니다.
5. 생성된 OAuth 클라이언트의 **JSON 파일을 다운로드**합니다.

그다음 FileHub PC 소스 폴더에서
`tools/Setup-GooglePublisherOAuth.cmd`를 **더블클릭**합니다. JSON 파일
선택 창에서 방금 내려받은 JSON을 고르면 됩니다. 또는 PowerShell에서:

```powershell
.\tools\Setup-GooglePublisherOAuth.ps1 -CredentialFile "$env:USERPROFILE\Downloads\다운로드한파일.json"
```

이 도구는 JSON 형식 검증 → GitHub CLI의 배포사 브라우저 로그인 확인
→ `dj5927/FileHub-AIO` GitHub Actions의 암호화된
`FILEHUB_GOOGLE_DESKTOP_OAUTH_JSON_BASE64` 저장소 Secret 설정
→ 개발 브랜치 Windows CI 재실행을 순서대로 처리합니다.
Google 로그인 토큰이나 JSON 전문은 화면에 출력하지 않습니다.
**GitHub CLI(`gh`)가 없으면** `https://cli.github.com/`에서 설치한 뒤
명령을 다시 실행하면 됩니다. 다른 계정의 Google OAuth JSON을 임의로
가져오거나 타사 공개 Client ID를 빌려 쓰지 않습니다.

## 실제 배포까지 필요한 검증

- [ ] FileHub 전용 JSON이 GitHub Secret에 등록됨
- [ ] 개발용 Windows ZIP 생성 및 파일 해시 검증
- [ ] 사용자 PC에서 Chrome 계정 선택·권한 승인 완료
- [ ] 최초 연결, 폴더 생성·열기 지연 측정
- [ ] 기존 Excel 반복 저장·다른 PC 충돌·복구 검증
- [ ] 장시간 드라이브 연결·해제·재접속 검증

**중요:** Google의 외부 앱 **테스트(Testing)** 모드에서 요청한 Drive
권한의 refresh token은 **7일 뒤 만료**되므로 테스트 통과 후에는
게시 상태와 Google 검증 정책을 별도로 검토해야 합니다. 제한된 소수
지인을 위한 비검증 앱은 인원 제한과 경고 화면을 감수하면 사용 가능한
경우가 있지만, 공개 배포용 앱은 Google의 검증·보안 요건을 충족해야
할 수 있습니다. 검증되지 않은 앱을 검증된 것으로 표시하지 않습니다.

Google 공식 문서(2026년 기준) Personal 모드는 **계정 하나로만** 테스트할
때 쉽게 시작할 수 있지만 지인 배포로 전환할 수 없다는 점을 특히
주의하세요: https://support.google.com/cloud/answer/15544987?hl=en

공식 자료:
- https://developers.google.com/identity/protocols/oauth2/native-app
- https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
- https://developers.google.com/identity/protocols/oauth2/production-readiness/overview

현재 안정 브랜치 `pc/windows`는 변경하지 않습니다. 이 단계가 완료되기
전에는 실사용 가능 Google 직접 엔진 ZIP을 완성본으로 발표하지 않습니다.
