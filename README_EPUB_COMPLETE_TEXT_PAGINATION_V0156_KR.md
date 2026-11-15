# NAS Mount Android v0.1.56 — EPUB 챕터 본문 전체 페이지 표시/읽기 연속성 수정

## 증상

- EPUB 파일이 실제로는 약 200페이지 분량인데 20~40회 정도만
  넘기면 끝나며, 이야기 중간 내용이 이어지지 않음.
- 사용자가 제보한 현상은 단순한 백분율 표시 문제가 아니라
  XHTML 챕터의 첫 화면만 나오고 다음 챕터로 이동하는 상태.

## 확인한 핵심 원인

v0.1.55 EpubReaderActivity.css()의 페이지형 CSS는
html과 body를 모두 height:100vh 및 overflow:hidden으로 제한하면서
body 내부에 CSS column-fill:auto로 페이지를 생성했습니다.
하지만 EpubReaderActivity.turn()은 Android WebView의
computeHorizontalScrollRange()-getWidth()로 다음 페이지 존재 여부를
결정합니다. 넘치는 column을 잘라 놓으면 그 스크롤 가능 거리가
0으로 나올 수 있어서, 본문 뒤쪽의 수많은 페이지를 건너뛰고
다음 XHTML 챕터로 이동하던 문제가 있었습니다.

Chrome headless에서 약 62만 자 분량의 1100개 문단을 사용하여
실제 렌더링을 재현했습니다.

| 렌더링 조건 | 뷰포트 폭 | 본문 전체 폭 | 스크롤 가능 폭 |
| --- | ---: | ---: | ---: |
| 기존 잘못된 CSS, 1열 | 784px | 452414px | 0px |
| 신규 CSS, 1열 | 784px | 470382px | 469598px |
| 신규 CSS, 폴드 2열 | 1184px | 390702px | 389518px |

기존에는 문서 본문 자체가 길어도 HTML root scrollWidth가
뷰포트와 같아 다음 페이지가 없는 것으로 오판했습니다.
새 CSS에서는 전체 컬럼이 정상적인 가로 스크롤 영역이 됨을 확인.

## 수정 사항

1. EpubPaginationPolicy.css() 도입
   - html은 가로 스크롤을 허용하며, body는 컬럼의 overflow를
     가리지 않고 스크롤 가능한 영역으로 노출합니다.
   - 페이지형은 column-count:1, 폴드 펼침은 column-count:2.
   - 좌우 여백 m에 대해 column-gap:2m을 사용하여,
     한 화면의 페이지 또는 두 페이지 묶음 너비가 뷰포트와 맞음.
     이로 인해 페이지 이동 시 문장 시작 부분이 픽셀 단위로
     반복 누락되거나 잘리는 누적 오차가 줄어듭니다.
2. EPUB 고유 CSS와 우선순위 충돌 방지
   - 기존에는 헤드가 열리자마자 독서 CSS를 삽입하므로 나중에
     나오는 원본 EPUB 스타일에 페이지 높이/폭/overflow가 덮였음.
   - EpubPaginationPolicy.injectAfterBookStyles()로 HTML head
     마지막 위치에 독서용 규칙을 삽입하고 핵심 레이아웃에
     important를 적용합니다.
3. WebView 실제 레이아웃 완료 후 다음 페이지 허용
   - onPageFinished만으로는 수평 컬럼 렌더링이 완성되지 않음.
   - postVisualStateCallback을 기다리고 1회 추가 측정 프레임 이후
     restorePosition 및 loadingChapter=false 처리합니다.
   - 이전 XHTML 로딩 결과는 generation 검사로 폐기합니다.
4. 안정적인 페이지 전환·읽던 위치 복원
   - 페이지 이동은 EpubPaginationPolicy.target()을 통해
     실제 viewport 거리만큼 이동합니다.
   - 재개/설정 변경으로 페이지 크기가 달라졌을 때는 새 화면의
     페이지 경계 이전 위치로 내려 스냅하여 읽지 않은 내용을
     앞으로 건너뛰지 않도록 조정했습니다.
   - 기존 동일 패키지 서명, 이전 책갈피·진행률 자료 보존.
5. EPUB 파일 안전성/기존 기능 유지
   - 원본 EPUB 전체 파일을 수정하거나 재압축하지 않음.
   - 챕터 spine 순서, 세로 연속 스크롤, 터치 넘김, Page Curl,
     상·하단바 숨김, 폰트 및 배경 설정, XML XXE 차단 유지.
   - 앱 내부 WebView JavaScript는 계속 비활성화.

## 자동 검증

- EpubPaginationPolicyTest: 기존 root/body overflow:hidden 재발 방지,
  단면/양면 간격, 긴 200페이지 이동, 뒤로 가기, CSS 삽입 우선순위,
  head가 없는 HTML, 읽기 위치 복원 시 문장 건너뛰기 방지.
- Chrome headless 구형 vs 신규 실제 문서 전체 스크롤 폭 측정.
- Gradle compile + JUnit + lintDebug + lintVitalRelease + assembleRelease.
- APK 서명/버전/해시 및 Y: 배포본 해시 비교는 작업일지 참조.

## 미검증/주의

- 이 작업 환경의 adb에는 갤럭시 폴드 기기가 연결되어 있지 않음.
  Chromium 헤드리스 레이아웃 검증과 Android 단위 테스트는
  수행했지만 실제 Samsung WebView에서 200쪽 분량을 끝까지
  넘기는 터치 테스트는 직접 수행하지 못함.
- EPUB마다 XHTML/OPF 구성과 자체 CSS가 다릅니다.
  특수한 고정 레이아웃 EPUB이나 이미지·표 비중이 높은 EPUB은
  추가 호환성 조정이 필요할 수 있습니다.
- 기존 버전에서 오류로 마지막 챕터까지 진행률이 기록된 책은
  동일한 위치로 복원됩니다. 처음부터 재검증할 경우 독서 위치를
  처음으로 이동하여 전체 본문이 이어지는지 확인해야 합니다.
