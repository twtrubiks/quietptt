# QuietPTT

安靜的 PTT Android 客戶端：沒有廣告、沒有遙測、不在背景做事、對外只連 ptt.cc。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器也連不到 ptt.cc，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`），
真的 PTT 畫面用 `tools/capture` 在使用者自己的電腦上抓成 fixture。

## 目前的進度

**只有骨架，還沒 build 過**（本機沒有 Android SDK）。第一次 session 先照「第一次 session」那一節 build 一次。

- `core`（純 Kotlin、JVM 測試）：`Transport`（`SshTransport`、`WebSocketTransport`）、`Big5Uao`、`Terminal`／`ScreenBuffer`／`Screen`、
  `ScreenParser`（登入、主選單、我的最愛、文章列表、文章頁、幾種提示）、`Session`（連線、登入流程、進我的最愛、進看板）
- `app`：登入頁、我的最愛、看板、文章（只顯示終端目前這一頁）、終端畫面（退路）、設定；`Connection`（前景才連線）、`Credentials`（KeyStore 加密）
- 守門：`CleanManifestTest`（權限只有 INTERNET、沒有 service／receiver）、`checkCleanDependencies`（依賴樹沒有 Firebase／GMS／廣告）
- 還沒做：`Session.readArticle`（翻完整篇）、推文、文章內的顏色與連結、我的最愛的目錄、列表往上載更多、終端畫面的顏色與手勢
- **fixture 是照記憶寫的示意，不是真的畫面**（`core/src/test/resources/screens/README.md`）：第一件事是請使用者用 `tools/capture` 抓真的來換掉，
  再照真的畫面修 `ScreenParser` 的正規式

## 規格

每個功能該怎麼運作寫在 `docs/spec.md`。**改功能之前先讀對應的段落**，改了行為要一起更新。

### 不做（這個 App 的定義，不是待辦）

- 廣告、任何第三方 SDK（Firebase、Analytics、Crashlytics、推播、Remote Config、A/B test）、自家後端
- 背景工作：前景服務、wakelock、WorkManager、開機自啟、定期查信、要求忽略電池最佳化
- 任何 INTERNET 以外的權限
- 預先載入圖片、自動播放影片

### 先不做（要做之前先問使用者）

發文、回文、站內信、通知、看圖（文章內的圖片連結點了用外部瀏覽器開）、備份／匯出、多帳號、ptt2、`bbsu`（UTF-8）連線。

### 之後可能會做

跟使用者討論過、記下來的想法在 `docs/ideas.md`。開工前先跟使用者確認。

## 程式的分層

```
app（Android、Compose）
  LoginScreen / FavoritesScreen / BoardScreen / ArticleScreen / TerminalScreen / SettingsScreen
  QuietPttApp（Route ＋ stack）  Connection（前景才連線、重連）  Credentials（KeyStore）  AppContainer
      │ 只透過 Session 與 Page
core（純 Kotlin，沒有 Android）
  ptt/Session        送按鍵、等畫面：login、openFavorites、openBoard、readArticle
  ptt/ScreenParser   Screen → Page（Login、MainMenu、Favorites、ArticleList、ArticleView、提示、Unknown）
  terminal/Terminal  bytes → ScreenBuffer（vt100 跳脫序列、Big5-UAO）→ Screen（不可變的快照）
  transport/         Transport 介面：SshTransport（JSch）、WebSocketTransport（OkHttp）
