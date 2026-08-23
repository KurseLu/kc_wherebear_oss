package com.kerberosclaw.wherebear

import com.kerberosclaw.wherebear.location.Outbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * enqueue 的 load → 改 → save 是三步。沒上鎖時兩條執行緒交錯就會互相蓋掉，
 * 離線累積期間直接掉件 —— 而離線補回報正是 outbox 存在的理由。
 *
 * 這裡不用 SharedPreferences（JVM 起不來），改注入記憶體 Store。
 * 那個 Store 本身刻意不上鎖：鎖必須在 Outbox 這一層，
 * 否則「儲存層剛好是執行緒安全的」會讓測試假綠。
 */
class OutboxConcurrencyTest {

    private class MemStore : Outbox.Store {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun item(i: Int) = JSONObject().put("n", i)

    @Test fun `兩執行緒各排入 100 筆，一筆都不能掉`() {
        val box = Outbox(key = "test_outbox", cap = 1000, store = MemStore())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        repeat(2) { t ->
            Thread {
                start.await()
                repeat(100) { i -> box.enqueue(item(t * 100 + i)) }
                done.countDown()
            }.start()
        }

        start.countDown()
        assertEquals(true, done.await(30, TimeUnit.SECONDS))
        assertEquals(200, box.load().size)
    }

    @Test fun `超過上限時砍最舊的，總數停在 cap`() {
        val box = Outbox(key = "test_outbox", cap = 50, store = MemStore())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        repeat(2) { t ->
            Thread {
                start.await()
                repeat(100) { i -> box.enqueue(item(t * 100 + i)) }
                done.countDown()
            }.start()
        }

        start.countDown()
        assertEquals(true, done.await(30, TimeUnit.SECONDS))
        assertEquals(50, box.load().size)
    }

    @Test fun `dedupKey 相同的只留最新那筆`() {
        val box = Outbox(key = "test_outbox", cap = 1000, store = MemStore())
        val key: (JSONObject) -> String = { it.optString("visit") }

        box.enqueue(JSONObject().put("visit", "v1").put("stage", "arrive"), key)
        box.enqueue(JSONObject().put("visit", "v1").put("stage", "depart"), key)
        box.enqueue(JSONObject().put("visit", "v2").put("stage", "arrive"), key)

        val q = box.load()
        assertEquals(2, q.size)
        assertEquals("depart", q.first { it.optString("visit") == "v1" }.optString("stage"))
    }

    @Test fun `count 跟著佇列走`() {
        val box = Outbox(key = "test_outbox", cap = 1000, store = MemStore())
        repeat(7) { box.enqueue(item(it)) }
        assertEquals(7, box.count)

        box.save(emptyList())
        assertEquals(0, box.count)
        assertEquals(0, box.load().size)
    }
}
