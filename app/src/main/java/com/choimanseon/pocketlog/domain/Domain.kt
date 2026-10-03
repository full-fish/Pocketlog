package com.choimanseon.pocketlog.domain

import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// ---------------------------------------------------------------- periods

/** [start, end) in local dates. Every total in the app is computed over one of these. */
data class Period(val start: LocalDate, val end: LocalDate) {
    val startMillis get() = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endMillis get() = end.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val days get() = ChronoUnit.DAYS.between(start, end)
    operator fun contains(date: LocalDate) = !date.isBefore(start) && date.isBefore(end)

    /** Month-based periods keep their start day (start day is capped at 28, so this is always valid). */
    fun shiftMonths(n: Long) = Period(start.plusMonths(n), end.plusMonths(n))

    fun label(today: LocalDate = LocalDate.now()): String {
        val last = end.minusDays(1)
        val year = if (start.year != today.year) "${start.year}년 " else ""
        return if (start.dayOfMonth == 1 && end == start.plusMonths(1)) "$year${start.monthValue}월"
        else "$year${start.monthValue}.${start.dayOfMonth} ~ ${last.monthValue}.${last.dayOfMonth}"
    }
}

/** The month period containing [date], starting on [startDay] (e.g. payday 25 → 9/25 ~ 10/24). */
fun monthPeriod(date: LocalDate, startDay: Int): Period {
    val d = startDay.coerceIn(1, 28)
    val start = if (date.dayOfMonth >= d) date.withDayOfMonth(d) else date.minusMonths(1).withDayOfMonth(d)
    return Period(start, start.plusMonths(1))
}

fun weekPeriod(date: LocalDate, weekStart: DayOfWeek): Period {
    val start = date.with(TemporalAdjusters.previousOrSame(weekStart))
    return Period(start, start.plusWeeks(1))
}

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

// ---------------------------------------------------------------- money

fun won(v: Long) = "%,d원".format(Locale.KOREA, v)
fun num(v: Long) = "%,d".format(Locale.KOREA, v)

/** "+3,200,000" for income, "-8,000" for expense; transfers have no sign. */
fun signedAmount(tx: Tx) = when (tx.type) {
    TxType.INCOME -> "+" + num(tx.amount)
    TxType.EXPENSE -> if (tx.amount < 0) "+" + num(-tx.amount) else "-" + num(tx.amount)
    TxType.TRANSFER -> num(tx.amount)
}

/** Compact "12.3만" for chart axes. */
fun shortWon(v: Long): String = when {
    v >= 100_000_000 -> "%.1f억".format(Locale.KOREA, v / 100_000_000.0).replace(".0억", "억")
    v >= 10_000 -> "%.1f만".format(Locale.KOREA, v / 10_000.0).replace(".0만", "만")
    else -> num(v)
}

/** Korean particle by final consonant: "식비".josa("이에요", "예요") → "식비예요", "편의점" → "편의점이에요". ㄹ takes "로". */
fun String.josa(withBatchim: String, without: String): String {
    val c = lastOrNull { !it.isWhitespace() } ?: return this + without
    if (c !in '가'..'힣') return this + without
    val jong = (c - '가') % 28
    val batchim = if (withBatchim == "으로") jong != 0 && jong != 8 else jong != 0
    return this + if (batchim) withBatchim else without
}

// ---------------------------------------------------------------- calculator keypad

/** Evaluates "12000+3000×2" with × ÷ before + −. Trailing operators are ignored. null = empty. */
fun evalExpr(expr: String): Long? {
    val nums = mutableListOf<Long>()
    val ops = mutableListOf<Char>()
    var cur = ""
    for (c in expr) {
        if (c.isDigit()) cur += c
        else if (c in "+−×÷" && cur.isNotEmpty()) { nums += cur.toLong(); ops += c; cur = "" }
    }
    if (cur.isNotEmpty()) nums += cur.toLong() else if (ops.isNotEmpty()) ops.removeAt(ops.lastIndex)
    if (nums.isEmpty()) return null
    // first pass: × ÷
    val n2 = mutableListOf(nums[0])
    val o2 = mutableListOf<Char>()
    for (i in ops.indices) {
        val b = nums[i + 1]
        when (ops[i]) {
            '×' -> n2[n2.lastIndex] = n2.last() * b
            '÷' -> n2[n2.lastIndex] = if (b == 0L) 0 else Math.round(n2.last().toDouble() / b)
            else -> { n2 += b; o2 += ops[i] }
        }
    }
    var r = n2[0]
    for (i in o2.indices) r = if (o2[i] == '+') r + n2[i + 1] else r - n2[i + 1]
    return r
}

