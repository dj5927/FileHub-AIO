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

LibreOffice / Collabora Office 및 HOP Android의 실행 코드 자체는
앱에 포함하지 않습니다. 호환 앱이 설치되어 있으면 문서 인텐트
(content URI)를 전달하는 기능도 제공합니다.

주의: rhwp는 예외입니다. 이 앱에는 내장 오피스 뷰어용
rhwp 웹 리소스(JavaScript/WebAssembly)가 포함되어 있습니다.
app/src/main/assets/office_hwp 와 app/office-assets의 분할된
WASM 파일이 해당 배포물의 일부입니다. rhwp 원본 저작권 및
서드파티 라이선스는
app/src/main/assets/office_hwp/THIRD_PARTY_LICENSES.md
와 원 프로젝트 https://github.com/edwardkim/rhwp 에서 확인하십시오.

내장 JSZip 3.10.2는 MIT 또는 GPLv3 듀얼 라이선스,
docx-preview 0.4.1은 Apache License 2.0,
ZXing Core 3.5.4는 Apache License 2.0입니다.
