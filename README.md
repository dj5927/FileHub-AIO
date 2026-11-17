# FileHub AIO PC for Windows

Windows 10/11용 NAS·클라우드 드라이브 마운트 프로그램입니다.

## 버전과 업데이트

- PC 버전: v1.3.84, 이후 v1.3.85 / v1.3.86...
- PC 전용 GitHub 릴리즈 태그: pc-v1.3.84 / pc-v1.3.85 ...
- Android 버전: v0.1.x — Windows 업데이트와 별개로 운영
- 프로그램 실행 시 트레이 업데이트 알림 / 환경 설정 → PC 업데이트 확인
- 새 PC 버전을 받으면 GitHub 릴리즈 ZIP SHA-256 검증 → 사용자의 설치 승인 → 원격 드라이브 안전 해제 → 프로그램 업데이트 후 재실행
- 업데이트를 강제로 설치하거나 사용자의 원격 데이터를 임의 삭제하지 않습니다.

## 설치

GitHub Releases에서 FileHub-AIO-PC-1.3.84-win-x64.zip 같은 Windows 전용 ZIP을 받습니다. 압축을 별도 폴더에 풀고 FileHubAIO.exe를 실행합니다. 기존 v1.3.83은 PC 업데이트 확인으로 v1.3.84로 업그레이드할 수 있습니다.

업데이트 설치 전에는 Excel/Word 등 열린 문서를 모두 저장하고 닫아야 합니다. 안전하게 해제하지 못한 NAS 드라이브가 있으면 프로그램 교체를 중단합니다. 이전 버전은 사용자 백업 폴더에 보관합니다.

## 포함 엔진 및 출처

- .NET 10 (Windows x64 자체 포함 빌드)
- rclone v1.76.0: https://github.com/rclone/rclone
- rclone-extra v1.76.0-extra (TeraBox): https://github.com/gulp79/rclone-extra
- WinFsp v2.2.26215: https://github.com/winfsp/winfsp

GitHub Actions는 공식 릴리즈 파일과 컴파일된 실행 파일의 SHA-256을 검사한 후 Windows 전체 패키지를 작성합니다. 제3자 엔진의 저작권/라이선스는 각 원본 프로젝트에 따르며, WinFsp에서 파생된 예제의 원 저작권 고지를 유지합니다.

## 소스 빌드

PowerShell에서 dotnet restore NASMount.csproj, dotnet publish NASMount.csproj -c Release -r win-x64 --self-contained true -o publish, dotnet publish LockBridge/LockBridge.csproj -c Release -r win-x64 --self-contained true -o bridge 를 순서대로 실행합니다.

두 프로젝트를 컴파일하는 것만으로 전체 배포본이 완성되는 것은 아닙니다. rclone, 확장 엔진, WinFsp 배포 파일은 GitHub Actions에서 공식 릴리즈로부터 수집합니다.

## 검증 범위

업데이트 무결성, 버전 필터링, 로컬 WinFsp 저장, 백업/롤백에 대한 자동 검사를 포함합니다. 실제 사용자 NAS의 다중 PC Excel 동시 편집 및 서버별 잠금 호환성은 별도 시험이 필요합니다.
