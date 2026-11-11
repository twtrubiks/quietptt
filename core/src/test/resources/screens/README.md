# 畫面 fixture

每個檔案是一張 24 列的終端畫面（純文字，顏色不留），給 `ScreenParserTest`、`SessionTest` 的假 PTT 用。

**大多是真的 PTT 畫面**：在雲端 VM 用 `tools/capture --ws` 登入錄的（帳號換成 x、IP 的數字換成 0）；
Test 板的發文、回文、刪文、`Q` 是 `capture --e2e-write` 在自己的文章上錄的。
使用者的我的最愛只留前 4 個看板（其他列清空），不把整份最愛放進 repo。
來源寫「示意」的是照記憶或 pttbbs 原始碼排的，不是錄的。

照 PTT 欄位排的單列文章用 `Fixtures.articleRow`（pttbbs `mbbsd/bbs.c` 的 `readdoent`）。
一整串的錄影在 `../recordings/`，用 `Fixtures.replay` 重播。

| 檔案 | 畫面 | 來源 |
| --- | --- | --- |
| `login.txt` | 歡迎畫面，「請輸入代號，或以 guest 參觀，或以 new 註冊:」在第 20 列 | 真機 |
| `login-welcome.txt` | 密碼對了之後「歡迎您再度拜訪」「請按任意鍵繼續」 | 真機 |
| `login-wrong-password.txt` | 密碼打錯之後的登入畫面 | 示意 |
| `main-menu.txt` | 【主功能表】，最下面一列有帳號（已遮） | 真機 |
| `favorites.txt` | 【看板列表】（按 `f` 進來的我的最愛），只留 4 個看板 | 真機 |
| `favorites-after-board.txt` | 從 MobileComm 退回來，標題列右邊多了「看板《MobileComm》」 | 真機 |
| `favorites-search-prompt.txt` | 我的最愛裡按 `s`：「【 搜尋全站看板 】」「請輸入看板名稱(按空白鍵自動搜尋):」 | 真機 |
| `board-welcome.txt` | 進 MobileComm 的進板畫面（任意鍵） | 真機 |
| `article-list.txt` | MobileComm 的文章列表：18 篇加 2 篇置底，有被刪的文章 | 真機 |
| `article.txt` | 文章第一頁「瀏覽 第 1/7 頁 (  5%)  目前顯示: 第 01~22 行」 | 真機 |
| `article-no-total-pages.txt` | 很長的置底文：「瀏覽 第 1 頁 (  1%)」沒有總頁數 | 真機 |
| `search-title-prompt.txt` | 文章列表按 `/`：最下面一列「搜尋標題: 」 | 真機 |
| `search-results.txt` | 搜「問題」的結果：標題列「系列《MobileComm》」、最下面「系列文章」、序號是結果裡的第幾筆（67347～67366） | 真機 |
| `search-no-results.txt` | 「 ◆ 找不到符合條件的文章。 … [按任意鍵繼續]」 | 真機 |
| `post-form.txt` | Test 板按 Ctrl-P：「發表文章於【 Test 】」「種類：1.測試 … 5.  圖 …」（一個字的分類前面補空白） | 真機 |
| `post-title.txt` | 種類按 Enter 不選之後第 22 列「標題：」 | 真機 |
| `post-editor.txt` | 編輯器，最下面「編輯文章 … ║插入│aipr║  1:  1」 | 真機 |
| `post-save-prompt.txt` | Ctrl-X：「【檔案處理】」「確定要儲存檔案嗎？」 | 真機 |
| `post-aborted.txt` | 答 a：「文章 沒有 存入」、任意鍵（沒發出去） | 真機 |
| `post-saved.txt` | 答 s：簽名檔、「順利貼出佈告，本篇文章不列入記錄，敬請包涵。」、任意鍵 | 真機 |
| `delete-notice.txt` | 刪文答 y 之後：列表上半沒清、第 14 列「正在刪除文章: …」、任意鍵 | 真機 |
| `reply-prompt.txt` | Test 板列表按 `y`：第 22 列「▲ 回應至 (F)看板 (M)作者信箱 (B)二者皆是 (Q)取消？[F]」，最下面那列還是列表的說明 | 真機 |
| `reply-form.txt` | 答 f：發文綱領、第 19 列「發表文章於【 Test 】」、第 20 列「原標題: Re: …」、第 21 列「採用原標題[Y/n]?」 | 真機 |
| `reply-form-quote.txt` | 採用原標題答 y：同一列留著「y」，第 22 列「請問要引用原文嗎(Y/N/All/Repost)？[Y]」 | 真機 |
| `reply-form-new-title.txt` | 採用原標題答 n：同一列換成「新標題：」，後面預填「Re: 原標題」 | 真機 |
| `article-info.txt` | Test 板自己的文章按 `Q`：游標那列上面的框（文章代碼、網址、價格），下框線「[再按 Q 切換價格列表]」，最下面任意鍵 | 真機 |
| `board-complete.txt` | 看板名稱補完「Mo」：「相關資訊一覽表」下面 44 個板名 | 真機 |
| `board-complete-truncated.txt` | 補完「M」：一頁放不下，最後一格「...」（再按空白鍵不翻頁） | 真機 |
| `board-keyword-results.txt` | 列出全部看板按 `/` 打「測試」：SOB_TEST、Test | 真機 |
| `unknown.txt` | 隨便一個認不出來的畫面 | 示意 |
| `class-root.txt` | 【分類看板】（主選單 `C`）：第 7 列起每列一個分類，14 項，最後一項「--> 即時熱門看板 <--」 | 真機 |
| `hot-boards.txt` | 即時熱門看板（分類第 14 項）的第 1 頁：第 1 列「[y]只列最愛」、最下面那列「看板列表」 | 真機 |
| `class-folders.txt` | 分類「戰略高手」：子分類「Σ」15 個 | 真機 |
| `class-boards.txt` | 子分類「CompScience」的看板（先跳過進板畫面） | 真機 |
| `mail-menu.txt` | 【私人信件區】的選單 | 真機 |
| `mail-menu-empty.txt` | 按了 `R` 沒有信：最下面那列「 ◆ 您沒有來信 … [按任意鍵繼續]」 | 真機的選單，最下面那列照 pttbbs `vshowmsg` 排的 |
| `mail-list.txt` | 【郵件選單】信箱的列表：未讀「+」、回信「r」「R:」、標記「M」、轉寄「轉」、備忘錄 | 第 0～2 列與最下面那列是真機的；信是假的（私人信件不進 repo），欄位照真機對齊 |
| `mail-read.txt` | 一封站內信：標頭右邊「站內  收件人」 | 示意（照 pmore 的標頭排的，內容是假的） |
| `mail-system.txt` | 系統信：沒有標頭，最下面那列「▲此頁內容會依閱讀者不同,原文未必有您的資料」沒有行號 | 最下面那列是真機的，內文是假的 |
