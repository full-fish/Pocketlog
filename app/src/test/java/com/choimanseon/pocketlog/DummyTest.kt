package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.data.Dummy
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.domain.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** 설정 → 더미 데이터: five years in, and out again without touching real rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DummyTest {
    @Test
    fun fiveYearsInAndOut() = runBlocking(Dispatchers.IO) {
        val dao = app.dao
        while (dao.categoriesOnce().isEmpty()) delay(20)
        app.db.clearAllTables()
        dao.seedIfEmpty()
        val real = dao.insert(Tx(amount = 8000, occurredAt = System.currentTimeMillis(), merchant = "진짜 내역"))
        val today = LocalDate.of(2026, 10, 4)

        val added = Dummy.insert(today)
        val rows = dao.txAround(0, Long.MAX_VALUE)
        val dummies = rows.filter { it.source == TxSource.DUMMY }
        assertTrue("rows: ${dummies.size}", dummies.size > 4000 && added > 4000)
        assertEquals(today.minusYears(5).year, dummies.minOf { it.occurredAt }.toLocalDate().year)
        assertTrue(dummies.any { it.installmentMonths > 1 } && dummies.any { it.installmentOf != null })
        assertEquals(dummies.size, dao.dummyCount().first())

        dao.deleteDummy()
        assertEquals(listOf(real), dao.txAround(0, Long.MAX_VALUE).map { it.id })
    }
}
