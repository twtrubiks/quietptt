# QuietPTT

安靜的 PTT Android 客戶端：沒有廣告、沒有遙測、不在背景做事、對外只連 ptt.cc。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。
VM 走 WebSocket 連得到真的 PTT（SSH 的 22 埠被擋），真的畫面用 `tools/capture --ws` 抓成 fixture。

## 功能

每個功能該怎麼運作（送什麼鍵、認什麼字、為什麼這樣做）寫在 `docs/spec.md`。**改功能之前先讀對應的段落**，改了行為要一起更新。

### 已經做了

- 登入：多帳號（記好幾組、登入頁挑；一次只連一條）、記住帳號與密碼、自動登入、重複登入照設定刪不刪其他連線、失敗可看當時的畫面
- 首頁四個分頁（點分頁或左右滑）；標題是登入的帳號，點了查詢我的資料、切換帳號、登出：
  - 我的最愛：整份列出（超過一頁也列）、有新文章的板名後面一個藍點、右邊是人氣（爆／HOT／人數，照 PTT 的顏色）、長按改位置／移除
  - 分組討論：分類、子分類、分類裡的看板
  - 即時熱門：長按加入我的最愛
  - 私人信件：列信、讀信、寫信、回信；有新信時分頁名稱右上角一個藍點、跳「你有新信件」（看 PTT 的標題列，不另外查）
- 看板：標題列右邊是板裡現在的人數；置底文、新的在上、往下捲載入更早的、往下拉重整；工具列發文、搜尋（標題／作者／推文數，有搜尋紀錄）、重整；
  長按一篇：查詢作者、文章資訊、同作者／同標題文章、複製全文，自己的文章可編輯、改標題、刪除
- 文章：排版照 BePTT（本文照 PTT 的 ANSI 色、簽名檔縮小、網址可點、圖片按「開啟圖片」才載）；
  推文有色條、樓層、連推疊一組、標原PO，點一則突顯同一個人的推文、長按跳選單（複製、查詢這個使用者）；
  工具列推文、回文（只回到看板）、到底、檢查新推文、分享；「⋮」查詢作者資料、文章資訊、同作者／同標題文章、寄信給作者、複製全文、文章字級；自己的文章可編輯、改標題、刪除
- 搜尋看板（英文找板名、中文找標題）、加入我的最愛（看板「⋮」、搜尋結果、即時熱門）、查詢網友
- 終端畫面：認不出來的畫面都退到這裡，有兩排軟鍵（含 Ctrl）、打字框
- 設定：連線方式（SSH／WebSocket）、深色模式、文章字級、滑動回上一頁的方向、記住密碼、自動登入、重複登入、清除搜尋紀錄、登出、忘記帳號
- 系統返回鍵、橫著滑回上一頁（方向可設）

### 還沒做

- 終端畫面的顏色與手勢（左右滑＝左右鍵、上下滑＝翻頁）

### 不做（這個 App 的定義，不是待辦）

- 廣告、任何第三方 SDK（Firebase、Analytics、Crashlytics、推播、Remote Config、A/B test）、自家後端
- 背景工作：前景服務、wakelock、WorkManager、開機自啟、定期查信、要求忽略電池最佳化
- 任何 INTERNET 以外的權限
- 預先載入圖片、自動載入圖片（只在按了「開啟圖片」之後抓那一張）、自動播放影片

### 先不做（要做之前先問使用者）

回文同時寄給作者、刪信、轉寄、群組寄信、備份／匯出、ptt2、`bbsu`（UTF-8）連線。

### 之後可能會做

跟使用者討論過、記下來的想法在 `docs/ideas.md`。開工前先跟使用者確認。

## 程式的分層

