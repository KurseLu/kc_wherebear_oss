package com.kerberosclaw.wherebear

import android.app.Application
import com.kerberosclaw.wherebear.location.LocationReporter
import com.kerberosclaw.wherebear.location.ReportingService
import com.kerberosclaw.wherebear.net.WBAuth

class WhereBearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WBAuth.init(this)
        LocationReporter.init(this)
        ReportingService.ensureChannel(this)

        // 冷啟動自動恢復回報：開關記在 prefs（見 LocationReporter.init）。
        // 有權限才起 —— 沒權限硬起前景服務在 Android 14 會直接被系統擋掉。
        //
        // 🔴 這裡有可能是「系統在背景把程序拉回來」，那時候起前景服務是不合法的。
        //    ReportingService.start() 已經把那個例外接住並回 false，不會再讓 app 崩掉；
        //    真正補起來的地方是 LocationReporter.onEnterForeground()。
        if (LocationReporter.isReporting.value && LocationReporter.hasForegroundLocation(this)) {
            ReportingService.start(this)
        }
    }
}
