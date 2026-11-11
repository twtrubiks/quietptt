package com.twtrubiks.quietptt.core.ptt

/**
 * 文章裡的網址是不是圖片，照 BePTT 的規則：副檔名是 jpg／jpeg／png／gif／gifv／webp，或是 imgur 的圖（`imgur.com/ID`、`i.imgur.com/ID`，ID 5 個字以上）。
 * 回傳真正能抓的圖片網址：imgur 沒有副檔名的補 `.jpg`、`gifv` 換成 `gif`、`imgur.dcard.tw` 換回 `imgur.com`；imgur 的相簿（`/a/`、`/gallery/`）不是一張圖，回 null。
 * `http://` 一律換成 `https://`：Android 9 起 App 預設不准明碼連線（OkHttp 直接丟「CLEARTEXT communication … not permitted」），
 * 舊文章的 `http://i.imgur.com/…` 不換就永遠載不出來；App 也不打算開放明碼連線。
 * 不是圖片回 null（App 照一般網址處理，交給瀏覽器）。
 */
fun imageUrl(url: String): String? {
    val fixed = url.replace("imgur.dcard.tw/", "imgur.com/").let {
        if (it.startsWith("http://", ignoreCase = true)) "https://" + it.substring("http://".length) else it
    }
    val lower = fixed.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')
    if (IMAGE_EXTENSIONS.any { path.endsWith(".$it") }) {
        // gifv → gif：拿掉的是路徑最後那個 v，不是整個網址的最後一個字（後面可能還有 ?…、#…）
        return if (path.endsWith(".gifv")) fixed.removeRange(path.length - 1, path.length) else fixed
    }
    val imgur = IMGUR.matchEntire(fixed) ?: return null
    return "https://i.imgur.com/${imgur.groupValues[1]}.jpg"
}

private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "gif", "gifv", "webp")

// imgur.com/ID、i.imgur.com/ID、m.imgur.com/ID（ID 是英數 5 個以上）；/a/、/gallery/ 是相簿，不算
private val IMGUR = Regex("""https?://(?:[im]\.)?imgur\.com/([A-Za-z0-9]{5,})/?""")
