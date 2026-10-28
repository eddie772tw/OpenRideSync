# Changelog

## v0.1.0-alpha.2 — 2026-10-28

首次正式附帶 Release APK 的發行版，App 版本 `0.1.0-alpha.2`，versionCode `3`。

### 包含內容

- 官方發行 APK 支援：建立專屬且持續使用的發行簽章（Release Signing Key），後續發行版本可保留資料直接覆蓋升級。
- GitHub Actions 自動化發行管線（`.github/workflows/release.yml`）：自動在推送版本 Tag 時注入原服務登入設定、簽署 Release APK 並上傳至 GitHub Releases。
- 帳號註冊前置指引：當帳號尚未於原廠 App 註冊時，明確引導使用者先在原廠 Honda RoadSync App 完成首次註冊與初始設定（新帳號直接初始化流程列為未來版本開發項目）。
- 支援開發者自建 Release APK 簽章傳入參數（`-PreleaseStoreFile`、`-PreleaseStorePassword` 等）。
- 包含與 `v0.1.0-alpha.1` 相同的 Gen1 連線、儀表互動、媒體、訊息、通話、快速回覆與本機診斷功能。

### 使用條件與已知限制

- 最低 Android 11（API 30）；compile／target API 36。
- 使用前提：使用者必須先在原廠 Honda RoadSync App 完成帳號註冊與服務條款初始設定，才能在 OpenRideSync 以同一個 Google 帳號登入讀取。
- 本次官方發行 APK 包含原服務登入設定；自行從原始碼建置者仍需自備 client 設定。
- Gen1.0／Gen1.5 為開發主線；Gen2 runtime 與 DFU 不在目前範圍。
- 重複連線、30 分鐘背景穩定性、完整媒體／通訊／導航情境及真實旅程採樣、上傳接受與回讀仍待全面驗收。

## v0.1.0-alpha.1 — 2026-10-27

首次公開原始碼預發行，App 版本 `0.1.0-alpha.1`，versionCode `2`。本次提供原始碼、建置方式、介面預覽與已知限制，未附 APK。

### 包含內容

- 獨立 Android 工程：Kotlin／Jetpack Compose App、auth-bridge、honda、diagnostics 與自寫 Java Gen1 encoder。
- 歡迎、首頁、設定導引、日間／夜間外觀、快速回覆、權限說明與本機診斷介面。
- 歡迎頁與關於頁顯示版本及登入設定，分開說明「包含設定」與「已登入成功」。
- Gen1 連線、儀表資料與把手互動的研究基線，以及相關主機回歸測試。
- 六張實際 Android 模擬器截圖、公開建置檢查、使用流程、相容性與驗收文件。
- 原創程式採 MIT 授權；第三方元件保留各自授權。

### 使用條件與已知限制

- 最低 Android 11（API 30）；compile／target API 36，開發工具使用 JDK 17。
- 公開建置預設未包含原服務登入設定，只能查看不依賴帳號的本機功能；實際登入與車輛服務另需適當配置。
- 本次沒有公開 APK，也未提供已確認的安裝／升級方案。私有設定、簽署材料、OEM 素材與原始實車資料不包含在發行原始碼中。
- Gen1.0／Gen1.5 為開發主線；Gen2 runtime 與 DFU 不在目前範圍。
- 重複連線、30 分鐘背景穩定性、完整媒體／通訊／導航情境及真實旅程採樣、上傳接受與回讀仍待驗。
- 主機測試、CI 與模擬器畫面不代替實車驗收；歷史實車基線的適用範圍見[相容性說明](docs/COMPATIBILITY.md)。

完整建置方式見 [README](README.md)，操作流程見[開始使用](docs/GETTING-STARTED.md)。
