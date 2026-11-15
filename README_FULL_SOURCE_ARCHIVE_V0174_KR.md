# FileHub AIO Android v0.1.74 전체 소스 백업 안내

- 이 폴더의 소스, Android 리소스, HWP/Office 뷰어 에셋, 코덱 애드온, Gradle 설정과 wrapper, 테스트, 개발 이력이 소스 백업 ZIP에 포함됩니다.
- `.gradle/`, 모든 `build/`, `.idea/` 등 **재생성 가능한 캐시·컴파일 산출물**은 ZIP에서 제외됩니다.
- 릴리스 서명 설정은 프로젝트 **외부**의 `../signing/signing.properties`와 비공개 키스토어를 참조하도록 되어 있으며, 개인정보·서명 비밀키는 ZIP에 포함하지 않습니다. 원래 릴리스 서명으로 배포하려면 별도의 키와 서명 설정이 필요합니다.
- `local.properties`는 기존 컴퓨터에 종속된 Android SDK 경로이므로 다른 환경에서는 SDK 경로를 수정해야 합니다.
- 같은 폴더에 배포된 v0.1.74 APK는 소스 백업 ZIP과 별도 파일입니다.
- 압축을 풀어 `NASMountAndroid_0_1_74` 폴더에서 Gradle wrapper를 통해 빌드할 수 있습니다. 서명 키가 없으면 기존 서명으로 APK 덮어 설치할 수 없습니다.
