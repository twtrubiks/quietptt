# QuietPTT

安靜的 PTT Android 客戶端：沒有廣告、沒有遙測、不在背景做事、對外只連 ptt.cc。
Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/record.yml`）。

## 文件在哪裡

| 要做什麼 | 先讀 |
|---|---|
| 改任何功能（送什麼鍵、認什麼字、為什麼這樣做） | `docs/spec.md` 對應的段落；**改了行為要一起更新** |
| 改測試、`record.sh`、抓 fixture、看 CI 的錄影 | `docs/testing.md` |
| 發版、發版金鑰 | `docs/release-key.md`、`.github/workflows/release.yml` |
| debug 與 release 的差別、換簽章要移除重裝 | `docs/debug-vs-release.md` |
| 雲端環境怎麼設定 | `docs/cloud-setup.md`；改了 `scripts/cloud-setup.sh` 或網路設定要一起更新 |
| 討論過、還沒做的想法 | `docs/ideas.md`；開工前先跟使用者確認 |

文件與程式註解**只寫現況**：不寫日期、不寫「以前是…」「…起改成」這種歷史。

## 功能

### 已經做了（細節都在 `docs/spec.md`）

- 登入：多帳號、記住帳號與密碼、自動登入、重複登入的處理、連線畫面
- 首頁五個分頁：我的最愛、分組討論、即時熱門、私人信件（讀、寫、回，新信提醒）、我
- 看板：列文章、載入更早的、重整、搜尋、發文（草稿、預設內文、預設簽名檔、沒選分類提醒）、長按選單
- 文章：照 BePTT 排版、推文、回文、分享、查詢作者、文章資訊；自己的文章可編輯、改標題、刪除
- 搜尋看板、加入我的最愛、查詢網友
- 終端畫面：認不出來的畫面都退到這裡
- 設定、系統返回鍵、橫著滑回上一頁

### 還沒做

- 終端畫面的顏色與手勢（左右滑＝左右鍵、上下滑＝翻頁）

### 不做（這個 App 的定義，不是待辦）

- 廣告、任何第三方 SDK（Firebase、Analytics、Crashlytics、推播、Remote Config、A/B test）、自家後端
- 背景工作：前景服務、wakelock、WorkManager、開機自啟、定期查信、要求忽略電池最佳化
- 任何 INTERNET 以外的權限
- 預先載入圖片、自動載入圖片（只在按了「開啟圖片」之後抓那一張）、自動播放影片

### 先不做（要做之前先問使用者）

回文同時寄給作者、刪信、轉寄、群組寄信、備份／匯出、ptt2、`bbsu`（UTF-8）連線。

## 程式的分層

```
app（Android、Compose）
  各個 Screen、QuietPttApp（Route ＋ stack）、Connection（前景才連線、重連）、Credentials（KeyStore）、AppContainer
      │ 只透過 Session 與 Page
core（純 Kotlin，沒有 Android）
  ptt/Session        送按鍵、等畫面
  ptt/ScreenParser   Screen → Page
  terminal/Terminal  bytes → Screen（vt100 跳脫序列、Big5-UAO；不可變的快照）
  transport/         Transport 介面：SshTransport（JSch）、WebSocketTransport（OkHttp）
  record/            錄影格式、遮帳號與 IP、時間量測：tools/capture 寫、測試讀；App 用不到
