# QuietPTT

安靜的 PTT Android 客戶端。

- **沒有廣告、沒有遙測**：沒有任何第三方 SDK，對外只連 `ptt.cc`
- **不在背景做事**：只在前景連線，退到背景 30 秒就斷線；沒有前景服務、沒有常駐、沒有查信
- **只要一個權限**：網路
- **兩種連線**：SSH（預設）或 WebSocket，設定裡切換
- **記住帳號與密碼**（可關）：密碼用手機的 KeyStore 加密，不會被備份出去

支援 Android 9 以上。

## 現況

骨架階段，還不能用。進度與架構見 [`CLAUDE.md`](CLAUDE.md)。

## 下載

正式版會放在 [GitHub Release](../../releases/latest)（`quietptt.apk`）。不上架 Google Play。

## 開發

Kotlin + Jetpack Compose，在 [claude.ai/code](https://claude.ai/code) 雲端 session 開發。
不碰 Android 的部分（連線、終端模擬、Big5-UAO、畫面辨識）是純 Kotlin 的 `core` module，用 JVM 單元測試；
畫面的測試在 GitHub Actions 的 Android 9 模擬器上跑並錄影（[`record.yml`](.github/workflows/record.yml)）。

- 規格：[`docs/spec.md`](docs/spec.md)
- 架構、約定：[`CLAUDE.md`](CLAUDE.md)
- 測試、錄影、抓畫面：[`docs/testing.md`](docs/testing.md)
- 雲端環境設定：[`docs/cloud-setup.md`](docs/cloud-setup.md)
- 發版金鑰：[`docs/release-key.md`](docs/release-key.md)
- 之後可能會做：[`docs/ideas.md`](docs/ideas.md)
