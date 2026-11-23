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
