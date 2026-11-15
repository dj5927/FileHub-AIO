# NAS Mount Android v0.1.46 — EPUB 열기 오류 수정

## 수정한 문제

- Galaxy Fold에서 EPUB을 열면 빈 화면에
  "EPUB을 열지 못했습니다: http://apache.org/xml/features/disallow-doctype-decl"
  오류가 표시됨.
- 원인: v0.1.45의 EpubArchive.parse()가 Apache/Xerces XML 보안
  기능을 모든 Android 기기가 지원한다고 가정하고,
  DocumentBuilderFactory.setFeature()를 강제로 호출함.
- Android 기본 XML 파서는 이 기능 URI를 지원하지 않는 경우가
  있으며, ParserConfigurationException 때문에 EPUB 정상 파일도
  container.xml 해석 전에 열기 실패.

## v0.1.46 변경

1. Android XML 파서에서 지원되지 않는 기능을 선택적 설정으로
   취급해 정상 XML 파싱 진행을 막지 않도록 수정.
2. 보안을 낮추지 않기 위해 XML을 파싱하기 전에 DTD 및 ENTITY
   선언을 바이트 수준에서 검사 후 거부. UTF-8/UTF-16LE/UTF-16BE
   테스트 추가.
3. XML DocumentBuilder의 EntityResolver에서도 외부 엔티티 요청을
   명시적으로 차단. XInclude 및 엔티티 확장도 비활성화.
4. 정상 EPUB2/3의 container.xml/OPF의 XML 네임스페이스 및
   메타데이터 처리 기능은 그대로 유지.
5. EPUB 외 압축파일·TXT·영상·NAS 연결·폴더 지정 등은
   수정하지 않음. v0.1.45 데이터 및 책갈피는 유지됨.

## 검증 항목

- Android Gradle: JUnit 단위 테스트, lintDebug, lintVitalRelease,
  assembleRelease.
- EPUB 합성 ZIP을 이용한 OPF spine 본문 순서 테스트.
- 지원되지 않는 XML 기능 URI를 설정할 때 실패 대신 안전하게
  우회하고 정상 EPUB XML을 파싱하는 회귀 테스트.
- XML 외부 파일 접근을 유도하는 DTD/ENTITY를 UTF-8 및
  UTF-16 인코딩에서 차단하는 보안 테스트.
- XML namespace prefix가 있는 한국어 EPUB 제목 파싱 테스트.

## 참고 사항

- 드래곤 라자 1.epub 실제 원본 파일을 전달받은 것은 아니고,
  첨부된 Galaxy Fold 오류 화면으로 원인을 특정하여 수정함.
- Galaxy Fold 실기기를 개발 환경에 연결하지 못한 경우 실제
  웹뷰 조판 및 페이지 넘김은 기기에서 추가 검증 필요.
- DRM 보호 EPUB은 지원하지 않음.
- 수정은 별도의 v0.1.46 소스 폴더와 APK로 배포, 이전 파일 보존.
