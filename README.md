# OpenRideSync

獨立 Android App，使用 Kotlin、Jetpack Compose 與自寫 Java Gen1 encoder。這是面向使用者版本的開發工程，並非 Honda 官方 App。

目前沿用已完成一次基本實車連線、儀表與把手互動的 Gen1 程式基線。公開建置使用公開圖示與系統字型；各個交付 APK 的配置、簽署與驗收身分需分開核對。Gen2 與 DFU 不在目前範圍。

## 建置

需要 JDK 17、Android SDK 36 與 Build Tools 36.0.0。設定 ANDROID_HOME，或在本機 local.properties 填入 sdk.dir。

    ./gradlew :diagnostics:check :honda:check :auth-bridge:check :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PwithHondaConfig=false -PwithOemUiAssets=false -PsourceCommit=<full-git-sha>
    python -m unittest discover -s tools/validation -p 'test_*.py'
    python -m unittest discover -s tools/diagnostics -p 'test_*.py'
    node tools/auth/login_test.js
    python tools/check_public_source.py

Windows 使用 gradlew.bat。預設建置沒有登入設定，帳號功能會明示無法使用；本機說明、權限導引與診斷仍可開啟。APK 位於 app/build/outputs/apk/debug/app-debug.apk，這個未配置的 debug 產物不作為已驗收的手機候選。

獲得適當的原服務 client 設定後，可明確傳入保存在研究 repo 私有目錄的檔案：

    ./gradlew :app:assembleDebug -PwithHondaConfig=true -PhondaConfigFile=<private-auth-client.json> -PwithOemUiAssets=false -PsourceCommit=<full-git-sha>

只會將指定的單一設定檔複製至忽略的建置輸出並打包，不會納入鄰近檔案。設定限於原有 allowlist client 欄位，不能放入使用者 token、密碼或服務帳號。這個選項不提供原服務授權，也不證明登入已通過。OEM 素材不由公開工程匯入。

## 使用與驗證

- [第一版使用流程](docs/GETTING-STARTED.md)
- [相容性與驗收範圍](docs/COMPATIBILITY.md)
- [開發與版本工作](docs/DEVELOPMENT.md)
- [第三方元件](THIRD-PARTY-NOTICES.md)

原始實車證據、APK、私有設定與簽署材料留在原研究 repo。公開 repo 只保存實作、合成測試及可分享的說明。不要在 Issue 附上帳號、token、車輛／裝置識別、通知正文、位置或未檢查的原始側錄。

原創程式的開源授權尚待作者選定；第三方程式與素材仍遵循各自授權。
