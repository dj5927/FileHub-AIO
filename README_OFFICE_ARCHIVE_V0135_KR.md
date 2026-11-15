# NAS Mount Android v0.1.35 — 오피스 외부 편집 연동 / ZIP·7z·RAR 압축 관리

## 1. 이번 개발본의 실제 구현 범위

### ZIP, 7z, RAR 압축 관리 (앱 내부 기능)

- 파일 탐색기에서 ZIP/7z/RAR 파일을 누르면 압축해제 확인창을 띄움.
- 현재 폴더에 **압축파일명과 같은 새 폴더**를 만든 후 압축 해제.
- 파일 여러 개 및 폴더를 선택하고 **더 보기 → 선택 파일 압축하기** 메뉴에서
  ZIP 또는 7z 생성. 기존 복사·이동·붙여넣기 코드와 동일한 저장소
  (Android 내부 저장소, SAF, WebDAV, SMB, FTP, SFTP, MYBOX, TeraBox)를 이용.
- 원본은 수정·삭제하지 않음. 대상 같은 이름이 있으면 덮어쓰지 않고 중단.
- 경로 탈출 ZIP-Slip/Windows 절대경로/중복 항목/비정상적인 깊이/과대
  압축 해제를 차단. 현재 용량 제한: 압축 원본 512 MiB, 압축 해제/새로
  압축할 원본 전체 1 GiB, 최대 10,000개 파일·폴더.
- RAR은 읽기·압축해제만 가능. RAR 새 압축 만들기 지원 안 함.
- 암호로 보호된 RAR·ZIP·7z, 분할 RAR/7z 파일 묶음 등은 이 버전에서
  완전 지원하지 않음.

### XLS/XLSX/ODS/HWP/HWPX 편집 (호환 편집 앱 연결 방식)

- 파일을 누르면 자체 **편집 중계 화면**이 열림.
- 문서를 NAS Mount 앱의 사설 캐시에 **최대 256 MiB**까지 내려받고,
  Android content URI로 설치된 호환 오피스 편집기에 전달함.
- 외부 편집 앱에서 문서를 열고 수정한 후 **그 앱 안에서 저장**하고
  NAS Mount 편집 중계 화면으로 돌아와
  **변경 내용을 원본에 저장**을 누르면 로컬/SAF/원격 원본에 반영.
- NAS 계정 암호/PAT/ndus는 외부 편집 앱에 전달하지 않고 임시 사본만 전달.
- 원본 저장은 기존 RemoteProvider.upload(..., overwrite=true)를 호출,
  v0.1.34의 TeraBox 복사·덮어쓰기 흐름을 그대로 사용.
- **중요: Collabora, HOP, rhwp, LibreOffice를 NAS Mount APK에 내장한
  것은 아님.** 별도의 호환 편집 앱을 설치해야 실제 문서 편집이 가능.
  외부 앱의 특정 파일 형식/MIME ACTION_EDIT 또는 ACTION_VIEW 및
  원본 content URI 직접 저장 지원 여부는 실기기 테스트 필요.
- 엑셀 계열은 LibreOffice 기반 Collabora Office 권장
  (https://play.google.com/store/apps/details?id=com.collabora.libreoffice).
- HWP/HWPX는 rhwp 기반 HOP Android 오픈소스 포트(베타)를 후보로 안내
  (https://github.com/dalgona039/hop_android).
- 이음오피스 자체는 Windows/Mac/Linux 데스크톱 앱으로 Android 지원이
  없어 그대로 포팅하거나 내장하지 않음.

## 2. 자동 검증 및 한계

- ZIP/7z 생성 → 해제 → 한글 파일명과 내용 비교 왕복 테스트 통과.
- ZIP-Slip, Windows 절대경로, 파일명 대소문자 중복 차단 테스트 통과.
- 파일명별 문서 라우팅과 MIME 테스트 통과.
- 기존 NAS Mount 기능 전체 회귀 테스트 통과.
- 오피스 외부 앱 인텐트/실제 엑셀 저장 및 HWP 레이아웃·서식 보존은
  삼성 실기기 및 외부 앱이 연결돼 있지 않아 미검증.
- RAR 실제 데이터 압축해제는 사용자 실제 샘플 파일로 추가 확인 필요.
- 중요 원본은 별도 백업 후 테스트. 실기기 검증 전에는 v0.1.35를
  **기능 테스트용 베타**로 취급하고, v0.1.34 안정본을 보관.

## 3. 코드·라이선스

- Apache Commons Compress 1.28.0: Apache-2.0.
- XZ for Java 1.12: 공개 자유 소프트웨어.
- junrar 8.1.2: RAR 해제 구현. 원 저작권/UnRAR 관련 사용 조건을 준수,
  RAR 파일의 독자적인 압축 알고리즘 재구현에 사용하지 않음.
- Collabora/HOP는 이번 APK에 번들하지 않음. 추천 연결 앱의 각자
  라이선스와 배포는 해당 프로젝트에서 관리.
- 기존 NextLib/FFmpeg GPLv3 소스 제공 및 라이선스 의무는 변경 없음.

## 4. 파일 보존

- v0.1.34 소스/서명 APK/사용자 저장된 연결 프로필은 변경하지 않았음.
- 새 파일을 v0.1.35 독립 버전 폴더에서 개발하고, 동일 기존 인증서로
  업데이트 설치 가능한 APK를 만듦.
