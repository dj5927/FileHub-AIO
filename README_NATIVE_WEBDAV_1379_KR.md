# NAS Mount v1.3.79 WebDAV 네이티브 잠금 실험 (배포/사용 금지)

## 목적

Excel 문서 옆의 `파일.xlsx.nasmount.lock` 폴더가 다른 클라이언트에서 보이는
문제를 해결하기 위한 서버 기본 `LOCK` 잠금 프로토콜 실험이다.

**현재 사용자에게 설치하는 새로운 PC NAS Mount 배포본이 아니다.**
일반 실행/드라이브 연결/실제 Office 파일 쓰기 경로는 기존 v1.3.78
`CooperativeLocks`와 rclone/WinFsp 브리지를 그대로 사용한다.
이 실험은 실제 원격 폴더에 어떠한 테스트 잠금 파일도 만들지 않는다.

## 구현

- `NativeWebDavLockProtocol.cs`: RFC4918 LOCK (Depth=0, Timeout), 두 번째
  잠금 충돌, Lock-Token 수신 검증, `If: (<token>)`을 포함하는 PUT,
  `<목적지 URL> (<token>)`으로 태그된 If 조건을 포함하는 MOVE,
  body 없는 LOCK refresh, UNLOCK, 만료 임박 잠금 쓰기 차단.
- `NativeWebDavLockLab.cs`: 네트워크 없이 `HttpMessageHandler` 메모리
  모의 WebDAV 서버에서 2명의 클라이언트가 같은 문서에 접근하는 시나리오 검사.
  잠금 없는 PUT 차단과 원본 보존, 다른 PC의 잠금 충돌, 정당한 소유자의
  조건부 PUT 및 Excel 임시 파일 MOVE, 10초 이내 만료된 잠금 거절,
  지원하지 않는 LOCK 및 잘못된 토큰 실패, UNLOCK 이후 두 번째 접근,
  `.nasmount.lock` 또는 `owner.json` 생성 0건 검증.
- 프로그램 진입 명령 `--native-webdav-lock-lab-test`: 외부 NAS, 실사용
  계정, 볼륨 문자, rclone, 사용자의 파일에는 접근하지 않음.

## 실제 검증

- `dotnet build NASMount.csproj -c Release --no-restore`: 성공, 오류 0
  (기존 WebView2/nullable 경고 5).
- `dotnet build LockBridge/LockBridge.csproj -c Release --no-restore`: 성공,
  오류 0 (기존 브리지 nullable 경고 33).
- `dotnet bin/Release/net10.0-windows/win-x64/NASMount.dll --native-webdav-lock-lab-test`:
  `NATIVE_WEBDAV_ZERO_SIDECAR_PROTOCOL_LAB_PASS checks=23`.
- `dotnet bin/Release/net10.0-windows/win-x64/NASMount.dll --self-test`:
  기존 자체 회귀 테스트 종료 코드 0.

## 실제 배포에 앞서 필요한 작업

1. 현 rclone 업로드는 WebDAV LOCK 토큰을 PUT/MOVE에 붙이지 않으므로,
   잠금 사용자의 업로드가 423 Locked로 실패할 수 있다. **이를 보완할
   per-file 토큰 전달 기능을 rclone backend에 추가하거나 승인된 중계 계층을
   만들어야 한다.** 전역 `--webdav-headers`는 동시 파일별 토큰이 달라
   정상적인 해결책이 아니다.
2. Excel 저장은 새 임시 파일을 업로드하고 최종 파일로 MOVE/덮어쓰기한다.
   PUT, MOVE, DELETE, 디렉터리 충돌, 잠금 만료/갱신, 잠금 소유권,
   WinFsp Close 이후 지연 업로드 완료 시점에 토큰 전송을 검증해야 한다.
3. 실제 NAS의 WebDAV가 RFC4918 LOCK을 지원하는지 먼저 안전한
   테스트 문서로 확인한다. 미지원이면 문서 쓰기를 허용하지 않아야 하며
   잠금 없는 쓰기를 자동으로 허용하면 안 된다.
4. 여러 PC가 기존 `.nasmount.lock` 버전과 새 native LOCK 버전을
   섞어 사용하면 서로의 잠금을 보지 못한다. 혼용 기간 대응이나 모든
   PC 동시 전환 설계가 필수다.
5. 실제 Synology 및 다른 서버의 2대 PC 동시 열기/수정 차단,
   수백 MB 이상 Excel 저장, rename 실패 복구, crash 후 TTL 회복,
   호환되지 않는 다른 클라이언트의 저장 차단 등을 실서버에서 검증한다.

**현 단계 결론:** 별도 잠금 폴더 없는 네이티브 서버 잠금 자체는
실험으로 재현했으나, 프로덕션 연결·업로드 통합은 완료되지 않았다.
기존 동작 중인 E:\NASMount_Releases\NASMount_1_3_78_GOOGLE_COLD_START_FULL\NASMount.exe,
Windows 드라이브, NAS 원본 파일과 서버측 잠금 폴더는 변경하지 않았다.
