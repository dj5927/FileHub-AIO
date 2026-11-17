# NAS Mount v1.3.76 — Excel 처음 열 때 파일명 대문자 변경 수정

## 실제 발견한 원인

사용자 증상: NAS에 소문자로 저장된 `.xlsx`를 **열기만 해도** Excel 제목에 `.XLSX`가 표시됨.

별도의 localhost WebDAV 테스트 서버에서 **실제 Excel COM**으로 확인:

| 확인 단계 | v1.3.75 | v1.3.76 |
|---|---|---|
| NAS 서버의 원본 파일명 | LabActual.xlsx | LabActual.xlsx |
| Windows 탐색기의 파일명 | LabActual.xlsx | LabActual.xlsx |
| Excel이 최초로 인식한 Book.Name | LABACTUAL.XLSX | **LabActual.xlsx** |
| Excel 최초 FullName | W:\LABACTUAL.XLSX | **W:\LabActual.xlsx** |
| 저장 후 NAS의 원본 파일명 | LabActual.xlsx | **LabActual.xlsx** |
| 기존 `.xls` 파일 열기·저장 | 별도 검증 필요 | **LabActual.xls 유지** |
| Excel 다른 이름으로 저장 | 별도 검증 필요 | **NewCase.xlsx 유지** |
| 한국어 포함 파일명 열기·저장 | 별도 검증 필요 | **원래 소문자 .xlsx 유지** |
| 하위 폴더 자동 변경 알림 | 정상 | **정상 (재검증)** |

**원인:** WinFsp 파일잠금 브리지의 `CaseSensitiveSearch=false`에서 Windows/Excel에 보이는 열린 파일명 경로가 대문자로 정규화됨. WinFsp의 `CasePreservedNames=true`만으로는 이 문제가 해결되지 않았음. 기존 v1.3.73은 Excel의 저장 시 원본 이름 복원만 처리하므로 최초 Excel 제목의 대문자는 남아 있었음.

**수정:** WinFsp 브리지의 `CaseSensitiveSearch=true`로 Windows에 전달되는 파일명 대소문자 유지. 임시로 구현한 `NormalizedName` 강제 지정은 실제 Excel 제목을 고치지 못했고 열기마다 파일 목록 조회 부하가 생길 수 있으므로 최종 소스에서 **제거**함. 실제 WinFsp 파일열기 및 파일 저장은 기존 경로 유지. 외부 WebDAV 변경 감지 알림은 **정확한 원본 대소문자로 전달**하도록 변경(더 이상 알림 경로를 무조건 대문자로 바꾸지 않음).

실제 수정과 검증은 테스트 NAS에서만 수행했으며 사용자 NAS의 파일명이나 데이터를 원격으로 변경하지 않았음.

## 보존된 기존 기능

- 이전 버전의 WinFsp 네트워크 드라이브 표시, 기본 파일잠금, 읽기전용 구분, 임시파일, 안전 해제 및 Excel 저장 무결성 방어
- 원본 파일명 대소문자 보존 및 Office 임시파일의 원자적 교체
- WebDAV 루트 및 하위 폴더 변경 알림 (외부 클라이언트 생성·삭제·수정)
- 일반 rclone **v1.76.0**, TeraBox 확장 rclone-extra **v1.76.0-extra**, Google 인증 및 시작 일괄 표시

주의: `CaseSensitiveSearch=true`로 Windows에 표시되는 파일시스템의 대소문자 처리 방식이 변경되므로, Linux NAS에 대소문자만 다른 동명이인 파일이 존재하는 경우에는 추가 현장 검증이 필요함. 미검증 프로토콜을 모두 완벽하게 지원한다고 주장하지 않음.

## 설치와 확인

1. 모든 업로드를 마치고 Excel을 닫은 후 NAS Mount를 **안전하게 연결 해제**.
2. 새 ZIP의 전체 파일을 **새 폴더**에 압축 해제. `NASMount.exe`뿐 아니라 **`tools/NASMountLockBridge.exe`와 `NASMountLockBridge.dll`을 반드시 함께 교체**해야 수정이 적용됨.
3. 실제 NAS 서버 또는 다른 연결 방식에서 해당 파일의 확장자가 아직 소문자인지 확인하고 NAS Mount 드라이브에서 다시 열어 Excel 제목을 비교.
4. 과거 버전으로 이미 실제 NAS 파일명이 `.XLSX`로 저장됐다면 이 업데이트가 NAS 파일을 자동 일괄 변경하지는 않음. 원래 이름을 확인하고 필요한 파일에 한해 소문자로 이름을 변경해야 함.

모든 자동 테스트는 localhost 모의 WebDAV 및 일회용 드라이브·테스트 Excel 문서로 실행됨. 실제 여러 PC 동시 편집은 사용자 환경 검증 필요.

