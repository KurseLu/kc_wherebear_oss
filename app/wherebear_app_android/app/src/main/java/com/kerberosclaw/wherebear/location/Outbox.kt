package com.kerberosclaw.wherebear.location

import com.kerberosclaw.wherebear.net.WBAuth
import org.json.JSONArray
import org.json.JSONObject

/**
 * 離線佇列（對應 iOS 的 outbox / visitOutbox）：斷線期間累積、回線一口氣補送。
 * 存 SharedPreferences 的 JSON 陣列字串；有上限、避免無限成長。
 *
 * 執行緒安全：`enqueue` 是 load → 改 → save 三步，本身不是原子操作 ——
 * ReportingService 在背景執行緒補送、UI 同時排入時會互相蓋掉，離線累積期間直接掉件。
 * SharedPreferences 自己的執行緒安全只涵蓋單次讀寫，管不到這個序列，
 * 所以四個方法一起上鎖（同一個 instance 的 monitor）。
 *
 * 儲存層抽成 [Store] 是為了讓上面那條規則測得到：SharedPreferences 只活在
 * Android runtime，JVM 單測起不來，等於這個併發修正沒有驗收條件。
 * 正式路徑仍走 prefs（預設值），測試注入記憶體版。
 */
class Outbox(
    private val key: String,
    private val cap: Int,
    private val store: Store = PrefsStore,
) {

    /** 只需要「用 key 存取一個字串」，故意不暴露 SharedPreferences 的其餘介面。 */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    private object PrefsStore : Store {
        override fun get(key: String): String? = WBAuth.prefs.getString(key, null)
        override fun put(key: String, value: String) =
            WBAuth.prefs.edit().putString(key, value).apply()
        override fun remove(key: String) = WBAuth.prefs.edit().remove(key).apply()
    }

    @Synchronized
    fun load(): MutableList<JSONObject> {
        val raw = store.get(key) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    @Synchronized
    fun save(items: List<JSONObject>) {
        count = items.size
        if (items.isEmpty()) {
            store.remove(key)
            return
        }
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        store.put(key, arr.toString())
    }

    @Synchronized
    fun enqueue(item: JSONObject, dedupKey: ((JSONObject) -> String)? = null) {
        val q = load()
        if (dedupKey != null) {
            val k = dedupKey(item)
            q.removeAll { dedupKey(it) == k }   // 同一 visit（到達+離開兩段）只留最新那版
        }
        q.add(item)
        while (q.size > cap) q.removeAt(0)
        save(q)
    }

    @Volatile var count: Int = 0
        private set

    @Synchronized
    fun refreshCount() { count = load().size }
}
