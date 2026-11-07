# 測試、錄影、抓畫面

寫或改 `app/src/androidTest/`、`scripts/ci/record.sh`、fixture 之前先讀；每次 push 前要跑的指令在 `CLAUDE.md` 的「在 VM 內驗證」。

## JVM 測試（主要的測試）

- `core/src/test/`：`Big5UaoTest`、`TerminalTest`（跳脫序列、全形字、夾在字中間的顏色、捲動）、`ScreenParserTest`（吃 fixture）
- `app/src/test/`：`CleanManifestTest`（守門）
- 連線層之後用 `mockwebserver3` 開一個假的 WebSocket 伺服器測 `WebSocketTransport` 與 `Session.login` 的流程（送什麼、收什麼）；
  SSH 那邊只能在真機或 CI 測

## 畫面 fixture（`core/src/test/resources/screens/`）

`ScreenParser` 的測試資料，每個檔案是 24 列文字（顏色不留）。**現在的幾張是照記憶寫的示意**，不是真的畫面。

在自己的電腦上抓真的：

```bash
./gradlew :tools:capture:run -q --console=plain
```

連上 ptt.cc（SSH）後每次畫面安靜下來會印出 24 列與辨識結果，然後等你輸入一行：

| 輸入 | 動作 |
| --- | --- |
| 任何文字 | 送出那段文字（例如帳號） |
| `:pw 密碼` | 送密碼加 Enter |
| `:enter` `:up` `:down` `:left` `:right` `:pgup` `:pgdn` | 按鍵 |
| `:save 名稱` | 把目前畫面存到 `tools/capture/out/名稱.txt`（這個資料夾在 `.gitignore`） |
| `:quit` | 離開 |

要抓的：`login`、`login-wrong-password`、`main-menu`、`favorites`、`article-list`、`article`，以及登入途中跳出來的每一種提示。
**存進 repo 前把自己的帳號、信箱、IP 改成假的**，再搬到 `core/src/test/resources/screens/`，然後照真的畫面修 `ScreenParser` 與測試的斷言。

## 模擬器上的測試（CI）

`record.sh` 依序：`DeviceTest`（不錄影）→ `DemoRecordingTest`（錄影）→ release 版冒煙測試（打開沒閃退、看到登入頁）。
模擬器**不連 PTT**：連不連得到不一定，也不該把真的帳號放進 CI。

- `DeviceTest`：只有真機才測得到的事，現在是 AndroidKeyStore 加解密帳密
- `DemoRecordingTest`：展示流程，用 UiAutomator 操作（真實時間、動畫會播）。不要用 Compose test rule、不要用 `ActivityScenario`，
  找元件用 `UiTest.waitFor` 每 0.1 秒輪詢。元件找到之後畫面又重組了，按之前要重新找一次。
  用 `By.text(...)`，要用 testTag 時用 `res("tag")`（root 已開 `testTagsAsResourceId`）。
  每個操作之間停 1～1.5 秒；全長控制在 150 秒內（`screenrecord` 上限 180 秒）
- release 冒煙測試沒有測試 APK，靠 `uiautomator dump` 找 `login_button`

## 看 CI 的結果

`record.yml` 把結果 force-push 到 `media` branch：`demo.mp4`、`frames/`（每 2 秒一張影格）、`screenshots/`、
`gradle-build.txt`、`device-test.txt`、`instrument.txt`、`logcat*.txt`、`info.txt`（branch、commit、run 連結、狀態）。
雲端 session 用 `git fetch origin media && git show origin/media:info.txt` 讀，影格用 `git show origin/media:frames/010.png > /tmp/f.png` 再看圖。
