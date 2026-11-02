# Gen1 多次實車與旅程上傳回饋

更新日期：2026-11-02，Asia/Taipei。實測日期為2026-10-28至10-30；11月2日從原手機的本機ADB擷取既存資料並離線核對。本報告提供去識別摘要及後續開發工作，原始證據由私有研究庫保管。

## 結論與候選範圍

新增五次連線均完成Assignment、安全狀態檢查及AES驗證，進入已驗證runtime；五次旅程合計203筆位置保存成功、14批正常上傳API成功並完成本機佇列確認。採樣、持久保存及上傳已取得實測證據；同一旅程的路徑明細對帳與原版App可見仍待驗。

本次驗證的是原研究候選，package為`org.openridesync.app`、Android 16／API 36、單一手機與原已配對Gen1車輛。APK SHA-256：

```text
671257208740b39654ec6a751efc31b79ddcf687b3855ef9ff09b5593c92245b
```

候選包含研究環境的登入設定與OEM素材，`SOURCE_COMMIT=unknown`，以APK hash及私有來源快照固定身分。它與本repo的Alpha／Release APK在來源、設定、素材及簽章上有差異，**本結果不代表目前公開發行APK已完成同一輪驗收**。[SOURCE-PROVENANCE](../SOURCE-PROVENANCE.json)提供公開工程的研究來源對照，不能取代受測APK身分。

10月21日首次約4分47秒的實車成功另有App journal、HCI及使用者觀察，見[相容性說明](COMPATIBILITY.md)。其27筆指派、204筆控制及211筆功能通知屬於該次歷史樣本，不混入下列新五次的數量。

## 新五次實測

| 場次 | 日期 | 已驗證連線至尾端斷線 | 位置保存成功事件 | 上傳成功批次 | App Control receipt成功／失敗事件 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | 2026-10-28 | 34分43秒 | 68 | 4 | 147／1 |
| 2 | 2026-10-29 | 30分12秒 | 49 | 3 | 76／4 |
| 3 | 2026-10-29 | 1分54秒 | 3 | 1 | 7／0 |
| 4 | 2026-10-29 | 37分25秒 | 66 | 3 | 57／0 |
| 5 | 2026-10-30 | 44分03秒 | 17 | 3 | 196／2 |
| 合計 | | 約148分17秒 | 203 | 14 | 483／7 |

五次沿用既有配對及key，完成AES驗證，沒有新的RSA exchange。加上10月21日，研究候選已有六次成功runtime。四次新連線超過30分鐘，已驗證連線期間未記錄另一筆斷線；使用者於11月2日確認五次尾端全部為結束測試／關車。

原始`DISCONNECTED UNAVAILABLE`、重試取消及service `FAILED_PRECONDITION`仍保留，不將它們改成已驗證的明確停止／final-clear。本批長時間runtime支持基本連線延續，尚不能代替30分鐘靜置、背景／鎖屏、通知停止及重連的逐項驗收。

## 旅程保存與上傳

- 五次`TRIP START`及203筆`TRIP RESPONSE SUCCEEDED`。同一受測來源在`TripPointStore.append`完成後才記錄保存成功，支持實際位置持久保存，而非僅開關、permission或排程已啟用。
- 14個operation分別完整對應`TRIP REQUEST STARTED → API REQUEST STARTED／SUCCEEDED → TRIP REQUEST SUCCEEDED`。同一來源的寫入為`PipelineService/CreateLocation`；最後TRIP成功在佇列acknowledge及清除in-flight後才記錄。
- 採證時`points.db`的離線`PRAGMA quick_check`為`ok`、`trip_points`為0筆，本帳號錄製仍啟用且沒有in-flight標記。成功上傳RPC返回及本機批次確認已有實測證據。
- 定位、通知與前景服務所需權限仍授予，系統定位開啟；`TripUploadJobService`的15分鐘持久化網路工作仍註冊，系統有最近成功執行紀錄。排程執行成功與14批實際上傳分開核對，空佇列工作不計作新上傳。
- 受測程式只在location前景能力、權限及已驗證連線條件符合時保存採樣；這是來源與runtime的交叉證據，沒有當時Android服務type或鎖屏狀態的獨立快照。

目前journal沒有每批點數、point UUID或觸發來源，無法把14批全部歸為週期自動觸發，也不宣稱203點已逐筆完成後端對帳。保存成功事件亦不保證採樣密度或路徑完整度。