```
app（Android、Compose）
  LoginScreen / HomeScreen（我的最愛、分組討論、即時熱門、私人信件）/ ClassBoardsScreen / BoardScreen / ArticleScreen / ReplyPostScreen / MailScreen / WriteMailScreen / TerminalScreen / SettingsScreen
  QuietPttApp（Route ＋ stack）  Connection（前景才連線、重連）  Credentials（KeyStore）  AppContainer
      │ 只透過 Session 與 Page
core（純 Kotlin，沒有 Android）
  ptt/Session        送按鍵、等畫面：login、showFavorites、showBoard、olderArticles、openArticle、articlePage、articleEnd、queryUser
  ptt/ScreenParser   Screen → Page（Login、MainMenu、Favorites、ClassRoot、MailMenu、MailList、MailForm、ArticleList、ReplyForm、ArticleView、提示、Unknown）
  terminal/Terminal  bytes → ScreenBuffer（vt100 跳脫序列、Big5-UAO）→ Screen（不可變的快照）
  transport/         Transport 介面：SshTransport（JSch）、WebSocketTransport（OkHttp）
  record/            錄影格式（Recording）與遮帳號、IP（Redactor）：tools/capture 寫、測試讀；App 用不到（R8 會拿掉）
```

- **core 不能 import android.\***（它是 Kotlin JVM module，compile 就會擋）。要碰 Android 的放 app
- `Transport` 兩種實作吐一樣的 Big5-UAO bytes，上層分不出來。切換是 `AppContainer.transport`（SharedPreferences `settings`），
  預設 SSH，下次連線生效。兩種都**不送 keep-alive**
- `SshTransport`：`bbs@ptt.cc:22`，伺服器接受 `none` 驗證（登入是在 BBS 畫面上做的）。
  Host key pin 在 `PttHost.KNOWN_HOSTS`，對不上就拒絕；站方換 key 時用 `ssh-keyscan -t rsa,ecdsa,ed25519 ptt.cc` 重抓、發一版。
  演算法只挑 Android 內建 JCE 就有的（ECDSA host key、DH group14、AES-CTR、HMAC-SHA2），**沒有 BouncyCastle**；
  真機上連不上時先看 logcat 是不是協商失敗，確定是才考慮加
- `WebSocketTransport`：`wss://ws.ptt.cc/bbs`，一定要帶 `Origin: https://term.ptt.cc`。OkHttpClient 整個 App 一個（`AppContainer.http`），
  選 WebSocket 或第一次按「開啟圖片」時才建
