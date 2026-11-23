# FileHub AIO PC v1.3.132 - PC 버전 표시 및 업데이트 상태 표시 수정

## 사용자 제보
- 실제 PC v1.3.131을 설치해도 제목 표시줄에는 'FileHub AIO v1.3.126'으로 표시됨.
- 환경 설정에서 'PC 업데이트 확인'을 실행한 뒤 하단 상태줄에 'GitHub PC 릴리즈 확인 중...' 문구가 남아 있음.

## 원인
- 기존 제목 표시줄, 상단 브랜드, 트레이 툴팁/메뉴, 진단창 제목에 v1.3.126이 하드코딩되어 있었음.
- PC 업데이트 확인의 성공·최신 버전·설치 거절·취소·오류 분기에서, 마지막에 상태줄을 비우는 공통 종료 처리가 누락되어 있었음.

## 수정
- UI 버전 표기는 PcUpdateService.InstalledVersion을 공통 출처로 사용. 해당 값은 실행 중인 EXE의 어셈블리 버전에서 가져오므로 수동 라벨과 별도로 어긋나지 않음.
- 버전 문자열은 major.minor.build (예: 1.3.132)로 표시하고, 실제 설치된 EXE의 어셈블리 버전이 그대로 반영되도록 함.
- 업데이트 확인은 finally에서 기본 상태줄(빈 문구)로 복원. 별도 WinFsp/새 버전 알림 영역은 유지.
- UI 테스트 진입점 --pc-title-footer-policy-test를 추가. 제목·상단 라벨·트레이·하단 상태 정리를 검사하며 실제 NAS와 GitHub에는 접속하지 않음.
- Windows 본체와 LockBridge 버전을 1.3.132로 맞춤.

## 검증
- Windows 앱 빌드 오류 0.
- --pc-title-footer-policy-test: PC_TITLE_VERSION_AND_UPDATE_FOOTER_POLICY_PASS version=1.3.132.
- 실제 계정 연결·드라이브 데이터·Windows 네트워크 드라이브 등록은 변경하지 않음.
- Google Drive/WebDAV 복사 진행률 변경(v1.3.131)과 MYBOX·TeraBox 기능은 유지.

## 배포 인수인계 (2026-11-23)

- 설치 대상 Windows PC 태그: pc-v1.3.132. GitHub 릴리즈 https://github.com/dj5927/FileHub-AIO/releases/tag/pc-v1.3.132
- FileHub-AIO-PC-1.3.132-win-x64.zip 용량 168,929,804 bytes / SHA-256 1EC46115479F9CF89ADC3104E2B3B594E871E59AB1E586C25F5A3BD3702C44FC.
- 원본은 검증된 공식 v1.3.131 ZIP을 기준으로 새 v1.3.132 FileHubAIO.exe와 LockBridge 엔진을 교체하고 Google OAuth, MYBOX, TeraBox 등 부가 구성요소는 유지함.
- 배포본의 --pc-title-footer-policy-test, --pc-updater-policy-test, --google-publisher-oauth-policy-test, --direct-webdav-routing-policy-test, --profile-backup-lab 및 LockBridge --self-test 모두 PASS.
- 전체 --ui-smoke-test는 이전부터 존재한 프로필 설정창의 'Checkbox clipped: EXCEL...' 레이아웃 검증에서 실패했지만, 새 버전 표시/업데이트 종료 단독 회귀 테스트는 통과함. 이 별도 UI 레이아웃 문제는 이번 작업 범위에서 변경하지 않음.
- 실제 사용자 PC에서 업데이트 확인 버튼 및 제목 표시 확인이 마지막 현장 검증 단계. 기존 드라이브/계정 정보는 수정하지 않음.

## 최종 공식 배포 결과 (2026-11-23)

- GitHub Actions run 45115236102: success. SHA-256/ZIP 내용/필수 구성요소를 다시 검증하고 PC v1.3.132 공식 릴리즈 생성 완료.
- 공식 ZIP FileHub-AIO-PC-1.3.132-win-x64.zip, 용량 168,929,804 bytes, SHA-256 1EC46115479F9CF89ADC3104E2B3B594E871E59AB1E586C25F5A3BD3702C44FC. GitHub 릴리즈 API의 asset digest와 로컬 배포본 해시 일치.
- 릴리즈 https://github.com/dj5927/FileHub-AIO/releases/tag/pc-v1.3.132
- 임시 배포 전송 터널과 전용 HTTP 서버 종료. 기존 사용자 드라이브 세션과 데이터는 변경하지 않음.
- 확인 항목: 버전 표시줄·트레이 v1.3.132, 업데이트 확인 후 하단 진행 문구 정리. 실제 PC 설치 후 UI 확인 권장.