手機原已保存的Honda每日清單快取有相符測試日期及時間範圍，帳號hash一致，current head與對應daily cache的request-bound digest通過。10月29日清單有三筆旅程，與當日三次測試一致；其他日期另有本批journal無法歸屬的旅程，不列入本App成果。現有路徑明細均屬較早的舊旅程，沒有這五次的新明細。因此，**每日清單提供日期／時間佐證，同一trip／point回讀、完整路徑及原版App可見仍待驗**。本輪沒有另向server查詢、觸發上傳或自動重送。

## 七筆Control非成功回覆

場次1、2、5分別有1、4、2筆`BLE_CONTROL_RECEIPT FAILED／FAILED_PRECONDITION`，均位於已驗證連線期間，之後連線及旅程仍持續。這些不是關車尾端事件，不能將本批寫成所有控制成功。

依受測來源，Control exchange在解析到非status1 receipt後記錄此結果；journal未保存原始status、命令或可對應的request ID，尚不能定位是哪項投影、模式或輸入，也無法從相鄰Function通知推論根因。

本次bugreport保存六份格式完整的BTSnoop、合計386,222 packets，header cumulative drops皆0；但最早raw timestamp已在10月31日，即使考慮先前確認的8小時對時差，也不涵蓋10月28至30日五次測試。**本批沒有對應實車ATT逐筆核對**，不能用10月21日的status1結果補足這七筆回覆。

## 開發回饋與後續驗收

公開工程已包含[GattSession的write-property選擇](../app/src/main/java/org/openridesync/app/ble/GattSession.kt)及[CompanionChoice的配對結果處理](../app/src/main/java/org/openridesync/app/pairing/CompanionChoice.kt)。研究中首筆Assignment前失敗的共用WRITE_NO_RESPONSE邊界已修正，後續五次初始化成功支持保留這條修正。此次回饋不新增推測性的V1.1 wire行為，也不鏡像研究庫的私有建置輸入。

後續以目前公開工程為開發來源：

- [ ] **定位七筆非成功Control**：下次測試同時保留當次HCI及最小必要的安全診斷，對應命令、wire request ID、raw receipt status與模式；取得可比對證據後再修正根因。參考[Gen1ControlExchange](../app/src/main/java/org/openridesync/app/ble/Gen1ControlExchange.kt)。
- [ ] **完成旅程明細對帳**：以同一帳號對應受測旅程，核對保存點、上傳批次與正常唯讀回讀的trip／point及路徑；另記錄原版App可見。保留不確定批次不得自動重送的行為。參考[TripRecording](../app/src/main/java/org/openridesync/app/trips/TripRecording.kt)與[TripViewModel](../app/src/main/java/org/openridesync/app/trips/TripViewModel.kt)。
- [ ] **補齊驗收情境**：30分鐘靜置及背景／鎖屏、明確停止與final-clear、Bluetooth OFF、權限撤銷、車端離線後明確重連，以及通話／訊息／真實導航的刺激與結果。
- [ ] **驗證公開交付APK**：固定來源、配置、簽章及APK hash，依[相容性清單](COMPATIBILITY.md)另行驗證；不沿用研究debug候選的安裝或升級結論。

## 證據完整性與資料保全

本批journal共18,355列，較10月22日啟用後快照新增13,553列；原669,794-byte前綴完整保留。承載五次新實車的三個App session共12,861列、序號連續、dropped與write_failures為0、status皆OK。三個session沒有terminal checkpoint，不能據此宣稱完整歷史`complete=true`。

| 私有原始證據 | 大小 | SHA-256 |
| --- | ---: | --- |
| 診斷journal | 2,668,050 bytes | `345c6402effbb98dda4a4dd0154a5ba332b21fad2aa1bff8fd29d3ab718da156` |
| 本輪bugreport | 145,039,568 bytes | `2d5d62dd105037c5d5f405ca14dd40f449ca70650b87fbdb5ac4dd64eae8f1d9` |

bugreport手機與本機hash一致；54份本輪證據檔已逐一核對雜湊。採證前後203份App檔案逐位元組相同；既有preferences、配對、wrapped key、系統bond、通知設定及匯出均保留。未安裝APK、修改設定、主動連車或發送服務寫入。

本repo只保存上述去識別結論。原始tar、日誌、HCI、位置、帳號、裝置／車輛識別、配對key、設定、簽署材料及OEM素材保留於私有研究環境，不附於公開Issue、PR或Release。
