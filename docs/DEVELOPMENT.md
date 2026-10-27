# 開發與版本工作

手機維持 Compose／Kotlin 與 Java 核心；App、auth-bridge、diagnostics、honda 為同一 Gradle 工程，encoder 已置於 App 原始碼中。一般建置不需要 OEM APK、反編譯目錄或研究 repo。

公開工程是後續 App 實作的主要來源。原研究 repo 繼續保存研究、私有建置輸入與原始證據；研究結論以可審查的程式／測試變更整合，不進行雙向目錄鏡像。

首個原始碼預發行為 [`v0.1.0-alpha.1`](https://github.com/eddie772tw/OpenRideSync/releases/tag/v0.1.0-alpha.1)（versionCode 2），首批 Alpha 實作已整合到 main。這次交付原始碼與發行說明，不附 APK；版本變更見 [CHANGELOG](../CHANGELOG.md)。

目前已實作及後續工作：

- 已在歡迎頁與關於頁顯示版本、登入設定與可用範圍；包含設定與實際登入結果分開說明。
- 已將 App 內使用說明改為安裝後可操作的步驟，授權頁對應 MIT 與第三方來源。
- 待完成：交付 APK 的登入設定、簽署與升級方式，以及安裝、登入、選車配對、基本騎乘、停止與診斷的完整驗收。
- 待補齊：相容性條目、重複連線、長時間背景穩定性與真實旅程上傳回讀。
- 依各功能的實車證據決定開放範圍；保留既有實作，不以測試數推論全功能完成。

每個候選記錄 exact source、APK SHA-256、簽章及配置。重建不得冒稱為歷史 APK 的逐位元組複本；SOURCE_COMMIT 宣告需配合乾淨來源檢查。正式簽署材料與配置候選輸出保存在研究 repo 私有目錄，公開 CI 不接觸它們。