tools/capture        對真的 PTT 抓畫面、跑 e2e
```

寫程式的規則：

- **core 不能 import android.\***（Kotlin JVM module，compile 就會擋）。和 Android 無關的邏輯一律放 core、用 JVM 測試
- `Session`：**送了鍵之後一律帶 `after`**（先等畫面變了），不然網路慢時會拿到按鍵之前的畫面。
  收資料、解析、等畫面都在 `Dispatchers.Default`，**不在主執行緒**
- `ScreenParser` 全靠文字特徵，**每個判斷旁邊註明認的是哪個字**。認不出來回 `Page.Unknown`，App 退到終端畫面，不會卡死。
  列表照 pttbbs 原始碼的固定欄位切；站方改版面時去 github.com/ptt/pttbbs 對欄寬，測試用真的畫面 fixture
- `AppContainer.scope` 沒有例外處理器：裡面 `launch` 的東西例外一定要接住（丟出去就閃退）
- 兩種 `Transport` 都**不送 keep-alive**。SSH **不帶 BouncyCastle**：真機連不上先看 logcat 是不是演算法協商失敗，確定是才考慮加
- 導覽是 `Route` ＋ `rememberSaveable` 的 stack，不引入 Navigation 套件；`Application.onCreate` 只掛前景觀察者，不碰網路
- Compose 列表都給 `key`

## 省電與乾淨（硬規則）

- 只在前景連線；退到背景 2 分鐘斷線（設定可以改成 5、10 分鐘，不能更長、不能不斷）；沒有 ping、沒有輪詢、沒有查新信
- 沒有前景服務、wakelock、WorkManager、BroadcastReceiver、ContentProvider；不要求忽略電池最佳化、不開 `largeHeap`
- 圖片按了「開啟圖片」才抓那一張（最多 20 MB、長邊 2048 縮小解碼）；其他連結交給外部瀏覽器
- 啟動不初始化任何東西；不打包字型，APK 目標 10 MB 以內
- `allowBackup="false"`：本機只有加密過的帳密、設定、搜尋紀錄、發文草稿
- 守門：`CleanManifestTest`（權限只有 `INTERNET`、沒有 service／receiver／provider）、
  `checkCleanDependencies`（release 依賴樹出現 `forbiddenGroups` 就不給 build）。
  新加依賴前先 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 看它拖進什麼

## 專案約定

- Gradle 根目錄就是 repo 根目錄；三個 module：`:core`、`:app`、`:tools:capture`
- applicationId / namespace `com.twtrubiks.quietptt`：**不要改**，一變手機會當成另一個 App（`scripts/ci/record.sh` 也依賴它）
- **minSdk 28**，compileSdk 37，targetSdk 36，Java / Kotlin target 17。依賴版本集中在 `gradle/libs.versions.toml`；
  build 報版本相容問題時以錯誤訊息為準調整
- AGP 9.x 用 built-in Kotlin：app **不要**套 `org.jetbrains.kotlin.android`；core 與 tools 套 `org.jetbrains.kotlin.jvm`；
  Compose 套 `org.jetbrains.kotlin.plugin.compose`
- JSch 用反射載入演算法，`app/proguard-rules.pro` 整包 keep
- **發版金鑰已經有了，不要再產生新的**。給使用者裝的 APK 從 GitHub Release 來；VM 的 debug 金鑰每個 session 都不一樣，
  簽章對不上的 APK 只能移除重裝

## 雲端 VM 環境

- Android SDK 由 `scripts/cloud-setup.sh` 裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供；SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central `429 Too Many Requests`，隔 20 秒重跑 `./gradlew` 即可；
  錯誤訊息常是 `Plugin [id: '...'] was not found`、`Could not resolve ...`，加 `--info` 才看得到 429。先重跑，不要改版本
- **VM 走 WebSocket 連得到真的 PTT**（`wss://ws.ptt.cc/bbs`，經過 VM 的 proxy；JVM 的 proxy 設定在 `JAVA_TOOL_OPTIONS`）；SSH 的 22 埠被擋，guest 被站方關了
- 帳密**不要請使用者貼進對話**：請他在雲端環境設定加 `PTT_ID`、`PTT_PASSWORD` 環境變數，用 `tools/capture --ws` 的 `:login` 登入，不印、不寫進檔案
- 對真的 PTT **只看不寫**（不發文、不推文、不寄信）。測試仍然靠 MockWebServer 與 fixture，不連真的 PTT
- `gh pr`／`gh issue` 不能用（GraphQL 被擋），改用 `gh api` 打 REST

## 驗證（每次 push 前）

```bash
./gradlew test assembleDebug assembleRelease assembleDebugAndroidTest
```

`test` 跑 core 與 app 的 JVM 測試；`assembleRelease` 會先跑 `checkCleanDependencies`、再跑 R8。

改了 `Session`／`ScreenParser`／`Terminal`／`Push` 之後，再跑 `tools/capture/build/install/capture/bin/capture --e2e MobileComm`
（再跑一次 `Soft_Job`：推文沒對齊的板）：App 的 `Session` 對真的 PTT 走完整個流程，只看不寫。
會寫到 PTT 的（發文、推文、回文、改標題、編輯、刪文）是 `--e2e-write`：**只在使用者要求時跑**，
寫死只在 Test 板、只動自己發的那篇、最後刪掉。改我的最愛、寄信的不在裡面，要測先問使用者

## 分支、commit 與 PR

- 推到哪個分支照使用者說的。雲端 session 的 git 只能推自己的 `claude/…` 分支，使用者要求才開 PR，CI 過了才合併
- commit 訊息**不加** `Co-Authored-By`、`Claude-Session` 這類尾巴
- 合併 PR 用 **Rebase and merge**：`main` 上的 commit 會換成新的 hash。合併後要在同一個分支繼續做，
  先 `git checkout -B <分支> origin/main`，再 `git push --force-with-lease`
- force push、改寫 `main` 的歷史都要先問使用者