```

- **core 不能 import android.\***（它是 Kotlin JVM module，compile 就會擋）。要碰 Android 的放 app
- `Transport` 兩種實作吐一樣的 Big5-UAO bytes，上層分不出來。切換是 `AppContainer.transport`（SharedPreferences `settings`），
  預設 SSH，下次連線生效。兩種都**不送 keep-alive**
- `SshTransport`：`bbs@ptt.cc:22`，伺服器接受 `none` 驗證（登入是在 BBS 畫面上做的）。
  Host key pin 在 `PttHost.KNOWN_HOSTS`，對不上就拒絕；站方換 key 時用 `ssh-keyscan -t rsa,ecdsa,ed25519 ptt.cc` 重抓、發一版。
  演算法只挑 Android 內建 JCE 就有的（ECDSA host key、DH group14、AES-CTR、HMAC-SHA2），**沒有 BouncyCastle**；
  真機上連不上時先看 logcat 是不是協商失敗，確定是才考慮加
- `WebSocketTransport`：`wss://ws.ptt.cc/bbs`，一定要帶 `Origin: https://term.ptt.cc`。OkHttpClient 整個 App 一個（`AppContainer.http`），只在選 WebSocket 時建
- `Terminal` 只做 PTT 會用到的跳脫序列；全形字的兩個 byte 中間可能夾跳脫序列（彩色 ANSI 常這樣），`pendingLead` 處理這個。
  所有 Big5 雙位元組字都算 2 欄；`Screen.lines` 是文字（全形一個字）、`Screen.text(row, from, to)` 照欄位切
- `ScreenParser` 全靠文字特徵，**每個判斷旁邊註明認的是哪個字**，改版面會壞是預期中的事：測試用真的畫面 fixture，認不出來回 `Page.Unknown`，
  App 退到 `TerminalScreen`，永遠不會卡死
- `Session`：一條連線一個，關了就丟掉重開。`awaitPage` 等「符合條件且 150 ms 內沒再變」的畫面（畫面是一段一段送來的）。
  收資料與解析都在 `Dispatchers.Default`，**不在主執行緒**
- `Connection`（app）：一次只有一條 `Session`。**App 退到背景超過 30 秒就斷線**（`ProcessLifecycleOwner`），
  回前景時若是因此斷的、又記得密碼就自動重登，回到我的最愛（還沒做：回到剛剛的看板或文章）
- `Credentials`：帳號明碼、密碼用 AndroidKeyStore 的 AES-256-GCM 加密，存在 SharedPreferences `account`；
  `allowBackup=false`，`data_extraction_rules.xml` 也排除它。「記住帳號與密碼」預設開，關掉只留帳號
- 導覽：sealed interface 的 `Route` ＋ `rememberSaveable` 的 stack（`QuietPttApp`），不引入 Navigation 套件；設定頁蓋在上面，不在 stack 裡
- 整個 App 共用的東西在 `AppContainer`（`QuietPttApplication.container`）。`Application.onCreate` 只掛前景觀察者，不碰網路

## 省電與不吃資源（硬規則）

- 只在前景連線；背景 30 秒斷線；沒有 ping、沒有輪詢、沒有查新信
- 沒有前景服務、wakelock、WorkManager、BroadcastReceiver、ContentProvider（`CleanManifestTest` 擋）
- 不要求忽略電池最佳化、不開 `largeHeap`
- 文章裡的圖片不預載、不內嵌載入；連結點了交給外部瀏覽器
- 啟動不初始化任何東西；不打包字型（用系統字型），APK 目標 10 MB 以內
- Compose 列表都給 `key`；終端只重畫有變的列（`Screen` 是快照，列沒變就不重組）

## 乾淨守門

- `app/src/test/.../CleanManifestTest`：manifest 權限只有 `INTERNET`、沒有 `<service>`／`<receiver>`／`<provider>`、沒有 `largeHeap`、`allowBackup="false"`
- `app/build.gradle.kts` 的 `checkCleanDependencies`（掛在 `preBuild`）：release 依賴樹出現 `forbiddenGroups` 裡的 group 就不給 build
- 新加依賴前先 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 看它拖進什麼

## 專案約定

