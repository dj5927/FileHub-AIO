# NAS Mount Android v0.1.31 — MYBOX/TeraBox 읽기·쓰기 오류 진단 및 보완

## 보고된 현상

- 삼성 폴드7에서 MYBOX/TeraBox 두 계정 모두 파일 목록과 폴더 목록은 표시됨.
- MYBOX 파일 읽기는 일부 가능해 보이나 복사/붙여넣기 등 쓰기 실패.
- TeraBox는 파일 읽기와 쓰기 모두 실패.
- 이전 앱은 복사 실패 이유를 감추고 `IOException` 또는
  `1개 중 0개 완료 후 작업이 중단됐습니다`만 표시함.
- 스크린샷의 실패한 항목은 TeraBox의 ROM/ZIP 등일 수 있으므로
  큰 파일을 실제 원격에 저장하는 검증은 작은 시험 파일로 대체해야 함.

## 수정

1. **TeraBox 파일 읽기**: /api/filemetas?dlink=1에서 다운로드 주소가
   없으면 PC rclone-extra와 동일하게 /api/home/info의 sign3/sign1을
   이용해 RC4+Base64 서명을 만들고, 파일 fs_id와 함께
   /api/download?type=dlink를 호출하여 일회성 다운로드 주소를 재발급.
   첫 다운로드 URL이 401/403/404/410을 반환해도 서명 주소 재발급을
   **한 번만** 수행. 기존 단일 주소 의존 방식 개선.
2. **TeraBox 원격 스트리밍/복사**: 파일 열기·다운로드·다른 폴더에
   복사할 때 모두 동일한 seekable 스트림 경로를 사용. TeraBox 서명
   다운로드 요청은 Referer와 브라우저 User-Agent 포함.
   계정의 `ndus` 쿠키는 TeraBox의 기본 도메인에만 보내고,
   외부 CDN/리디렉션에는 전달하지 않음. HTTPS, DNS/IP 보안 검사 유지.
3. **TeraBox 쓰기 토큰**: 웹 홈에서 jsToken을 받기 전 같은 호스트의
   정상적인 URL 리디렉션을 최대 3회 허용. 다른 호스트의 로그인 화면으로
   이동하려 하면 인증 정보를 전송하지 않고 중단.
   파일 쓰기 API가 명시적으로 `4000023` 또는 `450016` 토큰 만료
   오류를 반환한 경우 새 jsToken을 가져와 같은 요청을 **한 번만 재시도**.
   그 외 오류는 반복 쓰기 금지. 별도 무단 삭제/덮어쓰기 없음.
4. **MYBOX 쓰기 진단**: 공식 PAT API
   `POST /v1/drive/files` → `uploadUrl` → multipart `Filedata`
   프로토콜은 유지. 오류가 발생하면 어느 단계인지(업로드 URL 발급,
   실제 바이너리 업로드, 일반 파일 변경)와 HTTP 401/403/409/423/
   429/507 등을 사용자에게 구별해 표시.
5. **공통 오류 화면**: 여러 파일 복사 중 중단되더라도 기존 완료 수와
   원본 보존 경고를 유지하면서 MYBOX/TeraBox의 정리된 HTTP/서버
   오류 코드를 함께 보여줌. 실제 쿠키/PAT, 서명 URL, 원격 전체 경로,
   서버 응답 본문은 팝업에 표시하지 않음.

## 보안 및 미지원

- TeraBox ndus API는 **공식 공개되지 않은 웹 API**여서 사이트 구조가
  바뀌면 다시 수정이 필요할 수 있음. 이번 코드는 실제 사용자의
  비밀번호/쿠키를 로그에 출력하지 않으며 사용자 승인 없이 쓰지 않음.
- MYBOX는 네이버 공식 Open API 및 개인 액세스 토큰(PAT)을 사용.
  PAT 권한, 계정 용량, 서비스 사용 제한/휴지통 상태는 서버가 결정.
- 다운로드 파일을 암호 해제하거나 보안 토큰을 우회하는 기능 없음.
- 실제 계정 인증이나 파일 업로드 테스트는 계정 정보가 없어 수행하지
  못했음. **기능 확정판이 아닌 수정 검증 베타**로 취급할 것.

## 자동 테스트

- `TeraBoxDownloadSignTest`: RC4 표준 벡터, 잘못된 서명 필드,
  시그니처 결정성.
- `TeraBoxWriteApiTest`: 같은 호스트 리디렉션, 외부 사이트로
  쿠키 미전송, 서버가 명시적으로 jsToken 만료를 보고했을 때만 갱신
  후 재전송(별도의 로컬 MockWebServer와 테스트용 가짜 쿠키 사용).
- `MyBoxWriteApiTest`: 업로드 URL 발급 단계 HTTP 403에서 코드·단계
  진단값을 정확하게 표시하고 PAT를 노출하지 않는지 검증.
- 기존 폴더 목록, 복사/이동/덮어쓰기 확인, 플레이어, 자막, 내장
  TXT 편집기와 APK 설치, Fold7 UI 등 이전 기능 회귀 테스트 포함.
- Gradle `:app:testDebugUnitTest :app:lintDebug :app:lintVitalRelease
  :app:assembleRelease --offline --no-daemon --max-workers=1` 통과.
- Windows 개발 PC에서 Java `CodeCache` 한도 오류 발생 시
  `-XX:ReservedCodeCacheSize=512m`을 설정하여 재빌드함.
  **이는 개발 PC 빌드 환경 문제이며 Android 앱 실행 메모리가 아님.**

## 실기기 확인 항목

테스트용 1KB ~ 1MB 문서만 사용해 MYBOX와 TeraBox에서 각각:
1. 파일 읽기/로컬 다운로드 결과가 원본과 일치하는지
2. 파일 복사 후 원본과 대상 크기가 같은지
3. 새 파일 업로드와 새 폴더 만들기/이름 변경이 되는지
4. 동일 이름이 있을 때 덮어쓰기 취소/승인이 제대로 작동하는지
5. 실패할 경우 팝업의 정확한 **HTTP/서버 오류 코드**가 무엇인지

업로드가 정상으로 확인되기 전까지 중요한 원본 파일은 이동·삭제하지 말 것.
PC용 v1.3.80 베타, 기존 APK, NextLib GPLv3 라이선스 이력은 유지.