// ---------------------------------------------------------------- installments

/** The memo the app writes on installment months; a user's own memo replaces it. */
val autoInstallmentMemo = Regex("""할부 \d+/\d+회차""")

/**
 * An installment purchase is kept as one row per month, like the card bill and 똑똑가계부 (사용자 결정 2026-10-03):
 * month 1 on the purchase day carries the remainder, months 2..n follow on the same day of each month.
 * Months 2..n get id 0; the caller sets installmentOf to month 1's id.
 */
fun installmentRows(purchase: Tx): List<Tx> {
    val n = purchase.installmentMonths
    if (n < 2 || purchase.type != TxType.EXPENSE || purchase.amount <= 0) return listOf(purchase)
    val part = purchase.amount / n
    val at = Instant.ofEpochMilli(purchase.occurredAt).atZone(ZoneId.systemDefault())
    return (1..n).map { k ->
        purchase.copy(
            id = if (k == 1) purchase.id else 0,
            amount = if (k == 1) purchase.amount - part * (n - 1) else part,
            occurredAt = at.plusMonths(k - 1L).toInstant().toEpochMilli(),
            memo = purchase.memo.takeUnless { it.isBlank() || autoInstallmentMemo.matches(it) } ?: "할부 $k/${n}회차",
        )
    }
}

/** "복제": the same spending again right now, as a one-off entry. */
fun Tx.copyNow(now: Long = System.currentTimeMillis()) = copy(
    id = 0, occurredAt = now, createdAt = now, updatedAt = now, source = TxSource.MANUAL, rawMessageId = null, scanJobId = null,
    status = TxStatus.CONFIRMED, installmentMonths = 0, installmentOf = null, memo = memo.takeUnless { autoInstallmentMemo.matches(it) }.orEmpty(),
)

// ---------------------------------------------------------------- stats

fun Tx.countable() = deletedAt == null && status != TxStatus.CANCELED && !excludeFromStats

fun total(txs: List<Tx>, type: TxType) = txs.filter { it.countable() && it.type == type }.sumOf { it.amount }

data class CategorySum(val category: Category?, val total: Long)

/**
 * Totals per top-level category. A Tx with splits is counted by its splits
 * (a Coupang order with groceries and a phone case lands in two categories).
 */
fun byTopCategory(txs: List<Tx>, splits: List<TxSplit>, categories: List<Category>, type: TxType): List<CategorySum> {
    val byId = categories.associateBy { it.id }
    fun top(id: Long?): Category? = byId[id]?.let { c -> c.parentId?.let { byId[it] } ?: c }
    val splitsByTx = splits.groupBy { it.txId }
    val sums = HashMap<Long?, Long>()
    for (tx in txs) {
        if (!tx.countable() || tx.type != type) continue
        val parts = splitsByTx[tx.id]
        if (parts.isNullOrEmpty()) sums.merge(top(tx.categoryId)?.id, tx.amount, Long::plus)
        else parts.forEach { sums.merge(top(it.categoryId ?: tx.categoryId)?.id, it.amount, Long::plus) }
    }
    return sums.map { (id, v) -> CategorySum(id?.let { byId[it] }, v) }
        .filter { it.total != 0L }
        .sortedByDescending { it.total }
}

/** Spend per day of the period, for the calendar and "same point last month". */
fun dailyExpense(txs: List<Tx>): Map<LocalDate, Long> =
    txs.filter { it.countable() && it.type == TxType.EXPENSE }
        .groupBy { it.occurredAt.toLocalDate() }
        .mapValues { (_, v) -> v.sumOf { it.amount } }