- Gradle 專案根目錄就是 repo 根目錄，`./gradlew` 在這裡跑；三個 module：`:core`、`:app`、`:tools:capture`
- applicationId / namespace：`com.twtrubiks.quietptt`（`scripts/ci/record.sh` 依賴這個值）。**不要改**：一變手機會當成另一個 App
- **minSdk 28（Android 9）**，compileSdk 36，targetSdk 36，Java / Kotlin target 17
- 依賴版本集中在 `gradle/libs.versions.toml`；build 報版本相容問題時，以錯誤訊息為準調整
- AGP 9.x 使用 built-in Kotlin：app **不要**套 `org.jetbrains.kotlin.android`；core 與 tools 是 Kotlin JVM module，套 `org.jetbrains.kotlin.jvm`，版本跟 Kotlin 一樣；
  Compose 套 `org.jetbrains.kotlin.plugin.compose`
- JSch 用反射載入演算法實作，`app/proguard-rules.pro` 整包 keep；OkHttp 自帶規則
- `android:allowBackup="false"`：唯一的本機資料是加密過的帳密，金鑰離不開手機
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `QUIETPTT_KEYSTORE`、`QUIETPTT_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `quietptt`）；沒有就用這台機器的 debug 金鑰
- **給使用者裝的 APK**：正式版從 GitHub Release 來。VM 的 debug 金鑰每個 session 都不一樣，簽章對不上的 APK 只能移除重裝（會忘記帳密，重打一次就好）
  - 發版：`.github/workflows/release.yml`。版本號用語意化版本。推 tag `v<版本>`，或把 `release-version` 改成新版本推上去
  - **發版金鑰還沒產生**：照 `docs/release-key.md` 做一次，`EXPECTED_CERT` 填好之後 release.yml 才會動。之後**不要再產生新的**
  - versionCode 是 2026-01-01 起算的分鐘數（workflow 算好用環境變數帶進來）
  - README 的下載連結是 `releases/latest/download/quietptt.apk`

## 雲端 VM 環境

- Android SDK 由 `scripts/cloud-setup.sh` 裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供；SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central `429 Too Many Requests`，隔 20 秒重跑 `./gradlew` 即可；
  錯誤訊息常是 `Plugin [id: '...'] was not found`、`Could not resolve ...`，加 `--info` 才看得到 429。先重跑，不要改版本
- **VM 連不到 ptt.cc**：連線層靠 MockWebServer（WebSocket）與 fixture 測；真的連線只能在 CI 的模擬器或使用者的手機上驗
- `gh pr`／`gh issue` 不能用（GraphQL 被擋），改用 `gh api` 打 REST

## 第一次 session

1. `./gradlew :core:test` 先過（純 Kotlin，最快知道骨架有沒有打錯字）
2. `./gradlew assembleDebug assembleRelease test assembleDebugAndroidTest`。版本相容的錯誤以訊息為準修 `libs.versions.toml`
3. 請使用者在自己電腦上跑 `./gradlew :tools:capture:run -q --console=plain`，抓登入、主選單、我的最愛、文章列表、文章頁，
   去掉帳號後放進 `core/src/test/resources/screens/`，再照真的畫面修 `ScreenParser` 與測試的斷言
4. push，看 CI 的錄影與 release 冒煙測試

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew test assembleDebug assembleRelease assembleDebugAndroidTest
```

`test` 跑 core 與 app 的 JVM 測試（含 `CleanManifestTest`）；`assembleRelease` 會先跑 `checkCleanDependencies`、再跑 R8。
和 Android 無關的邏輯一律放 core、用 JVM 測試，幾秒就有結果。

## 分支與 PR

- 推到哪個分支照使用者說的（現在直接在 `main` 上做）。雲端 session 的 git 只能推自己的 `claude/…` 分支，使用者要求才開 PR，CI 過了才合併
- 合併 PR 用 **Rebase and merge**：`main` 上的 commit 會換成新的 hash。合併後要在同一個分支繼續做，
  先 `git checkout -B <分支> origin/main`，再 `git push --force-with-lease`。force push、改寫 `main` 的歷史都要先問使用者

## 測試與錄影

模擬器上的測試（`DeviceTest`、`DemoRecordingTest`、release 冒煙測試）怎麼寫、CI 的錄影怎麼看（`media` branch）、
fixture 怎麼抓，都在 `docs/testing.md`。**改測試或 `record.sh` 之前先讀**。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。
