# 開發與版本工作

手機維持 Compose／Kotlin 與 Java 核心；App、auth-bridge、diagnostics、honda 為同一 Gradle 工程，encoder 已置於 App 原始碼中。一般建置不需要 OEM APK、反編譯目錄或研究 repo。

公開工程是後續 App 實作的主要來源。原研究 repo 繼續保存研究、私有建置輸入與原始證據；研究結論以可審查的程式／測試變更整合，不進行雙向目錄鏡像。

首個原始碼預發行為 [`v0.1.0-alpha.1`](https://github.com/eddie772tw/OpenRideSync/releases/tag/v0.1.0-alpha.1)（versionCode 2），首批 Alpha 實作已整合到 main。這次交付原始碼與發行說明，不附 APK；版本變更見 [CHANGELOG](../CHANGELOG.md)。

目前已實作及後續工作：

- 已在歡迎頁與關於頁顯示版本、登入設定與可用範圍；包含設定與實際登入結果分開說明。
- 已將 App 內使用說明改為安裝後可操作的步驟，授權頁對應 MIT 與第三方來源。
- **官方 APK 發行與簽章**：透過 `.github/workflows/release.yml` 在 GitHub Actions 中由 Secrets 注入登入設定（`HONDA_AUTH_CLIENT_JSON`）與專屬發行簽章（`RELEASE_KEYSTORE_BASE64` 等），產出已配置且簽章固定的 Release APK 並附帶 SHA-256 校驗單。此專屬簽章將持續沿用，以確保後續版本皆可保留資料升級。
- **開發者自行建置**：公開儲存庫不包含原服務設定或簽章金鑰。自行建置者須從自己的原廠 App 環境取得 client 參數後，透過 `-PwithHondaConfig=true -PhondaConfigFile=...` 注入。
- **帳號前置條件與後續開發項目**：目前版本要求使用者必須先在原廠 Honda RoadSync App 完成首次註冊與條款初始化；「新帳號直接初始化流程（`LoginFromAndroid` 等）」列為未來版本開發項目。
- **核對工具（Validation Tooling）**：`tools/validation/check_build_readiness.py` 目前專責日常 CI 的 `clean-unconfigured-debug` 檢查；Release 工作流程先以 `apksigner`、badging 與 ZIP 檢查取代，針對已配置 Release APK 的專用核對規則列為後續補充項目。
- **相容性與驗收**：目前基準已視為通過首版放行門檻；後續仍持續擴充更多手機／車型相容性條目、長時間背景穩定性與真實旅程上傳回讀紀錄。

每個候選記錄 exact source、APK SHA-256、簽章及配置。重建不得冒稱為歷史 APK 的逐位元組複本；SOURCE_COMMIT 宣告需配合乾淨來源檢查。正式簽署材料與私密設定透過 GitHub Secrets 或私有環境管理，不寫入公開 Git 歷史。
