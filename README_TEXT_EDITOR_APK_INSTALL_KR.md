# NAS Mount Android v0.1.28 — 문서 편집기 + APK 설치 기능

## 기준 버전

이전 v0.1.27 소스를 변경 없이 복제하여 별도의 v0.1.28
(`versionCode=29`, `versionName=0.1.28`)을 작성했다.
기존 재생기(NextLib FFmpeg), 자막, 폴드7 인터페이스, MYBOX/TeraBox
읽기·쓰기, 덮어쓰기 확인, 다중선택 파일관리자, 즐겨찾기와 경로 탐색을 유지한다.

## 1. 내장 텍스트 문서 편집

- 파일 탐색기에서 `txt`, `md`, `log`, `csv`, `json`, `xml`,
  `ini`, `cfg`, `nfo`, `srt`, `smi`, `yaml`, `vtt` 등
  일반 텍스트 파일을 누르면 `TextEditorActivity`가 실행된다.
- 내부 저장소/Downloads, SD/SAF, WebDAV/SMB/FTP/SFTP, MYBOX/TeraBox
  원격 저장소의 텍스트 파일을 직접 열어 편집하고 저장한다.
- UTF-8, UTF-8 BOM, UTF-16 LE/BE BOM, 한글 MS949(EUC-KR 폴백)
  인코딩을 인식하고 원래 인코딩으로 다시 저장한다. CRLF/LF 줄바꿈을
  불필요하게 변환하지 않으며 인코딩 불가능한 문자 삽입은 오류로 중단한다.
- 메모리 폭증 방지를 위해 **한 문서당 4MiB 제한**. 바이너리 내용/NUL 거부.
- 앱 안에 편집기/저장 버튼, 변경사항 표시, 저장하지 않은 상태의 뒤로가기
  확인을 구현. 데이터 읽기/쓰기 작업은 백그라운드 작업자 스레드에서 처리.
- 로컬 파일은 같은 폴더에 안전한 임시 파일을 쓴 뒤 기존 경로로 교체한다.
  SAF 문서는 해당 문서 URI에 rwt 쓰기로 저장하고, 원격 서버에는 사용자
  저장 버튼 클릭에 한해서 기존 파일 덮어쓰기 프로토콜을 사용한다.
- 휴대폰의 일반 문서 포맷 DOC/DOCX/HWP/PDF는 이번 범위에 포함되지 않는다.

## 2. APK 설치

- 로컬 / 다운로드 폴더 / SAF SD카드 / 모든 연결된 NAS 경로의 단독
  `.apk` 파일을 누르면 파일을 **앱 전용 캐시**로 스트리밍하여
  설치 파일을 검증한다.
- APK ZIP 구조 및 `AndroidManifest.xml` 존재 여부 확인 후,
  Android 패키지 관리자에 설치 가능한 패키지인지 확인한다.
- 설치 확인창에 실제 APK의 패키지명, 버전, 파일 크기를 표시한다.
- 사용자가 **설치 화면 열기**를 눌렀을 때만 Android 시스템
  `ACTION_INSTALL_PACKAGE`를 `content://` FileProvider URI +
  읽기 권한으로 호출한다. 일부 기기는 `ACTION_VIEW` 대체 지원.
- API 26+ `PackageManager.canRequestPackageInstalls()`가 false면
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`에서 NAS Mount의
  **이 출처의 앱 허용**을 켜도록 안내한다. 설정에서 돌아온 경우
  설치를 재개한다. **자동 설치/보안 우회는 하지 않는다.**
- 기존 FileProvider를 기기 호환성이 높은 직접 하위 클래스로 바꾸고,
  `REQUEST_INSTALL_PACKAGES` 권한을 AndroidManifest에 추가했다.
- APK 캐시로 복사할 수 있는 최대 크기는 **2GiB**이며,
  `.apks`/`.xapk`의 분할 APK 묶음은 현재 단독 APK 설치 대상이 아니다.

## 변경된 파일

- `app/build.gradle` v0.1.28 / versionCode29
- `app/src/main/AndroidManifest.xml` 설치 권한, 비공개 편집 Activity,
  파일 제공자 하위 클래스 등록
- 신규 `EditableFileTypes.java`, `TextDocumentCodec.java`,
  `TextEditorActivity.java`, `ApkInstallStaging.java`,
  `NasMountFileProvider.java`
- 수정 `MainActivity.java`: 로컬/SD/원격 파일 분기, 편집 결과로
  원격 목록 캐시 갱신, 설치 권한 설정/복귀/시스템 설치 관리자 실행
- 신규 `TextDocumentCodecTest.java`, `EditableFileTypesTest.java`,
  `ApkInstallStagingTest.java`

## 검증

- Gradle `:app:testDebugUnitTest :app:lintDebug :app:lintVitalRelease
  :app:assembleRelease --offline --no-daemon --max-workers=2` 성공.
- 단위 테스트: **201개 모두 통과**, 실패 없음.
- 새 테스트에서 UTF-8/UTF-16/한글 MS949, BOM·줄바꿈 보존, 잘못된 바이너리,
  인코딩 불가능한 이모지 손실 방지, 로컬 원자적 덮어쓰기, TXT/APK
  확장자 분기, APK 전송 스테이징 및 위조 ZIP 거부 등을 검증.
- 서명된 APK를 통한 **실기기 설치·APK 사용자 승인·원격 TXT 편집 후 서버
  반영 동작은 연결된 Android 기기가 없어 아직 검증하지 못함**.
  첫 테스트는 작은 임시 텍스트 파일과 신뢰할 수 있는 APK에서 실시.

## 주요 주의 사항

APK는 Android 설치 관리자가 설치 가능 여부 및 앱 서명을 최종 판단한다.
NAS Mount에서 직접 승인 없이 설치되지 않으며, 기기 보안 설정에서
알 수 없는 출처를 허용해야 할 수 있다. Google Play 배포 시
`REQUEST_INSTALL_PACKAGES` 권한 정책 심사가 필요할 수 있다.

원격 파일을 저장하면 실제 서버의 원본을 덮어쓰므로 다른 컴퓨터에서
동시에 편집하지 않는지 확인할 것. TeraBox 연동은 여전히 비공식 웹 API이며
실제 계정의 쓰기 동작을 별도로 테스트해야 한다. NextLib FFmpeg GPLv3
배포 라이선스 의무는 이전 버전과 동일.
