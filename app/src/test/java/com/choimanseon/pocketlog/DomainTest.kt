package com.choimanseon.pocketlog

import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.Period
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
import java.time.LocalDate
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
}
