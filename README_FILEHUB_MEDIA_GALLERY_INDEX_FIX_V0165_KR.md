# FileHub AIO Android v0.1.65 — NAS 동영상 복사 후 갤러리·업로드 목록 미표시 수정

## 접수 증상
- NAS의 영상을 휴대폰에 복사하면 FileHub 파일관리자에는 있으나 갤러리·YouTube 업로드 목록에는 보이지 않음.

## 원인
- FileTransferEngine.copyOne의 로컬 파일 복사가 완료된 뒤 Android 미디어 스캔 호출 누락.
- MainActivity.downloadRemote(Android 10+)는 영상/사진/음악 구분 없이 MediaStore.Downloads 항목으로만 저장.
- 이전에 복사한 미등록 파일은 새 복사 동작의 수정만으로는 기존 갤러리에 등록되지 않음.

## 변경
1. MediaIndexing.java 신설. Video/Images/Audio/Downloads MediaStore 컬렉션과 MIME 정의.
2. NAS의 다운로드 버튼: 영상은 Movies/FileHub AIO/, 사진은 Pictures/FileHub AIO/,
   음악은 Music/FileHub AIO/, 기타 파일은 Download/에 등록.
   Android 10 이상은 MediaStore의 해당 타입 컬렉션, RELATIVE_PATH,
   IS_PENDING=1 저장 후 IS_PENDING=0 게시.
   Android 8~9는 목적별 공개 폴더에 파일 쓰기 완료 후 MediaScannerConnection.scanFile 요청.
3. NAS 복사 후 휴대폰 내부 저장소에 붙여넣을 때, 완전히 생성·닫힌 로컬 미디어 파일을
   MediaScannerConnection.scanFile로 등록. 폴더 하위 영상, 덮어쓰기, 같은 폴더
   이동 파일도 반영. 다운로드 중 부분 파일은 색인하지 않음.
4. 기존 파일 재검색 메뉴: 내부 저장소의 파일이 있는 폴더를 열고
   상단 현재 폴더 작업(점점점) → 이 폴더 미디어 재검색.
   최대 3,000개 미디어, 최대 20,000개 항목 / 12단계 탐색,
   숨김 표시 .nomedia 폴더와 심볼릭 링크 제외. 비동기 스캔 요청일 뿐
   갤러리가 즉시 반영했다는 뜻은 아님.

## 확인
- Android JUnit 401/401 PASS, 신규 MediaIndexingTest 5/5 통과.
- Gradle testDebugUnitTest, lintDebug, lintVitalRelease, assembleRelease 성공.
- signed release APK signature v2 인증서 이전 v0.1.64와 동일.
- applicationId com.nasmount.mobile 유지, versionCode 66, versionName 0.1.65.
- 기존 v0.1.64 배포파일과 사용자 파일·NAS 계정은 수정하지 않음.
- Android 기기가 ADB에 연결되지 않아 갤럭시 갤러리·YouTube
  실기기 최종 확인은 사용자의 설치 후 검증 필요.

## 설치 후 재현 검증
1. NAS의 MP4 영상 1개를 복사 → 내부 저장소/Movies 등 공개 폴더에 붙여넣기.
2. 갤러리 및 YouTube 업로드 선택에 새 영상이 나타나는지 확인.
3. 다운로드 버튼으로 내려받은 영상은 Movies/FileHub AIO/ 경로 확인.
4. 이전에 복사해 놓은 영상이 안 보이면 해당 폴더의
   현재 폴더 작업 → 이 폴더 미디어 재검색.
5. 파일이 .nomedia 아래 있거나 특정 앱에서 .mkv 등 파일 형식을
   지원하지 않는 경우 계속 보이지 않을 수 있음.
