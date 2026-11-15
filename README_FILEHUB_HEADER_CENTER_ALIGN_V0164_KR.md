# FileHub AIO v0.1.64 — 상단 아이콘·글자 로고 정중앙 정렬

## 사용자 증상

갤럭시 폴드에서 메인 삼선 아이콘과 FileHub AIO 글자 로고,
펼침 메뉴의 폴더 이미지 아이콘과 FileHub AIO 글자 로고가
수직으로 어긋나 보임. 로고 크기는 유지하고 정렬만 요청.

## 확인된 원인

v0.1.63 두 워드마크 ImageView가 ScaleType.FIT_START였음.
368×66의 투명 여백 포함 PNG가 90×30dp 및 87×22dp View에
축소되면 실제 글자가 상단에 치우쳐 출력됨.
홈 삼선 벡터는 처음부터 40dp×65dp View 정중앙이었고,
메뉴 파스텔 아이콘은 별도의 30×33dp 레이아웃.
또한 아이콘 시작 칸이 메인은 40dp, 메뉴는 30dp+4dp로 달랐음.

## 변경 사항

- MainActivity 홈 워드마크 FIT_START → FIT_CENTER,
  기존 90×30dp 로고 크기와 65dp 상단바는 유지.
- 펼침 메뉴 워드마크도 FIT_CENTER, 기존 87×22dp 크기 유지.
- 펼침 메뉴 로고 아이콘을 새 FrameLayout에 배치해
  홈 삼선 버튼과 같은 폭 40dp, 헤더 높이 65dp로 통일.
  실제 아이콘 이미지는 기존 30×33dp로 유지하면서
  FrameLayout 정중앙 Gravity.CENTER로 표시.
- 메뉴 글자 로고의 왼쪽 추가 여백 4dp 제거.
  홈과 펼침 메뉴 모두 8dp 패딩 + 40dp 시작 칸 뒤에서
  워드마크 영역이 시작함.
- 기존 PNG 투명도, 폴더 기능·즐겨찾기·NAS 계정·환경설정,
  65dp 헤더와 시스템바 여백은 그대로 유지.

## 정렬 수치 검증

FileHubBrandLayout.visibleImageCenterDp에서 Android ImageView
FIT_CENTER 배치 수식과 PNG 알파 콘텐츠 경계 사용.

- 홈 워드마크 368×66 PNG 알파 y=10..56을
  90×30dp View에 표시한 실제 콘텐츠 중심 = 32.5dp.
- 메뉴 워드마크 87×22dp View 표시 콘텐츠 중심 = 32.5dp.
- 홈 삼선 버튼 View 중앙 = 32.5dp.
- 메뉴 파스텔 아이콘 148×148 PNG 알파 y=20..132,
  30×33dp View 표시 콘텐츠 중심 ≈32.9dp.
  로고와의 수직 차이 0.5dp 미만.
- FileHubBrandLayoutTest 기존 3개 + 신규 2개 모두 통과.

## 버전·배포·제약

v0.1.63을 보존하고 독립 소스 NASMountAndroid_0_1_64 수정.
versionCode65, versionName0.1.64, package com.nasmount.mobile,
기존 서명·사용자 데이터 보존. Gradle 396/396 JUnit,
Android Lint Debug/Vital Release와 Release APK 빌드 통과.
실제 폴드가 ADB에 연결되지 않아 물리 기기 시각 검증은 아직 필요.
