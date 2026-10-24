# SNS Grab - 社群媒體全能無水印下載器

<p align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" width="128" height="128" alt="SNS Grab Icon" />
</p>

<p align="center">
  <b>一款基於 Android Jetpack Compose 與 Kotlin 原生開發的社群媒體無水印影音極速下載工具</b><br />
  全面支援 <b>Facebook</b>、<b>陸抖 (抖音)</b>、<b>TikTok</b>、<b>Instagram</b>、<b>Threads</b>、<b>X (Twitter)</b> 與 <b>Bluesky</b> 七大主流社群平台。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin_1.9+-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Language" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose_Material3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Compose" />
  <img src="https://img.shields.io/badge/Architecture-Clean_%26_Serverless-blue?style=flat-square" alt="Serverless" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License" />
</p>

---

## 🌟 支援平台與特色

| 平台 | 支援範圍 | 解析特色 | 登入需求 |
| :--- | :--- | :--- | :---: |
| **📘 Facebook** | Reels、Watch 短影片、一般貼文影片、`fb.watch` 分享短網址 | 直連 Meta CDN 抓取高畫質 HD/SD 原生 MP4 漸進式串流 | **免登入** |
| **🪕 陸抖 (抖音)** | 短影片、一般作品、圖集相簿、`v.douyin.com` 短網址 | **自動過濾複製文字中夾帶的多餘宣傳文字與標籤**，官方原生 **1080P 無水印原畫** | **免登入** |
| **🎵 TikTok** | 國際版短影音、`vt.tiktok.com` / `vm.tiktok.com` 分享短鏈 | 解析下載官方 100% 無水印高清原片 | **免登入** |
| **📷 Instagram** | 照片、影片、多圖輪播 (Carousel)、Reels、限時動態 (Stories) | 直連官方 CDN 抓取未壓縮無水印原圖原片 | 公開貼文**免登入**<br />限動支援可選登入 |
| **🧵 Threads** | 公開貼文影片、圖片、`threads.com/share/` 與 `threads.net/t/` | 伺服器端渲染（SSR）直出解析，多重雙引擎爬蟲備援 | **免登入** |
| **🐦 X (Twitter)** | 推文影片、GIF、多圖推文 | 自動匹配最高位元率無損原畫（多重備援解析） | **免登入** |
| **🦋 Bluesky** | 影片串流、相片貼文 | 底層對接去中心化 AT Protocol XRPC，直連 PDS 媒體節點 | **免登入** |

---

## 🚀 核心亮點技術

### 1. 智慧連結提取與文字淨化（Smart URL Sanitization）
* 從抖音等平台複製連結時，剪貼簿常伴隨大量促銷文案、話題 `#標籤` 與前後綴宣傳字串。
* SNS Grab 內建智慧 URL 萃取引擎，點擊「貼上」或手動輸入時**自動過濾多餘文字**，精準鎖定並解析真正的網址。

### 2. 多重防檔名重複覆蓋機制（Anti-Collision File Naming）
* 全面杜絕同名檔案被靜默覆蓋遺失的風險。
* 檔名結構規範化：`{平台前綴}_{作者名}_{作品ID}.mp4`（例如 `DY_高妹來了_7698754950691913329.mp4`、`Threads_ruru.___s_De3HohzH5Iu.mp4`）。
* 本地相簿若已存在同名檔案時，系統會自動遞增序號（如 `_1`、`_2`）依序儲存，確保珍貴回憶永不流失。

### 3. MP4 底層二進位時間戳校正（`Mp4Patcher`）
* 解決從社群下載歷史影片後，相簿排序跑到幾年前甚至最底部的長年頑疾。
* 透過二進位直接修補 MP4 封裝頭部的 `mvhd` 與 `tkhd` Atom 建立時間戳記，並同步更新 Android MediaStore 系統索引，**確保所有影片下載後在手機相簿一律即時置頂於「今天」**。

### 4. 零伺服器中轉（Serverless Zero-Leak Architecture）
* 完全在手機客戶端本機完成所有解析與下載，直接連線至各社群平台官方 CDN。
* 無任何中介伺服器、無第三方轉址服務、無廣告追蹤、無個資外洩隱患。

### 5. 極致輕量（~16.6 MB）
* 不掛載肥大的 FFmpeg 與 Python 執行環境，純原生 Kotlin 協程極速併發，下載不發燙、不耗電、不佔手機空間。

---

## 📱 系統要求

* **作業系統**：Android 8.0 (API 26) 以上（全面適配 Android 14 / Android 15）
* **架構支援**：`arm64-v8a`、`armeabi-v7a`、`x86_64`

---

## 📦 安裝與建置

### 直接下載 APK
專案根目錄已提供最新編譯完成之乾淨無混淆安裝包：
👉 **[`SNSGrab.apk`](SNSGrab.apk)**（可直接下載傳送至手機安裝）

### 自行編譯 (From Source)
```bash
# Clone 專案
git clone https://github.com/YOUR_USERNAME/SNSGrab.git
cd SNSGrab

# 執行單元測試
./gradlew testDebugUnitTest

# 編譯 Debug APK
./gradlew assembleDebug

# 編譯 Release APK
./gradlew assembleRelease
```
編譯產出之路徑：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 🛠️ 開發技術棧

* **UI 框架**：Jetpack Compose + Material Design 3
* **程式語言**：Kotlin 1.9+
* **非同步機制**：Kotlin Coroutines & Flow
* **網路通訊**：OkHttp 4
* **儲存機制**：Android Storage Access Framework & MediaStore API (Scoped Storage)
* **圖像載入**：Coil (Compose)

---

## 📄 免責聲明 (Disclaimer)

本軟體僅供個人備份自己或公開授權之合法社群影音內容使用。請尊重原創作者之智慧財產權與各平台之服務條款，勿用於任何未經授權之商業用途或侵權行為。
