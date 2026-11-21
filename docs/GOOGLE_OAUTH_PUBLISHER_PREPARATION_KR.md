# FileHub AIO — Google OAuth 공개 배포 준비 자료

작성일: 2026-11-21  
문서 상태: **운영자 내부 검토용 초안. 공개 개인정보처리방침이나 Google 승인 완료 증빙이 아님.**

## 1. 목적과 현재 상태

목표는 배포받은 사용자가 FileHub의 연결 버튼을 누르고 일반 브라우저에서 자신의 Google 계정을 승인한 뒤, Windows 드라이브에서 자신의 Google Drive 파일을 사용하는 것이다. 게시자가 등록한 Desktop OAuth 클라이언트를 제품에 구성하며, 최종 사용자에게 Google Cloud 프로젝트 생성, JSON 제공, 클라이언트 ID 입력 또는 토큰 복사를 요구하지 않는다.

이 문서는 최신 `GOOGLE_DIRECT_HANDOFF_20261120_KR.md`와 현재 Windows 소스의 **게시자 소유 OAuth → Google 직접 API → WinFsp** 경로만 검토했다. README의 과거 안정판 설명, 다른 저장소 제공자, Android 제품 및 역사적 OpenList/rclone 경로 전체에 대한 정책을 대신하지 않는다.

게시자용 Desktop OAuth 클라이언트와 실제 사용자 승인은 아직 확인되지 않았다. 작업용 브라우저의 Cloud Console 접근은 `Site Unavailable / HTTP 403`으로 막혔지만, 사용자의 일반 Windows Chrome에서는 기존 `nas mount` 프로젝트(ID: `nas-mount-514420`)에 접근할 수 있다. 2026-11-21 후속 화면에서 `OAuth 구성 생성됨` 알림과 `아직 이 프로젝트에 OAuth 클라이언트를 구성하지 않았습니다` 안내를 확인했다. 초기 Google Auth 구성 생성은 완료됐으며 다음 단계는 Desktop app 클라이언트 생성이다. 이 개요 화면만으로 앱 이름·지원 이메일·External 선택값·프로젝트 소유권·Drive API 활성화·공개 검증 상태를 확정하지 않는다. 실제 화면의 값을 이 자료와 대조해야 한다.

## 2. 등록 항목 준비표

| 항목 | 준비 값 또는 상태 | 확인 사항 |
| --- | --- | --- |
| 앱 표시 이름 | FileHub AIO | 제품명·홈페이지·동의 화면·실행 파일 설명의 표기 일치 확인 |
| 제품 설명 | Windows용 NAS·클라우드 드라이브 연결 앱 중 Google Drive 직접 연결 기능 | 현재 문서의 범위는 Google Drive PC 기능 |
| OAuth 클라이언트 유형 | Desktop app | 게시자 프로젝트에서 생성. Web application 클라이언트나 타사 앱 클라이언트 차용 금지 |
| 사용자 유형 | External | 배포받은 외부 사용자의 각자 Google 계정 사용이 목적 |
| 배포 상태 목표 | Production | Testing 사용자 목록을 최종 배포 해법으로 사용하지 않음 |
| API | Google Drive API | 실제 게시자 프로젝트에서 활성화 여부 확인 필요 |
| 요청 범위 | `https://www.googleapis.com/auth/drive` | Restricted 범위. 아래 기능별 필요성과 검증 제출 필요 |
| 법적 운영 주체·책임자 | **확인 필요 — 값 미정** | 저장소 계정명, 라이선스 저작자, NAS 예시 도메인을 운영 주체로 추정하지 않음 |
| Google Cloud 프로젝트·소유 계정 | `nas mount` / `nas-mount-514420`, 초기 OAuth 구성 생성 확인, 소유 계정 미확인 | 2026-11-21 사용자 후속 화면 기준. 클라이언트 미생성 안내 표시. 소유·관리 권한과 복구 가능한 관리 체계는 별도 확인 |
| 사용자 지원 이메일 | **확인 필요 — 값 미정** | 실제 수신·응답 가능한 운영자 지정 주소 |
| 개발자 연락 이메일 | **확인 필요 — 값 미정** | Google 심사 연락 수신용. 사용자 지원 주소와 각각 확인 |
| 공식 홈페이지 URL | **확인 필요 — 값 미정** | 실제 앱 소개와 공개 정책 링크가 있는 운영자 관리 페이지 |
| 개인정보처리방침 URL | **확인 필요 — 값 미정** | 운영자 승인·공개 후 입력. 이 내부 문서를 그대로 제출하지 않음 |
| 승인 도메인·소유권 | **확인 필요 — 값 미정** | 홈페이지·정책의 실제 도메인 및 Search Console 소유권 확인 |
| 로고 | **제출본·사용 권한 확인 필요** | 권한이 확인된 제품 자산만 사용 |
| 소스·PC 업데이트 저장소 | `dj5927/FileHub-AIO` | 소스/업데이트 위치로 확인됨. 공식 홈페이지·정책·법적 주체의 증명으로 대체하지 않음 |

