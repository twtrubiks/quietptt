# 之後可能會做

跟使用者討論過、使用者說之後可能實作的想法。開工前先跟使用者確認。

## `bbsu` 連線（UTF-8）

ptt.cc 的 SSH 用 `bbsu` 登入會直接吐 UTF-8，不必解 Big5-UAO。
沒用的原因：WebSocket 只有 Big5-UAO，兩種連線 bytes 不一樣就要兩套解碼與兩套 fixture。
如果之後 WebSocket 拿掉、或 UAO 之外的字（表情符號）顯示有問題，再考慮。

## 終端畫面的顏色與手勢

`Cell.attr` 已經有顏色，終端畫面還沒畫出來。手勢：左右滑是左右鍵、上下滑是翻頁（打字框與 Ctrl 已經做了，見 `docs/spec.md`「終端畫面」）。

## App 內提醒更新

App 打開時（一天最多一次）查 GitHub 的 `releases/latest`，有新版就在我的最愛上方顯示「有新版本」加下載按鈕（交給系統的下載與安裝）。
不自己在背景下載安裝。查不到就安靜跳過。這會讓 App 自己連到 ptt.cc 以外的地方（不是使用者按了才連），要做之前先問使用者。

## Host key 自動檢查

GitHub Actions 每週 `ssh-keyscan ptt.cc` 跟 `PttHost.KNOWN_HOSTS` 比，不一樣就開 issue。

## 量每一步花多少時間

`recordings/` 的錄影每一段收到的 bytes 都有時間（`Recording.Received.ms`）。寫個小工具算「送鍵之後分幾段來、段跟段隔多久」，
看時間花在哪一步、`Session.QUIET_MS`（150 ms）是不是太保守。只在本機分析錄影，不連 PTT。
VM 經 proxy 從國外連，間隔跟手機上不一樣，只能當參考。下面「少等畫面安靜」看這個結果再決定怎麼做。

## 少等畫面安靜

每送一次鍵都要等畫面 150 ms 沒再變才算畫完（`Session.QUIET_MS`），網路好的時候這段比來回一趟還久。
文章頁看到頁尾進度列畫完就不等（`pageQuiet`）；我的最愛、看板列表、載入更早的、搜尋、選單之間還在等。

- 找每一種頁面「最後才畫」的東西，看到就不等。列表要小心：只移游標時 PTT 只重畫兩列，頁尾還是上一次的，會把畫到一半的當成畫完
- 或直接把 150 ms 縮短：fixture 測不到時間上的問題，只能在真機上看

## 打開 App 先列上次的我的最愛

連線、登入、讀我的最愛這段時間現在只看到「連線中…／登入中…」。把上次的清單存在手機上（不備份），打開就先列，登入完換成最新的；
登入好之前點了看板，等登入好再進去。

代價：清單可能是舊的（在終端或別的 App 改過）；打開 App 的流程要改（現在先停在登入頁）；手機上多存一份看板名稱。要做之前先討論。

## ptt.cc 文章網址在 App 裡開

文章、推文裡別篇文章的網址（`https://www.ptt.cc/bbs/板名/M.….html`）現在交給外部瀏覽器。
改成換算成文章代碼（AID），進那個板用代碼找到那篇打開（BePTT、PttX 都有做）。只連 PTT。

再進一步：manifest 的 Activity 加 intent-filter，別的 App 分享的 ptt.cc 網址可以選 QuietPTT 開（要做之前先問使用者）。

## 圖示的小地方

- 讀信頁右上角「回信」還是字（`MailScreen`），可以換成文章頁「回文」的圖示（`AppIcons.Reply`）
- 長按圖示跳出文字提示（M3 `TooltipBox`），第一次看到「>_」的人知道是終端

## Baseline Profile（看過，現在不做）

Compose 官方建議的啟動、捲動加速。從 GitHub 裝的 APK 要加 `profileinstaller` 才生效，它預設帶一個 ContentProvider（`CleanManifestTest` 擋）；
App 用幾次之後系統也會自己最佳化，目前不划算。

## 三星鍵盤的剪貼簿（看過，現在不做）

用三星鍵盤時，App 裡每個輸入框都打不開鍵盤工具列的「剪貼簿」（按了沒反應）；長按貼上、複製都正常，換 Gboard 也正常。
原因在 Compose 的輸入框本身：`OutlinedTextField`、`BasicTextField`＋`TextFieldState` 都打不開，包在 `AndroidView` 裡的原生 `EditText` 打得開，沒有鍵盤選項可以調。

要修只能把輸入框換成原生 `EditText`（先換打長文字的：發文、推文、回文、寫信的內文、編輯文章、預設內文）。代價是外框與深淺色要自己包、
文字狀態要自己同步（注音選字到一半不能被打斷）、鍵盤彈出時的捲動與模擬器測試找輸入框的方式都要重做，而且只能靠實機來回驗證。
換來的只有剪貼簿的歷史清單，目前不划算。
