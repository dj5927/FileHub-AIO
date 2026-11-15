# NAS Mount Android v0.1.24 — Restore Rotation and Speed Icons on Samsung Fold

## v0.1.24 변경 사항

1. **삼성 Fold7에서 빈 원형 버튼 3개로 표시되던 회전/배속 +/- 아이콘 복구**: v0.1.23의 `CenteredPlayerTool`은 글꼴 아이콘을 없애고 빈 글자를 가진 TextView의 `onDraw(Canvas)`에서 회전 화살표, +, -를 그렸으나, 실제 Samsung Fold7 화면에서는 회색 둥근 배경만 나타나고 아이콘 셋이 모두 안 보이는 문제 발생. 사용자가 실기기 스크린샷으로 확인.
2. **배경+아이콘을 한 번에 그리는 방식으로 교체**: `CenteredPlayerTool.java`를 수정해 `IconBackground extends android.graphics.drawable.Drawable`이 `Drawable.draw(Canvas)`에서 둥근 배경, 테두리, 중앙 아이콘을 모두 그리도록 구현. 버튼의 실제 drawable bounds 중심을 기준으로 회전 호+화살촉과 ＋/－ 수평·수직선을 그린다. `VideoPlayerActivity.roundTool`은 새 `CenteredPlayerTool`의 배경을 다시 일반 GradientDrawable로 교체하지 않도록 분기 처리. 따라서 **원형 배경이 렌더링되는 시점에 아이콘도 동일한 경로에서 렌더링**되고 삼성의 빈 TextView 본문 onDraw 의존성을 제거.
3. **기존 기능 유지**: 화면 방향 전환(자동→가로→세로), +/- 배속 0.1배 증감, 속도 초기화, Galaxy Fold7 접힘/펼침 반응형 UI, WebDAV 수정일 표시, SMI 한국어 외부자막, NextLib FFmpeg, 메뉴 접기 상태 및 연결 정보 모두 그대로 유지. 기존 v0.1.23 APK와 소스는 덮어쓰지 않음.

## 검증 및 유의사항

- package `com.nasmount.mobile`, versionCode `25`, versionName `0.1.24`. 기존 v0.1.23을 삭제하지 않고 동일한 서명으로 덮어 설치 가능.
- 빌드 명령: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintVitalRelease :app:assembleRelease --offline --no-daemon --max-workers=2 --console=plain`.
- 최초 빌드 중 Microsoft OpenJDK 17.0.20.1의 `C2 CompilerThread3`에서 `EXCEPTION_ACCESS_VIOLATION` JVM 내부 충돌 발생; 별도 `hs_err_pid39824.log`에 증거 보존. 이는 Android 소스 컴파일 오류가 아닌 Gradle JVM 오류. 재실행 시 해당 Java 프로세스에 `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1`을 설정하여 C2 최적화를 제한하자 빌드 정상 종료. 최종 JUnit 124/124, lintDebug/lintVitalRelease, assembleRelease 모두 성공.
- 실제 폴드7 기기 연결은 없어 **새 Drawable이 화면에 실제 표시되는지 폰에서 재검증 필요**. 코드 경로와 APK 포함 확인은 단말 육안 확인과 구별한다. 배경과 아이콘을 같은 Drawable.draw 단계에서 그리므로 기존 TextView.onDraw 생략에 따른 원형 버튼만 남는 문제를 원천적으로 피하도록 변경.
- NextLib FFmpeg GPLv3에 따른 배포 소스·라이선스 의무 기존과 동일.

## 릴리스 파일

- APK: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.24_ICON_VISIBILITY_FIX.apk
- 소스 ZIP: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.24_SOURCE.zip
- 안내: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.24_README_KR.md
- SHA256: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.24_SHA256.txt

---

## 이전 버전 v0.1.23 기록

## v0.1.23 변경 사항

1. **원형 회전 아이콘과 +/− 버튼 정중앙 정렬**: 폴드7 재생 화면 캡처에서 상단 회전 화살표와 오른쪽 + 배속 증가 기호가 원형 배경 한쪽으로 치우친 문제 수정. 삼성 글꼴의 글리프 간격/기준선에 의존하던 TextView 문자 그리기 대신 새 CenteredPlayerTool.java에서 Android Canvas의 실제 view.width/2와 view.height/2를 기점으로 회전 호·화살촉·플러스·마이너스 선을 직접 그린다. 컨트롤 크기가 폴드 접힘/펼침에 따라 달라져도 중앙을 유지하도록 구현. 재생 속도 0.1배 단위, 회전 모드 자동/가로/세로 등 기존 버튼 기능 보존.
2. **NAS/WebDAV 수정 날짜 미표시 원인 수정**: 파일 리스트 MainActivity.fileRow에는 예전부터 entry.modified > 0이면 파일 용량 행 오른쪽 끝에 수정 날짜가 표시되는 코드가 있었으나, 실제 WebDavXml.parse에서는 WebDAV 서버 PROPFIND 응답의 getlastmodified 속성을 읽지 않고 건너뛰어서 WebDAV 파일들이 수정일=0으로 나타났음. 새 WebDavTimestamp.java로 표준 RFC1123 HTTP-date 및 ISO8601 시각을 UTC epoch milliseconds로 읽고, WebDavXml에서 성공한 propstat의 getlastmodified를 Models.Entry.modified 필드에 복원. 해당 속성이 없는 경우에만 creationdate를 대신 사용. 실패한 404 propstat의 날짜는 무시, 날짜가 실제로 없으면 공란 유지. 폴더 목록 allprop 1회 요청 그대로 활용해 날짜별 추가 네트워크 쿼리/스크롤 중 새로고침을 수행하지 않음.
3. **목록 오른쪽 날짜 표기 정리**: 파일과 폴더 목록 LIST/DETAILS 모드에서 용량·자막 배지 아래 정보 행 오른쪽 끝에 yyyy.MM.dd 형식으로 깔끔하게 표시. 가독성 11sp, 최소 너비 77dp, 화면 낭독용 날짜 설명 추가. 파일 이름이 길어도 두 번째 줄 오른쪽에 표시. 격자 보기에는 동일한 좌우 정보행이 없으므로 이 변경의 적용 대상은 목록/자세히 보기.
4. **기존 기능 보존**: v0.1.22의 nPlayer 스타일 하단 재생바, 좌우 도구, FFmpeg NextLib 디코더 24개 네이티브 라이브러리 및 4 ABI, SMI/SRT 한글 자막, 폴드7 펼침 대화면 자동 레이아웃, 즐겨찾기/네트워크 접기 상태, 사용자 NAS 계정과 연결, 폴더 세션 캐시 유지.

## 빌드·검증 및 주의

- package=com.nasmount.mobile, versionCode 24, versionName 0.1.23. 기존 v0.1.22와 같은 키로 서명해 앱 삭제 없이 덮어 설치 가능.
- WebDavProtocolTest에 HTTP 207 모의 서버 3건(수정일 우선/생성일 fallback/404 실패 속성 무시), 새 WebDavTimestampTest에 5건(HTTP-date/ISO8601/한국 +09:00/불량 값/과거 값) 추가. 전체 자동 테스트, lintDebug, lintVitalRelease, assembleRelease 검증.
- WebDAV 서버가 수정 날짜와 생성 날짜를 모두 제공하지 않으면 임의 시각을 표시하지 않음. 폴더 캐시에는 실제로 받은 수정 시각이 저장되므로 앱을 재실행하고 폴더에 재진입하면 새로운 날짜 값 표시 가능.
- 삼성 폴드7 실기기 ADB 연결은 없어 실제 UI 재생/화면 날짜 검증은 아직 필요. NextLib GPLv3 배포 의무는 이전과 동일.

## 릴리스

- APK: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.23_ICONS_WEBDAV_DATES.apk
- SOURCE ZIP: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.23_SOURCE.zip
- README: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.23_README_KR.md
- SHA256: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.23_SHA256.txt

---

## 이전 버전 v0.1.22 기록

## v0.1.22 변경 사항

1. **nPlayer 스타일 재생 컨트롤**: 기존 영상 가운데 대형 재생/이전/뒤로/앞으로/다음 버튼을 하단 100dp 반투명 재생 조작바로 재배치. Media3 1.11.1의 `exo_player_control_view.xml` 앱 리소스 override로 기본 재생 아이콘, 뒤로/앞으로 건너뛰기, 진행 시간, 전체 길이, 드래그 가능한 seekbar를 모두 원래 Media3 컨트롤 ID에 연결해 영상 탐색/시간 동기화는 Media3가 계속 처리. 재생바 위쪽에 밝은 얇은 진행 바, 왼쪽 현재 시간/전체 시간, 아래 중앙 작은 재생/건너뛰기 버튼, 우측 전체화면 방향 버튼을 배치. 상단은 뒤로 가기/영화명/⋯ 간결한 정보바. 기존 가운데 거대한 재생 버튼 제거. Media3의 마지막 2초 동안 '진행바만 남는' 컨트롤 애니메이션을 비활성화해 상단/좌우/하단/잠금이 같은 자동 숨김 시점에 동작.
2. **오른쪽 가운데 0.1배속 조절**: 둥근 ＋ / 현재속도 n.n× / −를 세로로 표시. ＋ 및 −는 정확히 0.1배 단위로 변하며 0.5×~3.0× 범위로 제한. 가운데 배속 표시를 누르면 1.0×로 복귀. `ExoPlayer.setPlaybackSpeed`으로 바로 적용되어 동영상 영상/오디오 재생에 반영되고, 자막 SRT/SMI/Media3 내장도 기존 플레이어 시간축에 동기화. 속도 설정은 재생 세션마다 기본 1.0×.
3. **왼쪽 가운데 도구 3종**: 방향 회전 원형 아이콘 누르면 `자동 → 가로 → 세로 → 자동` 순서로 변경되며 하단 모드 설명 표시. 오디오 아이콘(♪) 아래 실제 재생 중인 AAC/E-AC3 등 코덱 라벨을 표시하고 누르면 현재 오디오 소스·트랙·언어 선택 또는 실제 디코더/코덱/채널 정보 보기 메뉴가 나타남. 사용자가 오디오 트랙을 선택하면 Media3 `TrackSelectionOverride`로 연결, 자동을 고르면 해당 선택 override 초기화. 자막 CC 아이콘 아래 실제 외부/내장/자동/끔 상태를 표시하고 기존 자막 설정/언어 선택/크기 조절 메뉴로 연결. 기존 2초 길게눌러 화면 잠금/잠금 해제 기능 유지. 수동 회전 선택은 자동 화면비에 의해 덮어쓰지 않음.
4. **Galaxy Fold7 펼침 전용 반응형 인터페이스**: 제조사 이름이 아닌 실제 앱 화면 너비(dp)와 smallestWidth/높이를 함께 판별하여 600dp 이상 넓게 펼쳐진 디스플레이/태블릿에서 대화면 레이아웃 사용, 일반 휴대폰과 Fold7 접힌 화면 및 좁은 분할 화면에서는 컴팩트 레이아웃 유지. 동영상 상단 바 좌우 여백 확대, 양쪽 도구 버튼 48dp→55dp(높이 충분할 때), 도구 레일 너비/거리/글자 크기 확대. 펼친 폴드에서 자동 회전은 가로/세로를 강제로 고정하지 않고 현재 대화면 형태를 사용하여 재생 유지. 활동 화면 전환(펼침/접힘) 시 `orientation|screenSize|smallestScreenSize|screenLayout`를 Activity가 직접 처리하므로 기존 NAS 원격 재생기와 파일 탐색기를 통째로 재생성하지 않아 재생 위치·연결을 보존.
5. **파일 관리자/설정 Fold 최적화**: 펼친 디스플레이에서 파일 목록과 홈 콘텐츠 최대 너비 1080dp, 페이지 가운데 정렬, 홈 저장소 카드 확장·여백/아이콘/폰트 향상, 파일 격자 3열→4열/5열/6열(너비별 자동). 왼쪽 사이드바는 화면 82%를 덮는 대신 최대 360dp로 제한해 Fold 대화면의 본문을 가리지 않음. 설정 내용도 펼친 화면에서 최대 850dp 가운데 정렬, 상단 카테고리 탭 중앙 정렬. 펼침/접힘 구성 변경에서는 현 NAS 폴더 경로와 원격 목록 메모리 캐시를 유지하고 같은 위치로 재렌더. 기존 v0.1.21의 즐겨찾기/네트워크 접기 상태 기록은 보존.
6. **기존 기능 보존**: SMI→SRT 한글 자막 변환, 외부/내장 자막 우선 선택 및 수동 변경, E-AC3/TrueHD/DTS FFmpeg 내장 디코더(4개 CPU ABI/24개 .so), 나스 스트리밍, 폴더 캐싱, 서체/자막크기/색상 배지/NFO 숨김, 세로 영상을 세로 화면으로 표시하는 휴대폰 자동 비율 선택은 그대로 유지.

## 검증 및 제한

- 패키지 `com.nasmount.mobile`, versionCode `23`, versionName `0.1.22`, 기존 v0.1.21과 동일한 릴리스 서명으로 덮어 설치 가능.
- Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 검증. `PlaybackControlPolicyTest` 5건과 `FoldPlayerLayoutPolicyTest` 4건에서 0.1배 단위·경계값·회전 순서·폴드/휴대폰/가로 회전/분할 창·대화면 콘텐츠/드로어 너비 계산 확인.
- Android SDK에 실행 가능한 Emulator가 없고 ADB 물리 단말 연결도 없으므로, Fold7 실제 펼침/접힘 및 Android Media3 플레이어 조작바 터치 동작·컨트롤 자동 숨김 시각적 실측 검증은 **실기기에서 추가 필요**. Java/JUnit/Android Lint/리소스 컴파일 성공은 실기기 조작 검증과 다름.
- 삼성 One UI/Android가 멀티윈도우/대화면에서 앱 방향 전환 요청을 무시할 수 있으며, 이 경우 물리 기기 정책 우선. FFmpeg NextLib GPLv3에 따른 재배포 소스/라이선스 의무는 이전과 동일.

## 릴리스 경로

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.22_NPLAYER_FOLD_CONTROLS.apk`
- Source ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.22_SOURCE.zip`
- README: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.22_README_KR.md`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.22_SHA256.txt`

---

## 이전 버전 v0.1.21 기록

## v0.1.21 변경 사항

1. **왼쪽 메뉴 최상위 항목 간소화**: 서랍 메뉴에서 `메인 저장소`, `SD 저장소`, `다운로드`, 중복 `원격 저장소` 항목 제거. 메인 저장소·SD·다운로드는 기존 **홈 화면의 바로가기**에서 계속 사용 가능. 새 최상위 메뉴는 `홈`, `즐겨찾기`, `네트워크 폴더`, 아래 `설정` 네 개. 기존 홈 화면 구성이나 사용자 NAS 설정·즐겨찾기 데이터는 삭제/수정하지 않음.
2. **즐겨찾기/네트워크 폴더 접기·펼치기**: 각 그룹 제목 오른쪽 삼각형 화살표 `▾`(펼침)/`▸`(접힘) 추가. 제목을 한 번 누르면 바로 하위 목록만 숨기거나 표시하며, 즐겨찾기에는 등록된 폴더 링크, 네트워크 폴더에는 원격 연결 링크가 나타남. 그룹 하위 항목을 누르면 원래 해당 위치로 이동. `연결 추가 · 관리`는 네트워크 폴더 아래에서 제공해 기존 연결 추가·변경 화면으로 접근 가능.
3. **앱 재실행에도 접힘 상태 유지**: 신규 `DrawerSectionState.java`는 `favorites_expanded`와 `network_expanded` 별도 상태 키를 사용. Android 앱 전용 `drawer_sections` SharedPreferences에 각 클릭 순간 `commit()`으로 동기 기록하여 메뉴를 닫았다 다시 열거나 앱 프로세스를 재실행해도 기존 상태 그대로 재현. 기존 v0.1.20에서 처음 업데이트한 사용자는 예전처럼 기본값이 모두 펼침이며, 이후 변경한 상태를 보존. 사용자가 즐겨찾기를 접어도 네트워크 폴더의 펼침 상태는 변하지 않음.
4. **UI 정리**: 기존 다크/라이트 모드 팔레트 반영. 그룹 제목은 일반 탐색 메뉴와 같은 아이콘·글꼴 정렬, 하위 목록은 안쪽 들여쓰기로 계층을 구분. 그룹 아래 항목이 없으면 간결한 안내. 설정은 기존처럼 하단에 위치. 단말 접근성에는 `즐겨찾기 접기/펼치기`, `네트워크 폴더 접기/펼치기`를 알림.
5. **나머지 기능 유지**: v0.1.20의 실제 한글 SMI 자동 감지·SRT 변환, 외부 자막 우선/수동 내장 자막, 네트워크 폴더 세션 캐싱, 컬러 자막 배지, NFO 숨김, 파일관리, NextLib FFmpeg 네이티브 디코더 4 ABI/24개 .so 등 그대로 유지.

## 검증/제한

- `com.nasmount.mobile` versionCode=22 / versionName=0.1.21. 기존 v0.1.20과 동일한 서명으로 덮어 설치 가능하며, 기존 사용자 NAS 연결·보안 설정·즐겨찾기 목록은 보존.
- Gradle `:app:testDebugUnitTest` / `:app:lintDebug` / `:app:lintVitalRelease` / `:app:assembleRelease` 검사. 신규 `DrawerSectionStateTest` 6건에서 처음 사용자 기본 펼침, 각 그룹 상태 독립성, 접힘/펼침 토글, 앱 객체 재생성 시 저장값 복원, 유효하지 않은 그룹 차단을 검사.
- 실제 삼성 Android 휴대폰은 개발 PC ADB에 연결되지 않아 메뉴의 시각적 간격·스크롤·실제 앱 재실행 동작은 실기기에서 추가 확인 필요. JUnit 및 릴리스 APK 빌드 성공만으로 UI 실기기 테스트를 완료했다고 주장하지 않음.
- GPLv3 NextLib FFmpeg 라이브러리 라이선스 및 재배포 시 대응 소스·저작권 고지 준수 의무 이전 버전과 동일.

## 릴리스

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.21_COLLAPSIBLE_DRAWER.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.21_SOURCE.zip`
- 설명: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.21_README_KR.md`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.21_SHA256.txt`