External과 Production 설정만으로 브랜드 및 제한 범위 심사가 완료되지는 않는다. 공개 배포 목표에 맞는 브랜드·범위 검증 상태를 별도로 확인해야 한다. 홈페이지와 개인정보처리방침은 실제 앱의 기능과 데이터 처리를 설명해야 하며, 도메인 소유권도 확인해야 한다. [G2][G4]

## 3. Drive 전체 범위의 필요성 — 제출 설명 초안

> FileHub AIO는 사용자가 승인한 Google Drive를 Windows 드라이브로 연결합니다. 사용자는 이미 보유한 폴더와 파일을 탐색하고 파일 내용을 읽으며, 허용된 쓰기 모드에서 폴더 생성, 새 파일 업로드, 이름 변경 및 휴지통 이동을 수행합니다. 앱이 생성했거나 개별 선택으로 허용된 파일만 다루는 방식은 기존 Drive 폴더 구조를 드라이브로 탐색하는 제품 기능을 충족하지 못합니다. 읽기 전용 범위는 생성·업로드·이름 변경·휴지통 이동에 필요한 쓰기를 제공하지 않습니다. 해당 기능을 제공하기 위해 Drive 전체 범위를 요청하며, 접근은 사용자가 승인한 계정과 앱에서 수행하는 파일 작업에 사용합니다.

위 문장은 **제품 설계에 따른 요청 근거**이며 Google이 범위를 승인했다는 뜻이 아니다. 제출 직전에 실제 공개판에서 제공할 기능과 일치하도록 검토해야 한다.

| 범위 | 현재 기능과의 관계 |
| --- | --- |
| `drive.file` | 앱 생성·개별 허용 파일 중심의 범위. 임의의 기존 Drive 트리 탐색이라는 현재 요구와 차이가 있음 |
| `drive.readonly` | 읽기만 제공하면 후보가 될 수 있으나 새 파일 업로드와 폴더·이름·휴지통 작업에 부족함 |
| `drive` | 현재 코드가 요청하는 범위. Google 분류상 Restricted이며 최소 범위 적합성과 심사 필요 |

현재 실계정 경로에서는 기존 파일의 내용 덮어쓰기를 허용하지 않는다. 따라서 Office 기존 문서 편집·경합 해결·완전한 네트워크 드라이브 동등성을 심사 설명이나 홍보 자료에 보장해서는 안 된다. 영구 삭제 대신 휴지통 이동을 구현한 점도 실제 동작대로 설명한다. Google 문서의 범위 분류는 [G3], 제한 범위 검증은 [G5]를 기준으로 한다.

## 4. 인증 및 외부 전송 흐름

| 단계 | 확인된 처리 | 근거 소스 |
| --- | --- | --- |
| 브라우저 승인 | 일반 Chrome 실행, 실패 시 시스템 브라우저. 강제 시크릿 창이나 개인 브라우저 탭의 원격 제어를 사용하지 않음 | `GoogleDriveAuth.cs` |
| 승인 요청 | `accounts.google.com/o/oauth2/v2/auth`, 계정 선택·동의, 임의 state, S256 PKCE, offline access | `GoogleDirectBrowserAuth.cs` |
| 승인 결과 수신 | PC의 `127.0.0.1` 임시 포트 `/callback`. state·중복 파라미터·길이·유효한 거절 응답 검증 | `GoogleDirectBrowserAuth.cs` |
| 코드 교환·재갱신 | `https://oauth2.googleapis.com/token`에 직접 POST. 새 토큰을 로컬 저장한 뒤 성공 처리 | `GoogleDirectBrowserAuth.cs`, `GoogleDirectOAuthSession.cs` |
| 계정 접근 확인 | Drive `about?fields=user(permissionId)`의 응답 성공 여부 확인. 이 코드에서는 permissionId 본문 값을 파싱·별도 저장하지 않음 | `GoogleDriveAuth.cs` |
| 파일 작업 | `https://www.googleapis.com/drive/v3/` 및 Google Drive 업로드 경로. 목록·메타데이터·파일 읽기·업로드·허용된 변경 작업 | `GoogleDirectApiClient.cs`, `RemoteMount.GoogleDirect.cs` |
| 업데이트 확인·다운로드 | GitHub Releases API와 해당 저장소의 HTTPS 릴리스 파일. 다운로드에는 GitHub의 리디렉션/CDN이 관여할 수 있음 | `PcUpdateService.cs` |

