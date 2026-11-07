# 畫面 fixture

每個檔案是一張 24 列的終端畫面（純文字，顏色不留），給 `ScreenParserTest` 用。

**現在的幾張是照記憶寫的示意，不是真的 PTT 畫面。** 在自己的電腦上用 `tools/capture` 抓到真的之後換掉，
並把測試的斷言改成真的內容。抓的時候記得先把自己的帳號、信箱之類的改掉再 commit。

| 檔案 | 畫面 |
| --- | --- |
| `login.txt` | 歡迎畫面，「請輸入代號」 |
| `login-wrong-password.txt` | 密碼打錯之後的登入畫面 |
| `main-menu.txt` | 【主功能表】 |
| `favorites.txt` | 【我 的 最愛】 |
| `article-list.txt` | 看板的文章列表 |
| `article.txt` | 文章內容的第一頁 |
| `unknown.txt` | 隨便一個認不出來的畫面 |