---

## 이전 버전 v0.1.20 기록

## v0.1.20 변경 사항

1. **SMI 외부 자막 자동 연결 누락 수정**: 파일 관리자에서 `.smi` 외부 자막 존재를 감지하더라도 재생기 v0.1.19가 `.srt` / `.vtt`만 찾아 Media3에 첨부했기 때문에, 한국어 SMI+영어 내장 ASS가 동시 존재하면 실제 TEXT 외부 트랙 0개로 내장 영어를 재생하던 문제. `SubtitleSourcePolicy.matchesExternalFilename`를 SRT/VTT/SMI/SAMI + 기존 언어 접미어까지 인식하도록 수정. NAS 원격 폴더의 같은 이름 SMI를 자동 다운로드(최대 2MB), 앱 내부 `cacheDir`에서 임시 UTF-8 SRT로 변환한 뒤 원래 외부 트랙 ID/Label로 Media3에 연결하므로 **기존 AUTO(외부 우선) 기능이 한국어 자막을 선택**함. 사용자 NAS의 원본 SMI 파일을 수정·이동·변경·삭제하지 않음.
2. **구형 한국어 자막 인코딩 지원**: 신규 `SamiSubtitleConverter.java`는 UTF-8(BOM 포함), UTF-16 LE/BE(BOM 포함), CP949/MS949(필요시 EUC-KR 대체)를 읽어 UTF-8 SubRip 형식으로 변환. SAMI `SYNC Start=` 단위 시간코드, `<P Class=KRCC>`, 다국어 자막 중 한국어 우선, `<BR>` 줄바꿈, `&nbsp;` 공백/빈 자막 구간, HTML 태그 및 일부 문자 엔티티 처리. SMI에 빈 구간이 있으면 이전 자막이 종료되도록 시간 구간을 정확히 구성. 서브 자막을 인코딩한 하드섭과는 별개로 작동.
3. **원격/로컬/수동선택 모두 적용**: WebDAV/SMB/FTP/SFTP 등 기존 `RemoteProvider.download`으로 SMI를 받아 변환. 휴대폰 로컬 `file://` 폴더와 사용자가 시스템 문서 선택기를 통해 직접 지정한 `.smi`도 변환. 원격 파일은 최대 2MB로 제한하고 원본 파일을 수정하지 않음. 앱 내부 변환 결과는 이름이 다른 임시 .srt이며 비동기로 만들어 동영상 재생을 막지 않음. 외부 SRT/VTT는 기존대로 유지. 직접 고른 SMI는 사용자가 설정한 외부 소스 모드로 전환, NAS 자동 연결이 수동 자막을 다시 덮어쓰지 않음. 영상별 AUTO(외부 우선)/EXTERNAL/EMBEDDED/OFF 선택은 v0.1.19와 같이 유지.
4. **실제 사용자 폴더 확인**: 사용자가 지정한 `X:\2.영화\5_애니메이션\그날 본 꽃의 이름을 우리는 아직 모른다` 를 읽기 전용 점검. MKV 영상 11개와 **파일명 정확히 대응하는 SMI 11개**를 확인. 샘플 첫 MKV의 내장 트랙은 ffprobe로 영어 ASS `eng` / `enm` 두 개를 확인, SMI의 실제 한국어 KRCC를 확인. v0.1.20 프로덕션 변환기를 Windows Java 환경에서 해당 실제 SMI 11개에 직접 적용한 결과 **모두 정상, 총 4,656개 자막 구간**, 각 출력 한글 정상, U+FFFD 깨짐 문자 없음. 실제 미디어나 SMI 내용을 프로젝트의 소스/테스트 리소스에 복사하지 않음. 단순 검증용 컴파일 코드는 `app/build/tmp/sami-real-validation` 아래에만 생성하여 릴리스 소스 ZIP에서 제외.
5. **기존 기능 보존**: NextLib Media3 FFmpeg 24개 .so, EAC3/DTS/TrueHD 디코더, 외부/내장 색상 자막 배지, 동영상 폴더 및 NFO 숨김, 폴더 캐싱, 라이트/다크 모드, 영화별 자막 크기와 소스 선택 등 유지.

## 검증 및 제한