사용자가 입력하는 Google 계정 비밀번호는 Google 브라우저 화면에서 처리한다. 앱은 OAuth 승인 결과를 처리하며 승인 코드를 게시자 중계 서버로 전달하는 현재 경로는 없다. 게시자 클라이언트 구성의 사전 확인은 새 HTTP 쿠키 저장소를 사용하는 익명 요청이며, 실제 사용자 브라우저의 쿠키를 읽거나 재사용하지 않는다. Desktop 공개 클라이언트의 배포 메타데이터를 비밀 서버 키처럼 취급해서 보안을 설명하지 않는다. [G1]

새 Google 정상 연결은 OpenList 서버를 호출하거나 rclone의 Google 인증을 이용하지 않는다. 기존 중계 토큰 파일은 자동 폐기하지 않지만 게시자 승인으로 재사용·재갱신하지 않는다. GitHub 업데이트 전용 HTTP 클라이언트에는 Google Bearer 토큰이나 Drive 파일을 넣는 코드가 없다. 위 표는 확인한 경로의 목록이며, 브라우저·Windows 자체 서비스나 다른 저장소 기능까지 포함한 전체 프로그램 네트워크 조사 완료를 의미하지 않는다.

## 5. 로컬 보관 데이터와 현재 보존 동작

앱의 기본 데이터 위치는 `%APPDATA%\NASMount`이다. 실제 사용자 폴더나 토큰 내용은 열지 않고 소스만 검토했다.

| 데이터 | 위치·내용 | 보호·보존 동작 |
| --- | --- | --- |
| 연결 설정 | `config.json`: 프로필 GUID, 사용자 지정 이름, 드라이브 문자, 연결 옵션 및 입력된 경로 등 | 일반 JSON. 저장 후 다음 실행에 사용. 계정 비밀번호를 이 파일에 저장한다는 뜻은 아님 |
| Google OAuth 자격 증명 | `google-direct-auth\<프로필 GUID>.token`: access/refresh token, 만료 시각, 클라이언트 메타데이터 | Windows DPAPI CurrentUser로 암호화. 저장·복구용 `.previous`, `.before-restore` 사본이 생길 수 있음. 로그아웃·프로필 삭제와 연동한 자동 삭제는 구현되어 있지 않음 |
| 메모리 캐시 | 폴더 목록, 파일 이름·ID·크기·형식·수정 시각·버전/체크섬, 읽은 파일 블록 | 직접 API/WinFsp 프로세스의 메모리 캐시. 목록의 일반 유효 시간 45초, 특정 일시 장애에서 오래된 목록 최대 5분 사용. WinFsp 항목·읽기 블록 유효 시간 30초. TTL은 정확한 시점의 보안 삭제 보장이 아님 |
| 업로드 준비·실패 복구 | `google-direct-stages\<프로필 GUID>\*.data`, `*.json` | 파일 내용과 이름·상위/대상 ID·상태·크기·체크섬 등. **이 파일들은 DPAPI로 암호화하지 않음.** 성공 또는 명시적 폐기 후 정리하며, 실패 시 내용과 기록을 보존. 정리 실패 시 잔여 파일이 남을 수 있음 |
| 복구 내보내기 | 사용자가 선택한 폴더의 복구 파일 | 원본 복구 기록을 지우지 않고 별도 복사. 기존 대상 파일을 덮어쓰지 않음. 내보낸 복사본의 후속 관리도 별도로 필요 |
| 로컬 로그 | `logs\yyyy-MM-dd.log`: 시각·상태·오류 등 | 현재 `AppStore.Log`에는 일수 기반 자동 삭제가 없음. 직접 Google 경로는 토큰·원시 인증 URL 대신 제한된 상태/오류를 기록하도록 구성했지만, 모든 앱 로그가 개인정보를 포함하지 않는다고 보장하지 않음 |
| 업데이트 파일 | `updates\<태그>\update.zip`, 추출 패키지 및 다운로드 임시 파일 | 업데이트 확인·다운로드/설치용. 실패 시 임시 다운로드·추출물을 정리하는 코드가 있으나 전체 이력에 대한 보존 기간을 보장하지 않음 |
| 사용자가 만든 설정 백업 | 사용자가 지정한 백업 파일 | 기존 `ProfileBackup`은 구형 `google-auth\*.conf`를 읽어 암호화 설정 백업에 포함할 수 있음. 새 DPAPI `google-direct-auth` 토큰 내보내기 기능으로 오인하면 안 됨. 로그·파일 캐시·복구 파일을 담는 백업이 아님 |

