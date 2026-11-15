# NAS Mount Android v0.1.26 — MYBOX 읽기/쓰기 + 터치 경로 탐색

## 이번 업데이트

기존 v0.1.25 소스를 그대로 복제한 신규 v0.1.26(versionCode 27) 버전입니다. Android 파일관리자와 플레이어, 4 ABI NextLib FFmpeg, 내부·외부 자막, Fold7 레이아웃, 다중선택·일괄 붙여넣기는 유지했습니다. PC용 NAS Mount 코드는 건드리지 않았습니다.

### 네이버 MYBOX 쓰기 지원

이전 Android 버전의 MYBOX 읽기 전용 제한을 해제하고 MYBOX **공식 PAT Open API**를 다음 기능에 연결했습니다.

- 파일·폴더 조회, 다운로드 및 동영상 스트리밍: 기존 기능 유지
- 파일 업로드: POST /v1/drive/files로 201 생성된 1회용 uploadUrl을 받아 `multipart/form-data`의 `Filedata`로 전송. 파일 크기·원본 파일명·덮어쓰기 안 함 옵션 전달. PAT는 uploadUrl로 전송하지 않음
- 새 폴더: POST /v1/drive/folders
- 파일·폴더 이름 변경: POST /v1/drive/resources/{resourceId}/rename
- MYBOX 내부 복사: POST /v1/drive/resources/{resourceId}/copy
- MYBOX 내부 이동: POST /v1/drive/resources/{resourceId}/move, 단 목적지가 루트일 경우 공식 move API의 parentId 필수 제약으로 복사 성공 후 원본 휴지통 이동 순서로 처리
- 파일·폴더 삭제: DELETE /v1/drive/resources/{resourceId}, 네이버 MYBOX **휴지통으로 이동**하며 영구 삭제 API는 사용하지 않음
- 내장/SD/WebDAV/SMB/FTP/SFTP와 MYBOX 간 다중 파일 복사·이동: 기존 선택/붙여넣기와 연결
- 기존 TeraBox는 공식 쓰기 API 미연동으로 읽기 전용 유지. MYBOX 계정의 암호 폴더 및 공유 받은 폴더는 공식 Open API 지원 범위 밖입니다.

실제 업로드 중 동일한 이름을 자동 덮어쓰지 않고, 동명이인 충돌·파일 크기 검증·부분 실패 시 원본 보호 절차를 따릅니다. 파일 크기가 알려진 로컬/SAF/원격 파일은 전량 RAM에 올리지 않고 스트리밍합니다. Android 공급자가 크기를 반환하지 않는 일부 파일은 앱 임시 디스크에 저장하여 크기를 계산한 후 전송합니다.

### 원격 파일 탐색기 인터페이스

- 원격 탐색기 목록 상단의 중복된 `즐겨찾기 / 업로드` 2개 대형 버튼 삭제. 즐겨찾기는 상단 ★/☆ 아이콘, 업로드는 상단 ⋮ > 파일 업로드에서 사용.
- `WebDAV · /01.개인자료/테스트` 텍스트 행 대신 **폴더 경로 전용 58dp 터치형 가로 경로줄**로 변경. 서버 종류 표시는 제거.
- 시작 아이콘 `⌂`로 루트 이동. `01.개인자료`를 탭하면 바로 `/01.개인자료`로 이동. `테스트` 탭 시 현재 폴더 유지. 더 깊은 경로는 각 단계마다 별도 터치 가능.
- 긴 경로를 잘라서 숨기지 않도록 가로 스크롤을 제공하고 현재 폴더는 강조 표시. 추가 네트워크 요청 없이 기존 폴더 세션 캐시 활용.

## 공식 문서

- 업로드: https://developers.mybox.naver.com/docs/files_upload
- 폴더 생성: https://developers.mybox.naver.com/docs/files_create_folder
- 이름 변경: https://developers.mybox.naver.com/docs/files_rename
- 복사: https://developers.mybox.naver.com/docs/files_copy
- 이동: https://developers.mybox.naver.com/docs/files_move
- 휴지통 삭제: https://developers.mybox.naver.com/docs/files_delete

## 검증 범위와 주의

- Gradle `:app:testDebugUnitTest :app:lintDebug :app:lintVitalRelease :app:assembleRelease --offline --no-daemon --max-workers=2` 빌드 성공.
- 오프라인 JUnit: 기존 테스트 + `MyBoxWriteApiTest` + `RemoteBreadcrumbsTest`. 테스트에는 **가짜 PAT와 localhost MockWebServer만 사용**하여 공식 API 경로, JSON 요청 값, 1회성 업로드 URL, multipart Filedata 바이너리 바이트 보존, 개인 토큰 미전송, 오류 경로, 폴더 경로 세그먼트 확인.
- 실제 사용자의 MYBOX 계정·NAS 파일은 건드리지 않았습니다. 사용자의 실제 PAT/서버에 연결한 MYBOX 쓰기 동작, Galaxy Fold7 터치 UI는 **실기기에서 추가 검증 필요**합니다. 먼저 별도 테스트 폴더에서 파일 1~2개를 생성/업로드/이동/휴지통 삭제한 뒤 사용하세요.
- NextLib FFmpeg GPLv3 포함 상태 및 재배포 라이선스 의무 이전 버전과 동일.
- PC v1.3.80 WebDAV 서버 잠금 베타는 이번 Android 변경과 별개이며, 실제 NAS 2대 PC Excel 충돌 테스트가 아직 완료되지 않았습니다. 루트 `작업일지.md`의 PC 베타 기록 보존.
