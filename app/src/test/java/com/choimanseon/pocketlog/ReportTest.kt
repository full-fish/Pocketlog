package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.ai.MonthlyReport
import com.choimanseon.pocketlog.data.Budget
import com.choimanseon.pocketlog.data.Dummy
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.domain.Period
import com.choimanseon.pocketlog.domain.monthPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** AI 월간 리포트: the app adds up the month itself; the AI only gets these numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportTest {
    private val dao get() = app.dao
    private fun at(m: Int, d: Int) = LocalDate.of(2026, m, d).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun clean() = runBlocking(Dispatchers.IO) {
        while (dao.categoriesOnce().isEmpty()) delay(20)
        app.db.clearAllTables()
        dao.seedIfEmpty()
    }

    @Test
    fun factsAddUp() = runBlocking(Dispatchers.IO) {
        val cats = dao.categoriesOnce()
        val food = cats.first { it.name == "식비" && it.parentId == null }
        val pay = cats.first { it.name == "급여" && it.parentId == null }
        dao.insert(Tx(amount = 12_000, occurredAt = at(9, 3), merchant = "김밥천국", categoryId = food.id))
        dao.insert(Tx(amount = 30_000, occurredAt = at(9, 20), merchant = "김밥천국", categoryId = food.id))
        dao.insert(Tx(type = com.choimanseon.pocketlog.data.TxType.INCOME, amount = 3_000_000, occurredAt = at(9, 25), merchant = "급여", categoryId = pay.id))
        dao.insert(Tx(amount = 20_000, occurredAt = at(8, 10), merchant = "본죽", categoryId = food.id))
        dao.upsert(Budget(amount = 30_000, categoryId = food.id))

        val f = MonthlyReport.facts(Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))!!
        assertEquals(42_000L, f.getJSONObject("this_month").getLong("spending"))
        assertEquals(3_000_000L - 42_000, f.getJSONObject("this_month").getLong("left"))
        assertEquals(20_000L, f.getJSONObject("last_month").getLong("spending"))
        val cat = f.getJSONArray("categories").getJSONObject(0)
        assertEquals(Triple("식비", 42_000L, 20_000L), Triple(cat.getString("name"), cat.getLong("amount"), cat.getLong("last_month")))
        assertEquals(42_000L, f.getJSONArray("budgets").getJSONObject(0).getLong("spent"))
        assertEquals(2, f.getJSONArray("places").getJSONObject(0).getInt("visits"))
        val daily = f.getJSONArray("daily")
        assertEquals(30 to 30_000L, daily.length() to daily.getLong(19)) // 9월 20일

        assertNull(MonthlyReport.facts(Period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)))) // nothing recorded: no report
    }

    /** For checking the prompt by hand: REPORT_FACTS_OUT=<file> writes last month's numbers from the dummy data. */
    @Test
    fun dumpDummyFacts() = runBlocking(Dispatchers.IO) {
        val out = System.getenv("REPORT_FACTS_OUT")
        assumeTrue(out != null)
        Dummy.insert(LocalDate.now())
        dao.upsert(Budget(amount = 2_500_000))
        val last = monthPeriod(LocalDate.now(), 1).shiftMonths(-1)
        File(out!!).writeText(MonthlyReport.facts(last)!!.toString(2))
    }
}
