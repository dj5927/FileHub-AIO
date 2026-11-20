## FileHub AIO Android v0.1.102 — WebDAV DDNS 인증서 별칭 개선

- 입력한 DDNS 주소를 변경하지 않고 어떤 제공업체 DDNS라도 WebDAV로 접속할 수 있도록 별칭 이름 검사를 보완했습니다.
- 정상적으로 신뢰된 HTTPS 인증서 체인은 그대로 확인하며, 사용자 지정 NAS 도메인의 인증서 이름 불일치만 예외로 처리합니다.
- WebDAV 폴더·파일·영상·복사·이동·삭제 및 시놀로지/QNAP 공유 링크 관리 HTTPS에 동일한 규칙을 적용합니다.
- Google Drive, TeraBox, Naver MyBox 및 다른 클라우드 HTTPS 인증서 처리는 변경하지 않았습니다.
- 530개 JVM 테스트, Android Lint, 서명된 Release APK 빌드 통과.
- 실제 Android 기기에서 DDNS 접속과 NAS 공유 링크 기능은 별도 사용자 환경 검증이 필요합니다.
- versionCode: 103