따라서 “모든 Google 데이터가 암호화되어 저장됨”, “앱 종료 즉시 파일 내용 전부 삭제”, “연결 삭제로 모든 로컬 데이터 삭제” 같은 문구는 현재 구현과 일치하지 않는다. 장치의 파일 접근 권한·디스크 암호화·운영체제 백업이 로컬 파일에 미치는 영향도 공개 정책을 확정할 때 검토해야 한다.

## 6. 연결 해제, Google 권한 취소, 로컬 삭제의 구분

| 사용자 동작 | 현재 수행하는 일 | 수행하지 않는 일 |
| --- | --- | --- |
| 드라이브 연결 해제 | 해당 마운트 종료. 열린 핸들·복구 대기 때문에 안전 종료가 거절될 수 있음 | Google 서버의 OAuth 권한 취소, 모든 로컬 데이터 삭제 |
| 연결 설정 삭제 | 안전 상태 확인 후 설정 목록에서 프로필 제거, 해당 Windows Credential Manager 항목 정리 | `google-direct-auth`의 DPAPI 토큰/백업 삭제, Google OAuth 취소, 복구 파일 전체 삭제 |
| Google 계정에서 접근 권한 삭제 | Google의 연결된 앱 관리에서 해당 앱의 계정 접근을 취소할 수 있음 | 이미 PC에 저장된 내용·설정·복구 파일·내보내기 파일 자동 제거 |
| 복구 파일 내보내기 | 사용자가 선택한 폴더로 복구 가능한 로컬 내용을 복사 | 원본 복구 기록 삭제나 Google 재업로드 |

현재 검토한 코드에는 Google OAuth revoke 엔드포인트를 호출하는 UI/메서드가 없다. 사용자는 Google 계정의 연결된 앱 관리에서 FileHub의 **계정 접근 권한**을 검토하고 제거할 수 있다. 단순히 앱의 자동 로그인을 해제하는 항목과 구분해 안내해야 한다. 공식 절차는 [G6]이다.

로컬 자격 증명 삭제·보존 기간·삭제 요청 응대 방법은 공개 정책 확정 전 운영자가 결정하고 실제 구현과 맞춰야 한다. 수동 정리 안내를 만들 경우 먼저 해당 프로필을 안전하게 연결 해제하고 미업로드 복구 내용을 확인·내보낸 뒤, 식별된 프로필의 토큰 및 사본·복구 파일·설정·관련 로그·내보낸 복사본을 각각 검토하도록 해야 한다. 다른 연결이 공유하는 `%APPDATA%\NASMount` 전체를 일괄 삭제하는 안내는 제공하지 않는다. 이 문서를 작성하면서 실제 파일을 삭제한 작업은 없다.

## 7. 공개 정책 및 검증 제출 전에 확정할 사항

1. 실제 운영 주체, 지원·개발자 연락처, 공식 홈페이지·정책 도메인과 소유권을 확정한다. 저장소의 NAS 예시 도메인이나 GNU GPL 본문의 FSF 저작자 표기를 운영자 정보로 사용하지 않는다.
2. Google 데이터의 접근 목적, 보관 위치, 제3자 전송, 사용자 통제, 삭제·보존 기준을 위 소스 근거와 맞춰 공개 문서로 작성한다. 광고·판매·분석·인적 열람·향후 서버 처리에 관한 운영상 약속은 운영자가 실제 방침을 승인해야 하며, 코드 검토만으로 확정하지 않는다.
3. 보류 업로드의 평문 로컬 보관, 프로필 삭제 후 토큰 잔존, 별도 권한 취소 및 새 인증 백업의 한계를 공개 정책·제품 설명과 맞춘다. 삭제 UI를 추가한다면 구현·검증 후에만 있다고 설명한다.
4. 게시자 Desktop 클라이언트·External·Production, 활성화한 API와 실제 요청 범위를 확인한다. 검증 여부와 정책 준수를 Publishing 상태 하나로 대체하지 않는다.
5. Restricted 범위의 검증 및 보안 평가 적용 여부는 실제 배포 구조와 Google 심사 요구로 판단한다. “PC 앱이므로 자동 면제” 또는 “서버가 없으므로 심사가 필요 없음”으로 단정하지 않는다. [G5]
6. 실제 게시자 클라이언트로 승인 화면, 앱 복귀, 갱신, 사용자 소유의 시험 파일 목록·읽기·새 업로드·이름 변경·휴지통 이동 및 오류/복구 동작을 검증한다. 시연에는 동의한 시험 계정과 폐기 가능한 파일만 사용한다.

