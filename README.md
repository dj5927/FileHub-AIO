# FileHub AIO

**NAS · Cloud · File Manager · Media & Document Viewer**

[한국어](#한국어) · [English](#english)

---

## 한국어

FileHub AIO는 NAS, 클라우드 및 로컬 저장소를 편리하게 이용하는 올인원 파일 관리 프로그램입니다. Windows에서는 원격 저장소를 네트워크 드라이브로 연결하고, Android에서는 파일 관리부터 미디어 재생과 문서 열람까지 하나의 앱에서 제공합니다.

### 지원 플랫폼

| 플랫폼 | 지원 환경 |
| --- | --- |
| Windows (PC) | Windows 10 / 11, x64 |
| Android | Android 8.0 이상, 스마트폰 · 태블릿 · 폴더블 |
| Android TV / Google TV | TV 전용 화면 및 리모컨 조작 |

### NAS 및 클라우드 연결

Windows와 Android 공통 지원:

| 연결 방식 | 용도 |
| --- | --- |
| SMB | Windows 공유 폴더 및 NAS |
| WebDAV | HTTP / HTTPS 기반 원격 저장소 |
| FTP / FTPS | FTP 서버 및 TLS 암호화 연결 |
| SFTP | SSH 기반 파일 전송 |
| Google Drive | Google 계정 인증 |
| Microsoft OneDrive | Microsoft 계정 인증 |
| 네이버 MYBOX | 개인 액세스 토큰(PAT) 연결 |
| TeraBox | 웹 로그인 및 세션 인증 |

- 연결 정보 저장, 읽기 전용 연결, 지원되는 저장소의 파일 읽기·쓰기.
- FTP / SFTP 파일명 문자 인코딩 설정.
- 암호화된 설정 백업 및 복원. 공통 NAS 연결 설정은 PC와 Android 간에 이전할 수 있습니다. Google Drive와 OneDrive 인증은 별도로 진행합니다.
- Synology NAS 공유 링크 생성 및 관리 기능. NAS 설정과 서버 API 지원 여부에 따라 이용할 수 있습니다.

Google Drive는 Google의 OAuth 승인 및 허용된 계정 범위에 따릅니다. OneDrive는 Microsoft 계정 및 조직의 동의 정책에 따라 연결이 제한될 수 있습니다.

### 네트워크 드라이브 (PC 전용)

- WinFsp 기반 원격 저장소 마운트 및 Windows 네트워크 드라이브 연결.
- 드라이브 문자 지정, Windows 탐색기에서 파일 열기·복사·이동·이름 변경·삭제.
- 연결된 저장소에서 Windows 애플리케이션 및 Office 문서 직접 열기.
- 지원되는 연결의 Office 파일 잠금 및 동시 편집 충돌 방지.
- 자동 연결, 시스템 트레이 관리, 저장소 용량 및 전송 상태 표시.
- Synology 공유 링크 생성 및 Windows 탐색기 연동.

### 파일 관리자 (안드로이드 전용)

- 내부 저장소, SD카드, USB 저장소 및 원격 저장소 탐색.
- 다중 선택, 복사, 이동, 삭제, 이름 변경, 폴더 생성 및 파일 검색.
- 백그라운드 파일 전송, 진행률 표시, 즐겨찾기, 정렬 및 목록·격자 보기.
- 휴지통: 로컬 파일과 폴더 복원, 완전 삭제, 자동 삭제 기간 설정(기본 7일). 외장 저장소의 접근 권한과 이동 지원 여부에 따라 동작합니다.
- ZIP / 7z 압축 및 해제, RAR 압축 해제 또는 외부 앱 연동(배포판에 따라 다름).
- 텍스트 파일 편집 및 저장.
- SFTP 파일 권한(chmod) 변경 및 심볼릭 링크 탐색.
- QNAP NAS 공유 링크 생성 및 관리(지원되는 서버 설정 필요).

### 미디어 및 독서 (안드로이드 전용)

- **동영상:** 로컬 및 원격 재생, 외부 자막, 이어보기, 다음 영상 자동 재생, 배속·밝기·볼륨 제어, 팝업 재생(PiP).
- **음악:** MP3, AAC, FLAC 등 지원 형식 재생, 백그라운드 재생.
- **이미지:** 사진 목록, 좌우 스와이프, 확대·축소.
- **만화책:** 이미지 및 지원 압축 형식, 페이지 이동과 읽기 진행 기록.
- **전자책:** TXT, EPUB, PDF 열람, 글꼴·여백 등 읽기 설정, 이어보기 및 책갈피.

실제 미디어 코덱 및 압축 형식 지원은 기기와 배포판에 따라 다릅니다.

### Office 및 문서 (안드로이드 전용)

| 형식 | 기능 |
| --- | --- |
| XLSX / XLS / ODS | 스프레드시트 열람, 지원 파일의 셀 편집·저장, 시트 전환·검색·확대 |
| DOCX | Word 문서 열람 |
| PPTX | PowerPoint 프레젠테이션 열람 |
| HWP / HWPX | 한글 문서 열람 |
| PDF | 페이지 탐색 및 확대·축소 |
| TXT 및 일반 텍스트 | 열람 및 편집 |

문서 편집·저장은 지원 형식, 저장소 쓰기 권한 및 이용 중인 에디션에 따라 제한될 수 있습니다.

### 화면 및 설정 (안드로이드 전용)

- 라이트·다크 테마 및 한국어·영어·일본어·중국어 간체 인터페이스.
- 스마트폰, 태블릿, 폴더블 화면 대응.
- Android TV 리모컨 모드, QR 코드 및 같은 네트워크를 통한 기기 간 설정 전송.

### 다운로드 및 소스

- **Windows:** [다운로드 (GitHub Releases)](https://github.com/dj5927/FileHub-AIO/releases/latest) · [PC 소스코드](https://github.com/dj5927/FileHub-AIO/tree/pc/windows)
- **Android:** 직접 배포용 APK는 별도 제공 · [Android 소스코드](https://github.com/dj5927/FileHub-AIO/tree/android/flutter-migration)
- **Google Play:** 스마트폰·태블릿·TV용 스토어 배포 준비 중. 스토어 에디션은 무료 및 프리미엄 기능으로 구성됩니다.

[GitHub Issues](https://github.com/dj5927/FileHub-AIO/issues) · [라이선스](https://github.com/dj5927/FileHub-AIO/blob/main/LICENSE)

---

## English

FileHub AIO is an all-in-one application for accessing NAS, cloud and local storage. On Windows, it mounts remote storage as network drives. On Android, it combines file management, media playback, reading and document viewing in a single app.

### Supported platforms

| Platform | Supported environment |
| --- | --- |
| Windows (PC) | Windows 10 / 11, x64 |
| Android | Android 8.0 or later; phones, tablets and foldables |
| Android TV / Google TV | TV interface and remote-control navigation |

### NAS and cloud connections

Supported on both Windows and Android:

| Connection | Purpose |
| --- | --- |
| SMB | Windows file shares and NAS |
| WebDAV | HTTP / HTTPS remote storage |
| FTP / FTPS | FTP servers and TLS-encrypted connections |
| SFTP | SSH file transfer |
| Google Drive | Google account authorization |
| Microsoft OneDrive | Microsoft account authorization |
| NAVER MYBOX | Personal access token (PAT) connection |
| TeraBox | Web login and session authentication |

- Saved connections, read-only connections, and file access on supported storage providers.
- FTP / SFTP filename encoding settings.
- Encrypted settings backup and restore. Common NAS connection profiles can be transferred between PC and Android; Google Drive and OneDrive require separate authorization.
- Synology NAS share-link creation and management, subject to NAS settings and API support.

Google Drive access is subject to Google's OAuth approval and permitted accounts. OneDrive access may be restricted by Microsoft account and organizational consent policies.

### Network drives (PC only)

- WinFsp-based remote storage mounting and Windows network drive connections.
- Drive letter assignment; open, copy, move, rename and delete files in Windows Explorer.
- Open files directly with Windows applications, including Office documents.
- Office file locking and concurrent editing conflict prevention for supported connections.
- Automatic connections, system tray controls, storage capacity and transfer status.
- Synology share-link creation and Windows Explorer integration.

### File manager (Android only)

- Browse internal storage, SD cards, USB storage and remote storage.
- Multi-selection, copy, move, delete, rename, create folders and search files.
- Background transfers, progress indicators, favorites, sorting, list and grid views.
- Recycle bin: restore local files and folders, permanently delete items and configure automatic deletion (7 days by default). Removable storage depends on access permissions and move support.
- ZIP / 7z compression and extraction; RAR extraction or external app integration, depending on the edition.
- Text file editing and saving.
- SFTP file permissions (chmod) and symbolic-link navigation.
- QNAP NAS share-link creation and management on supported server configurations.

### Media and reading (Android only)

- **Video:** local and remote playback, external subtitles, resume playback, automatic next-video playback, speed, brightness and volume controls, and picture-in-picture.
- **Music:** playback of supported formats such as MP3, AAC and FLAC; background playback.
- **Images:** photo gallery, swipe navigation and zoom.
- **Comics:** image and supported archive formats, page navigation and reading progress.
- **E-books:** TXT, EPUB and PDF reading, font and margin settings, resume reading and bookmarks.

Media codec and archive format compatibility depends on the device and edition.

### Office and documents (Android only)

| Format | Features |
| --- | --- |
| XLSX / XLS / ODS | Spreadsheet viewing, supported cell editing and saving, sheet navigation, search and zoom |
| DOCX | Word document viewer |
| PPTX | PowerPoint presentation viewer |
| HWP / HWPX | Hancom document viewer |
| PDF | Page navigation and zoom |
| TXT and plain text | Viewing and editing |

Editing and saving depend on the supported file format, storage write permissions and edition.

### Interface and settings (Android only)

- Light and dark themes; Korean, English, Japanese and Simplified Chinese interfaces.
- Layouts for phones, tablets and foldables.
- Android TV remote-control mode and device-to-device settings transfer via QR codes and the local network.

### Downloads and source code

- **Windows:** [Download (GitHub Releases)](https://github.com/dj5927/FileHub-AIO/releases/latest) · [PC source code](https://github.com/dj5927/FileHub-AIO/tree/pc/windows)
- **Android:** Direct-distribution APK provided separately · [Android source code](https://github.com/dj5927/FileHub-AIO/tree/android/flutter-migration)
- **Google Play:** Store release for phones, tablets and TVs is in preparation. The store edition offers free and premium features.

[GitHub Issues](https://github.com/dj5927/FileHub-AIO/issues) · [License](https://github.com/dj5927/FileHub-AIO/blob/main/LICENSE)