- `com.nasmount.mobile` versionCode=21 / versionName=0.1.20, 기존 v0.1.19와 동일한 서명으로 덮어 설치 가능. NAS 연결 계정과 설정 유지.
- 전체 Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 검증. 신규 `SamiSubtitleConverterTest` 7개로 한국어/시간/줄바꿈/HTML/CP949/UTF8 BOM/언어 선택/빈 구간·오류 처리 검사, 기존 `SubtitleSourcePolicyTest`에는 Anohana MKV와 동일한 SMI 파일명 매칭 사례 추가.
- Android 실기기 연결 없음(ADB devices 0)이므로 사용자의 삼성 휴대폰에서 WebDAV 자막 지연 다운로드·변환·렌더링 전체 흐름은 실기기에서 확인해야 함. 실제 파일 11개 프로덕션 Java 변환 테스트는 통과했으나 Android 기기의 네트워크 플레이어 UI 출력 확인과는 구별함.
- SMI로부터 텍스트 및 시각 정보를 SubRip로 변환하므로 SAMI의 폰트/색상 HTML 스타일이 그대로 유지되지는 않음(사용자 설정의 앱 자막 크기/폰트를 따름). 원본은 변환되지 않고 그대로 보존. 기타 외부 ASS/SSA/PGS는 자동 연결 대상에 포함되지 않음. NextLib GPLv3 배포 관련 의무 이전과 동일.

## 신규 배포 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.20_SMI_KOREAN_AUTO_SUBTITLE.apk`
- 소스: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.20_SOURCE.zip`
- 안내: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.20_README_KR.md`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.20_SHA256.txt`

---

## 이전 버전 v0.1.19 기록

## v0.1.19 변경 사항

1. **외부 자막 최우선 자동 선택**: 영상에 내장 자막과 동일 이름의 외부 SRT/VTT 자막 파일이 함께 있으면 재생 기본 설정은 `자동 (외부 자막 우선)`. Media3 1.11.1의 `SubtitleConfiguration.Builder.setId`와 `setLabel`로 외부 자막 트랙을 명시적으로 식별, `onTracksChanged`가 수신한 실제 Media3 트랙 그룹에서 외부 자막을 선택하는 `TrackSelectionOverride` 적용. 외부 자막이 없는 경우에만 한국어 기본 언어 우선의 내장 자막을 선택. 단순히 Media3의 기본 언어 선택 알고리즘에 두 트랙을 맡기지 않으므로 두 자막이 한국어여도 외부 자막 우선. 외부 SRT/VTT는 `영화명.srt`, `영화명.ko.srt`, `영화명.ko-KR.forced.srt`, `영화명.en.vtt` 등의 언어 접미어도 자동 매칭. 잘못된 유사 파일명은 무시.
2. **재생 중 자막 소스 직접 선택**: 상단 `자막` → `자막 소스 선택` → `자동 (외부 자막 우선)`, `외부 자막`, `내장 자막`, `자막 끄기` 네 개의 라디오 옵션. 외부·내장 중 선택 시 다른 소스를 화면에 섞지 않고 명시적으로 제한. `내장 자막 언어 / 트랙 선택` 메뉴는 외부 SRT/VTT 트랙을 필터링해 영상 안의 언어 트랙만 표시하고, 원하는 트랙 또는 기본 언어를 선택하면 내장 자막 모드로 전환. `모든 자막 끄기` 및 `자동 선택으로 되돌리기`를 빠른 메뉴에 유지. 해당 영상을 다시 열어도 사용자가 선택한 소스 모드가 유지되도록 영상 식별자별 앱 내 SharedPreferences에 저장 (기본값 AUTO). 특정 내장 언어 트랙 개별 지정은 현 재생 세션 내에서 유지.
3. **비동기 외부 자막 연결과 수동 선택 충돌 방지**: NAS 폴더에서 외부 SRT/VTT를 검색해 늦게 받아오더라도 사용자가 `내장` 또는 `자막 끄기`를 선택했다면 이를 외부 자막으로 강제 변경하지 않음. 수동 외부 파일 선택 시 외부 자막 모드로 변경. 수동으로 외부 자막을 제거한 뒤 진행 중이던 NAS 자동 탐색 결과가 다시 붙는 문제도 방지. 자막을 새로 붙여 MediaItem을 재생성할 때는 이전 Media3 TrackGroup에 대한 override를 먼저 초기화해, 새 트랙이 준비되면 올바르게 선택.
4. **내장 자막 존재 여부 판정 개선**: 이전의 `전체 TEXT 트랙 수 - 외부 파일 수` 방식 대신 외부 자막에 붙인 ID/Label을 이용해 실제 내장 자막을 구별. 외부 SRT만 존재하는 파일을 내장 자막이라고 잘못 기록하지 않도록 하고, 외부와 내장이 함께 있을 때는 두 그룹 모두 유지.
5. **지원 범위/기존 기능 유지**: 자동으로 연결되는 외부 자막은 현재 Media3 내장 처리 가능한 SRT/VTT. SMI/ASS 등 다른 확장자를 외부 SRT처럼 로드할 수 있다고 주장하지 않음. 실제 영상에 인코딩된 하드섭은 자막 트랙을 끄더라도 사라지지 않음. 기존 FFmpeg NextLib 1.11.1-0.18.0, 4 ABI/24 네이티브 라이브러리, NAS 구간 스트리밍, 다크모드, 자막 크기 및 TTF, 네트워크 폴더 캐시, 외부/내장 색상 배지, NFO 숨기기, 롱프레스 동영상 폴더 설정은 모두 유지.

## 검증 및 실기기 확인

- 앱 `com.nasmount.mobile`, versionCode `20`, versionName `0.1.19`. v0.1.18과 동일한 앱 서명으로 사용자 연결정보/설정 유지하며 덮어 설치 가능.
- Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 최종 통과 확인. `SubtitleSourcePolicyTest`에서 외부 우선/외부 없음 시 내장/내장 강제/외부 강제/자막 끄기/정확한 ID 판별/자막 언어 접미어 자동 파일명 매칭 테스트.
- Media3 1.11.1의 공식 `SingleSampleMediaSource` / `DefaultMediaSourceFactory` 소스에서 `SubtitleConfiguration.id` 및 `label`이 `Format.id`/ `Format.label`으로 그대로 전달되는 경로를 확인.
- Android 기기 및 실제 사용자의 NAS 미연결이므로 실제 외부+내장 자막 동시 존재 MKV에서 자막 선호도와 수동 전환 결과는 **삼성 실기기에서 검증 필요**. 최종 APK 빌드 및 JVM 테스트 성공만으로 재생 화면을 실측 검증했다고 주장하지 않음.
- NextLib FFmpeg는 GPLv3. 외부 배포 전 관련 소스·라이선스 제공 조건 확인 필요.

## 릴리스 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.19_EXTERNAL_SUBTITLE_FIRST.apk`
- SOURCE: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.19_SOURCE.zip`
- README: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.19_README_KR.md`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.19_SHA256.txt`

---

## 이전 버전 v0.1.18 기록

## v0.1.18 변경 사항

1. **내장 자막 1개인 MKV 감지 개선**: v0.1.17의 1MiB 일괄 스캔을 개선. Matroska 파일의 실제 EBML Header → Segment → Tracks → TrackEntry/TrackType=17 계층을 따라 자막 트랙을 찾음. SeekHead 안의 Tracks/SeekID 참조 바이트를 진짜 Tracks로 오인하여 조기 판정하던 버그를 수정. 내장 자막 트랙이 **단 1개만 있어도** 검출. 다른 영상/오디오 트랙 개수와 무관. 영상 헤더가 앞에 있는 경우 최초 8KiB 정도만 읽고 바로 확인하고, 파일 앞쪽에 큰 Void/메타데이터 영역이 있을 때만 **최대 4MiB**까지 검사. 파일 전체 다운로드 없음. 영상 데이터 Cluster가 먼저 시작되면 불필요한 대용량 영상 프레임 스캔 중단. 초기 검사에서 아직 확인되지 않으면 실제 재생 도중 Media3가 찾은 트랙 정보로 보완. v0.1.17 시절 로컬 메타데이터 탐지 실패로 0을 캐시한 경우도 앱 실행 중 첫 탐색 시 다시 1회 검사하도록 수정. 네트워크 일시 오류는 해당 영상 재탐색 시 재시도. **일부 MP4 등 비-Matroska 원격 영상과 4MiB 경계 밖의 비표준 MKV Tracks는 여전히 목록에서 미검출될 수 있음**; 배지 없음이 자막 없음의 확정은 아님.
2. **자막 아이콘 색상 배지**: 동영상 목록·자세히 보기의 파일 용량 옆에 둥근 녹색 배지 `● 외부`, 붉은색 배지 `● 내장`을 각각 표시. 외부 자막 확장자(SRT/SMI/ASS 등)는 표시하지 않음. 외부와 내부가 둘 다 확인되면 두 개 배지를 나란히 표시. 라이트/다크 모드에서 배경과 문자 대비를 별도 설정. 격자 보기에서는 용량 아래에 작은 배지를 놓고 카드 높이를 조정. 영상 재생 중 새로 확인된 자막 트랙도 돌아온 목록에서 색상 배지로 표시.
3. **동영상 폴더의 NFO 숨김**: '동영상 폴더로 설정'한 폴더와 하위 폴더에서는 기존 SRT/SMI/ASS/SSA/VTT/SUB/SUP/IDX/TXT와 함께 **NFO(.nfo, .NFO)** 파일도 목록에서만 숨김. 동영상 파일과 자막·NFO 파일은 수정하거나 삭제하지 않음. 동영상 폴더 설정을 해제하면 기존과 같이 NFO 표시. 설정 화면의 항목 및 설명도 '자막·TXT·NFO 파일 숨기기'로 변경.
4. 기존 네트워크 폴더 세션 캐싱, 동영상 폴더 길게 누르기 설정, 다크모드, NAS 연결, Media3/NextLib FFmpeg 코덱과 동영상 플레이어 기능은 유지.

## 검증

- 동일 서명·패키지 `com.nasmount.mobile`, versionCode 19, versionName 0.1.18, 기존 v0.1.17 앱 덮어 설치 가능.
- JUnit에는 실제 FFmpeg 생성 MKV(SRT 내장 1개) 및 무자막 샘플 검사 외 **SeekHead 내부 Tracks 참조가 실제 트랙을 가리지 않는 회귀 테스트**, 1MB보다 멀리 있는 정식 EBML Tracks, 네트워크 단일 자막의 조기 발견, 색상 배지 상태 플래그 조합, NFO 대소문자 확장자 추가.
- `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 확인 후 Android releases에 신규 APK/소스/README/SHA256 저장.
- Android 휴대폰과 NAS 서버의 실제 MKV 파일이 개발 PC에 연결돼 있지 않으므로 기기의 Media3 실재생 및 사용자 NAS 폴더 전체의 20개 영상에 대한 검증은 별도로 필요.
- NextLib FFmpeg GPLv3 라이선스·소스 공개 의무는 이전과 동일.

## v0.1.18 배포 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.18_SINGLE_SUBTITLE_BADGES_NFO.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.18_SOURCE.zip`
- 안내 문서: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.18_README_KR.md`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.18_SHA256.txt`

---

## 이전 버전 v0.1.17 기록

## v0.1.17 변경 사항