## 8. 현재 갖춘 증빙과 아직 없는 증빙

최신 이력에 기록된 근거는 Windows Release 빌드 성공, UI 정책 4개 및 OAuth/API 정책 28개 통과, 모의 loopback/state/거절/응답 크기/사전 확인 검사, 구형 중계 토큰의 네트워크 요청 없는 거부와 암호화 파일 보존 검사이다. 게시자 패키징의 합성 구성 DryRun 성공도 실제 클라이언트가 유효하다는 증거가 아니다.

기록된 실제 게시자 준비 검사 결과는 `GOOGLE_PUBLISHER_LIVE_AUTH_UNAVAILABLE code=publisher_not_configured`였다. **실계정 승인 성공, Google의 브랜드·범위 승인, 실제 Drive 마운트와 파일 저장 시험, 공개 배포 완료 증빙은 아직 없다.** 등록 진행 후 이 단락을 실제 증거와 날짜로 갱신해야 한다. 이 초안 작성은 공개 페이지 게시, GitHub 업로드, 검증 신청 제출 또는 사용자 데이터 조회를 수행하지 않는다.

## 9. 근거 파일

| 근거 | 확인 대상 |
| --- | --- |
| `GOOGLE_DIRECT_HANDOFF_20261120_KR.md` | 최근 사용자 요구, 구현 전환, 테스트 및 미완료 항목 |
| `GoogleDriveAuth.cs`, `GoogleDirectBrowserAuth.cs` | 정상 브라우저 승인·loopback·PKCE·Google 접근 확인·로컬 성공 처리 |
| `GoogleDirectOAuthSession.cs`, `GoogleDirectCredentialStore.cs` | Google 직접 갱신·게시자 grant 판별·DPAPI 저장·이전 파일 보존 |
| `GoogleDirectApiClient.cs`, `RemoteMount.GoogleDirect.cs` | Google 직접 라우팅·파일 API·휴지통·연결 해제 안전 장치 |
| `LockBridge/DirectGoogleWinFsp.cs`, `LockBridge/DirectGoogleWriteStage.cs` | 캐시·쓰기 제한·로컬 업로드/복구 기록 |
| `GoogleDirectRecovery.cs` | 복구 내보내기와 원본 보존 |
| `Core.cs`, `MainForm.cs`, `ProfileBackup.cs` | 데이터 위치·로그·연결 삭제·기존 설정 백업 |
| `PcUpdateService.cs`, `README.md`, `LICENSE` | 업데이트 대상, 역사적 제품 설명, 배포 라이선스 문서. 운영자/정책 URL의 대체 근거가 아님 |

## 10. Google 공식 참고 자료

- [G1 — OAuth 2.0 for Mobile & Desktop Apps](https://developers.google.com/identity/protocols/oauth2/native-app): Desktop 클라이언트, 시스템 브라우저, loopback, PKCE 및 토큰 처리.
- [G2 — Production readiness overview](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview): External/Production과 검증 상태의 구분.
- [G3 — Choose Google Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth): Drive 범위의 기능과 Restricted 분류.
- [G4 — Submit for brand verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/brand-verification): 실제 브랜딩, 연락처, 홈페이지·정책과 도메인 확인.
- [G5 — Restricted scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification): 제한 범위 검증·보안 평가 관련 요구.
- [G6 — Manage links between your Google Account & apps from other developers](https://support.google.com/accounts/answer/13533235): Google 계정 접근 권한 확인·제거 절차.

공식 자료 확인일: 2026-11-21. 정책과 콘솔 항목은 변경될 수 있으므로 실제 신청 직전에 다시 확인한다.
