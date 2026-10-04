package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.GroupBy
import com.choimanseon.pocketlog.domain.Period
import com.choimanseon.pocketlog.domain.PeriodUnit
import com.choimanseon.pocketlog.domain.TxFilter
import com.choimanseon.pocketlog.domain.groupSums
import com.choimanseon.pocketlog.domain.label
import com.choimanseon.pocketlog.domain.matches
import com.choimanseon.pocketlog.domain.moveCategory
import com.choimanseon.pocketlog.domain.periodOf
import com.choimanseon.pocketlog.domain.shift
import com.choimanseon.pocketlog.domain.trendBuckets
import com.choimanseon.pocketlog.domain.byTopCategory
import com.choimanseon.pocketlog.domain.evalExpr
import com.choimanseon.pocketlog.domain.installmentRows
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.shortWon
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.total
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class DomainTest {
    @Test fun installmentIsOneRowPerMonth() {
        val at = LocalDate.of(2026, 1, 31).atTime(14, 21).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = installmentRows(Tx(amount = 52_000, occurredAt = at, installmentMonths = 3))
        assertEquals(listOf(17_334L, 17_333L, 17_333L), rows.map { it.amount }) // month 1 takes the remainder
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), rows.map { it.occurredAt.toLocalDate().toString() })
        assertEquals(listOf("할부 1/3회차", "할부 2/3회차", "할부 3/3회차"), rows.map { it.memo })
        assertEquals("노트북", installmentRows(Tx(amount = 900_000, occurredAt = at, installmentMonths = 3, memo = "노트북"))[2].memo)
        assertEquals(1, installmentRows(Tx(amount = 52_000, occurredAt = at)).size) // 일시불
        assertEquals(1, installmentRows(Tx(amount = -52_000, occurredAt = at, installmentMonths = 3)).size) // a refund is not spread
    }

    @Test fun monthPeriodStartsOnPayday() {
        assertEquals(Period(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 25)), monthPeriod(LocalDate.of(2026, 10, 3), 25))
        assertEquals(Period(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 25)), monthPeriod(LocalDate.of(2026, 10, 25), 25))
        assertEquals(Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)), monthPeriod(LocalDate.of(2026, 10, 31), 1))
        assertEquals(Period(LocalDate.of(2025, 12, 25), LocalDate.of(2026, 1, 25)), monthPeriod(LocalDate.of(2026, 2, 3), 25).shiftMonths(-1))
    }

    @Test fun periodLabels() {
        val today = LocalDate.of(2026, 10, 3)
        assertEquals("10월", monthPeriod(today, 1).label(today))
        assertEquals("9.25 ~ 10.24", monthPeriod(today, 25).label(today))
        assertEquals("2025년 12월", monthPeriod(LocalDate.of(2025, 12, 5), 1).label(today))
    }

    @Test fun keypadExpressions() {
        assertEquals(15000L, evalExpr("12000+3000"))
        assertEquals(18000L, evalExpr("12000+3000×2"))
        assertEquals(4000L, evalExpr("12000÷3"))
        assertEquals(9000L, evalExpr("12000−3000+"))
        assertNull(evalExpr(""))
    }

    @Test fun shortWonUnits() {
        assertEquals("12.3만", shortWon(123_000))
        assertEquals("5만", shortWon(50_000))
        assertEquals("1.2억", shortWon(120_000_000))
    }

    @Test fun splitsAndCancelsInCategoryTotals() {
        val food = Category(id = 1, type = TxType.EXPENSE, name = "식비", icon = "", color = 0)
        val lunch = Category(id = 2, type = TxType.EXPENSE, name = "점심", icon = "", color = 0, parentId = 1)
        val home = Category(id = 3, type = TxType.EXPENSE, name = "생활용품", icon = "", color = 0)
        val txs = listOf(
            Tx(id = 10, amount = 8000, occurredAt = 0, categoryId = 2),
            Tx(id = 11, amount = 21400, occurredAt = 0, categoryId = 3), // order with 2 splits
            Tx(id = 12, amount = 5000, occurredAt = 0, categoryId = 1, status = TxStatus.CANCELED),
            Tx(id = 13, amount = -3000, occurredAt = 0, categoryId = 1), // partial refund
            Tx(id = 14, type = TxType.TRANSFER, amount = 50000, occurredAt = 0),
        )
        val splits = listOf(
            TxSplit(txId = 11, name = "생수", amount = 12400, categoryId = 2),
            TxSplit(txId = 11, name = "키친타월", amount = 9000, categoryId = 3),
        )
        val sums = byTopCategory(txs, splits, listOf(food, lunch, home), TxType.EXPENSE).associate { it.category?.name to it.total }
        assertEquals(mapOf("식비" to 17400L, "생활용품" to 9000L), sums)
        assertEquals(26400L, total(txs, TxType.EXPENSE))
    }

    @Test fun statsPeriods() {
        val d = LocalDate.of(2026, 10, 3)
        val mon = DayOfWeek.MONDAY
        assertEquals(Period(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5)), periodOf(PeriodUnit.WEEK, d, 1, mon))
        assertEquals(Period(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1)), periodOf(PeriodUnit.QUARTER, d, 1, mon))
        assertEquals(Period(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)), periodOf(PeriodUnit.YEAR, d, 1, mon))
        // payday 25: 10/3 is in 9/25 ~ 10/24, which is October's; Q4 = Oct·Nov·Dec periods
        assertEquals(YearMonth.of(2026, 10), monthPeriod(d, 25).month())
        assertEquals(Period(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 12, 25)), periodOf(PeriodUnit.QUARTER, d, 25, mon))
        assertEquals(monthPeriod(d, 25), monthPeriod(YearMonth.of(2026, 10), 25))
        assertEquals("2026년 4분기", periodOf(PeriodUnit.QUARTER, d, 25, mon).label(PeriodUnit.QUARTER, d))
        assertEquals("2025년", periodOf(PeriodUnit.YEAR, d, 1, mon).shift(PeriodUnit.YEAR, -1).label(PeriodUnit.YEAR, d))
        assertEquals("9.28 ~ 10.4", periodOf(PeriodUnit.WEEK, d, 1, mon).label(PeriodUnit.WEEK, d))
        val buckets = trendBuckets(PeriodUnit.MONTH, monthPeriod(d, 1), 1, mon)
        assertEquals(12, buckets.size)
        assertEquals(listOf("11월", "10월"), listOf(buckets.first().second, buckets.last().second))
        assertEquals(10, trendBuckets(PeriodUnit.CUSTOM, Period(d, d.plusDays(10)), 1, mon).size) // one bar a day
    }

    @Test fun slicesOpenTheirOwnTransactions() {
        val zone = ZoneId.systemDefault()
        fun at(day: Int, hour: Int) = LocalDate.of(2026, 10, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val food = Category(id = 1, type = TxType.EXPENSE, name = "식비", icon = "", color = 0)
        val lunch = Category(id = 2, type = TxType.EXPENSE, name = "점심", icon = "", color = 0, parentId = 1)
        val cats = listOf(food, lunch)
        val txs = listOf(
            Tx(id = 1, amount = 8000, occurredAt = at(5, 12), categoryId = 2, paymentMethodId = 7, merchant = "김밥천국"), // Monday lunch
            Tx(id = 2, amount = 3000, occurredAt = at(6, 23), categoryId = 1, merchant = "편의점 "),
            Tx(id = 3, amount = 50000, occurredAt = at(6, 9), type = TxType.INCOME),
        )
        val base = TxFilter(at(1, 0), at(31, 0), TxType.EXPENSE)
        fun check(by: GroupBy, expected: Map<String, Long>) {
            val groups = groupSums(txs, emptyList(), cats, listOf(PayMethod(id = 7, kind = PayKind.CREDIT, name = "삼성카드")), base, by)
            assertEquals(expected, groups.associate { it.label to it.total })
            groups.forEach { g -> assertEquals(g.label, g.total, txs.filter { g.filter.matches(it, null, mapOf(2L to 1L)) }.sumOf { it.amount }) }
        }
        check(GroupBy.CATEGORY, mapOf("식비" to 11000L))
        check(GroupBy.SUBCATEGORY, mapOf("식비 › 점심" to 8000L, "식비" to 3000L))
        check(GroupBy.PAY, mapOf("삼성카드" to 8000L, "결제수단 없음" to 3000L))
        check(GroupBy.MERCHANT, mapOf("김밥천국" to 8000L, "편의점" to 3000L))
        check(GroupBy.WEEKDAY, mapOf("월요일" to 8000L, "화요일" to 3000L))
        check(GroupBy.HOUR, mapOf("점심 11~14시" to 8000L, "밤 22~24시" to 3000L))
    }

    @Test fun dragCategoriesAcrossLevels() {
        fun c(id: Long, parent: Long? = null, sort: Int) = Category(id = id, type = TxType.EXPENSE, name = "$id", icon = "", color = id, parentId = parent, sort = sort)
        // A(1) [a1(11), a2(12)], B(2), C(3)
        val rows = listOf(c(1, sort = 0), c(11, 1, 0), c(12, 1, 1), c(2, sort = 1), c(3, sort = 2))
        fun moved(id: Long, gap: Int, parent: Long?) = moveCategory(rows, id, gap, parent).associate { it.id to (it.parentId to it.sort) }
        assertEquals(mapOf(3L to (null to 0), 1L to (null to 1), 2L to (null to 2)), moved(3, 0, null)) // C to the top
        assertEquals(mapOf(2L to (1L to 1), 12L to (1L to 2)), moved(2, 2, 1)) // B becomes A's second child; C keeps sort 2, still after A
        assertEquals(1L, moveCategory(rows, 2, 2, 1).first { it.id == 2L }.color) // and takes A's color
        assertEquals(mapOf(11L to (null to 1), 2L to (null to 2), 3L to (null to 3)), moved(11, 3, null)) // a1 out, between A and B
    }
}
