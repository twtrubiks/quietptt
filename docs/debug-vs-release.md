# debug 版與 release 版

兩個版本的功能、程式邏輯完全一樣，差在**打包方式**、**速度**和**簽章**。
給人用的是 release 版（GitHub Release 發的就是它）；debug 版是開發時方便接除錯器用的。

## 對照

|  | debug | release |
|---|---|---|
| R8（刪掉沒用到的程式碼、改短名稱、最佳化） | 沒有：Compose、Material3、Kotlin、OkHttp、JSch 全部原封不動打包 | 有，只留用得到的部分 |
| 刪掉沒用到的資源 | 沒有 | 有 |
| Compose 預覽工具（`ui-tooling`） | 有 | 沒有 |
| 啟動加速的設定檔（`baseline.prof`，Compose 等套件附的） | 沒有 | 有 |
| 可以接除錯器（debuggable） | 可以 | 不行 |
| 速度 | Compose 明顯比較卡（沒最佳化，加上 debuggable 的執行期檢查） | 順很多 |
| 簽章 | 每個 VM session 自己的 debug 金鑰 | GitHub 發版用發版金鑰（`docs/release-key.md`）；在 VM 沒金鑰時退回 debug 金鑰 |

## 大小（約略）

| | APK | 程式碼（dex） |
|---|---|---|
| debug | 33.4 MB | 8 個 dex，合計約 32 MB |
| release | 3.5 MB | 1 個 dex，3.1 MB |

差了快 10 倍，幾乎都是 R8 刪掉的程式碼。CLAUDE.md 的「APK 目標 10 MB 以內」指的是 release 版。

## 設定在哪裡

都在 `app/build.gradle.kts`：

- `buildTypes.release`：`isMinifyEnabled = true`（R8）、`isShrinkResources = true`（刪資源）、
  `proguardFiles(...)`（R8 的規則；JSch 用反射載入演算法，`app/proguard-rules.pro` 整包 keep）
- `debugImplementation(libs.androidx.compose.ui.tooling)`：預覽工具只進 debug 版
- 簽章：有環境變數 `QUIETPTT_KEYSTORE`、`QUIETPTT_KEYSTORE_PASSWORD` 就用發版金鑰（release.yml 會帶），
  沒有就用這台機器的 debug 金鑰（`~/.android/debug.keystore`）
- debuggable 是 Android Gradle Plugin 的預設：debug 開、release 關

## 怎麼 build

```bash
./gradlew assembleDebug
```

```bash
./gradlew assembleRelease
```

產出在 `app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release.apk`。
`assembleRelease` 會先跑 `checkCleanDependencies`（依賴樹不能有 Firebase、GMS、廣告）再跑 R8，比 debug 慢。

## 裝在手機上要注意的

- 兩個版本的 applicationId 一樣（`com.twtrubiks.quietptt`），手機上**只能裝一個**，新的蓋舊的。
- **簽章不同就不能直接蓋**，要先移除再裝。移除會清掉記住的帳密（存在 App 自己的資料裡，密碼用 AndroidKeyStore 加密），重新打一次就好。
  - GitHub Release 的正式版：發版金鑰，每一版都一樣，可以一直直接更新。
  - 雲端 VM 給的測試 APK：VM 的 debug 金鑰，**每個 session 都不一樣**（同一個 session 給的可以互相蓋）。
  - 所以從 VM 的測試版換到正式版（或反過來）都要移除重裝一次。
- 覺得滑動、捲動卡卡的，先確認裝的是不是 debug 版：debug 版本來就比較慢，不代表 release 版也慢。

## release 版才會出的問題

R8 會刪掉「看起來沒用到」的程式碼。靠反射才會用到的東西（像 JSch 的演算法）沒寫 keep 規則的話，
debug 版正常、release 版才壞。所以：

- CI 的模擬器上有 release 版的冒煙測試（打開沒閃退、看得到登入頁；`docs/testing.md`）
- 加新的依賴、特別是會用反射的，要跑一次 `assembleRelease` 並在 release 版上實際用過