1. **내장 자막 감지 개선**: v0.1.16에서 `VideoPlayerActivity.onTracksChanged`는 외부 SRT/VTT 파일이 자동 첨부되면 `subtitleTracks.isEmpty()`가 false가 되어 영상의 실제 내장 자막을 기록하지 않는 버그가 있었음. 현재는 Media3 TEXT 트랙 수에서 앱이 별도로 추가한 외부 자막 트랙 수를 제외해 실제 내장 자막을 구분하고 양성 기록. 외부 SRT와 내부 자막이 함께 있어도 `내장 자막` 배지가 표시되도록 개선.
2. **네트워크·로컬 MKV 재생 전 확인**: `MatroskaSubtitleProbe`가 .mkv/.webm 파일의 EBML `Tracks/TrackEntry/TrackType=17` 자막 트랙을 검사. 네트워크 영상 전체 다운로드 없이 **첫 최대 1MiB만 읽고 닫음**. 보이는 동영상에 한해 별도의 저우선순위 단일 작업자에서 탐지하며 NAS 폴더 수/목록 조회와 스레드를 분리. 같은 영상의 NAS 헤더는 앱 실행 중 중복 검사하지 않음. 로컬/SAF 영상은 기존 Android MediaExtractor 검사 후 해당 MKV 헤더로도 검사해 PGS/ASS 등에서 MediaExtractor가 놓친 트랙을 찾을 수 있음. 대용량 EBML 헤더/원격 미지원 포맷은 첫 1MiB 안에 Tracks가 없을 수 있으므로 **배지 없음은 내장 자막 없음의 확정이 아님**. 나중에 재생하면 Media3가 발견한 자막 양성 정보를 저장함.
3. **세션 폴더 캐시**: `FolderSessionCache`가 성공적으로 읽은 원격 폴더 목록과 각 하위 폴더의 항목 수를 앱 메모리에 보관. 폴더를 위아래로 이동하거나 스크롤하고 다시 돌아왔을 때 네트워크를 재조회하거나 '항목 수 확인 중...'을 반복 노출하지 않도록 하고, 캐시 항목은 화면에 즉시 표시. 세션 시작 시 비어 있으며 앱을 재실행하면 새로 조회. 서버 응답 오류는 캐시하지 않아 재시도 가능. 수동 새로고침·폴더 생성·파일 업로드·이름 변경·삭제 후 연결 단위 캐시를 무효화하고 다시 조회. 빠른 화면 이동 시 예전 비동기 응답이 현재 폴더를 덮어쓰지 않도록 화면 세대 검사 적용. 로컬/SAF 하위 항목 수도 동일한 실행 중 캐시를 사용, 파일 자체의 변경은 반영할 때만 재조회.
4. **길게 눌러 동영상 폴더로 설정**: 메인 저장소, SD/SAF, SMB/WebDAV/FTP/SFTP, 읽기 전용 클라우드까지 폴더 항목을 길게 누르면 **동영상 폴더로 설정 / 해제** 메뉴 제공. 폴더 자체와 모든 하위 폴더에서 SRT/SMI/ASS/SSA/VTT/SUB/SUP/IDX/TXT 자막 파일을 목록에서만 숨김. 파일 삭제 없음. 한 번 설정하면 전체 '자막 파일 숨기기' 스위치를 자동으로 활성화하고 기존 `FolderScopeFilter`의 경로 prefix와 폴더 경계를 활용. 같은 이름의 `Movies2` 형제 폴더에는 적용되지 않음. 상위 폴더에 이미 설정돼 있으면 하위 폴더도 자동 적용되고 별도 복잡한 경로 입력 불필요. 직접 등록 규칙을 해제하거나 설정 화면에서 예전 규칙을 삭제할 수 있음.
5. **설정 사용법 개선**: 자막 파일 숨기기 섹션에 '탐색기에서 폴더 길게 누르기' 안내 추가. 기존 수동 네트워크 경로 방식은 '고급'으로 안내를 내려 선택사항으로 유지. 외부 자막 표시/내장 자막 표시는 자막 파일을 탐색기에서 숨기더라도 그대로 보존.
6. 모든 v0.1.16 기능(다크모드, FFmpeg 네이티브 디코더 4 ABI 24개 .so, 동영상 스트리밍, 외부 자막 자동 연동, 자막 글꼴/크기, 이어보기, 볼륨/밝기, 오디오 디코더 정보, NAS 연결 프로필 등) 유지.

## 검증 및 실기기 확인

- 앱 패키지 `com.nasmount.mobile`, versionCode=18, versionName=0.1.17. 기존 v0.1.16과 동일한 앱 서명 및 연결/설정 유지. `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 검증.
- 순수 Java EBML 테스트와 **실제 FFmpeg로 생성한 MKV(자막 있음·없음)** 두 개의 테스트 파일을 포함해 트랙 감지 정확도를 확인. `FolderSessionCacheTest`로 최초 1회 조회, 재방문 캐시 반환, 새로고침 시 무효화, 실패 시 재조회 가능 확인. `FolderScopeFilterTest`에서 SD/SAF 경로 상속 및 SUP/IDX 확장자 확인.
- ADB로 연결된 Android 실기기 없음. 삼성 휴대폰의 NAS 접근·대용량 MKV 헤더 검사·자막 배지 표시 및 폴더 이동 스크롤 UX는 현장 검증 필요. 네트워크를 통해 추가로 읽는 메타데이터 양은 영상당 최대 1MiB이며 헤더에 Tracks가 없는 경우에는 실제 재생 후 다시 확인.
- NextLib FFmpeg는 GPLv3 라이선스이므로 제3자 배포 시 라이선스 준수·대응 소스 제공이 필요.

## 새 배포 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.17_SUBTITLE_CACHE_FOLDER.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.17_SOURCE.zip`
- 설명: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.17_README_KR.md`
- 체크섬: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.17_SHA256.txt`

---

## 이전 버전 v0.1.16 기록

## v0.1.16 변경 사항

1. **설정 → 인터페이스 탭과 다크모드**: 설정 최상단에 카테고리 탭(인터페이스 / 동영상 / 자막 / 코덱 / 일반)을 추가하여 해당 설정 섹션으로 이동. 인터페이스의 화면 테마에서 **시스템 설정에 맞춤 / 라이트 모드 / 다크 모드** 중 선택 가능. 기본은 기존 UI와 동일한 라이트. 선택값은 `appearance` SharedPreferences에 저장되어 다음 실행에도 유지. 파일관리자 홈/파일 목록/원격 저장소/사이드메뉴/설정 카드의 배경·텍스트·구분선·버튼 색상과 Android 시스템 탐색바 대비까지 테마에 맞춤. 동영상은 기존 검은색 몰입형 플레이어 화면을 유지하고 대화상자 테마만 선택값을 따름.
2. **동일 이름 외부 자막 자동 표시**: 로컬/SD SAF/원격 NAS 폴더 목록을 가져올 때 이미 받은 **같은 폴더의 파일 이름만** 검사하여 `영화명.srt`, `영화명.ko.srt`, `영화명.en.ass`, `영화명.ja.vtt`, `영화명.ko-KR.smi`처럼 동영상과 이름이 일치하는 자막을 검출. 동영상 목록의 파일 용량 옆에 `외부 자막 SRT/ASS` 등으로 표시. SRT, SMI, VTT, ASS, SSA, SUB, SUP, IDX, TXT 확장자 대상. 자막 파일 숨김 기능을 켜도 폴더 스캔에서 인식되므로 영상의 자막 표시 유지.
3. **내장 자막 표시**: 로컬 파일/SAF에서 스크롤에 보이는 동영상은 백그라운드 MediaExtractor가 인식하는 자막 트랙을 메타데이터 검사하여 실제로 발견되면 `내장 자막` 표시. 네트워크 영상은 목록마다 대용량 파일을 랜덤 읽지 않고 **실제로 재생했을 때 Media3가 영상 내 텍스트 트랙을 확인한 경우에만** SHA256 영상 식별키로 앱 내부에 기록하고 재생 종료 후 목록에 표시. 재생 중 외부 SRT/VTT 사이드카를 별도로 불러온 경우 내장 자막으로 오인하지 않도록 구분. FFmpeg/NextLib 지원 MKV 자막 중 일부는 Android MediaExtractor가 목록에서 인식하지 못할 수 있으므로 **표시가 없더라도 내장 자막이 없다는 확정은 아님**.
4. **폴더 성능 보호**: NAS 외부자막 표시는 이미 수신한 자식 파일명으로 계산해 추가 서버 요청이 없음. 로컬 내장자막 검사만 보이는 영상에 한해 백그라운드로 진행. 동영상 전체 다운로드 없음. 목록/격자/자세히 보기 모두 적용. 기존 다국어 선택/영상별 자막 크기·TTF 설정은 유지.
5. 기존 Media3/NextLib FFmpeg(4 CPU ABI, 네이티브 라이브러리 24개), 스트리밍, 자동 회전, 2초 잠금, 밝기/볼륨, 이어보기, 오디오 코덱 자동 표시, 코덱 파일 가져오기, 연결 프로필 보존.

## 검증 및 제한

- 패키지 `com.nasmount.mobile`, versionCode=17, versionName=0.1.16. 기존 앱 v0.1.15와 동일한 키로 서명하여 덮어 설치 가능하며 기존 NAS 연결과 설정 유지.
- Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 검증. 신규 `AppearanceSettingsTest`와 `SubtitleInventoryTest`로 테마 모드/외부 자막 이름 매칭/지원 자막 MIME을 확인.
- Android 실기기/에뮬레이터가 개발 PC에 연결되지 않아 삼성 Android의 다크모드 레이아웃 및 실제 로컬/원격 자막 배지 UI 확인은 남아 있음. Native extractor가 자막 형식을 지원하지 않으면 재생 후 Media3 확인 기록으로 보완.
- NextLib GPLv3 라이선스와 제3자 배포 시 소스 공개 의무는 이전 버전과 동일.

## 릴리스 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.16_DARK_SUBTITLE_BADGES.apk`
- SOURCE: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.16_SOURCE.zip`
- README: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.16_README_KR.md`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.16_SHA256.txt`

---

## 이전 버전 v0.1.15 기록

## v0.1.15 변경 사항

1. **가짜 EAC3 버튼 제거, 현재 오디오 코덱 자동 감지**: v0.1.14까지 상단에 모든 영상마다 고정으로 표시되던 `EAC3` 텍스트를 제거. 이제 Media3 `getAudioFormat()`와 `getCurrentTracks()`에서 실제 선택된 오디오 트랙의 형식(`Format.sampleMimeType`과 `Format.codecs`)을 읽어 AAC, AC3, E-AC3, E-AC3 Atmos, Dolby TrueHD, DTS, DTS-HD, FLAC, ALAC, Opus, Vorbis, PCM 등으로 표시. 준비 중에는 '오디오 확인 중', 실제 오디오 트랙이 없는 영상에는 '오디오 없음' 표시. 파일 이름만으로 코덱을 추측하지 않음.
2. **버튼 터치 시 실제 오디오 정보 확인**: '현재 영상 오디오 정보' 창에서 코덱/Media3 MIME/코덱 식별자, 채널 수(모노/스테레오/5.1/7.1), 샘플레이트(예: 48kHz), 알려진 경우 평균 오디오 비트레이트, 언어를 표시. 값이 없는 경우 '정보 없음'으로 안내.
3. **현재 사용 중인 디코더 감지**: Media3 `AnalyticsListener.onAudioInputFormatChanged`, `onAudioDecoderInitialized`, `onAudioDisabled` 이벤트를 구독. **실제로 초기화된 디코더의 이름**을 확인해 Android MediaCodec(`c2.*`, `OMX.*`)와 NextLib FFmpeg(`ffmpeg*`)를 구분하고 원래 디코더 이름도 표시. 아직 디코더 초기화 이벤트가 없는 경우 '확인 중'으로 표시. APK에 FFmpeg가 포함되어 있다는 이유만으로 FFmpeg가 재생 중이라고 허위 표시하지 않음. 재생 중 오디오 트랙 변경에 따라 버튼도 갱신.
4. **기존 기능 모두 유지**: NextLib FFmpeg 1.11.1-0.18.0, WebDAV/SMB/FTP/SFTP/TeraBox/MYBOX 구간 스트리밍, 가로·세로 원본비율 자동 회전, 상단/잠금버튼/하단 컨트롤 페이드 동기화, 2초 터치 잠금, 밝기/볼륨 스와이프, 이어보기, 자막 언어/영상별 글꼴·크기 설정, 코덱 폴더 가져오기.

## 테스트 및 제한

- 앱 패키지 `com.nasmount.mobile`, versionCode `16`, versionName `0.1.15`. v0.1.14와 동일한 서명으로 데이터 유지하면서 덮어 설치 가능.
- Android Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 성공. 신규 `AudioPlaybackLabelsTest` 4건을 포함한 전체 테스트 63건.
- 개발 PC에 Android 기기/에뮬레이터 연결이 없으므로 실물 휴대폰에서 실제 AAC, E-AC3, DTS, TrueHD, FFmpeg 폴백 디코딩 시 상단 코덱 명칭과 디코더 이름이 각각 올바르게 갱신되는지는 추가 현장 확인 필요.
- NextLib FFmpeg는 GPLv3이며 제3자에게 배포할 때 대응 소스 공개와 라이선스 준수 필요. `THIRD_PARTY_LICENSES/NextLib_GPL-3.0.txt` 보존.

## 새 배포 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.15_AUDIO_CODEC_INFO.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.15_SOURCE.zip`
- README: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.15_README_KR.md`
- 체크섬: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.15_SHA256.txt`

