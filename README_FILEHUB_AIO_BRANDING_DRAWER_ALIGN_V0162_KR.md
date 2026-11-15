# FileHub AIO (구 NAS Mount Android) v0.1.62

## 요청

사용자가 최종 선택한 두 가지 로고를 Android 앱 두 곳에 적용.

1. 메인 상단 NAS Mount 텍스트 자리에 파스텔 청록·블루
   FileHub AIO 글자 로고(워드마크).
2. 왼쪽 펼침 메뉴 상단 NAS Mount 텍스트 자리에
   파스텔 폴더·이미지·비디오·문서 이미지 로고와 워드마크.

펼침 메뉴를 열었을 때 상단바 높이, 시작 위치, 크기를 메인
화면과 일치하도록 보정. 기존 파일·NAS 설정 및 사용자 데이터 유지.

## 이미지 리소스

- app/src/main/res/drawable-nodpi/filehub_aio_wordmark.png
  - 선택한 첫 번째 글자 로고에서 글자만 크롭하고 투명 처리.
    어두운 상단바에서도 보이도록 파스텔 청록·블루 대비 조정.
  - 320x46 PNG, SHA256:
    68056BB77D58D9DA1D9EBFA5F640604A1FDF06819E1AE106FC74BE13E588D6B8
- app/src/main/res/drawable-nodpi/filehub_aio_icon.png
  - 선택한 두 번째 파스텔 폴더·문서·사진·영상 로고.
    배경 부분을 투명 처리하고 원본 비율 유지.
  - 128x122 PNG, SHA256:
    679C4BB521D005FBDBE69663262BB477C9768BF8805637DFB55594F6AB498271
- 두 파일 모두 APK 안에 포함되어 오프라인 표시.

## Android UI 적용

- MainActivity.render 홈 화면 제목에 FileHub AIO 워드마크 이미지 표시.
  폴더 탐색 중인 화면은 기존처럼 폴더명을 텍스트로 표시.
- MainActivity.showDrawer 펼침 메뉴 상단에 40dp 파스텔 아이콘과
  짧은 워드마크를 나란히 표시.
- 메뉴 헤더 높이 106dp에서 65dp로 조정.
- 홈 헤더와 메뉴 헤더에 같은 FileHubBrandLayout.HEADER_HEIGHT_DP=65
  상수 사용.
- 메뉴 panel의 topMargin을 실제 Android statusBarInsetPx로 설정.
  bottomMargin은 실제 navigation bar inset와 일치.
  이전의 추가 하단 8~12dp 여백 제거.
- 접힌·펼친 갤럭시 폴드에서도 화면 너비에 따라 로고 가로 폭 제한.
- 메뉴 닫기 애니메이션, 즐겨찾기, 네트워크 연결 목록은 그대로 유지.

## 브랜딩, 데이터 호환성

- AndroidManifest 표시 앱 이름 FileHub AIO, 실행 앱 아이콘도
  사용자 선택 두 번째 파스텔 로고.
- MainActivity와 SettingsActivity 앱 정보 이름·버전 변경.
- packageId는 com.nasmount.mobile 그대로 유지.
  따라서 기존 NAS Mount 설치 앱 위에 업데이트 가능.
- 저장된 원격 계정, 토큰, 폴더 용도 설정, 즐겨찾기, 동영상 시청 기록,
  만화책/TXT/EPUB 책갈피와 읽던 위치, 미디어 원본은 그대로 보존.
- 기존 Android 내부 파일경로, provider authorities 및
  엔진·플러그인 식별자는 변경하지 않음.

## 검증과 제한

- FileHubBrandLayoutTest 새 3개 테스트: 65dp 동일 헤더,
  작은 화면·폴드 펼침 화면 너비, 상태바·하단 탐색바 위치.
- Gradle Java 컴파일, JUnit 전량, Android Lint Debug/Vital Release,
  서명된 릴리스 APK, aapt 메타데이터, 이전 서명 인증서 검증.
- 원본으로부터 생성한 PNG와 Windows 프로젝트에 저장된 PNG의
  SHA256 일치.
- 갤럭시 폴드 실기기가 이 작업 환경의 ADB에 연결되지 않아
  실제 터치 상태에서의 로고 위치, Android 런처 아이콘 캐시 및
  메뉴 열고 닫기 애니메이션은 기기에서 직접 검증하지 못함.
