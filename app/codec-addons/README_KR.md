# NAS Mount Android v0.1.14 내장 코덱 / 외부 파일 보관

v0.1.14부터 **NextLib FFmpeg 1.11.1-0.18.0이 APK에 포함**되어 있습니다.
기본 Android 디코더에서 미지원되는 E-AC3·TrueHD·DTS 등은
`NextRenderersFactory(EXTENSION_RENDERER_MODE_ON)`에서 FFmpeg 확장으로
자동 선택하도록 구성했습니다.

사용자가 설정에서 코덱 폴더를 선택하면 `.so`, `.aar`, `.zip` 파일을
Android 앱 전용 NASMount/codecs 폴더로 안전하게 가져오고, 무결성 검사
성공 후 파일 제공자가 삭제를 허용하면 이동하고 아니면 복사합니다.
**가져온 임의의 바이너리를 재생 중 직접 로드하지 않습니다.**
MX Player 전용 `libffmpeg.mx.so`는 Media3 디코더로 실행할 수 없습니다.

NextLib은 GPLv3이므로 제3자 배포 시 해당 라이선스를 반드시 준수합니다.

---

## v0.1.8의 이전 선택형 빌드 방식(더 이상 필수 아님)

## 중요한 구분

기본 APK는 Android 플랫폼에 내장된 E-AC3 디코더가 있으면 바로 사용한다.
모든 제조사/기기에서 E-AC3를 지원한다고 보장하지 않는다.

Android의 .so 파일은 운영체제·CPU ABI·JNI 인터페이스가 일치해야 한다.
오디오 디코더 .so 하나를 휴대폰의 임의 폴더에 놓는 것만으로는
Media3 ExoPlayer가 자동으로 인식할 수 없다. 검증되지 않은 네이티브
플러그인을 실행 중 임의 로딩하지 않는다.

## 선택적 애드온 빌드 방식

1. LGPL/GPL 등의 배포 의무를 확인하고, Media3 **1.11.1**과 호환되는
   FFmpeg Audio Renderer 클래스
   `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` 및
   해당 CPU ABI용 .so를 포함한 신뢰 가능한 AAR을 빌드한다.
2. 검증한 파일을 이 폴더에 `media3-ffmpeg-eac3.aar`로 넣는다.
3. APK를 다시 빌드하면 Gradle에서 이 확장 AAR을 조건부 포함한다.
4. 설치 후 플레이어의 상단 'EAC3'에서 모듈 포함 여부를 확인할 수 있다.
5. 실제 E-AC3 영상으로 음성 재생을 검증한다. 단순 렌더러 클래스
   존재 여부만으로 디코딩 성공을 보장하지 않는다.

## 오픈소스 참고

- Next Player: https://github.com/anilbeesetti/nextplayer
- NextLib (Media3 FFmpeg 확장): https://github.com/anilbeesetti/nextlib
- 두 프로젝트는 GPLv3. 소스/수정물/배포 방식에 따라 라이선스 준수가
  필요하므로 이 릴리스에는 NextLib 바이너리나 코드를 포함하지 않는다.
- NextLib의 `NextRenderersFactory`는 별도 API를 사용하므로 본
  `DefaultRenderersFactory`와 그대로 호환되는 AAR로 가정하면 안 된다.
  NextLib을 선택한다면 렌더러 생성부도 해당 API로 연결해야 한다.

현재 배포 APK에는 FFmpeg 확장 AAR/.so가 **미포함**이다.