---

## 이전 버전 v0.1.14 기록

## v0.1.14 변경 사항

1. **실제 FFmpeg 디코더 포함**: 기존 Media3 1.11.1과 호환되는 Maven Central 배포본 `io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.18.0`을 Android Gradle에 연결. `NextRenderersFactory` + `EXTENSION_RENDERER_MODE_ON`으로 재생기 생성 방식을 변경하여 **기기 기본 MediaCodec 우선**, 지원되지 않는 오디오/비디오 형식은 내장 FFmpeg 확장에 자동 할당되도록 설정. WebDAV/SMB/FTP/SFTP/TeraBox/MYBOX에서 이미 사용 중인 `VideoStreamDataSource` 및 구간 스트리밍은 유지.
2. **지원하는 소프트웨어 디코더**: NextLib 공식 지원 목록에 따라 오디오 Vorbis/Opus/FLAC/ALAC/PCM μ-law/A-law/MP3/AMR/AAC/AC-3/E-AC3/DTS(dca)/MLP/Dolby TrueHD, 비디오 H.264/HEVC/VP8/VP9/AV1(dav1d) 지원. `FfmpegSubtitleExtractorsFactory`와 `FfmpegSubtitleSourceKt.withFfmpegSubtitles`도 연결해 MKV 내장 ASS/SSA, PGS, VobSub, DVB 같은 NextLib 전용 자막 추출 및 디코딩 경로를 활성화. 단, ASS/PGS 등 스타일/비트맵 자막은 SRT 텍스트 자막처럼 TTF 글꼴·크기를 자유롭게 변경할 수 없고 렌더러가 원본으로 그릴 수 있음. 모든 존재 가능한 파일 형식·스트리밍 서버를 보장한다는 뜻은 아님. 오디오 디코딩은 일반적으로 PCM 출력이며, TrueHD Atmos 비트스트림 패스스루와 별개의 기능. 안드로이드 기기가 특정 코덱을 제공하지만 그 디코더가 실제 초기화 중 오류를 내는 경우에는 별도 오류 복구가 필요할 수 있음.
3. **FFmpeg 네이티브 라이브러리 4개 ABI 번들**: APK 내 `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` 각각 `libavcodec.so`, `libavutil.so`, `libmedia3ext.so`, `libass.so`, `libswresample.so`, `libswscale.so` 포함. APK 파일 안에 총 24개 네이티브 라이브러리 확인. 따로 외부에서 .so 파일을 설치하지 않아도 이 빌드의 내장 FFmpeg 자동 선택 가능.
4. **외부 코덱 폴더를 선택하면 NAS Mount 전용 폴더로 가져오기**: Android SAF에서 외부 폴더를 고르면 `.so`/일부 MX `.so.*`/`.aar`/`.zip` 파일을 앱 소유 `Android/data/com.nasmount.mobile/files/NASMount/codecs` 폴더(실제 위치는 기기마다 다를 수 있음)로 복사. 복사 직후 원본/대상 SHA-256을 비교하고 공급자가 원본 삭제 권한을 허용하면 **이동** 완료, 삭제 권한이 없으면 원본은 남기고 **복사** 완료로 안내. 사용자 파일 삭제/변경 실패를 방지하도록 파일 크기/개수 제한, 안전한 이름 정규화, 중복 파일명 분리. 파일을 실제로 추출하거나 원격 코드를 실행하지 않음.
5. **외부 MX Player 파일 처리**: MX Player 전용 `libffmpeg.mx.so`는 NextLib/Media3 JNI 규격과 맞지 않으므로 가져온 파일을 **저장만** 하고 실행하지 않음. 자동 디코더 대체는 앱에 내장하여 서명한 NextLib FFmpeg를 사용함. 외부 파일만 갖다 놓아서 제3자 임의 디코더를 런타임 활성화하는 기능은 아님.
6. **제목바·자물쇠 2초 늦게 사라지는 문제 보완**: Media3의 최상위 `exo_controller`는 하단 컨트롤을 먼저 페이드 아웃시키고 나중에 GONE으로 바뀌는 구조. `exo_bottom_bar`의 실제 alpha와 visibility를 `OnPreDrawListener`로 화면 프레임마다 추적하고, 상단 제목바와 잠금 버튼의 alpha를 그대로 맞추도록 변경. 기존처럼 늦게 도착하는 컨트롤 종료 이벤트를 기준으로 상단을 지연 숨기지 않음. 잠금 상태의 별도 잠금 해제 버튼은 이전 기능 유지.
7. 기존 영상별 자막 크기/언어 선택, TTF 폰트, 원본 비율 자동 회전, 2초 잠금, 밝기/볼륨 조절, 이어보기, 로컬/원격 파일 탐색 및 사용자 연결 정보는 유지.

## 라이선스 / 배포 유의사항

- **NextLib은 GPLv3**: https://github.com/anilbeesetti/nextlib. 이 앱과 결합하여 제3자에게 배포할 때는 해당 라이선스 조건(대응 소스코드 제공 등)을 준수해야 함. 이 빌드는 개인 테스트 목적이며, 라이선스 고지 없이 상용/비공개 바이너리로 재배포하면 안 됨.
- `THIRD_PARTY_LICENSES/NextLib_GPL-3.0.txt`에 NextLib 라이선스 사본을 보관. NextLib APK에 들어 있는 `assets/native-dependencies/`에도 FFmpeg와 네이티브 의존성의 라이선스 고지가 포함됨.
- NextLib의 FFmpeg 디코더가 지원하는 파일과 Android Media3의 컨테이너/스트리밍 지원은 서로 다를 수 있음. E-AC3·TrueHD·DTS 실기기 샘플 재생 검증은 추가로 필요.

## 검증 / 설치

- 앱: `com.nasmount.mobile`, versionCode `15`, versionName `0.1.14`. 기존 서명 키를 사용하며 v0.1.13에 덮어 설치 가능.
- Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 최종 검증. 기존 56개 테스트와 코덱 파일 필터/안전한 파일명 테스트 3개 추가.
- Android 실기기/에뮬레이터가 개발 PC에 연결되지 않아 실제 E-AC3·TrueHD·DTS 오디오 출력, 자동 전환, 제목/잠금 동기 페이드, SAF 원본 파일 이동은 휴대폰에서 확인할 필요 있음. Android 빌드 성공만으로 모든 디코더 재생 성공을 주장하지 않음.

## 배포 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.14_FFMPEG_ALL_CODECS.apk`
- SOURCE: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.14_SOURCE.zip`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.14_SHA256.txt`

---

## 이전 버전 v0.1.13 기록

## v0.1.13 업데이트

1. **상단 제목·자물쇠·하단 재생바 동시 표시/숨김**: v0.1.12에서는 제목·자물쇠를 플레이어 위의 별도 뷰로 유지하고 Media3의 최종 visibility 콜백을 받은 후 별도 페이드 처리했으므로 영상 컨트롤 사라짐 뒤 최대 약 2초 지연이 생겼다. 새 버전에서는 제목바와 잠금버튼을 Media3 기본 exo_controller 뷰 안으로 이동시켰다. 컨트롤 부모가 동일하므로 기본 하단바의 표시·페이드·숨김과 함께 나타나고 사라지며, 후행 타이머를 사용하지 않는다. 잠금 상태에서만 최상위 뷰에 독립된 해제 버튼을 표시한다. 컨트롤 뷰를 찾지 못하는 커스텀 테마 환경에서는 기존 콜백을 폴백으로 유지한다.
2. **내장 자막 다국어 선택**: 재생 화면 '자막 → 내장 자막 / 언어 선택'에서 Media3가 인식한 자막 TrackGroup의 각 언어·트랙 표시(한국어/영어/일어 등). 선택시 TrackSelectionOverride로 해당 트랙만 활성화. 사용 가능한 트랙이 없다면 안내. '자동 (기본 언어)' / '자막 끄기' 선택 가능. 외부 SRT/VTT를 별도로 수동 선택·제거하는 메뉴도 유지. 내부 트랙에 지원되지 않은 형식은 메뉴에 표시되지 않는다. 언어 코드 ko/kor, en/eng, ja/jpn, zh/zho/chi 등을 표시 라벨로 변환.
3. **영상별 자막 크기**: '자막 → 자막 크기 (이 영상에 저장)'에서 12~48sp 크기 선택. 로컬 파일 URI 또는 NAS 연결+경로+크기의 기존 SHA256 영상 식별값을 key로 앱 내부에 저장하며, 같은 영상을 다시 열면 해당 크기 재적용. '기본값 사용'을 선택하면 해당 영상의 전용 값을 삭제하여 메인 설정값을 사용. SubtitleView의 내장 폰트 크기 적용을 끄고 사용자 크기 우선 적용.
4. **메인 설정 → 자막 기본 설정**: 기본 자막 크기(초기값 20sp), 기본 언어(초기값 **한국어 ko**) 선택 가능. 자막 언어는 한국어·English·日本語·中文·스페인어·프랑스어·독일어·러시아어·포르투갈어·힌디어·인도네시아어·태국어·베트남어·미상 중 선택. 새 영상에서 자동 트랙 선택시 Media3 preferredTextLanguage로 지정; 영상에 없는 언어를 강제로 생성하지 않는다.
5. **외부 코덱 도움말 명확화**: USBhost/MX_FFmpeg GitHub의 2025-06-19 릴리스 1.90.1v2에는 MX Player 전용 `libffmpeg.mx.so.*`가 제공된다. 이 파일은 MX Player가 사용하는 전용 인터페이스용 바이너리이며 Media3/ExoPlayer가 직접 읽는 디코더 모듈이 아니다. 코덱 폴더를 지정해도 자동 활성화/로드되지 않는다. Media3용 FFmpeg Java Renderer + JNI 연결부와 기기별 ABI에 맞게 빌드하여 APK에 포함해야 E-AC3/TrueHD 지원을 검증할 수 있다. 새 설정에서는 기기가 제공하는 기본 E-AC3와 TrueHD 디코더 존재 여부를 별도로 표시한다. **외부 MX .so를 무리하게 로드하거나 배포 APK에 포함하지 않음**. E-AC3/TrueHD 확장 지원은 아직 구현되지 않았음.
6. 기존 자동 화면 회전(가로·세로 실제 원본 크기), 수동 화면 전환, 2초 터치잠금, 볼륨/밝기 세로 스와이프, 이어보기 위치저장, 구간 스트리밍, 외부 SRT/VTT·TTF 설정, 폴더 숨기기 모두 유지.

