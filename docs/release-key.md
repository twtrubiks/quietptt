# 產生發版金鑰（只做一次）

給別人裝的 APK 一律從 GitHub Release 來，每一版都要用**同一把**金鑰簽：
換了金鑰，手機上的舊版就不能直接更新，只能移除重裝。所以這把金鑰只產生一次，備份好，之後永遠用它。

**金鑰還沒產生**：`release.yml` 的 `EXPECTED_CERT` 是空的，發版會直接失敗並提示。照下面做一次。

## 1. 產生 PKCS12 金鑰

在自己的電腦上（需要 JDK 的 `keytool`），放在 repo 外面：

```bash
cd ~/work/experiments
keytool -genkeypair -v -storetype PKCS12 -keystore quietptt-release.p12 \
  -alias quietptt -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=QuietPTT"
```

密碼問一次就好（PKCS12 的金鑰檔與金鑰共用同一個密碼）。
`quietptt-release.p12` 與密碼要備份（密碼管理器、另一顆硬碟），弄丟了就再也發不出能直接更新的版本。
`.gitignore` 已經擋掉 `*.p12`，但還是不要放進 repo 的資料夾裡。

## 2. 設定 GitHub Secrets

```bash
base64 -w0 quietptt-release.p12 | gh secret set ANDROID_SIGNING_KEYSTORE --repo <owner>/quietptt
gh secret set ANDROID_SIGNING_PASSWORD --repo <owner>/quietptt   # 貼上密碼
```

## 3. 把憑證的 SHA-256 填進 release.yml

```bash
keytool -list -v -keystore quietptt-release.p12 -alias quietptt \
  | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
```

印出來的 64 碼填進 `.github/workflows/release.yml` 的 `EXPECTED_CERT`。
workflow 簽完會核對這個值，Secret 被換成別把金鑰的話就不發版。

## 4. 發版

版本號用語意化版本（`1.0.0`、`1.0.1`、`1.1.0`…）。兩種發法：

- 推一個 tag：`git tag v1.0.1 && git push origin v1.0.1`
- repo 根目錄的 `release-version` 內容寫版本（例如 `1.0.1`），改了推上去（推不了 tag 的環境用這個）

都會 build、簽名、發 Release（tag `v1.0.1`）。先確認 CI（`record.yml`）過了再發。
