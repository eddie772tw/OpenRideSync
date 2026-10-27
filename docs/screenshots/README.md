# App 截圖

這些畫面由 Android Emulator 實際執行 OpenRideSync 後，以 `adb exec-out screencap -p` 擷取。PNG 保留原始像素；主 README 僅透過 HTML 寬度設定縮小展示。

## 版本與環境

| 項目 | 內容 |
| --- | --- |
| App 版本 | `0.1.0-alpha.1`，versionCode `2` |
| 原始碼 | [`378afc992de5204eb4ff38e19490e04178d98ee6`](https://github.com/eddie772tw/OpenRideSync/commit/378afc992de5204eb4ff38e19490e04178d98ee6)，[首版開發 PR](https://github.com/eddie772tw/OpenRideSync/pull/1) |
| APK SHA-256 | `060ee6a35b27181fd08b0b4b35394a1df5f9505e7e074f67bff5bba41eb5ba23`；已與模擬器內安裝的 APK 核對 |
| 建置設定 | debug；`withHondaConfig=false`、`withOemUiAssets=false` |
| 模擬器 | Android 15／API 35，Google APIs x86_64，system image revision 9 |
| 畫面 | 1080 × 2400，420 dpi，直向，繁體中文（`zh-TW`） |
| 擷取日期 | 2026-10-27 |
| 帳號與車輛 | 未登入帳號、未連接車輛，未匯入私人設定、車庫或旅程資料 |

## 畫面對照

| 檔案 | 開啟方式與顯示狀態 |
| --- | --- |
| [home.png](home.png) | 從導引返回首頁；帳號未登入、儀表未連線 |
| [setup.png](setup.png) | 歡迎頁 → 開始設定；顯示設定導引的第一步 |
| [quick-replies.png](quick-replies.png) | 設定 → 快速回覆範本；使用 App 內建範本，未編輯或傳送訊息 |
| [permissions.png](permissions.png) | 設定 → 權限與功能；保留未授權狀態，未按下權限請求 |
| [settings-dark.png](settings-dark.png) | 設定 → 日間／夜間外觀 → 夜間；顯示外觀選擇與功能設定入口 |
| [diagnostics.png](diagnostics.png) | 設定 → 診斷與匯出；顯示模擬器內的本機 App 操作紀錄，未執行匯出 |

圖檔大小與 SHA-256 見 [`manifest.json`](manifest.json)。畫面只代表這個開發版本的介面與本機操作，不構成登入、藍牙通訊、車載儀表顯示、旅程上傳或實車驗收證據。

更新截圖時，請同步記錄來源 commit、APK 雜湊與建置設定，並檢查畫面是否包含個人資料。主 README 的預覽可能與 main 分支的版本不同，請以此處標示的來源為準。
