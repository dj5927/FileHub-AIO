# NAS Mount Android v0.1.38 - PPTX 표시 개선, 문서 제스처, 음악·이미지 앱 내장

## PowerPoint

- 기존 pptx-browser 4.1.5 Canvas 렌더러에서 사용자 실문서 일부가
  흰색 배경/회색 사각형만 표시되는 문제가 보고되었습니다.
- @web-ppt/core 0.8.0 (MIT) / fflate 0.8.x (MIT)를 APK에 내장하고
  더 폭넓은 OOXML/EMF/WMF/그룹 도형을 해석하는 SVG 렌더링을
  **기본 보기**로 바꿨습니다.
- SVG 표시 실패 시 자동으로 기존 Canvas 보기로 전환합니다.
  '기존 보기' / '호환 보기' 버튼으로 수동 비교도 가능합니다.
- 기존 PPTX 편집·저장 PptxWriter 경로는 유지하여 새 뷰어의 표시
  결과가 원본 편집 파일을 불필요하게 다시 직렬화하지 않도록 분리.
- 손가락 좌우 스와이프로 이전/다음 슬라이드 이동.
  두 손가락으로 1.0~4.0배 확대/축소. 확대 시 스와이프 페이지 넘김
  대신 이미지 이동을 우선합니다.
- 특정 PPTX의 완벽한 렌더링은 실제 해당 원본 파일을 시험해야 하므로
  확인 전 '완전 해결'로 표시하지 않습니다.

## HWP / Excel 제스처

- Excel: 실제 셀 그리드만 0.6~3.0배 확대/축소합니다.
  상단 시트·수식줄과 하단 저장바는 고정.
- HWP: 내부 rhwp 편집기 scroll-container의 2점 터치를 감지하고
  실제 편집기의 확대/축소 버튼을 작동시킵니다.
  문서 CSS만 강제 확대하는 방식은 편집 커서 좌표가 틀어질 수 있어
  사용하지 않습니다.
- 브라우저 공용 제스처를 office-gestures.js에 두어
  한 손가락의 정상 스크롤과 두 손가락 확대를 구분합니다.

## 음악 플레이어

- MP3, M4A, AAC, WAV, FLAC, OGG, OPUS 등 파일을 누르면 NAS Mount
  자체 ExoPlayer 음악 서비스에서 재생합니다.
- 메인 파일 목록 맨 아래에 가로 61dp 미니 플레이어를 표시합니다.
  곡 제목, 재생/일시정지, 종료, 재생 위치 이동을 지원합니다.
- Android mediaPlayback Foreground Service + 미디어 알림을 사용하여
  사용자가 앱을 벗어나거나 화면을 꺼도 재생을 유지할 수 있도록 구성.
- 원격 SMB/FTP/SFTP/WebDAV/MYBOX/TeraBox 파일은 기존 영상
  스트리밍 어댑터 VideoStreamDataSource를 재사용합니다.
- 원격 프로필 인증정보는 Android SecureStore에 남으며 알림 또는
  외부 서비스로 전달하지 않습니다.
- Android 14+ 필수 foregroundServiceType 권한과
  Android 26~28 startForeground 호환 분기를 포함합니다.
- 재생 중 Android에서 앱을 강제 종료하거나 기기 전원을 재부팅하면
  재생이 지속되거나 자동 재시작된다는 보장은 없습니다.
- 현재 버전은 1곡 재생, 수동 재생/일시정지/탐색/종료 중심이며
  여러 곡 자동 플레이리스트·셔플·반복 재생은 미구현입니다.

## 내장 이미지 뷰어

- JPG/JPEG/PNG/WEBP/BMP/GIF/HEIC/HEIF를 별도 사진 앱 실행 없이
  NAS Mount 자체 ImageViewerActivity에서 엽니다.
- 전체화면 맞춤, 두 손가락 1~6배 확대, 한 손가락 패닝,
  더블탭 확대/복귀, Android 9+ GIF/움직이는 이미지 재생 지원.
- 로컬·SAF·Android MediaStore·원격 NAS/클라우드 경로 통합.
- 사설 임시 캐시에 이미지를 내려받아 로딩하며, 최대 100MiB로 제한.
  Android의 ImageDecoder/BitmapFactory 사용, 최대 4096픽셀로 축소
  디코딩해 큰 사진의 메모리 과부하를 줄입니다.

## 검증과 한계

- Android Gradle JUnit / Lint / Release 빌드 결과는 작업일지 참조.
- 로컬 Chrome에서 SVG 렌더러로 실제 생성 PPTX를 파싱하고
  PPTX 텍스트 수정 후 재저장, 제스처 synthetic touch
  스와이프 +1 및 pinch 2.35배 검증.
- 실제 Galaxy Fold7의 하드웨어 사진 코덱/GIF 터치, 화면 꺼짐 이후
  재생 및 삼성 배터리 관리, MYBOX/TeraBox 원격 오디오와
  해당 미니쿠치 원본 PPTX는 개발환경에 직접 연결되지 않아 미검증.
- 중요한 PPTX는 텍스트를 수정하기 전에 원본을 복사해 시험하세요.
  복잡한 문서 서식의 1:1 보존은 여전히 제한적입니다.

## 라이선스

- @web-ppt/core 0.8.0 MIT, fflate 0.8.x MIT (번들).
- AndroidX Media3 1.11.1, NextLib 등 기존 라이선스/GPLv3 의무 유지.
- 모든 문서·사진·음악 데이터는 사용자 저장소나 기기 내부 캐시에서 처리.