- `Terminal` 只做 PTT 會用到的跳脫序列；全形字的兩個 byte 中間可能夾跳脫序列（彩色 ANSI 常這樣），`pendingLead` 處理這個。
  OSC（`ESC ]` … `BEL`／`ESC \`）整段吃掉：文章網址是 OSC 8 超連結，印出來會把整頁擠歪。
  所有 Big5 雙位元組字都算 2 欄；`Screen.lines` 是文字（全形一個字）、`Screen.text(row, from, to)` 照欄位切
- `ScreenParser` 全靠文字特徵，**每個判斷旁邊註明認的是哪個字**，改版面會壞是預期中的事：測試用真的畫面 fixture，認不出來回 `Page.Unknown`，
  App 退到 `TerminalScreen`，永遠不會卡死。文章列表、我的最愛照 pttbbs 原始碼的**固定欄位**切（`mbbsd/bbs.c` `readdoent`、`mbbsd/board.c` `brdlist_coldefs`），
  站方改版面時去 github.com/ptt/pttbbs 對欄寬
- `Session`：一條連線一個，關了就丟掉重開。`awaitPage` 等「符合條件且 150 ms 內沒再變」的畫面（畫面是一段一段送來的）；
  **送了鍵之後一律帶 `after`**（先等畫面變了），不然網路慢時會拿到按鍵之前的畫面。連線關了丟 `SessionClosedException`，
  等不到丟 `UnexpectedScreenException`（帶當時的畫面）；`login` 不丟例外，全部變成 `LoginResult`。
  `showFavorites`／`showBoard`／`openArticle` 從現在的畫面走過去（已經在就不按鍵），一次只跑一個（mutex）。
  收資料、解析、等畫面都在 `Dispatchers.Default`，**不在主執行緒**
- App 端每一頁用 `rememberNavigation` 在進來時叫上面那幾個；往回走只 pop stack，由回到的那一頁自己按左鍵退回去。
  `AppContainer.scope` 沒有例外處理器，裡面 `launch` 的東西例外一定要接住（丟出去就閃退）
- `Connection`（app）：一次只有一條 `Session`。**App 退到背景超過 2 分鐘就斷線**（`ProcessLifecycleOwner`；背景期間或剛回來 15 秒內
  socket 自己死掉也算），回前景時若是因此斷的、又記得密碼就自動重登，**頁面留在原地**（`Online.resumed`，stack 不清）；手動登入才從我的最愛開始。打開 App（process 第一次到前景）時設定的「自動登入」（預設開）開著又記得密碼就直接登入（`loginOnLaunch`）。設定裡「登出」走 `Connection.logout`：取消重登，這次不會自己又登回去
- `Credentials`：帳號明碼（與上次登入時間）、密碼用 AndroidKeyStore 的 AES-256-GCM 加密，存在 SharedPreferences `account`；
  `allowBackup=false`，`data_extraction_rules.xml` 也排除它。「記住帳號與密碼」預設開，關掉只留帳號
- 導覽：sealed interface 的 `Route` ＋ `rememberSaveable` 的 stack（`QuietPttApp`），不引入 Navigation 套件；設定頁蓋在上面，不在 stack 裡
- 整個 App 共用的東西在 `AppContainer`（`QuietPttApplication.container`）。`Application.onCreate` 只掛前景觀察者，不碰網路

## 省電與不吃資源（硬規則）

- 只在前景連線；背景 2 分鐘斷線；沒有 ping、沒有輪詢、沒有查新信
- 沒有前景服務、wakelock、WorkManager、BroadcastReceiver、ContentProvider（`CleanManifestTest` 擋）
- 不要求忽略電池最佳化、不開 `largeHeap`
- 文章裡的圖片不預載、不自動載；按了「開啟圖片」才抓那一張（最多 20 MB、長邊 2048 縮小解碼）；GIF 會動，捲出畫面就停；其他連結點了交給外部瀏覽器
- 啟動不初始化任何東西；不打包字型（用系統字型），APK 目標 10 MB 以內
- Compose 列表都給 `key`；終端只重畫有變的列（`Screen` 是快照，列沒變就不重組）

## 乾淨守門

- `app/src/test/.../CleanManifestTest`：manifest 權限只有 `INTERNET`、沒有 `<service>`／`<receiver>`／`<provider>`、沒有 `largeHeap`、`allowBackup="false"`
- `app/build.gradle.kts` 的 `checkCleanDependencies`（掛在 `preBuild`）：release 依賴樹出現 `forbiddenGroups` 裡的 group 就不給 build
- 新加依賴前先 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 看它拖進什麼

## 專案約定

- Gradle 專案根目錄就是 repo 根目錄，`./gradlew` 在這裡跑；三個 module：`:core`、`:app`、`:tools:capture`
- applicationId / namespace：`com.twtrubiks.quietptt`（`scripts/ci/record.sh` 依賴這個值）。**不要改**：一變手機會當成另一個 App
- **minSdk 28（Android 9）**，compileSdk 37（AndroidX 與 okhttp-android 要求），targetSdk 36，Java / Kotlin target 17
- 依賴版本集中在 `gradle/libs.versions.toml`；build 報版本相容問題時，以錯誤訊息為準調整
- AGP 9.x 使用 built-in Kotlin：app **不要**套 `org.jetbrains.kotlin.android`；core 與 tools 是 Kotlin JVM module，套 `org.jetbrains.kotlin.jvm`，版本跟 Kotlin 一樣；
  Compose 套 `org.jetbrains.kotlin.plugin.compose`
- JSch 用反射載入演算法實作，`app/proguard-rules.pro` 整包 keep；OkHttp 自帶規則
- `android:allowBackup="false"`：本機資料只有加密過的帳密（金鑰離不開手機）、設定與看板裡的搜尋紀錄（`search_history`，也不隨換機傳輸）
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `QUIETPTT_KEYSTORE`、`QUIETPTT_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `quietptt`）；沒有就用這台機器的 debug 金鑰
- **給使用者裝的 APK**：正式版從 GitHub Release 來（debug 與 release 的差別、大小、換簽章要移除重裝：`docs/debug-vs-release.md`）。VM 的 debug 金鑰每個 session 都不一樣，簽章對不上的 APK 只能移除重裝（會忘記帳密，重打一次就好）
  - 發版：`.github/workflows/release.yml`。版本號用語意化版本。推 tag `v<版本>`，或把 `release-version` 改成新版本推上去
  - **發版金鑰已經有了**（`docs/release-key.md`），`EXPECTED_CERT` 填好了；金鑰檔與密碼在使用者手上與 GitHub Secrets。**不要再產生新的**
  - versionCode 是 2026-01-01 起算的分鐘數（workflow 算好用環境變數帶進來）
  - README 的下載連結是 `releases/latest/download/quietptt.apk`