## 릴리스 검증

- 앱 ID `com.nasmount.mobile`, `versionCode=14`, `versionName=0.1.13`. 기존 버전과 동일한 서명으로 덮어 설치 가능.
- `testDebugUnitTest`, `lintDebug`, `lintVitalRelease`, `assembleRelease` 검증, 기존 테스트 + SubtitlePreferencePolicyTest.
- 실제 삼성 Android 기기 미연결이므로 내장 다중 자막 MKV 샘플 실제 트랙 전환과 UI 동시 페이드, 영상별 글꼴 크기 표시·저장에 대한 실기기 검증은 남음.
- 참조: https://github.com/USBhost/MX_FFmpeg/releases/tag/1.90.1v2

## 배포

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.13_SUBTITLES_SYNC.apk`
- 소스: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.13_SOURCE.zip`
- 설명/체크섬: `NASMount_Android_v0.1.13_README_KR.md`, `NASMount_Android_v0.1.13_SHA256.txt`

---

## 이전 버전 v0.1.12 기록

## v0.1.12 주요 변경 사항

1. **동영상 터치 잠금 3초 → 2초**: 2000ms 연속 누름으로 화면 잠금/잠금 해제. 경계 타이밍 단위 테스트 변경. 기존 자물쇠 버튼 잠금 및 화면 터치→잠금 해제 버튼 표시 방식은 그대로 유지.
2. **재생 컨트롤 동기 숨김**: Media3 플레이어 컨트롤의 가시성 콜백 하나로 위쪽 영화 제목/자막/EAC3/화면방향 바와 오른쪽 아래 자물쇠 버튼을 동시에 200ms 페이드로 숨김·표시. 잠금 상태에서는 상단바가 표시되지 않고 잠금 해제 버튼만 필요할 때 잠깐 표시.
3. **화면 세로 스와이프 조절**: 영상 화면 **왼쪽 중간 영역 위/아래** 스와이프로 화면 밝기 높이기/낮추기(해당 재생 Activity 창에만 적용, Android 전체 시스템 밝기를 변경하지 않음). **오른쪽 중간 영역 위/아래** 스와이프로 Android 음악/미디어 볼륨 높이기/낮추기. 조절 중 % 표시, 손을 떼면 자동 사라짐. 화면 윗쪽 제목과 아래 재생 컨트롤 영역은 제스처에서 제외해 기본 컨트롤과 충돌을 줄이고, 수평 동작은 좌/우 더블탭 탐색과 별개로 처리. 잠금 시 스와이프 조작은 차단.
4. **영상별 시청 지점 기억과 이어보기**: 로컬 파일 URI 또는 원격 NAS 연결 ID+상대 경로+파일 크기로 영상을 구분하고 SHA-256 해시만 기기 내부 SharedPreferences(`video_watch_progress`)의 키로 저장. 원격 비밀번호나 경로를 저장하지 않음. 재생 중 약 5초마다, 앱 화면 일시정지/종료할 때 재생 위치와 길이를 기록. 다시 열었을 때 10초 이상 시청했고 종료 직전이 아닌 영상은 **“이어서 재생할까요?”** 창을 열어 '이어보기'와 '처음부터' 중 선택. 선택 전 자동 재생은 멈추어 두고, 이어보기 선택 시 기록 위치부터 재생. 끝까지 본 영상 및 마지막 부근 기록은 지우며 처음부터 재생하도록 처리.
5. 기존 **원본 영상 가로/세로 해상도 기준 자동 회전**, 수동 회전 버튼, WebDAV/SMB/FTP/SFTP/TeraBox/MYBOX 구간 스트리밍, SRT/VTT 자막, TTF 글꼴 선택, NAS 파일 탐색, 코덱 폴더 표시 등의 기능은 그대로 유지.

## 설치와 검증

