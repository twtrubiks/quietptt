# 測試、錄影、抓畫面

寫或改 `app/src/androidTest/`、`scripts/ci/record.sh`、fixture 之前先讀；每次 push 前要跑的指令在 `CLAUDE.md` 的「在 VM 內驗證」。

## JVM 測試（主要的測試）

- `core/src/test/`：`Big5UaoTest`、`TerminalTest`（跳脫序列、全形字、夾在字中間的顏色、捲動）、`ScreenParserTest`（吃 fixture）
- `core/src/test/.../transport/FakePtt`：用 `mockwebserver3` 開的假 ws.ptt.cc。連上送第一個畫面，之後照收到的 frame 換畫面（畫面是 fixture 文字，
  送出前用 CUP 一列一列定位、編成 Big5-UAO）。`received` 記下 App 送的每一個 frame（`Session.send` 一次一個 frame）
  - `WebSocketTransportTest`：Origin、binary frame 進出、一個字拆在兩個 frame、對方關掉時 `read` 回 null
  - `SessionTest`：登入流程（提示疊在同一畫面、重複登入答 y 只答一次、密碼錯、過場畫面不按 Enter、斷線馬上失敗）與走到各頁按了哪些鍵、
    整篇文章一頁一頁翻（真機錄的 7 頁）。假 PTT 的畫面是真機錄的 fixture，按鍵反應照真機；每次回應都慢 300 ms（比 `QUIET_MS` 久），
    確定 Session 不會拿按鍵之前的畫面當答案
  - `ArticleReplayTest`：重播真機錄影（`recordings/article-pages.txt`），每頁認得出來、網址的 OSC 8 不變亂碼、接起來是整篇
  - `MailWriteTest`：寄信、回信（`sendMail`／`replyMail`）的畫面辨識與按鍵：先查收件人（PTT 會補成別人的不寄）、私人信件區用上下鍵選「站內寄信」、
    收不到／今日免費額度用完（答 n）、存檔之後沒寄出去與認不出來（可能寄出去了）、回信的採用原標題／新標題／引用原文、信箱變了、系統信、帳號被重新註冊。
    主選單、私人信件區、信箱列表是真機的 fixture，寄信與回信的畫面照 pttbbs `mail.c` 排的
  - `ReplyPostTest`：回文到看板（`replyPost`）的畫面辨識與按鍵：`y` 之後的「回應至」三種、已結案、看板變了不回、採用原標題／新標題、引用原文、
    內文打在引言上面、引言太多（e 回編輯器放棄，不強制寫入）、存檔之後認不出來（可能發出去了）。
    列表、板規、存檔提示、「順利貼出佈告」是真機的 fixture，`y` 之後的提示與回文表單照 pttbbs `bbs.c` 排的；
    真機的 `y` 提示、回文表單（`reply-prompt`、`reply-form*`）在 `RealActionScreensTest` 認，列號、字跟照原始碼排的一樣
- `app/src/test/`：`CleanManifestTest`（守門）、`ArticleListTest`（點哪一列送哪篇）、`MailFormTest`（寫信頁：收件人、標題長度、回信的預設標題）、
  `ReplyPostFormTest`（回文頁：預填的標題、改過的標題長度、內文不能空）
- SSH 那邊只能在真機或 CI 測

## 畫面 fixture 與錄影（`core/src/test/resources/screens/`、`recordings/`）

- `screens/*.txt`：`ScreenParser` 的測試資料，每個檔案是 24 列文字（顏色不留）。**大多是真機錄的**（哪些不是見該資料夾的 README）
- `recordings/*.txt`：`tools/capture` 錄的一段連線，bytes 與按鍵各帶毫秒，格式在 `core/.../record/Recording.kt`。
  `Fixtures.replay(名稱)` 餵進 `Terminal`，回傳每次送出按鍵之前的畫面；終端模擬的問題（例如 OSC）只有原始 bytes 測得出來。
  放進 repo 前只留需要的那段（`article-pages.txt` 只有打開文章到翻完的那幾個事件），使用者的我的最愛之類的不要放

### 錄真的畫面

**雲端 VM 走 WebSocket 連得到真的 PTT**（`--ws`；SSH 的 22 埠被擋）。guest 被站方關了（回「請改用網頁版」），一定要用帳號登入。
帳密**不要貼進對話**：使用者在雲端環境的設定（session 標題列的環境選單 → Edit → Environment variables）加 `PTT_ID`、`PTT_PASSWORD`，
新的 session 起來才讀得到；在 VM 裡用 `:login` 登入，兩個都不會印出來、不會寫進檔案；印在終端機上的畫面也會遮帳號與 IP（工具的輸出會留在對話紀錄裡）。
只看不寫：不發文、不推文、不寄信；會寫的功能用下面的 `--e2e-write`，只在使用者要求時跑。
JVM 走 proxy 的設定在 `JAVA_TOOL_OPTIONS` 裡已經有了，不用另外設。

