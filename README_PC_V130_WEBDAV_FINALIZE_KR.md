# FileHub AIO PC v1.3.130 - Windows 복사 99% 지연 개선

## 현상
- PC v1.3.129는 NAS로 복사 시 0~99%는 부드럽게 증가하지만, 마지막 99%에서 4~8초 대기.
- WebDAV WinFsp 엔진에서 스테이징 PUT 완료 후 반복 PROPFIND, MOVE, 임시 파일 DELETE에 걸리는 서버 응답 시간이 추가됨.

## 수정
- PUT 성공 응답의 강한 ETag가 있으면 스테이징 PROPFIND 생략. 서버가 제공하지 않으면 기존 확인 유지.
- 최종 파일의 중복 사전 PROPFIND 제거. 최초 Create에서 존재 여부를 검증하고 MOVE Overwrite:F가 경쟁 생성 충돌을 차단함.
- MOVE 성공 응답에서 강한 ETag가 제공되면 해당 서버의 완료 인증값을 사용하며 추가 최종 PROPFIND는 생략. ETag가 없으면 기존 PROPFIND 수행.
- 확인한 최종 메타데이터를 WinFsp Flush/Cleanup에서 재사용해 중복 NAS 요청 제거.
- 성공한 MOVE에 따라 사라진 임시 파일을 DELETE하는 불필요한 요청 제거.
- 서버 응답, 메타데이터, MOVE 등의 단계별 소요 시간을 밀리초로 기록하되 경로/계정 이름은 기록하지 않음.
- 기존 파일 수정, Office 파일, 비순차 쓰기, 미지원 서버 및 다른 저장소 엔진의 저장/복구 처리 방식 유지.

## 안전성
- 실제 NAS가 PUT와 보호된 MOVE를 수락하기 전에는 완료 처리하지 않음.
- 새 파일, 기존 파일, 잠금, 동시 수정, Office 저장 및 비정상 종료 복구 회귀 검증 수행.
- 사용자 실제 NAS의 마지막 대기 시간은 서버 응답 지연에 따라 달라질 수 있으며 현장 확인이 필요.

## 최종 시험 및 공식 배포 기준 (2026-11-23)

- PC v1.3.130 배포 ZIP: FileHub-AIO-PC-1.3.130-win-x64.zip, 168,915,274 bytes. SHA256 5B2DBE19B6DC7C8234C6E4E2359F5D84B96D5724E08054DABF961F922D58797D.
- PC v1.3.129 기준 느린 WebDAV 시험에서 24MiB 전송 진행률 100% 보고 후 복사 반환까지 약 6.1초. v1.3.130 새 방식은 강한 MOVE ETag 서버 시험에서 약 3.1초. 사용자의 NAS에서는 실제 RTT에 따라 다름.
- 24MiB/23,600,123바이트 원본·서버 SHA-256 일치. WebDAV 반복 저장, Office 임시 파일 이름 변경, 충돌 방지, 잠금, crash journal 회귀 검증 통과.
- PC 앱 업데이트, 원격 경로, EXE 실행, Google Chrome 로그인 구성, Google publisher OAuth, PC/Android 백업 정책, LockBridge self-test 통과.
- 릴리즈 URL: https://github.com/dj5927/FileHub-AIO/releases/tag/pc-v1.3.130
- 기존 1.3.129 공식 ZIP에서 검증된 Google OAuth/부가 엔진 구성요소 유지, 새 1.3.130 앱 및 WinFsp 엔진으로 교체. Android는 변경 없음.
