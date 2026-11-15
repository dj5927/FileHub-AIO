# NAS Mount Android v0.1.32 — MYBOX NPE 근본 수정 / TeraBox 읽기·쓰기 재검토

## 사용자 보고

- MYBOX: 탐색/파일 읽기는 어느 정도 되지만 새 폴더 생성과 복사·붙여넣기,
  파일 업로드가 실패. MYBOX에 붙여넣기할 때 `NullPointerException` 표시.
- TeraBox: 폴더 만들기는 되지만 파일 열기/다운로드/복사·붙여넣기가 실패.
- 이전 v0.1.31 수정 뒤에도 같은 현상이 남았다는 실기기 피드백.

## MYBOX: 확인된 실제 근본 원인

`MyBoxWriteApi.java`는 이전 버전에서 다음 순서로 정적 초기화되었다.

1. `static final MyBoxWriteApi LIVE = new MyBoxWriteApi(...)`
2. `private static final Lookup LIVE_LOOKUP = ...`

Java 정적 필드 선언 순서 때문에 1번 LIVE를 만들 때 2번 LIVE_LOOKUP은
아직 null이었다. 따라서 **모든 프로덕션 MYBOX 쓰기 요청의 내부 lookup
객체가 null**로 고정되고, `mkdir`, `upload`, `copy/move`, `rename`,
`delete`에서 첫 목록/폴더 조회 시 `NullPointerException`이 발생했다.
반면 읽기는 별개 `CloudProvider` 경로이므로 계속 동작했다.

**수정:** LIVE_LOOKUP 선언 아래에서 LIVE를 초기화하고,
생성자에서 null resolver를 즉시 거부하도록 검사.
신규 테스트에서 `MyBoxWriteApi.LIVE`의 실제 lookup이 null이 아님을
반드시 검증한다. 이 오류는 실제 서버 권한 문제가 아니라 앱 코드 버그였다.
공식 MYBOX Open API 폴더 생성 요청 형식은 이전 코드와 문서가 일치하므로
불필요한 변경 없이 유지했다.

## TeraBox: 다운로드 및 서버 응답 처리

- TeraBox는 파일 다운로드용 최초 dlink가 `d.terabox.com` 같은
하위 도메인을 가리킬 수 있다. v0.1.31에서는 ndus 쿠키를
본 도메인/WWW에만 보내고 하위 도메인에는 보내지 않아,
파일 목록·폴더 생성은 되지만 파일 다운로드가 HTTP 403 등으로
실패할 여지가 있었다.
- 신뢰할 수 있는 `*.terabox.com`, `*.nephobox.com` 도메인에
한정하여 443/HTTPS 다운로드 요청에 쿠키 전달.
다른 CDN/유사 도메인/리디렉션에는 여전히 쿠키를 전달하지 않는다.
- 파일 dlink 다운로드의 User-Agent를 PC TeraBox 클라이언트
표기와 일치시켰다. 기존 주소 실패 시 서명 URL로 다시 조회하는
v0.1.31 기능을 그대로 유지했다.
- PC용 TeraBox API 백엔드와 대조해, 웹 API 서버가
  `errno=-6` 및 `Url-Domain-Prefix` 헤더로 계정별 API
  지역 도메인을 알려줄 경우 안전한 TeraBox 하위 도메인인지 확인하고
  해당 API 요청을 한 차례 다시 보내도록 수정.
  단순 네트워크 타임아웃은 중복 쓰기 위험이 있으므로 자동 재시도하지 않는다.
- TeraBox 분할 업로드가 HTTP 오류 또는 서버 errno로 실패하면
  단순 `IOException` 대신 **분할 업로드 단계 + 실제 코드**를
  사용자에게 표시하도록 개선했다.
- TeraBox 쓰기 API는 공식 공개되지 않은 ndus 웹 인터페이스여서
  실제 서버와 계정에 따라 정상 작동 여부가 달라질 수 있다.

## 자동 검증

- `MyBoxWriteApiTest`: 실제 배포용 LIVE writer의 조회 객체가
  null이 아님을 검증(이번 회귀 오류의 직접 방지), 테스트 생성자의
  null resolver 즉시 거절.
- `CloudDownloadHostTest`: 첫 다운로드 dlink 서브도메인 허용,
  위장 도메인/외부 CDN에 쿠키를 절대 전달하지 않는 도메인 검사.
- `TeraBoxWriteApiTest`: 지역별 서버 헤더 처리,
  악성 도메인/URL/포트에 대한 처리 차단.
- 전체 Gradle `:app:testDebugUnitTest :app:lintDebug
  :app:lintVitalRelease :app:assembleRelease` 성공,
  단위 테스트 **230/230 통과**.
- Gradle 프로세스 CodeCache 관련 환경 오류를 피하기 위해
  `ReservedCodeCacheSize=512m`, `--max-workers=1`로 실행.

## 릴리스 및 실사용 검증 제한

- 기존 v0.1.31을 보존하고 v0.1.32로 새로운 소스와 APK 빌드.
  기존 서명 인증서를 유지하여 업데이트 설치 가능.
- MYBOX의 NPE 원인은 소스 수준에서 확정됐으나, 실제 폴드7과
  사용자 PAT로 API 쓰기 성공은 아직 실기기 확인하지 못함.
- TeraBox의 인증/지역 도메인 관련 수정은 서버 동작에 대한
  가설을 보완한 것으로, 실제 ndus 계정에서의 성공은 아직 미검증.
- 중요한 자료 대신 테스트 폴더에 아주 작은 임시 TXT 파일을
  사용하여 MYBOX 폴더 생성 → 업로드 → 다운로드, TeraBox 다운로드 →
  업로드 → 복사 순으로 점검. 실패하면 새 버전의 HTTP/errno
  코드와 단계만 공유하고 PAT/ndus/서명 다운로드 URL은 공유하지 말 것.
- PC NAS Mount native WebDAV LOCK v1.3.80 베타 별도 보존,
  기존 안드로이드 영상 종료/재생 목록/문서 편집기/APK 설치 기능 유지.
- NextLib FFmpeg GPLv3 배포 관련 의무는 기존 이력대로 유지.