- 앱 ID `com.nasmount.mobile`, `versionCode=13`, `versionName=0.1.12`. 기존 v0.1.11 동일 릴리스 서명 사용, **앱을 삭제하지 않고 덮어 설치**. 연결 계정과 사용자 설정은 기존대로 유지.
- Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease`를 실행. 추가된 순수 로직 테스트는 밝기/볼륨 제스처 구분·차단/클램프, 재생 이어보기 조건, 2초 터치 잠금 경계값.
- 실제 Android 실기기나 에뮬레이터가 개발 PC에 없어 구간 스트리밍 중에 손가락으로 밝기·볼륨 스와이프, 영화 종료 후 이어보기 다이얼로그의 화면 표시, 상/하단 애니메이션 일치 여부는 휴대폰 검증 필요.
- E-AC3 FFmpeg ABI별 `.so`는 기본 APK에 포함되지 않음. 기본 Android 디코더 사용.

## 새 릴리스 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.12_GESTURES_RESUME.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.12_SOURCE.zip`
- 설명/해시: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.12_README_KR.md`, `NASMount_Android_v0.1.12_SHA256.txt`

---

## 이전 버전 v0.1.11 기록

## v0.1.11 주요 변경

1. **영상 원본 크기 기준 자동 화면 방향**: 앱을 실행한 직후에는 세로로 시작한 다음 Media3가 실제 디코딩된 VideoSize를 알리면 영상의 표시 폭·높이, 회전 메타데이터(0/90/180/270), 픽셀 가로세로비를 반영해 방향을 결정. 1920x1080 및 3840x2160 등 가로 영상은 가로(Landscape) 전체화면, 1080x1920 및 720x1280 쇼츠, 1:1 정사각형 영상은 세로(Portrait) 유지. 휴대폰의 자동 회전 잠금 상태와 무관. 영상 크기가 아직 없으면 세로 유지; 재생이 시작되어 크기가 확인된 시점에 자동 방향 변경. 사용자가 상단 '가로/세로' 버튼으로 수동 변경하면 이후 VideoSize 이벤트가 이를 다시 뒤집지 않음. 영상 재생/탐색은 기존 Media3 인스턴스를 유지.
2. **재생 컨트롤과 함께 나타나는 '🔒 잠금' 버튼**: 영상 화면 오른쪽 아래(기본 플레이바 위) 터치 영역 48dp 크기. 컨트롤을 열면 나타나며 누르면 바로 화면 터치 잠금. 재생 컨트롤·타임라인·두 번 탭 탐색·제목 버튼을 더 이상 누를 수 없고 영상/음성은 계속 재생.
3. **잠금 중 원터치 해제 진입**: 잠긴 화면을 한 번 터치하면 '🔓 잠금 해제' 버튼이 3.5초간 나타남. 해당 버튼을 터치하면 즉시 잠금 해제. 잠긴 화면에서 3초 길게 누르기로도 잠금 해제 가능.
4. **기존 3초 길게 누르기 잠금·해제 유지**: 짧은 탭, 스와이프, 멀티터치는 잠금 조건에서 제외하고, 자물쇠 버튼은 길게 누르기 처리와 독립적으로 작동하므로 제스처가 기기에서 불안정한 경우에도 확실한 수동 대체 수단 제공.
5. Android 프로젝트 versionCode=12, versionName=0.1.11. 기존 com.nasmount.mobile 앱에 덮어 설치 가능하며 기존 연결·설정·폰트 데이터는 보존.

## 검증 및 제한

- 기존 테스트에 VideoOrientationPolicyTest(영상 표시방향 6건)와 잠금 버튼 상태 테스트(1건) 추가. Android Gradle testDebugUnitTest, lintDebug, lintVitalRelease, assembleRelease 검증.
- 연결된 Android 휴대폰/에뮬레이터가 없으므로 Samsung 실제 단말의 가로/세로 전환 타이밍 및 터치 인터셉트 실사용 검증은 남아 있음. 자동 테스트만으로 단말 현장 동작까지 보장하지 않음.
- 동영상 전체 다운로드 없는 네트워크 구간 스트리밍, EAC3 기본 디코더/선택형 FFmpeg 애드온 안내, 자막 SRT/VTT 및 TTF 글꼴 설정, 로컬/네트워크 자막파일 숨김 규칙은 그대로 유지.

## 새 릴리스

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.11_ASPECT_AUTO_LOCK_BUTTON.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.11_SOURCE.zip`
- README/SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.11_README_KR.md`, `NASMount_Android_v0.1.11_SHA256.txt`

---

## 이전 버전 v0.1.10 기록

## v0.1.10 변경 사항

- **영상 재생은 휴대폰 자동 회전 잠금 상태와 무관하게 가로(Landscape) 전체화면으로 시작.** 이전 v0.1.9에 `SCREEN_ORIENTATION_PORTRAIT`가 지정돼 있어 수평 16:9 영화가 세로폰 중앙에 작은 띠로 보이던 문제 수정. AndroidManifest와 Activity 모두 `LANDSCAPE` 명시. Android의 시스템 자동 회전 옵션을 변경하거나 접근 권한을 요청하지 않음.
- **수동 화면방향 전환 버튼**: 플레이어 상단 제목·자막·EAC3 버튼 뒤에 '세로' 또는 '가로' 버튼 추가. 화면 고정을 임시로 수동 변경할 수 있으며 영상 재생/탐색 위치는 그대로 보존. Activity의 `orientation|screenSize|keyboardHidden` Configuration 변경을 직접 처리하여 플레이어를 다시 생성하지 않음. 동영상 종료 시 파일 관리자는 원래 방향으로 유지.
- **메뉴 설정 버튼 네비게이션 영역 가림 수정**: 앱 왼쪽 슬라이드 ScrollView의 아래쪽 여백을 시스템 NavigationBar 높이 + 12dp만큼 확보. Android WindowInsets 변경도 감지하여 동적으로 갱신. 기존 ScrollView/슬라이드 애니메이션, 즐겨찾기 및 연결 메뉴 유지. 설정 버튼이 시스템 내비게이션 버튼과 겹치지 않게 조정.
- **영상 3초 길게 누르기 터치 잠금**: 화면 아무 곳이나 약 3초 동안 손가락을 움직이지 않고 누르면 잠금. 다시 약 3초 누르면 해제. 잠금 중에는 PlayerView 자식 컨트롤·탐색바·탭/더블탭·상단 버튼으로 입력이 전달되지 않음. 상태 안내가 잠깐 나타나며 비디오 재생은 계속됨. 짧은 터치·드래그·여러 손가락 입력은 잠금 조건에서 제외. 정확히 3초에 손을 떼면 최종 시간 검사로 누락되지 않도록 보완. 시스템 홈/뒤로가기 제스처 등 Android OS 자체 제스처는 차단하지 않음.
- 기존 NAS 스트리밍, Google Drive Android 제외, TeraBox 세션 로그인, SRT/VTT 자막, TTF 글꼴 선택, 코덱 폴더 설정 및 목록 표시, 파일명/정렬/숨김 기능 등 v0.1.9 기능 보존.

## 빌드 / 검증

- Android `com.nasmount.mobile` `versionCode=11` `versionName=0.1.10`.
- `testDebugUnitTest`, `lintDebug`, `lintVitalRelease`, `assembleRelease` **BUILD SUCCESSFUL**. JUnit **38/38 success** (기존33 + 3초 잠금 경계조건·잠금/해제·스와이프/떨림 테스트 5개). 기존 서명키로 APK 서명.
- 개발 PC에 실제 Android 휴대폰/에뮬레이터 연결이 없으므로 Samsung 회전 잠금 상태에서 강제 가로 전환·터치 잠금·하단 버튼 위치는 실제 휴대폰에서 시험이 필요함. 코드/자동화 검증 통과가 실기기 검증까지 완료됐다는 뜻은 아님.
- FFmpeg E-AC3 확장 바이너리 `.so`는 이번 릴리스에도 미포함. 기본 Android 디코더 사용.

## 배포 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.10_LANDSCAPE_TOUCH_LOCK.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.10_SOURCE.zip`
- 설명/해시: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.10_README_KR.md`, `NASMount_Android_v0.1.10_SHA256.txt`

---

## 이전 버전 v0.1.9 기록

## v0.1.9 주요 업데이트

1. **동영상 세로 전체화면**: 동영상 열기 시 플레이어 Activity를 세로(Portrait)로 고정하고 시스템 상태바·내비게이션바는 몰입형으로 숨김. 파일 관리자 방향은 변경하지 않음.
2. **동영상 상단 제목·메뉴**: 재생 영상을 가리지 않도록 상단 제목·뒤로가기·자막·EAC3 버튼을 22dp 아래로 옮겨 영상 위에 오버레이로 배치. Media3 재생 컨트롤이 숨겨지면 함께 숨겨지고 다시 컨트롤을 표시하면 나타남. 하단의 고정 'NAS 구간 스트리밍' 안내 글씨는 제거.
3. **좌우 두 번 탭하기**: 영상 영역 오른쪽에서 더블탭하면 +N초, 왼쪽에서 더블탭하면 -N초 구간 이동. 짧은 시간 안내를 표시하며 재생 컨트롤의 기본 탭/타임라인 조작은 유지. 설정에서 5·10·15·20·30·60초 중 선택(기본 10초).
4. **화면비 설정**: 영상 전체 보기(비율 유지), 확대해서 꽉 채우기(비율 유지·일부 잘림), 화면 강제 채우기(비율 변형) 3종. Media3 PlayerView 비율 조절 사용.
5. **왼쪽 메뉴 하단 설정**: 톱니바퀴 아이콘과 '설정' 항목을 추가. 설정 Activity는 '동영상 플레이어', '자막 글꼴', '자막 파일 숨기기', '코덱 · 호환성', '일반' 항목으로 카테고리 분리하여 앞으로 추가 기능을 확장할 수 있도록 구성.
6. **자막폰트 가져오기 및 목록 선택**: Android 파일 선택기에서 .ttf 글꼴 가져오기. 16MB 제한, TTF 헤더 및 Typeface 열기 가능 여부 확인 후 앱 내부 비공개 저장소 `files/subtitle_fonts`에 복사, 등록된 글꼴을 모두 리스트에 표시하고 글꼴별 즉시 선택 가능. 기본 글꼴 복귀 버튼·한글/영어 미리보기 제공. 선택 폰트는 Media3 SubtitleView(외부 SRT/VTT 및 지원되는 자막)의 표시 스타일에 적용. 원본 TTF를 재배포하지 않음.
7. **자막/TXT 파일 숨김 폴더 관리**: SRT·SMI·VTT·ASS·SSA·SUB·TXT 확장자 파일을 사용자가 등록한 폴더 및 하위폴더의 목록에서만 숨김. 휴대폰/SD는 Android 폴더 선택기, 네트워크는 연결 선택 및 폴더 경로 지정, 또는 현재 열었던 폴더 원클릭 등록. 설정에서 등록 폴더를 확인/해제할 수 있고 원본 파일은 삭제하거나 이동하지 않음. 자막 자동 탐색은 계속 가능. 설정 저장 후 파일 목록 갱신.
8. **코덱 애드온 보관 폴더 선택**: Android 폴더 선택기에서 FFmpeg 등 확장 코덱 파일을 보관할 경로 저장. 선택한 폴더 내 .aar/.so 후보 파일 이름과 개수, 기기 기본 ABI(`Build.SUPPORTED_ABIS[0]`), Media3 1.11.1 호환 대상, 기기 E-AC3 기본 디코더 여부 표시. **경로 지정과 파일 발견은 코덱 설치/활성화를 의미하지 않음.** 호환되는 FFmpeg Java 렌더러+ABI별 네이티브 라이브러리를 검증하고 앱을 재빌드해야 함. 검증되지 않은 외부 네이티브 파일을 실행 중 동적으로 로드하지 않음. GPLv3 NextPlayer/NextLib 바이너리 무단 포함 없음.
9. 이전 v0.1.8의 **WebDAV/SMB/FTP/SFTP/클라우드 원격 스트리밍, SRT/VTT 자막 선택·자동 탐색, Google Drive Android 제외, TeraBox ndus** 등은 그대로 유지.

## v0.1.9 설치 및 시험

- Android 패키지: `com.nasmount.mobile`, `versionCode=10`, `versionName=0.1.9`. v0.1.8과 동일한 릴리스 서명 인증서로 덮어 설치(앱 삭제 금지).
- Gradle `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleRelease` 검증. 단위 테스트는 33건(기존29+폴더 숨김 범위/확장자4건)이며 오류/실패 없음.
- 실기기/에뮬레이터가 연결되지 않아 실제 화면의 제스처, 22dp 타이틀 간격, SAF 폴더 접근, TTF 렌더링 및 실서버 재생은 실제 휴대폰 확인이 필요함.
- E-AC3는 여전히 Android 기기 기본 디코더에 의존하며 **FFmpeg .so 바이너리는 기본 APK에 포함되지 않음**.

## v0.1.9 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.9_PLAYER_SETTINGS_FONTS.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.9_SOURCE.zip`
- 파일 체크섬: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.9_SHA256.txt`

---

## 이전 버전 v0.1.8 기록

## v0.1.8 변경 사항

- 파일 리스트의 두 번째 줄: **파일 용량은 왼쪽**, **최종 수정 날짜(yyyy. M. d.)는 오른쪽 작은 글씨(10.5sp)**로 나눠 표시. 파일 이름 줄 높이는 유지. 생성일은 서버별 제공 여부가 달라 최종 수정 날짜로 통일.
- 동영상을 누르면 외부 플레이어 선택기나 전체 파일 다운로드 없이 **NAS Mount 내장 Media3(ExoPlayer) 플레이어**로 이동. Android 내장 저장소, SD/SAF, MediaStore, SMB, FTP, SFTP, WebDAV, TeraBox 및 MYBOX 경로 지원을 위한 진입점 추가.
- 원격 동영상 데이터 소스는 재생 시 필요한 바이트만 읽음. SMB는 랜덤액세스, FTP는 REST 오프셋, SFTP는 파일 오프셋, WebDAV는 HTTP Range, TeraBox/MYBOX는 검증된 HTTPS 다운로드 링크의 HTTP Range 사용. **영상 전체를 임시 저장하지 않음.** 탐색 시 Media3가 새 바이트 범위 연결을 염. 서버/CDN의 Range 미지원 시 탐색이 실패할 수 있음.
- 자막 **SRT / WebVTT**: 상단 자막 버튼에서 Android 문서 선택기로 수동 선택하거나 해제 가능. 같은 폴더의 `동영상파일명.srt/.vtt`가 있으면 자동으로 탐색(원격 자막은 2MiB 이하만 임시 캐시). Media3가 지원하는 내장 트랙은 플레이어의 기본 트랙 선택 UI를 활용. ASS/SSA의 스타일을 100% 지원한다고 주장하지 않음.
- 기본 E-AC3 코덱은 안드로이드 장치의 디코더를 사용. 필요 시 **Media3 FFmpeg Audio Renderer + 해당 CPU ABI용 .so를 포함한 검증된 AAR**을 `app/codec-addons/media3-ffmpeg-eac3.aar`에 놓고 APK를 다시 빌드하면 선택적으로 포함. 설치 중에 임의 .so 파일을 직접 로딩하거나 외부 소스의 바이너리를 무검증 실행하지 않음. **이 기본 릴리스 APK에 FFmpeg/E-AC3 확장 바이너리는 포함되지 않음.** 추가 방법은 `app/codec-addons/README_KR.md` 참조.
- Next Player 및 NextLib의 구조·UI·코덱 방향을 검토했지만 두 저장소는 **GPLv3**이므로 해당 프로젝트의 소스와 바이너리를 이번 APK에 직접 포함하지 않음. 참고: https://github.com/anilbeesetti/nextplayer 및 https://github.com/anilbeesetti/nextlib. 현재 구현은 AndroidX Media3와 독자적인 NAS 스트리밍 어댑터임.
- 앱 ID `com.nasmount.mobile`, versionCode=9, versionName=0.1.8; 이전 v0.1.7과 동일한 릴리스 서명 유지. 저장한 NAS 프로필·테라박스 ndus 등은 변경하지 않음.

## 기기 확인이 필요한 항목

1. WebDAV에서 수 GB MP4/MKV 영상을 클릭했을 때 바로 내장 플레이어가 열리고 전체 다운로드 대기 없이 재생하는지.
2. 영상 탐색바를 중간으로 이동한 뒤 서버가 `Range`를 지원하면 새 구간에서 재생되는지. WebDAV 단위 테스트는 206 Range/200 Range 미지원 처리까지 확인.
3. 같은 이름의 .srt/.vtt 자막 자동 적용 및 수동 선택, 한글 표시. 플레이어에서 복귀해 목록이 유지되는지.
4. E-AC3 음성은 기기 디코더가 있을 경우에만 기본 APK에서 가능. 미지원 기기는 호환 FFmpeg 애드온을 별도 구성·빌드한 뒤 검증 필요.
5. 연결된 Android 실기기/에뮬레이터가 개발 PC에 없어 실제 Samsung 기기의 재생·오디오·네트워크 속도·서버 구간 탐색 성공 여부는 아직 현장 미검증.

## 배포 파일

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.8_STREAM_PLAYER.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.8_SOURCE.zip`
- SHA256: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.8_SHA256.txt`

---

## 이전 버전 v0.1.7 기록

## v0.1.7 주요 개선

1. TeraBox 연결 추가/수정 창 아래 왼쪽에 **PC에서 가져오는 방법** 버튼 추가. 누르면 PC Chrome 로그인(F12 > Application > Cookies > 해당 TeraBox 도메인 > ndus)을 단계별로 안내하며 창은 닫히지 않음. Google 계정으로 로그인해도 같은 방법을 사용. 세션값은 계정 인증정보이므로 외부에 공개 금지.
2. 모든 페이지 왼쪽 상단에 삼선(☰) 메뉴를 고정하고 홈(⌂) 버튼을 바로 옆에 배치. 메인/SD/원격 내부 깊은 폴더에서 한 번에 앱 홈으로 이동.
3. 왼쪽 탐색 서랍은 250ms 슬라이드 및 배경 음영 페이드, 바깥 탭·뒤로가기로 부드럽게 닫힘.
4. 파일 목록은 기본 60dp의 압축된 평면 행으로 표시. 폴더는 '폴더' 대신 **n개 항목**으로 표시; 로컬/SAF 및 원격 WebDAV/SMB/SFTP/FTP/TeraBox/MYBOX는 보이는 폴더부터 최대 2개 동시 조회하는 방식으로 숫자를 갱신하며, 실패 시 '항목 수 미확인'. 개별 NAS 폴더 개수 확인에 추가 네트워크 요청이 발생할 수 있음.
5. 상단 돋보기 검색은 현재 폴더에서 로드된 항목의 파일명을 대소문자 구분 없이 검색. 결과는 처음 100건까지 클릭 이동. 서버 전체 재귀 검색은 수행하지 않음.
6. 상단 보기(☷) 버튼에서 **목록 / 격자 / 자세히**, **이름 오름·내림 / 최신·오래된 날짜 / 크기 큰·작은 순**, **숨긴 파일 표시**, **이미지 썸네일 표시**를 선택. 설정은 이 앱 내 모든 파일 목록에 저장 적용. 썸네일은 표시 중인 이미지의 로컬/SAF 파일과 1MB 이하 원격 파일에만 비동기로 표시. Android 시스템/서버 정책상 숨김 속성을 판단하지 못하는 원격 서비스는 점(.) 이름만 숨김 처리.
7. 우측 ⋮ 폴더 작업에서 '새 폴더 만들기' 대신 '새로 만들기' → 새 파일 / 새 폴더를 선택. 내장/SD 및 읽기·쓰기 지원 NAS 원격에서 작업; TeraBox/MYBOX 베타는 읽기 전용이므로 생성 불가. 원격 새 파일은 비어 있는 0바이트 파일 PUT+MOVE로 생성하며 기존 파일 덮어쓰기를 요청하지 않음.
8. v0.1.6의 Google Drive Android 제외, TeraBox Chrome Google 로그인 안내 및 ndus 수동 연결, WebDAV 하위 한글 폴더 지원 등 이전 기능은 유지.

## 설치 및 검증 한계

- 앱 ID `com.nasmount.mobile`, versionCode=8, versionName=0.1.7. 이전 APK와 동일한 릴리스 서명 인증서로 **기존 설치본을 삭제하지 말고 덮어 설치**.
- Gradle `testDebugUnitTest`, `lintDebug`, `lintVitalRelease`, `assembleRelease` 테스트 수행. 신규 목록 정렬/숨김/검색 정책 단위 테스트와 WebDAV 빈 파일 PUT+MOVE MockWebServer 테스트 포함.
- 연결된 Android 휴대폰/에뮬레이터가 개발 환경에 없어 실제 화면의 DPI/간격/키보드·드로어 애니메이션 및 실서버의 폴더 항목 수·파일 생성은 기기 검증이 필요함. 테스트 문서로만 새 파일 생성부터 확인.
- 원격 서버 성능 보호를 위해 폴더 개수 및 미리보기는 화면에 보이는 행부터 요청하며 네트워크 지연 시 '항목 수 미확인' 표시 가능.

## 새 릴리스 위치

- APK: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.7_FILE_MANAGER_UI.apk`
- 소스 ZIP: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.7_SOURCE.zip`
- SHA256 정보: `D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.7_SHA256.txt`

---

## 이전 버전 v0.1.6 기록

## v0.1.6 Google account sign-in policy

- Google's OAuth sign-in is blocked inside Android WebView by Google's security policy. No user agent spoofing or security bypass is performed.
- Added a TeraBox option 'Google account login (open in Chrome)'. Google sign-in uses the external browser. Existing TeraBox password login stays in WebView, and the WebView explicitly blocks navigation to Google OAuth.
- IMPORTANT: Chrome login does NOT automatically connect TeraBox to NAS Mount. Chrome's browser cookies cannot be imported by the app. Existing TeraBox beta backend still needs an ndus cookie.
- Manual path: Sign in to TeraBox in Chrome on YOUR OWN PC -> F12 -> Application -> Cookies -> TeraBox -> locate ndus -> securely transfer the secret to your own phone and paste it into NAS Mount's 'TeraBox login session (ndus)' field. Never send the session token to ChatGPT or other people; treat it like a password.
- A fully automatic authorization-code flow would require TeraBox Open Platform registration and partner client_id/client_secret/private_secret and token API implementation. None of those are present in this build.
- v0.1.6 versionCode 7 / versionName 0.1.6. Same RSA APK signing certificate as v0.1.5. No Google Drive support on Android. Existing SMB/FTP/SFTP/WebDAV/MYBOX/TeraBox profiles are retained.
- Unit/Gradle build checks completed; live Android Chrome authentication and ndus provider login are not tested because no device is attached.
# v0.1.5 변경 기록

## v0.1.5 핵심 수정

- 사용자 휴대폰에서 TeraBox PC 웹 로그인 화면은 열리지만 아이디/비밀번호 입력칸을 눌러도 Android 키보드가 뜨지 않는 증상이 보고됨.
- Android AlertDialog가 내부 WebView를 네이티브 텍스트 편집기로 인식하지 못할 때 설정할 수 있는 `FLAG_ALT_FOCUSABLE_IM` 키보드 차단 플래그를 창 표시 직후 해제. `SOFT_INPUT_ADJUST_RESIZE`로 키보드 표시 시 창 높이를 조정.
- WebView 터치 포커스 활성화 및 선택된 HTML 입력란에 한해 소프트 키보드 요청 보완. 일반 페이지 영역을 터치할 때는 키보드를 강제로 열지 않도록 처리.
- 이전 버전의 PC 웹 로그인·ndus 쿠키·앱 설치 이동 차단·WebDAV/MYBOX/SMB/FTP/SFTP 기능 보존. Google Drive는 Android에서 계속 제외.
- 패키지 `com.nasmount.mobile`, `versionCode=6`, `versionName=0.1.5`, 기존 릴리스 키로 서명. 이전 설치본 삭제 없이 업데이트.
- PC에서 자동 테스트/Lint/릴리스 빌드 검증. Android 실기기/에뮬레이터가 연결되지 않아 키보드 표시·실계정 로그인 성공은 휴대폰에서 최종 확인 필요.

## v0.1.4에서 유지한 변경

1. Google Drive를 Android 앱에서 제외했습니다. Google OAuth 승인 버튼, Google Drive 원격 저장소 추가 옵션, Drive API 관련 소스 및 Google Play 인증 라이브러리 의존성을 제거했습니다. 이제 Google Cloud 계정 등록은 필요하지 않습니다.
2. v0.1.3 이전에 저장했던 Google Drive 프로필 및 해당 즐겨찾기는 삭제하는 대신 화면에서 숨깁니다. Google 비밀번호를 저장하지 않았으며, 다른 네트워크 프로필과 네이버 MYBOX PAT, WebDAV 계정 정보는 유지됩니다.
3. TeraBox의 '세션 가져오기'를 누르면 https://www.terabox.com/korean/login 공식 웹 로그인으로 직접 이동합니다. Android 모바일 사이트가 앱 설치로 유도하는 문제를 줄이기 위해 WebView에 PC 브라우저 User-Agent, viewport, 웹 저장소를 적용했습니다.
4. WebView 내부의 앱 설치·Play 스토어·intent://·market://·APK 다운로드 주소 이동을 차단하고, 공식 TeraBox 웹과 인증 제공자 웹 경로만 허용합니다. 다른 앱을 실행하지 않습니다. 'PC 웹 새로고침'을 추가했습니다.
5. 로그인 후 사용자가 '세션 가져오기'를 누르면 TeraBox 사이트의 ndus 쿠키만 확인해 Android Keystore로 암호화해서 저장하도록 기존 방식을 유지합니다. 별도로 로그인한 세션값을 직접 입력하는 기능도 그대로입니다.
6. TeraBox 웹사이트가 WebView 자체를 차단하거나 Google/Facebook 계정 로그인을 허용하지 않는 경우에는 PC 웹 모드로도 로그인할 수 없을 수 있습니다. 사이트 정책을 우회하지 않으며, 웹 로그인 성공/ndus 획득은 실제 Android 기기 현장 테스트가 필요합니다.

## 홈·원격 저장소 기능

- 메인 저장소 / 다운로드 / SD 카드(장착 시에만) / 이미지 / 오디오 / 동영상
- 원격 저장소: SMB, FTP, SFTP, WebDAV, TeraBox(베타), 네이버 MYBOX(베타)
- Android 홈·좌측 메뉴에서 폴더 즐겨찾기를 누르면 해당 경로로 바로 이동
- 내장/SD의 파일 열기·복사·이동·이름 변경·삭제
- WebDAV/SMB/SFTP/FTP의 목록/파일 작업 및 네트워크 업로드/다운로드
- TeraBox 및 MYBOX는 읽기 전용 폴더 조회·다운로드 베타(업로드·삭제·이름변경 비활성)
- WebDAV 하위 폴더 한글 URL 인코딩/경로 비교 수정(v0.1.2부터 유지)

## 설치

기존 NAS Mount Android v0.1.5를 삭제하지 말고 새 APK를 덮어 설치하세요. 앱 패키지는 com.nasmount.mobile, versionCode=7, versionName=0.1.6이며 기존의 RSA 릴리스 인증서로 서명했습니다. WebDAV·MYBOX·TeraBox 연결 설정과 즐겨찾기는 유지됩니다. 기존 Google Drive 연결은 목록에서 보이지 않지만 다른 설정 파일까지 강제로 삭제하지는 않습니다.

TeraBox 연결: 원격 저장소 → + → TeraBox → 'TeraBox 웹 로그인에서 세션 가져오기' → 공식 웹 로그인 페이지에서 인증 → '세션 가져오기' → 연결 이름 확인 → 저장 → 연결하기. 홈페이지가 모바일 앱 설치 화면으로 이동하려고 하면 그 링크를 차단합니다. 일반적으로 이메일/비밀번호 웹 로그인을 시험해 보세요. Google 계정 등의 제3자 인증이 Android WebView에서 차단될 수 있습니다. 로그인이 실패하면 수동 ndus 세션 입력이 대안입니다.

## 검증·제한

- Gradle assembleRelease, testDebugUnitTest, lintDebug, lintVitalRelease 확인
- 새 TeraBoxWebPolicyTest: 공식 웹 로그인 주소 허용, 앱 설치 리디렉션 차단, 악성 호스트 차단, 정상 로그인 도메인 허용
- 모바일 실사용 테스트는 개발 PC에 연결된 Android 휴대폰/에뮬레이터가 없어 아직 하지 않았습니다. TeraBox 실계정 ndus 수집 및 폴더 조회 성공을 보장하지 않습니다.
- TeraBox는 비공식 API 기반 베타이므로 서버 정책/쿠키 형식 변경 시 로그인 또는 파일 목록이 작동하지 않을 수 있습니다.
- Google Drive 없이 동작합니다. Google Cloud 프로젝트·OAuth 클라이언트 생성·Drive API 활성화는 **필요하지 않습니다**.
- apk/소스 ZIP에는 사용자 NAS 비밀번호, ndus 세션, MYBOX PAT, 릴리스 개인 서명키와 서명 비밀번호를 넣지 않습니다.

## 파일 위치

소스: D:\GPT\NAS_MOUNT\Android\NASMountAndroid_0_1_6
APK: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.6_TERABOX_CHROME_LOGIN.apk
소스 ZIP: D:\GPT\NAS_MOUNT\Android\releases\NASMount_Android_v0.1.6_SOURCE.zip
개인 서명키: D:\GPT\NAS_MOUNT\Android\signing (사용자에게 공유하거나 소스에 포함하지 않음)

## 다음 실기기 점검

1. TeraBox 웹 로그인에서 아이디·비밀번호 필드를 터치하면 Android 소프트 키보드가 표시되고 글자를 입력할 수 있는지
2. 키보드가 펼쳐진 상태에서 로그인 입력칸/버튼이 가려지지 않는지, 로그인 후 ndus 세션을 얻을 수 있는지
3. WebDAV·MYBOX 기존 로그인 정상 여부, Google Drive 메뉴가 사라졌는지