# FileHub AIO Windows v1.3.131 - 클라우드 파일 복사 진행률

## 사용자 재현 증상

- WebDAV: 파일 전송 중 0~99%까지 진행되지만 99%에서 NAS PUT/MOVE 완료를 몇 초 동안 기다림.
- Google Drive: Windows 탐색기의 복사 창이 나타날 때 이미 99%로 보이고 실제 업로드가 끝나면 종료됨.
- 네이버 MYBOX와 테라박스는 정상적인 0~100% 진행률을 보임.

## 원인과 수정

1. Google WinFsp DirectGoogleWriteStage는 Write에서 PC 임시파일만 기록하고 마지막 Cleanup에서만 Google API resumable 업로드를 실행했음.
2. Windows Create의 allocationSize는 실제 파일 크기가 아니라 4096바이트 단위로 반올림한 값일 수 있음. 따라서 단일 HTTP PUT에 이 값을 Content-Length로 쓰면 전송 끝부분이 불일치할 수 있음.
3. 신규 순차 복사(2MiB 이상)는 Google Drive의 공식 resumable upload 세션을 시작하고, Windows가 파일 데이터를 쓸 때 1MiB/4MiB/8MiB 구간을 전송. 서버가 HTTP 308 Range로 수신한 바이트만 진행률에 반영.
4. 실제 파일의 마지막 구간은 WinFsp 마지막 Cleanup까지 보류하고 정확한 파일 길이로 최종 청크를 전송. 완료 응답의 file ID/size와 로컬 파일 MD5를 확인하여 최종 게시.
5. 기존 파일 편집, Office 문서/임시 파일, 비순차 쓰기, 충돌, Google API 오류와 중단은 기존 로컬 저널 복구 방식으로 보호.
6. 구글 OAuth/Chrome 로그인, 테라박스, MYBOX와 다른 원격 프로토콜 구현은 변경하지 않음.
7. WebDAV는 스트리밍 중간 읽기에서 FileStream의 짧은 read를 정상적으로 다시 읽는 안정성 수정. WebDAV 서버의 마지막 PUT/MOVE 완료 확인은 여전히 필요하므로 끝부분 지연이 서버 특성에 따라 남을 수 있음.

## 검사

- 모의 Google API로 6MiB+123B 원본과 업로드 내용 비교, 서버 전송 응답 전에 최종 완료 금지, 중단 시 로컬 복구본 보존.
- Windows 실제 WinFsp 네트워크 드라이브에서 8MiB+123B 파일 CopyFileEx 진행률 및 원본/서버 바이트 일치.
- 기존 Google 파일 열기, 폴더 생성·이름변경, 여러 번 Flush, 신규 업로드, 반복 저장, 동시 수정 충돌, 403 거부/복구 테스트.
- WebDAV WinFsp 대용량/비정렬 길이 파일 복사 SHA-256, Excel 임시파일 교체, 파일 잠금/이름변경·삭제/복구 테스트.
- 모든 실사용 서버의 속도 동일성을 보장하는 테스트는 아니며, Google 계정 실제 연결과 사용자의 NAS에서는 현장 검증이 필요함.

## 최종 빌드 및 배포 추적 (2026-11-23)

- PC v1.3.131 Windows 실행파일/WinFsp 엔진 빌드 성공. 정식 배포 ZIP: FileHub-AIO-PC-1.3.131-win-x64.zip, 168,927,907 bytes, SHA-256 A4853F0A24C4084C346639BC1F3559C2CDBED7B490886F8B5929AC36983C8382.
- 검증된 v1.3.130 기본 패키지의 Google publisher OAuth, MyBox/TeraBox, rclone 관련 부가 구성요소를 보존하고 새 1.3.131 앱과 WinFsp 엔진으로 교체.
- 실제 WinFsp 네트워크 모의 Google 드라이브로 8,388,731바이트 파일 CopyFileEx: Windows 진행 샘플 10회, 진행 구간 약 748ms, 전부 같은 바이트 확인. 신규 파일 스트리밍 중 서버에 미완료 게시 금지, 취소 시 로컬 저널 보존 통과.
- 구글 기존 파일 여러 번 저장, Office 플러시, 충돌, 403 실패 로컬 복구, 구글 로그인 UI/게시자 OAuth, PC updater/EXE/설정 백업 테스트 통과.
- WebDAV 로컬 WinFsp 복사 24MiB/23,600,123바이트 SHA-256, 파일 잠금, 한글 폴더 이름변경, 엑셀 임시파일 교체 테스트 통과. 사용자 NAS의 99% 마지막 응답 지연은 아직 실기기 재검증 필요.
- 공식 릴리즈 URL: https://github.com/dj5927/FileHub-AIO/releases/tag/pc-v1.3.131