```bash
tools/capture/build/install/capture/bin/capture --ws
```

**端對端**：`capture --e2e [看板]` 用 App 的 `Session`（同一份程式碼）照 App 的順序走一次真的 PTT——登入、我的最愛、
我的最愛整份（超過一頁的話翻完；再把游標放到第 2 頁讀一次；只印數字不印板名）、進板、
載入更早的兩次、開推文最多的那篇翻到最後、推文切成一則一則（推文列都要切得出來，「? 同一人同一分鐘、沒接起來」給人看該不該接）、
標頭（標題要跟列表上的對得上）與簽名檔、等寬的列、網址各幾個、檢查新推文（重讀回第一頁）、
複製全文（`wholeArticle`，要跟一頁一頁讀的一樣、做完回到列表）、文章資訊（`articleInfo`：那篇與最後一篇置底文按 `Q`，要讀到代碼、網址是這個板的、回到列表）、退回、重整（退出重進要回到最新那頁）、開置底文、在文章裡重整、
看板裡搜尋（標題：列表上最常見的分類字，開結果裡最新的那篇、重讀、搜尋結果的重整（重搜）、載入更前面的結果；作者；同標題（那篇拿掉「R: 」的標題，看板長按的「尋找同標題文章」）；推文數高於 30；找不到的關鍵字；
退回整個列表）、退回我的最愛（整份再讀一次要一樣）、再進一次同一個板（要回到最新那頁）、
首頁另外三個分頁（【分類看板】、第一個分類與它的第一個子分類、退回我的最愛、即時熱門看板、從熱門的列表進板、信箱、
讀最新一封**已讀的**信、退回我的最愛；信只印幾封、未讀幾封、讀到幾行，不印寄件人、標題、內文，失敗時也不印畫面）——印出每一步的結果與毫秒，只看不寫。
站方對搜尋有頻率限制，一次只搜 6 次，不要連著跑很多次。
改了 `Session`、`ScreenParser`、`Terminal`、`Push` 之後在 VM 跑一次（要有 `PTT_ID`、`PTT_PASSWORD`）。
推文對不對齊是看板的設定，至少跑一個有對齊（MobileComm）、一個沒對齊（Soft_Job）的板：

```bash
tools/capture/build/install/capture/bin/capture --e2e MobileComm
```

**會寫到 PTT 的**（發文、推文、回文、改標題、編輯內文與在推文下面回應、刪文）另外是 `capture --e2e-write`，**只在使用者明確要求時跑**：
程式寫死只在 **Test 板**、只動自己剛發的那一篇（每一步之前確認列表上那篇的作者是自己），推文推在自己的文章上（PTT 只給 →），
最後刪掉；中途失敗也會把那篇刪掉。回文是回自己那篇兩次（採用原標題＋引用原文、新標題＋不引用），讀回來對標題、內文、引言之後各自刪掉。順便走找看板（板名補完「Mo」「M」、中文「測試」），發文、刪文之後 PTT 的訊息畫面會印出來。

```bash
tools/capture/build/install/capture/bin/capture --e2e-write
```

在自己的電腦上也能錄：

```bash
./gradlew :tools:capture:installDist
tools/capture/build/install/capture/bin/capture
```

Windows 用 `gradlew.bat` 與 `tools\capture\build\install\capture\bin\capture.bat`。預設走 SSH（跟 App 一樣），加 `--ws` 走 WebSocket。
用 installDist 跑，`:pw` 輸入密碼時不會顯示；`./gradlew :tools:capture:run -q --console=plain` 也能跑，但密碼會顯示在畫面上。

每送一次就等畫面安靜 0.5 秒，印出 24 列與辨識結果，同時寫進 `tools/capture/out/`（在 `.gitignore` 裡）：

| 檔案 | 內容 |
| --- | --- |
| `recording-時間.txt` | 收到的 bytes（base64）與送出的按鍵，各帶毫秒；給測試重播 |
| `screens-時間.txt` | 每一步：送了什麼、辨識成哪一頁、24 列文字；給人看 |
| `名稱.txt` | `:save 名稱` 另存的畫面（fixture 格式） |

**個人資料**：帳號換成同樣長度的 `x`、IP 的數字換成 `0`（三種檔都是，存檔時才換，所以帳號在錄到一半才知道也沒關係）。
停在「請輸入代號」或「請輸入您的密碼」時打的字，不管是帳號還是密碼都不寫進檔案、而且都會被遮；`:pw` 打的密碼不寫進檔案。
交出去之前還是打開 `screens-*.txt` 看一遍。

