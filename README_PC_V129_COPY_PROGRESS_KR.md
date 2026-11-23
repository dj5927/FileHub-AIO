# FileHub AIO Windows 1.3.129 - NAS 복사 진행률 개선

## 원인

- Windows 탐색기에서 FileHub 네트워크 드라이브로 복사하면 진행 창이 처음부터 99%로 보였다가 NAS 업로드가 끝난 뒤 닫히는 현상.
- 기존 WebDAV WinFsp Write는 로컬 저널만 빠르게 기록하고 성공을 반환했으며, 실제 서버 PUT는 마지막 Flush/Cleanup에서 이루어졌음.
- RaiDrive의 내부 비공개 코드를 복제한 것이 아니라 FileHub의 쓰기 경로와 Windows 네이티브 복사 콜백을 기준으로 개선함.

## 변경

- 2MiB 이상 신규 파일의 크기가 알려진 순차 복사에는 서버 비공개 스테이징 파일로 WebDAV PUT를 진행하면서 WinFsp Write에 네트워크 배압을 적용.
- 서버가 임시 목적지의 MOVE Overwrite:F를 정확히 지키는지 같은 폴더에서 자체 파일로 검증한 뒤에만 활성화.
- 스테이징 업로드 완료 후 크기, ETag를 확인하고 최종 경로로 서버측 MOVE. 기존 파일 덮어쓰기는 허용하지 않음.
- 서버 미지원, 비순차 기록, Office 문서, 크기 변경 등은 기존 조건부 PUT와 로컬 저널 저장으로 복귀.
- 완전히 기록되지 않은 파일은 비정상 종료 후 자동으로 NAS에 업로드하지 않고 로컬 복구본으로 보존.
- 적용 범위는 WebDAV 단일 WinFsp 엔진. 구형 rclone 호환 모드와 타 클라우드 엔진은 대상 아님.

## 2026-11-23 로컬 검사

- 실제 Windows WinFsp 드라이브에 24MiB와 23,600,123바이트 파일을 복사하고 서버 파일 SHA-256 일치 확인.
- 4MiB/s로 제한된 WebDAV 시험 서버에서 CopyFileEx가 25개 진행 샘플을 보고, 0% 약 48ms부터 100% 약 4,137ms까지 진행. 전체 복사 약 6,442ms.
- 조건부 저장/충돌 검출, Excel 반복 저장과 임시 파일 치환, 폴더 이름 변경, 복구와 잠금 회귀 테스트 통과.
- 실제 사용자 NAS 및 탐색기 GUI의 최종 속도 평가는 설치 후 확인 필요.

## 공식 PC 릴리즈 전달

- 릴리즈 태그: pc-v1.3.129
- ZIP SHA-256: D589FDBD0B9E3D5F59F3EBC7631F489EE4BEA593D0CCEF29205E6F01AE47BF73
- ZIP 용량: 168,913,736 bytes. 패키징 시 기존 검증된 v1.3.128의 Google OAuth와 부가 엔진을 유지하고, v1.3.129 앱/WinFsp 엔진으로 교체함.

## 최종 배포 및 인수인계 기록 (2026-11-23)

- 공식 GitHub PC 릴리즈: https://github.com/dj5927/FileHub-AIO/releases/tag/pc-v1.3.129
- ZIP 이름: FileHub-AIO-PC-1.3.129-win-x64.zip
- ZIP 크기: 168,913,736 bytes, SHA-256: D589FDBD0B9E3D5F59F3EBC7631F489EE4BEA593D0CCEF29205E6F01AE47BF73. GitHub 릴리즈 API digest 및 로컬 원본 해시 일치.
- GitHub Actions run 45102327231 성공: 11개 청크 다운로드 및 재결합, 전체 SHA-256, ZIP 무결성/필수 파일, 공식 PC 릴리즈 생성 확인.
- 개발 및 시험: 24MiB와 23,600,123 bytes 파일 전송 후 SHA-256 일치, 4MiB/s 제한 Windows CopyFileEx 25개 진행 샘플(초기 0%), 서버가 진행형 PUT를 거부할 때 기존 조건부 PUT로 폴백하고 서버 바이트 동일 확인. WebDAV 기존 파일 저장, Office 임시파일 교체, 충돌/복구/잠금 회귀 시험 성공.
- APK/Android는 변경하지 않음. 실제 사용자 NAS에서는 서버 지원 여부와 Explorer 복사 창 0~100% 표시를 최종 현장 확인해야 함.
- 전달용 임시 HTTPS 터널 및 로컬 읽기 전용 배포 서버 모두 종료. Windows 기존 앱 프로세스 및 실제 NAS 연결은 종료하지 않음.
