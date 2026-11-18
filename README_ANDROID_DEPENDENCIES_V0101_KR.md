# FileHub AIO Android v0.1.101 외부 소스 전체 업데이트

기준일: 2026-11-18
이전 Android 최신 버전: v0.1.100 (versionCode 101)
업데이트 후 버전: v0.1.101 (versionCode 102)
GitHub 소스 브랜치: main
기존 패키지 ID: com.nasmount.mobile (유지)
최소 OS: Android API 26 (유지)
targetSdk 35 (유지)
compileSdk 37 (AndroidX Core/OkHttp 5의 요구로 상향)

## 업데이트

| 구성 | 이전 버전 | 새 버전 |
|---|---|---|
| Android Gradle Plugin | 8.10.0 | 9.5.0 |
| Gradle 및 Wrapper | 8.11.1 | 9.9.1 |
| AndroidX DocumentFile | 1.0.1 | 1.1.0 |
| AndroidX Core | 1.16.0 | 1.19.1 |
| OkHttp 및 MockWebServer | 4.12.0 | 5.5.1 |
| SSHJ (SFTP) | 0.39.0 | 0.41.2 |
| Commons Net (FTP) | 3.11.1 | 3.13.0 |
| ZXing Core | 로컬 JAR 3.5.3 | Maven 3.5.4 |
| org.json 테스트용 | 20240303 | 20260814 |
| AndroidX Lifecycle transitive | 2.10.0 | 2.11.0 |
| AndroidX ExifInterface transitive | 1.3.6 | 1.4.2 |
| Kotlin Coroutines transitive | 1.9.0 | 1.11.0 |
| Bouncy Castle transitive | 1.78.1 | 1.84 (SSHJ 경유) |
| JSZip 웹 내장 | 3.10.1 | 3.10.2 |

## 최신 유지

- AndroidX Media3 1.11.1 및 NextLib FFmpeg 1.11.1-0.18.0:
  네이티브 코덱과 ABI가 맞물린 쌍으로 유지함.
- Commons Compress 1.28.0, XZ 1.12, junrar 8.1.2,
  jcifs-ng 2.1.10: 최신 안정 버전 유지.
- SheetJS 커뮤니티 0.20.3 및 rhwp 0.9.0: 최신 확인 버전 유지.
- docx-preview는 npm 공식 0.4.1 배포본과 기존 APK 내장 JS가
  SHA-256까지 동일함을 확인. 불필요한 변경 없음.
- PPTX 브라우저 번들은 프로젝트 맞춤 번들이므로, 출처 검증 없이
  교체하지 않음. 내장 HWP WASM은 기존 SHA-256 검증 조립 로직 유지.

## 안정성 검증

- JDK 17 / Gradle 9.9.1 / AGP 9.5.0 / compileSdk 37.
- Android JVM 단위 테스트 528건: 실패 0, 제외 1.
- TeraBox 및 MYBOX 업로드 테스트는 MockWebServer 5의 기본 host가
  localhost로 바뀐 점 때문에 9건이 실패했으나, 테스트의 모의 URL만
  숫자 루프백 127.0.0.1로 변경 후 모두 통과함.
  **실제 업로드 대상 서버의 보안 검증 로직은 완화하지 않음.**
- JSZip 3.10.2 UTF-8 ZIP 압축·해제 라운드트립 및 내장 JS 해시 검증.
- 기존 release APK와 새 APK가 같은 서명 인증서인지 확인.
- OTA 버전 정보: versionCode 102, versionName 0.1.101.
- 앱에 포함된 WASM은 Gradle 빌드에서 기존 분할 조립 및 해시 검증.
- 실제 Android 기기에서의 NAS 로그인, SMB/SFTP 전송, 원격 영상
  EAC3/DTS/TrueHD 재생, 문서 렌더링, TV 리모컨 동작은 별도 실기기
  확인이 필요함. JVM 테스트만으로 전체 실기기 동작을 보증할 수 없음.

## 자동 의존성 갱신

- GitHub main의 .github/dependabot.yml이 Android Gradle 직접
  의존성을 매주 월요일 오전 5시(KST)에 검사하여 PR/커밋을 생성.
- .github/workflows/android-dependencies-pr-check.yml은 해당 PR에
  대해 Gradle 빌드·JVM 테스트·내장 JS 체크를 수행.
- 자동 병합, 자동 서명, 자동 OTA 릴리즈는 비활성.
- Media3/NextLib 쌍은 독립 자동 업데이트에서 제외.
- Gradle Wrapper 9.9.1 배포 ZIP과 Wrapper JAR의 공식 SHA-256
  검증 값은 gradle/wrapper/gradle-wrapper.properties 및 QA 기록 참조.

## 서명 및 배포 보안

- 운영 서명키는 GitHub에 업로드하지 않고 별도 보안 저장소의
  기존 key-generation 환경을 사용.
- APK의 서명 인증서는 구버전 v0.1.100과 같아야 하며, 해시를 확인.
- release-staging/v0.1.101에는 APK와 공개 릴리즈 메타데이터만 포함.
- GitHub OTA 워크플로는 SHA256SUMS 및 versionCode.txt를 검사한 후
  Android 정식 릴리즈를 게시.
- WinFsp 등 PC 프로젝트 파일은 Android 앱 빌드에 영향을 주지 않음.

문제 발생 시 GitHub에서 v0.1.100 APK를 찾아 별도 테스트 기기에서
복구할 수 있지만, Android가 기본적으로 낮은 versionCode로의
덮어쓰기를 막으므로 데이터가 있는 본기기에서 무조건 다운그레이드
설치를 시도하지 말 것.
