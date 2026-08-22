package com.kerberosclaw.wherebear.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kerberosclaw.wherebear.MainActivity
import com.kerberosclaw.wherebear.R
import com.kerberosclaw.wherebear.core.ReportFrequency

/**
 * 背景回報的載體。
 *
 * iOS 的作法是 UIBackgroundModes:location ＋ significant-change ＋ CLVisit，
 * 系統決定什麼時候把 app 叫醒 —— 也因此原專案吃過「冷啟動沒註冊監控 → 好幾小時空白」的虧。
 * Android 沒有那套喚醒機制，取而代之是 foreground service：只要服務活著，
 * 定位更新就穩定進來，不受 Doze 影響。代價是通知列會常駐一則通知（系統規定，拿不掉）。
 *
 * 換句話說：這一塊 Android 其實比 iOS 可靠，但使用者看得見。
 */
class ReportingService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (!LocationReporter.hasForegroundLocation(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val freq = LocationReporter.frequency.value
        val interval = freq.pollSeconds * 1000

        val priority = if (freq == ReportFrequency.SAVER)
            Priority.PRIORITY_BALANCED_POWER_ACCURACY   // 對應 iOS kCLLocationAccuracyHundredMeters
        else
            Priority.PRIORITY_HIGH_ACCURACY             // 對應 kCLLocationAccuracyNearestTenMeters

        val request = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(interval / 2)   // 系統若剛好有更新可提早給，但不會比這更密
            .setMaxUpdateDelayMillis(interval * 2)      // 允許批次遞送 → 省電
            .setWaitForAccurateLocation(false)
            .build()

        callback?.let { client.removeLocationUpdates(it) }
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { LocationReporter.onLocation(it) }
            }
        }
        client.requestLocationUpdates(request, cb, mainLooper)
        callback = cb

        // 起手先取一次，不必等第一個 interval
        runCatching { client.lastLocation.addOnSuccessListener { it?.let(LocationReporter::onLocation) } }

        // START_STICKY：被系統回收後自動重建（＝iOS 冷啟動自動恢復的對應物）
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.reporting_notification_title))
            .setContentText(getString(R.string.reporting_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "wb_reporting"
        private const val NOTIF_ID = 4201

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reporting_channel_name),
                NotificationManager.IMPORTANCE_LOW,   // 不出聲、不橫幅
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(ch)
        }

        /**
         * 啟動回報服務。回傳 true = 真的起起來了。
         *
         * 🔴 **絕對不要讓這裡的例外往外丟。**
         * Android 12 起禁止從背景啟動前景服務（`ForegroundServiceStartNotAllowedException`）。
         * 這個函式有四個呼叫點，其中兩條會在背景執行：
         *   · `WhereBearApp.onCreate` —— 系統把被回收的程序拉回來時
         *   · `BootReceiver` 收到 `MY_PACKAGE_REPLACED`（`BOOT_COMPLETED` 是官方豁免、這個不是）
         *
         * 例外從 `Application.onCreate` 逃出去的後果不是「這次沒起來」，是**整個 app 冷啟動就崩**，
         * 而系統每次想把它拉回來又再崩一次 —— 回報從此不會自己恢復，得使用者手動打開 app。
         *
         * 實測（ASUS Zenfone 11 Ultra / Android 15）：
         *   2026-08-19 15:57、08-20 22:00 各崩一次，位置分別斷了 5 小時與整晚。
         *
         * 起不來不是死路：`LocationReporter.onEnterForeground()` 會在回到前景時補一次，
         * 那時候啟動前景服務是合法的。
         */
        fun start(context: Context): Boolean {
            ensureChannel(context)
            val i = Intent(context, ReportingService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException（API 31+）繼承自 IllegalStateException，
                // catch 父型別就不用為了型別本身再做一次版本判斷。
                Log.w(TAG_START, "背景中不能啟動前景服務，等回前景再補：${e.message}")
                false
            }
        }

        private const val TAG_START = "WBReportingService"

        fun stop(context: Context) {
            context.stopService(Intent(context, ReportingService::class.java))
        }

        fun restart(context: Context) { start(context) }   // onStartCommand 會重下 request
    }
}
