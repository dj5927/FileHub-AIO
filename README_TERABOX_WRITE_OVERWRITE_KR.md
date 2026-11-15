# NAS Mount Android v0.1.27 — TeraBox 쓰기 + 덮어쓰기 확인

기준 소스는 v0.1.26이며 버전 코드는 28, 버전 이름은 0.1.27입니다.
기존 플레이어(NextLib/FFmpeg), 자막, 폴드 전용 UI, 클릭 가능한 경로줄,
MYBOX PAT 읽기·쓰기, 다중 선택 파일관리자 및 다른 원격 프로토콜을 유지합니다.
PC용 NAS Mount v1.3.80 베타는 수정하지 않았습니다.

## TeraBox ndus 기반 읽기·쓰기

- 새 폴더 만들기: POST /api/create isdir=1, rtype=0
- 파일 이름 바꾸기: POST /api/filemanager opera=rename
- 파일·폴더 복사: POST /api/filemanager opera=copy, 비동기 작업 결과 확인
- 파일·폴더 이동: POST /api/filemanager opera=move
- 파일·폴더 삭제: POST /api/filemanager opera=delete (일반 휴지통 삭제만; 영구 삭제 API 미사용)
- 파일 업로드: /rest/2.0/pcs/file?method=locateupload →
  /api/precreate → /rest/2.0/pcs/superfile2?method=upload 분할 업로드 →
  /api/create 파일 생성. 조각별 MD5 응답과 업로드한 바이트 수 확인.
- 웹 세션 ndus 재사용, jsToken 추출·캐싱. 업로드 서버는 허용된
  *.terabox.com, *.nephobox.com HTTPS만 접근하도록 검사하고, HTTP 리디렉션 차단.
- TeraBox에서 MYBOX, WebDAV, SMB, FTP, SFTP, 로컬, SD카드와
  다중 파일 복사·이동/붙여넣기 기능 연동.
- 무료 계정 4GB 이하, 프리미엄 계정은 회원 상태 확인 후 128GB까지
  업로드를 시도하도록 설정. 분할 방식은 4MiB 단위이며 프리미엄 대용량
  실제 속도·호환성은 아직 확인하지 못했습니다.
- TeraBox는 0바이트 파일 업로드를 지원하지 않아 빈 파일 생성만
  명시적으로 안내합니다. 내용이 있는 실제 파일 업로드는 지원합니다.

**주의:** TeraBox의 위 웹 API는 공식 공개 문서가 없는 비공식 세션 API입니다.
PC용 rclone-extra TeraBox 백엔드의 처리 순서를 참조해 Android용으로 구현한
것으로, TeraBox 서버의 인증·응답 형식 변경 시 동작하지 않을 수 있습니다.

## 덮어쓰기 동작 (사용자 요청 반영)

**파일이 있으면 무조건 금지하는 방식이 아닙니다.**

1. 파일 복사·이동 뒤 붙여넣기 또는 업로드를 선택합니다.
2. 같은 이름의 기존 파일이 있다면 **덮어쓰기 확인창**을 보여줍니다.
3. **취소:** 기존 파일과 복사 대기 목록을 유지합니다.
4. **덮어쓰기:** 사용자가 명시적으로 승인한 뒤 새 데이터를 전송합니다.
5. 여러 폴더가 같은 이름일 경우 확인 후 하위 같은 이름 파일은 교체하고,
   무관한 파일은 유지하는 합치기 방식으로 처리합니다. 파일/폴더 종류가
   달라 직접 교체할 수 없는 경우에는 안전하게 중단합니다.

TeraBox에서는 승인 후 기존 파일을 직접 지우지 않고 별도 임시 이름으로
먼저 업로드한 뒤 서버 측 복사(ondup=overwrite)로 교체합니다.
업로드나 무결성 확인 단계에서 실패한 경우에는 교체 호출을 하지 않습니다.
성공 후 임시 파일은 일반 휴지통 삭제합니다. 동일 TeraBox 내부 복사·이동에도
사용자 승인에 따른 덮어쓰기 옵션을 전달합니다.

MYBOX의 업로드 세션 및 서버 복사·이동은 승인한 경우에만 isOverwrite=true로 전송합니다.
WebDAV는 승인 후에만 MOVE Overwrite=T를 전달합니다. SMB/FTP/SFTP는 임시 파일을
전송한 후 기존 이름의 백업을 시도하고 새 파일 이름을 반영한 뒤 정리합니다.
로컬 저장소는 스테이징 파일 생성 후 교체하며, SD카드(SAF)는 공급자 권한과
이름 변경 기능에 따라 실패할 수 있습니다. SD카드에서 교체가 실패할 때
미완료 임시 파일이 남을 수 있어 주의가 필요합니다.

다중 파일 작업은 전체 일괄 트랜잭션이 아닙니다. 오류 시 일부 항목만 완료됐을
가능성이 있으므로 원본을 지우기 전에 결과를 확인해야 합니다.

## 테스트 및 배포

- Gradle :app:testDebugUnitTest :app:lintDebug :app:lintVitalRelease :app:assembleRelease
  --offline --no-daemon --max-workers=2 통과.
- JUnit **186/186 통과**. 신규 TeraBoxWriteApiTest:
  폴더 생성, rename, copy/move, DELETE, async task, 1·2개 분할 업로드 MD5,
  세션 확인, 무료 계정 4GB 제한, 승인 전 충돌 차단, 승인 후 서버 COPY ondup,
  승인 후 임시 업로드→교체→정리, 업로드 실패 시 교체 미실행, 악성 업로드 호스트 차단.
- FileTransferOverwriteTest: 기존 파일 충돌 시 무변경, 승인 후 파일 복사/이동,
  기존 폴더 병합, 파일/폴더 종류 불일치 차단.
- 모든 TeraBox 테스트는 localhost MockWebServer와 **가짜 ndus**만 사용했습니다.
  **실제 TeraBox 계정 쓰기, Fold7 터치 UI, 앱의 네트워크 간 이동은 아직
  실기기 검증하지 않았습니다.** 테스트용 폴더와 작은 임시 파일로 먼저 확인하세요.
- PC용 v1.3.80 베타는 별도 작업 이력 보존. 실제 Synology WebDAV
  native LOCK 및 두 PC Excel 저장 충돌 실험 대기.
- 기존 NextLib FFmpeg GPLv3 관련 배포·소스 제공 의무 유지.

## 참고 구현

- https://github.com/Temkina/rclone-extra/tree/master/backend/terabox
- https://github.com/Temkina/rclone-extra/blob/master/backend/terabox/api.go
- https://github.com/Temkina/rclone-extra/blob/master/backend/terabox/terabox.go