| 輸入 | 動作 |
| --- | --- |
| 文字 | 送出那段文字；`\r` 代表 Enter，`Stock\r` 就是板名連 Enter 一次送出（跟 App 一樣） |
| `:pw` | 輸入密碼 |
| `:login` | 用環境變數 `PTT_ID`、`PTT_PASSWORD` 登入（雲端 session 用；送帳號、等密碼提示、送密碼） |
| `:enter` `:up` `:down` `:left` `:right` `:pgup` `:pgdn` `:home` `:end` `:esc` `:space` `:tab` `:bs` | 按鍵 |
| `:note 文字` | 在錄影裡留一句話（「接下來進 Stock 板」），重播時看得懂在做什麼 |
| `:save 名稱` | 另存目前畫面 |
| `:quit` | 結束並存檔（中途斷線也已經存了） |

### 錄一次完整的流程（照 App 的按鍵順序，約 5 分鐘）

照 App 會送的鍵一步一步打，錄到的就是 App 會遇到的畫面；每一頁 `:save` 一張。

1. 連上後打帳號（會自動加 Enter）。想錄密碼錯誤的畫面：先打錯一次密碼，`:save login-wrong-password`，再打一次帳號
2. `:pw` 輸入密碼。之後跳出來的提示照 App 的答法：重複登入 `y\r`（會踢掉手機上同一個帳號的連線）、刪除錯誤嘗試 `y\r`、請按任意鍵 `:enter`、認不出來的畫面 `:enter`。
   每一種提示都 `:save`（`login-duplicate`、`login-delete-failed`、`login-anykey`…）
3. 主選單：`:save main-menu`，然後 `f\r`
4. 我的最愛：`:save favorites`，然後 `s`、`Stock\r`（游標移到 Stock）、`:enter`（進板）。有板規就 `:save board-rules`、`:enter`
5. 文章列表：`:save article-list`。打畫面上某一篇的序號加 `\r`（例如 `735478\r`），再 `:enter` 開文章
6. 文章：`:save article`，`:pgdn` 翻一頁 `:save article-page2`，翻到最後一頁 `:save article-last`，`:left` 回列表
7. 置底文：`:end`、`:enter`，`:save article-pinned`，`:left`
8. `:left` 回我的最愛，`:quit`

把 `recording-*.txt`、`screens-*.txt` 與存下來的畫面交給 Claude（貼在對話或放進 repo 都可以），
就能一次把 fixture、`ScreenParser`、`Session` 的按鍵序列與假 PTT 都照真的改。

## 模擬器上的測試（CI）

`record.sh` 依序：`DeviceTest`（不錄影）→ `DemoRecordingTest`（錄影）→ release 版冒煙測試（打開沒閃退、看到登入頁）。
模擬器**不連 PTT**：連不連得到不一定，也不該把真的帳號放進 CI。

- `DeviceTest`：只有真機才測得到的事，現在是 AndroidKeyStore 加解密帳密、文章裡的動圖（`ImageDecoder`；模擬器是 Android 9，走寫暫存檔那條路）
- `DemoRecordingTest`：展示流程，用 UiAutomator 操作（真實時間、動畫會播）。不要用 Compose test rule、不要用 `ActivityScenario`，
  找元件用 `UiTest.waitFor` 每 0.1 秒輪詢。元件找到之後畫面又重組了，按之前要重新找一次。
  用 `By.text(...)`，要用 testTag 時用 `res("tag")`（root 已開 `testTagsAsResourceId`）。
  對話框（`AlertDialog`、`ModalBottomSheet`）是另一個視窗，吃不到 root 那個設定：裡面的元件要用 `res(...)` 找，對話框的 `modifier` 加 `DialogTestTags`（設定頁的對話框都加了）。
  每個操作之間停 1～1.5 秒；全長控制在 150 秒內（`screenrecord` 上限 180 秒）
- release 冒煙測試沒有測試 APK，靠 `uiautomator dump` 找 `login_button`

## 看 CI 的結果

`record.yml` 把結果 force-push 到 `media` branch：`demo.mp4`、`frames/`（每 2 秒一張影格）、`screenshots/`、
`gradle-build.txt`、`device-test.txt`、`instrument.txt`、`logcat*.txt`、`info.txt`（branch、commit、run 連結、狀態）。
雲端 session 用 `git fetch origin media && git show origin/media:info.txt` 讀，影格用 `git show origin/media:frames/010.png > /tmp/f.png` 再看圖。
