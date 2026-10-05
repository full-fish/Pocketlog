package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.auto.Repeats
import com.choimanseon.pocketlog.data.Favorite
import com.choimanseon.pocketlog.data.Repeat
import com.choimanseon.pocketlog.data.TxSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** 즐겨찾기 반복 기록: the right days, and runs missed while the phone was off are caught up once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepeatTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun nextDates() {
        assertEquals(LocalDate.of(2026, 10, 25), Repeats.nextDate(Repeat.MONTHLY, 25, LocalDate.of(2026, 10, 5)))
        assertEquals(LocalDate.of(2026, 11, 25), Repeats.nextDate(Repeat.MONTHLY, 25, LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2026, 2, 28), Repeats.nextDate(Repeat.MONTHLY, 31, LocalDate.of(2026, 1, 31))) // no 31st: the last day
        assertEquals(LocalDate.of(2026, 3, 31), Repeats.nextDate(Repeat.MONTHLY, 31, LocalDate.of(2026, 2, 28)))
        assertEquals(LocalDate.of(2026, 10, 12), Repeats.nextDate(Repeat.WEEKLY, 1, LocalDate.of(2026, 10, 5))) // a Monday: the next one
        assertEquals(LocalDate.of(2026, 10, 9), Repeats.nextDate(Repeat.WEEKLY, 5, LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun missedRunsAreCaughtUp() = runBlocking(Dispatchers.IO) {
        val dao = app.dao
        while (dao.categoriesOnce().isEmpty()) delay(20)
        app.db.clearAllTables()
        dao.seedIfEmpty()
        val cats = dao.categoriesOnce()
        val subs = cats.first { it.name == "구독" && it.parentId == null }
        val ott = cats.first { it.parentId == subs.id && it.name == "OTT" }
        dao.upsert(Favorite(amount = 17_000, merchant = "넷플릭스", categoryId = subs.id, tags = "${ott.id}", repeat = Repeat.MONTHLY, repeatDay = 25, nextAt = at(2026, 8, 25)))

        assertEquals(2, Repeats.runDue(at(2026, 10, 5), zone))
        val rows = dao.txAround(0, Long.MAX_VALUE).filter { it.source == TxSource.REPEAT }.sortedBy { it.occurredAt }
        assertEquals(listOf(at(2026, 8, 25), at(2026, 9, 25)), rows.map { it.occurredAt })
        assertEquals(listOf(ott.id), dao.tagsOfOnce(rows[0].id))
        assertEquals(at(2026, 10, 25), dao.favorites().first().single().nextAt)
        assertEquals(0, Repeats.runDue(at(2026, 10, 5), zone)) // nothing twice
    }
}
