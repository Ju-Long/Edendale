---
updated: 2026-10-06
summary:
  - Edendale에는 계정 시스템도, 자체 서버도 없습니다. 앱에는 분석, 광고, 추적 기능이 없습니다.
  - 라이브러리, 설정, 저장된 로그인 정보는 기기에 남습니다. 동기화되는 것은 iCloud나 내 OneDrive처럼 당신이 관리하는 서비스를 거칩니다.
  - Google Drive, OneDrive, Dropbox에는 읽기 전용으로만 접근하며, 기기에서 직접 연결해 동영상 목록을 보여 주고 재생하는 데에만 씁니다.
  - 작품 정보, 자막 검색, 건너뛰기 버튼, 예고편은 아래에 적힌 서비스에, 적힌 목적으로만 연결합니다.
---

## 이 방침의 적용 대상

이 방침은 Apple 기기(iPhone, iPad, Mac, Apple TV, Apple Vision Pro), Android, Windows용 Edendale 앱과 이 웹사이트(edendale.babasama.com)에 적용됩니다. Edendale은 [github.com/Ju-Long/Edendale](https://github.com/Ju-Long/Edendale)에서 공개적으로 개발하는 무료 오픈 소스 프로젝트입니다. ‘우리’는 Edendale을 개발하는 사람들을 뜻합니다.

## 우리는 당신의 데이터를 수집하지 않습니다

Edendale에는 계정 시스템이 없으며, 앱에서 정보를 받는 서버도 운영하지 않습니다. 앱에는 분석, 광고, 추적, 충돌 보고를 위한 코드가 없습니다. 당신의 정보는 우리에게 전달되지 않으므로, 판매하거나 대여하거나 공유할 것이 없습니다.

기기에서 앱 개발자와 진단 정보를 공유하도록 허용한 경우, Edendale을 설치한 스토어(Apple, Google, Microsoft)가 자체 개인정보 처리방침에 따라 충돌 보고서와 집계된 사용 통계를 우리에게 제공할 수 있습니다. 우리는 이를 문제 해결에만 사용합니다.

## 기기에 남는 것

- **라이브러리:** 추가한 폴더와 소스, 그 안에서 Edendale이 찾은 파일의 이름·크기·날짜·재생 시간, 그리고 각 파일과 연결된 영화나 에피소드.
- **설정:** 재생, 오디오, 화면, 자막, 조작에 관한 설정과 작품별로 기억하는 선택.
- **내려받은 자막.**
- **저장된 로그인 정보와 연결된 계정:** 서버 비밀번호, S3 액세스 키, 클라우드 로그인 토큰. 시스템의 보호된 저장소에 보관됩니다. Apple 기기에서는 키체인, Android에서는 Android Keystore로 암호화된 저장소, Windows에서는 Windows 데이터 보호(DPAPI)를 사용합니다. 유효 기간이 짧은 액세스 토큰은 메모리에만 보관합니다.

Edendale은 온라인 서비스에 연결하기 전에 기기에서 파일 이름을 읽어 영화와 에피소드를 알아내며, 동영상을 어디에도 업로드하지 않습니다.

## 동기화되는 것과 그 경로

Edendale은 당신이 관리하는 서비스를 통해서만, 그리고 당신이 켠 경우에만 동기화합니다.

- **Apple 기기:** iCloud를 사용하면 시청 진행률, 평점, 즐겨찾기, 관심 목록이 당신의 비공개 iCloud 데이터베이스를 통해 동기화됩니다. 연결된 계정과 저장된 로그인 정보는 iCloud 키체인을 통해 iPhone, iPad, Mac, Apple Vision Pro로 동기화됩니다. Apple TV는 자체 사본을 보관합니다.
- **Windows:** OneDrive 복제를 켜면 시청 진행률과 작품 상태가 당신의 OneDrive에 있는 폴더를 통해 복사됩니다. 로그인 정보와 계정은 기기 밖으로 나가지 않습니다.
- **Android:** Android 자체 백업에 라이브러리와 시청 데이터가 포함될 수 있습니다. 로그인 정보, 키, 계정 토큰은 백업과 기기 간 전송에서 제외됩니다.
- **TMDB 계정(선택):** The Movie Database에 로그인하면 Edendale은 즐겨찾기, 관심 목록, 평점을 그 계정과 동기화합니다. 시청 진행률은 TMDB로 보내지 않습니다.

## Edendale이 사용하는 온라인 서비스

아래의 각 서비스는 여느 인터넷 연결과 마찬가지로 기기의 IP 주소를 받으며, 그 밖에 다음 정보를 받습니다.

- **[TMDB](https://www.themoviedb.org/privacy-policy)**(The Movie Database): 영화와 시리즈의 정보 및 이미지를 위해. Edendale이 파일 이름에서 읽은 제목과 연도(전체 파일 이름, 폴더, 파일 자체는 보내지 않습니다)와 살펴본 작품의 TMDB ID. 로그인한 경우 TMDB 세션도 함께 보냅니다.
- **[Wyzie Subs](https://wyzie.io/privacy)**: 온라인에서 자막을 검색할 때만. 작품의 TMDB ID, 시즌과 에피소드 번호, 요청한 언어, 당신의 API 키.
- **[TheIntroDB](https://theintrodb.org/docs/privacy)**: 건너뛰기 버튼이 켜져 있을 때만(기본값은 꺼짐). 작품의 TMDB ID, 시즌과 에피소드 번호, 동영상 길이.
- **[YouTube](https://policies.google.com/privacy)**: 예고편 재생을 선택했을 때만. Apple 기기와 Android 기기에서는 YouTube의 개인정보 보호 강화 모드(youtube-nocookie.com)로 재생합니다. Windows에서는 브라우저에서 youtube.com의 예고편을 엽니다.
- **연결한 저장소:** 다음 섹션에서 설명합니다.

## 연결한 저장소

Edendale은 기기의 폴더와 당신이 연결한 저장소(SMB, NFS, SFTP, WebDAV 서버, S3 호환 저장소, Google Drive, OneDrive, Dropbox)에서 동영상을 재생합니다. 사용할 수 있는 서비스는 플랫폼마다 다르며, Google Drive는 현재 Apple 기기에서 사용할 수 있습니다. 모든 연결은 기기에서 선택한 서비스로 바로 이어집니다. 우리가 운영하는 서버를 거치는 것은 없습니다.

- **로그인:** Google Drive, OneDrive, Dropbox는 PKCE를 사용하는 OAuth 2.0으로 각 서비스 자체 페이지에서 로그인하므로 Edendale은 비밀번호를 볼 수 없습니다. 서버 로그인 정보(사용자 이름, 비밀번호, 액세스 키)는 해당 서버로만 보냅니다.
- **읽기 전용 접근:** Edendale은 읽기 전용 권한만 요청합니다. Google은 `openid`, `email`, `drive.readonly`, Microsoft는 `Files.Read`, `User.Read`, `offline_access`, Dropbox는 `account_info.read`, `files.metadata.read`, `files.content.read`입니다. Edendale은 저장소의 어떤 것도 만들거나, 바꾸거나, 공유하거나, 지울 수 없습니다.
- **Edendale이 읽는 것:** 계정을 표시하고 소스를 구분하기 위한 계정 ID와 이메일 주소, 살펴보고 연결한 위치에 있는 파일과 폴더의 이름·크기·날짜·재생 시간, 그리고 재생하는 동안에만 동영상의 내용.
- **Edendale이 보관하는 것:** 파일 정보는 기기의 라이브러리에 포함됩니다. 로그인 토큰과 로그인 정보는 위에서 설명한 보호된 저장소에 들어갑니다. 동영상 데이터는 재생하는 동안 메모리에만 머물며 디스크에 저장되지 않습니다.
- **TV:** Apple TV는 TV에서 전송을 시작하고 iPhone이나 iPad에서 확인한 경우에만, 로컬 네트워크의 암호화된 연결을 통해 그 기기에서 계정이나 로그인 정보를 받을 수 있습니다. TV에서는 다른 기기에서 승인하는 코드로 OneDrive에 로그인할 수도 있습니다.

## Google 사용자 데이터

Google Drive를 연결하면 Edendale은 다음에 접근합니다.

- Google 계정의 고유 ID와 이메일 주소(`openid`, `email`): 연결된 계정을 표시하고 여러 계정을 구분하기 위해 사용합니다.
- Google Drive의 파일과 폴더(`drive.readonly`): Edendale은 살펴보고 연결한 폴더의 목록을 보여 주고, 그 안에 있는 파일의 이름·크기·날짜·동영상 길이를 읽고, 재생하기로 선택한 동영상을 스트리밍합니다.

Edendale은 이 데이터를 Google Drive 소스 기능(폴더 선택, 폴더 안 동영상 목록 표시, 재생)을 제공하는 데에만 사용합니다. 다른 소스와 마찬가지로 Edendale은 기기에서 파일 이름을 읽어 영화와 에피소드를 알아내고, 정보를 찾기 위해 알아낸 제목과 연도만 TMDB로 보냅니다.

데이터는 당신의 기기에 남습니다. 파일 정보는 라이브러리에, 연결된 계정(ID, 이메일 주소, 로그인 토큰)은 키체인에 보관되며, iCloud 키체인이 이를 당신의 다른 Apple 기기와 동기화합니다. Apple TV에는 iPhone이나 iPad에서 전송을 확인했을 때만 전달됩니다. Google 사용자 데이터는 우리에게도, 우리가 운영하는 어떤 서버에도 보내지지 않으므로 우리는 이를 보거나 읽지 않습니다. 판매되지 않고, 광고에 사용되지 않으며, 인공지능이나 머신러닝 모델을 개발·개선·학습시키는 데에도 사용되지 않습니다.

Edendale의 접근을 끝내려면 소스를 제거하고(라이브러리에서 해당 파일도 제거됩니다) **설정 → 계정**에서 로그아웃하세요. **로그아웃 및 접근 권한 취소**를 선택하면 Google에서도 Edendale의 접근 권한이 취소됩니다. [Google 계정의 서드 파티 연결](https://myaccount.google.com/connections)에서 언제든지 접근 권한을 삭제할 수도 있습니다. 앱을 삭제하면 그 기기에 앱이 저장한 모든 것이 삭제됩니다.

Edendale이 Google API에서 받은 정보를 사용하고 다른 앱으로 전송하는 것은 제한된 사용(Limited Use) 요건을 포함한 [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)(Google API 서비스 사용자 데이터 정책)를 준수합니다.

## Microsoft 및 Dropbox 계정

OneDrive와 Dropbox도 같은 방식입니다. 읽기 전용으로 접근하며, 동영상 목록을 보여 주고 재생하는 데에만 사용하고, 당신의 기기에만 보관합니다. **설정 → 계정**에서 로그아웃하세요. Dropbox의 경우 **로그아웃 및 접근 권한 취소**를 선택하면 Dropbox에서도 Edendale의 접근 권한이 취소됩니다. [Microsoft 계정에 접근할 수 있는 앱](https://account.live.com/consent/Manage)이나 [Dropbox 연결된 앱](https://www.dropbox.com/account/connected_apps)에서 Edendale을 제거할 수도 있습니다. 회사 또는 학교 Microsoft 계정은 소속 조직에서 관리할 수 있습니다.

## 이 웹사이트

이 웹사이트는 GitHub Pages에서 호스팅되는 정적 사이트입니다. 쿠키를 설정하지 않고, 브라우저에 아무것도 저장하지 않으며, 양식이 없고, 다른 사이트에서 분석 도구나 글꼴, 스크립트를 불러오지 않습니다. 아무것도 저장하지 않고 브라우저 설정에 따라 언어를 고르며, 당신이 선택한 언어는 페이지 주소에만 남습니다. 호스트인 GitHub은 IP 주소 같은 일반적인 요청 정보를 받습니다. 자세한 내용은 [GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)를 참고하세요. Edendale 앱을 여는 링크는 당신의 기기에서 처리됩니다.

## 아동

Edendale은 아동을 포함해 누구의 개인정보도 고의로 수집하지 않습니다. 앱은 우리에게 아무것도 보내지 않으므로 우리가 수집할 수 있는 것이 없습니다.

## 당신의 선택

앱에서 언제든지 데이터를 확인하고, 바꾸고, 지울 수 있습니다. 소스 제거, 계정 로그아웃, iCloud 동기화나 OneDrive 복제 끄기, 앱 삭제가 가능합니다. 우리는 당신의 개인정보를 전혀 보유하지 않으므로, 이러한 기능이 열람 및 삭제 권리를 행사하는 방법입니다. 위 서비스가 보유한 데이터에는 각 서비스의 개인정보 처리방침이 적용됩니다.

## 이 방침의 변경

앱이 데이터를 다루는 방식이 바뀌면 이 페이지와 상단의 날짜를 업데이트합니다. 모든 개정 내역은 GitHub의 프로젝트 기록에 공개됩니다.

## 문의

이 방침이나 Edendale의 개인정보 보호에 관한 질문은 [github.com/Ju-Long/Edendale/issues](https://github.com/Ju-Long/Edendale/issues)에 이슈로 남겨 주세요.