## 雲端 VM 環境

- Android SDK 由 `scripts/cloud-setup.sh` 裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供；SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central `429 Too Many Requests`，隔 20 秒重跑 `./gradlew` 即可；
  錯誤訊息常是 `Plugin [id: '...'] was not found`、`Could not resolve ...`，加 `--info` 才看得到 429。先重跑，不要改版本
- **VM 走 WebSocket 連得到真的 PTT**（`wss://ws.ptt.cc/bbs`，經過 VM 的 proxy；JVM 的 proxy 設定在 `JAVA_TOOL_OPTIONS`）；SSH 的 22 埠被擋。
  guest 被站方關了。帳密**不要請使用者貼進對話**：請他在雲端環境設定加 `PTT_ID`、`PTT_PASSWORD` 環境變數，用 `tools/capture --ws` 的 `:login` 登入，
  不印、不寫進檔案；只看不寫（不發文、不推文、不寄信）。使用者要求時才用 `capture --e2e-write` 在 Test 板測會寫的功能（只動自己發的那篇）。測試仍然靠 MockWebServer 與 fixture，不連真的 PTT
- `gh pr`／`gh issue` 不能用（GraphQL 被擋），改用 `gh api` 打 REST

## 在 VM 內驗證（每次 push 前）

```bash
./gradlew test assembleDebug assembleRelease assembleDebugAndroidTest
```

`test` 跑 core 與 app 的 JVM 測試（含 `CleanManifestTest`）；`assembleRelease` 會先跑 `checkCleanDependencies`、再跑 R8。
和 Android 無關的邏輯一律放 core、用 JVM 測試，幾秒就有結果。

改了 `Session`／`ScreenParser`／`Terminal`／`Push` 之後，再跑 `tools/capture/build/install/capture/bin/capture --e2e MobileComm`（再跑一次 `Soft_Job`：推文沒對齊的板）：
App 的 `Session` 對真的 PTT 走完整個流程（只看不寫）。會寫到 PTT 的（發文、推文、回文、改標題、編輯、刪文）是 `--e2e-write`：
**只在使用者要求時跑**，寫死只在 Test 板、只動自己發的那篇、最後刪掉。改我的最愛、寄信的不在裡面，要測先問使用者

## 分支與 PR

- 推到哪個分支照使用者說的（現在直接在 `main` 上做）。雲端 session 的 git 只能推自己的 `claude/…` 分支，使用者要求才開 PR，CI 過了才合併
- 合併 PR 用 **Rebase and merge**：`main` 上的 commit 會換成新的 hash。合併後要在同一個分支繼續做，
  先 `git checkout -B <分支> origin/main`，再 `git push --force-with-lease`。force push、改寫 `main` 的歷史都要先問使用者

## 測試與錄影

模擬器上的測試（`DeviceTest`、`DemoRecordingTest`、release 冒煙測試）怎麼寫、CI 的錄影怎麼看（`media` branch）、
fixture 怎麼抓，都在 `docs/testing.md`。**改測試或 `record.sh` 之前先讀**。
fixture 是真機錄的畫面（`core/src/test/resources/screens/`），`recordings/` 是原始 bytes 的錄影，`Fixtures.replay` 重播。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `docs/cloud-setup.md`，改了 `scripts/cloud-setup.sh` 或網路設定要一起更新。
