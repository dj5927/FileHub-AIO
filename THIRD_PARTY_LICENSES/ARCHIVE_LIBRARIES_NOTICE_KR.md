# 추가 아카이브 라이브러리 고지

본 NAS Mount Android 앱에는 다음 소프트웨어가 Maven Central 의존성으로
포함됩니다. 전체 원 라이선스는 각 소스 프로젝트의 LICENSE를 확인하십시오.

- Apache Commons Compress 1.28.0
  https://commons.apache.org/proper/commons-compress/
  Apache License, Version 2.0.

- XZ for Java 1.12 (LZMA/LZMA2)
  https://tukaani.org/xz/java.html

- junrar 8.1.2 (RAR 추출 전용)
  https://github.com/junrar/junrar
  UnRAR 고지 및 관련 조건 포함.
  **UnRAR 소스 코드는 RAR(WinRAR) 호환 압축 프로그램을 개발하거나
  RAR 압축 알고리즘을 재구현하는 용도로 사용할 수 없습니다.**
  NAS Mount는 RAR을 압축해제하는 데만 사용하며 RAR 생성은 구현하지 않습니다.

LibreOffice / Collabora Office, 이음오피스 / rhwp, HOP Android의 코드는
이번 앱에 포함하지 않았습니다. 설치된 호환 오피스 앱으로 인텐트
(content URI)를 전달하는 연결 방식입니다.
