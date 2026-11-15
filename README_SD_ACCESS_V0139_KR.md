# NAS Mount Android v0.1.39 — SD카드 접근 권한 수정

## 문제 원인

- Android 11(API 30) 이상은 Storage Access Framework(SAF)에서
  SD카드 전체 루트 폴더를 선택할 수 없도록 제한할 수 있습니다.
  시스템 파일 선택기의 '이 폴더 사용' 버튼이 비활성화되어도
  NAS Mount의 일반 런타임 권한 요청으로는 해결되지 않습니다.
- 기존 NAS Mount Android는 SD 저장소 선택 시 항상 SAF 폴더 선택창을
  먼저 호출해 이 제한에 영향을 받았습니다.
- 공식 참고: https://developer.android.com/training/data-storage/shared/documents-files
  및 https://developer.android.com/training/data-storage/manage-all-files

## 수정 내용

- 앱 첫 화면에서 SD 저장소를 선택하면 다음 옵션을 안내합니다.
  - **전체 접근 설정**: Android 11 이상에서 Android 시스템의
    NAS Mount '모든 파일에 대한 접근 허용' 설정으로 이동.
    권한이 실제로 허용되고 SD카드 폴더가 읽히면 SAF 없이
    SD카드 전체를 직접 열어 파일을 탐색/관리합니다.
  - **SD 폴더 선택**: 전체 권한을 원하지 않는 경우 SD카드의
    사용 가능한 하위 폴더에만 SAF 영구 권한을 부여합니다.
    SD카드 최상위 폴더는 기기에 따라 선택 불가할 수 있습니다.
- Android 10 이하: SD카드 폴더 선택을 사용합니다.
- 일반 파일 선택창이 아닌 StorageVolume의 SD 위치를 초기 위치로
  지정하여 SD카드를 찾는 번거로움을 줄였습니다.
- 사용자가 시스템 권한 설정에서 돌아오면 실제 권한 상태를 재검사하고
  허용된 경우 SD카드를 자동으로 엽니다. 거부한 경우 하위 폴더
  선택 기능으로 전환할 수 있도록 안내합니다.
- StorageVolume.getDirectory(API 30+)로 실제 장착된 SD 루트를 찾고
  기기별 대체 경로는 getExternalFilesDirs/StorageManager에서만
  도출합니다. 임의 UUID나 추정된 마운트 주소를 사용하지 않습니다.
- 승인한 SAF 폴더가 기기에 실제로 장착된 SD카드인지 UUID/권한을
  확인하고 영구 읽기 권한을 유지합니다. 읽기 전용 폴더일 경우
  상태를 구분해서 표시합니다.
- SD 폴더 화면에서 "SD카드 접근 권한 변경"으로 다시 설정 가능.
- SD 전체 경로에서 뒤로가기를 누를 때 내부 /storage 폴더로
  벗어나지 않고 NAS Mount 홈으로 복귀하도록 수정.
- 읽기 전용으로 마운트된 카드도 목록에서는 인식하고, 쓰기 권한은
  Android 또는 카드의 실제 상태에 따릅니다.

## 적용 방법 (삼성 등 Android 11 이상)

1. v0.1.39 APK를 기존 NAS Mount 위에 덮어 설치합니다.
2. 홈 > SD 저장소 > 전체 접근 설정 > 시스템에서
   NAS Mount의 '모든 파일에 대한 접근 허용'을 켭니다.
3. NAS Mount로 돌아오면 SD카드 전체가 자동으로 열리는지 확인합니다.
4. 전체 파일 권한을 사용하고 싶지 않으면 SD 저장소에서
   'SD 폴더 선택'을 누르고 SD카드 안의 하위 폴더를 선택합니다.
   이 방식은 선택한 폴더 및 하위 폴더만 읽고 쓸 수 있습니다.

## 검증 및 제한

- JUnit에서 SD카드 인식, Android 버전별 권한 분기,
  잘못된 저장소 URI 거부, 마운트 경로 추출 규칙을 검증합니다.
- Android Gradle 전체 테스트/Lint/릴리스 빌드 결과와 APK 서명은
  프로젝트 작업일지에 기록합니다.
- 개발 환경에 실제 SD카드가 장착된 Galaxy 기기가 연결되지 않았기에
  Samsung 파일 선택기와 쓰기 권한의 실기기 동작까지 완료를
  보장하지 않습니다.
- Android의 보호 영역 Android/data 및 Android/obb는
  전체 파일 접근 권한으로도 대부분 접근할 수 없습니다.
- 전체 파일 접근은 SD뿐 아니라 공유 저장소에 넓은 파일 접근 권한을
  허용하는 고위험 권한입니다. 사용자가 명시적으로 허용해야 합니다.
  사용자가 거부하면 하위 폴더 방식으로도 사용할 수 있습니다.
