package com.choimanseon.pocketlog.domain

import com.choimanseon.pocketlog.data.Budget
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    /** The month this period mostly covers: 9/25 ~ 10/24 is October's. */
    fun month(): YearMonth = YearMonth.from(start.plusDays(14))

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

/** The month period that mostly covers [month] (the inverse of [Period.month]). */
fun monthPeriod(month: YearMonth, startDay: Int) = monthPeriod(month.atDay(15), startDay)

fun weekPeriod(date: LocalDate, weekStart: DayOfWeek): Period {
    val start = date.with(TemporalAdjusters.previousOrSame(weekStart))
    return Period(start, start.plusWeeks(1))
}

enum class PeriodUnit(val label: String) { WEEK("주"), MONTH("월"), QUARTER("분기"), YEAR("년"), ALL("전체"), CUSTOM("기간 지정") }

/** The [unit] period containing [date]. Quarters and years are made of month periods, so they start on [startDay] too. */
fun periodOf(unit: PeriodUnit, date: LocalDate, startDay: Int, weekStart: DayOfWeek): Period {
    val month = monthPeriod(date, startDay).month()
    fun from(first: YearMonth, months: Long) = Period(monthPeriod(first, startDay).start, monthPeriod(first.plusMonths(months), startDay).start)
    return when (unit) {
        PeriodUnit.WEEK -> weekPeriod(date, weekStart)
        PeriodUnit.QUARTER -> from(month.withMonth((month.monthValue - 1) / 3 * 3 + 1), 3)
        PeriodUnit.YEAR -> from(month.withMonth(1), 12)
        else -> monthPeriod(date, startDay)
    }
}

fun Period.shift(unit: PeriodUnit, n: Long): Period = when (unit) {
    PeriodUnit.WEEK -> Period(start.plusWeeks(n), end.plusWeeks(n))
    PeriodUnit.MONTH -> shiftMonths(n)
    PeriodUnit.QUARTER -> shiftMonths(3 * n)
    PeriodUnit.YEAR -> shiftMonths(12 * n)
    PeriodUnit.CUSTOM -> Period(start.plusDays(days * n), end.plusDays(days * n))
    PeriodUnit.ALL -> this
}

fun Period.label(unit: PeriodUnit, today: LocalDate = LocalDate.now()): String {
    val last = end.minusDays(1)
    return when (unit) {
        PeriodUnit.MONTH -> label(today)
        PeriodUnit.QUARTER -> month().let { "${it.year}년 ${(it.monthValue - 1) / 3 + 1}분기" }
        PeriodUnit.YEAR -> "${month().year}년"
        PeriodUnit.ALL -> "전체 기간"
        else -> {
            fun d(x: LocalDate) = (if (start.year == last.year && start.year == today.year) "" else "${x.year}.") + "${x.monthValue}.${x.dayOfMonth}"
            if (days == 1L) d(start) else "${d(start)} ~ ${d(last)}"
        }
    }
}

/** Bars of the trend chart: the [unit] periods leading up to and including [p], or [p] cut into pieces. */
fun trendBuckets(unit: PeriodUnit, p: Period, startDay: Int, weekStart: DayOfWeek): List<Pair<Period, String>> {
    fun back(n: Int, label: (Period) -> String) = (n - 1 downTo 0).map { p.shift(unit, -it.toLong()) }.map { it to label(it) }
    fun split(step: PeriodUnit, label: (Period) -> String): List<Pair<Period, String>> =
        generateSequence(periodOf(step, p.start, startDay, weekStart)) { it.shift(step, 1) }.takeWhile { it.start < p.end }.map { it to label(it) }.toList()
    return when (unit) {
        PeriodUnit.WEEK -> back(12) { "${it.start.monthValue}/${it.start.dayOfMonth}" }
        PeriodUnit.MONTH -> back(12) { "${it.month().monthValue}월" }
        PeriodUnit.QUARTER -> back(8) { it.month().let { m -> "${m.year % 100}' ${(m.monthValue - 1) / 3 + 1}Q" } }
        PeriodUnit.YEAR -> back(6) { "${it.month().year}" }
        PeriodUnit.ALL -> split(PeriodUnit.YEAR) { "${it.month().year}" }
        PeriodUnit.CUSTOM -> when {
            p.days <= 31 -> (0 until p.days).map { d -> p.start.plusDays(d).let { Period(it, it.plusDays(1)) to "${it.dayOfMonth}" } }
            p.days <= 120 -> split(PeriodUnit.WEEK) { "${it.start.monthValue}/${it.start.dayOfMonth}" }
            else -> split(PeriodUnit.MONTH) { "${it.month().monthValue}월" }
        }
    }
}

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

// ---------------------------------------------------------------- money

fun won(v: Long) = "%,d원".format(Locale.KOREA, v)
fun num(v: Long) = "%,d".format(Locale.KOREA, v)

/** "+3,200,000" for income, "-8,000" for expense; transfers and savings have no sign (a savings withdrawal does). */
fun signedAmount(tx: Tx) = when (tx.type) {
    TxType.INCOME -> "+" + num(tx.amount)
    TxType.EXPENSE -> if (tx.amount < 0) "+" + num(-tx.amount) else "-" + num(tx.amount)
    TxType.TRANSFER, TxType.SAVING -> num(tx.amount)
}

val TxType.label get() = when (this) {
    TxType.EXPENSE -> "지출"
    TxType.INCOME -> "수입"
    TxType.TRANSFER -> "이체"
    TxType.SAVING -> "저축"
}

/** The categories a transaction of [type] can have: top rows, not the 공통 태그 group. */
fun List<Category>.tops(type: TxType) = filter { it.type == type && it.parentId == null && !it.tagGroup }

/** Tags offered with [categoryId]: its own, then the shared ones of its type. */
fun List<Category>.tagsFor(categoryId: Long?, type: TxType): List<Category> {
    val group = firstOrNull { it.type == type && it.tagGroup }?.id
    return filter { !it.hidden && it.parentId != null && (it.parentId == categoryId || it.parentId == group) }.sortedBy { it.parentId != categoryId }
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

/** What a budget has used in its own period around a day: this week, this month or this year (TODO #35). */
data class BudgetUse(val budget: Budget, val period: Period, val spent: Long) {
    val left get() = budget.amount - spent
    val percent get() = if (budget.amount > 0) (spent * 100 / budget.amount).toInt() else 0
    /** 이번 주 · 이번 달 · 올해 */
    val label get() = when (budget.period) { PeriodUnit.WEEK -> "이번 주"; PeriodUnit.YEAR -> "올해"; else -> "이번 달" }
    /** What can still be spent each day until the period ends, today included. */
    fun perDay(today: LocalDate) = left / ChronoUnit.DAYS.between(today, period.end).coerceAtLeast(1)
}

/** 주 · 월 · 연 통일 (TODO #37): 1년 = 12달 = 52주, rounded to 100원. */
fun convertBudget(amount: Long, from: PeriodUnit, to: PeriodUnit): Long {
    if (from == to) return amount
    fun perYear(u: PeriodUnit) = when (u) { PeriodUnit.WEEK -> 52.0; PeriodUnit.YEAR -> 1.0; else -> 12.0 }
    return Math.round(amount * perYear(from) / perYear(to) / 100) * 100
}

/**
 * The budgets that make [budgets] whole again when 통일 is on: per category, the one in [preferred] (else month, week, year)
 * sets the other two periods. Only changed or new rows come back.
 */
fun linkedBudgets(budgets: List<Budget>, preferred: PeriodUnit): List<Budget> = budgets.groupBy { it.categoryId }.flatMap { (_, own) ->
    val units = listOf(PeriodUnit.WEEK, PeriodUnit.MONTH, PeriodUnit.YEAR)
    val base = (listOf(preferred) + listOf(PeriodUnit.MONTH, PeriodUnit.WEEK, PeriodUnit.YEAR)).firstNotNullOf { u -> own.firstOrNull { it.period == u } }
    units.filter { it != base.period }.mapNotNull { u ->
        val amount = convertBudget(base.amount, base.period, u)
        val have = own.firstOrNull { it.period == u }
        when {
            have == null -> Budget(categoryId = base.categoryId, amount = amount, period = u)
            have.amount != amount -> have.copy(amount = amount)
            else -> null
        }
    }
}

/** The days to load for [budgetUses]: from the earliest to the latest day any of [budgets] covers around [date]. */
fun budgetSpan(budgets: List<Budget>, date: LocalDate, startDay: Int, weekStart: DayOfWeek): Period {
    val periods = budgets.map { periodOf(it.period, date, startDay, weekStart) }.ifEmpty { listOf(monthPeriod(date, startDay)) }
    return Period(periods.minOf { it.start }, periods.maxOf { it.end })
}

/** Week budgets first, then month, then year. [txs] and [splits] must cover [budgetSpan]. */
fun budgetUses(budgets: List<Budget>, txs: List<Tx>, splits: List<TxSplit>, categories: List<Category>, date: LocalDate, startDay: Int, weekStart: DayOfWeek) =
    budgets.sortedWith(compareBy({ it.period.ordinal }, { it.categoryId != null })).map { b ->
        val p = periodOf(b.period, date, startDay, weekStart)
        val inside = txs.filter { it.occurredAt.toLocalDate() in p }
        val ids = inside.mapTo(HashSet()) { it.id }
        val spent = if (b.categoryId == null) total(inside, TxType.EXPENSE)
        else byTopCategory(inside, splits.filter { it.txId in ids }, categories, TxType.EXPENSE).firstOrNull { it.category?.id == b.categoryId }?.total ?: 0
        BudgetUse(b, p, spent)
    }

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

// ---------------------------------------------------------------- slices of a period (분석 · 내역 목록)

/** Which transactions a list or a chart slice stands for. */
data class TxFilter(
    val start: Long,
    val end: Long,
    val type: TxType? = null,
    val category: Long? = null,
    val uncategorized: Boolean = false,
    val tag: Long? = null,
    val untagged: Boolean = false,
    val pay: Long? = null,
    val noPay: Boolean = false,
    val merchant: String? = null,
    val weekday: Int? = null, // DayOfWeek value
    val hours: IntRange? = null,
    val brand: String? = null, // every branch: Categorizer.brandOf
)

fun TxFilter.matches(tx: Tx, splits: List<TxSplit>?, tags: List<Long>?): Boolean {
    if (!tx.countable() || tx.occurredAt < start || tx.occurredAt >= end || (type != null && tx.type != type)) return false
    if (category != null && tx.categoryId != category && splits.orEmpty().none { it.categoryId == category }) return false
    if (uncategorized && (tx.categoryId != null || splits.orEmpty().any { it.categoryId != null })) return false
    if (tag != null && tag !in tags.orEmpty()) return false
    if (untagged && !tags.isNullOrEmpty()) return false
    if (pay != null && tx.paymentMethodId != pay) return false
    if (noPay && tx.paymentMethodId != null) return false
    if (merchant != null && tx.merchant.trim() != merchant) return false
    if (brand != null && com.choimanseon.pocketlog.auto.Categorizer.brandOf(tx.merchant) != brand) return false
    val at = Instant.ofEpochMilli(tx.occurredAt).atZone(ZoneId.systemDefault())
    if (weekday != null && at.dayOfWeek.value != weekday) return false
    return hours == null || at.hour in hours
}

/** 지출 패턴 (TODO #35). [heat]: rows are the days of the week from the week's first day, columns 3-hour blocks from midnight. */
class Pattern(val heat: List<List<Long>>, val weekdayPerDay: Long, val weekendPerDay: Long, val places: List<Place>)

/** One brand: every branch together ("스타벅스 김포점", "스타벅스 광진점"); [name] is the branch seen most. */
data class Place(val brand: String, val name: String, val branches: Int, val visits: Int, val total: Long)

/**
 * Repeat records (always 9:00) and screenshots without a time (saved at 12:00) would make false hot spots, so the heat map
 * leaves them out. The daily averages count the days of [period] up to today.
 */
fun pattern(txs: List<Tx>, type: TxType, period: Period, today: LocalDate, weekStart: DayOfWeek): Pattern {
    val zone = ZoneId.systemDefault()
    val mine = txs.filter { it.countable() && it.type == type }
    val heat = List(7) { MutableList(8) { 0L } }
    mine.forEach { tx ->
        val at = Instant.ofEpochMilli(tx.occurredAt).atZone(zone)
        val noTime = tx.source == TxSource.SCREENSHOT && at.hour == 12 && at.minute == 0
        if (tx.source == TxSource.REPEAT || noTime) return@forEach
        heat[(at.dayOfWeek.value - weekStart.value + 7) % 7][at.hour / 3] += tx.amount
    }
    val days = generateSequence(period.start) { it.plusDays(1) }.takeWhile { it < period.end && !it.isAfter(today) }.toList()
    fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
    fun perDay(weekendDays: Boolean): Long {
        val n = days.count { weekend(it) == weekendDays }
        return if (n == 0) 0 else mine.filter { weekend(it.occurredAt.toLocalDate()) == weekendDays }.sumOf { it.amount } / n
    }
    val places = mine.filter { it.merchant.isNotBlank() }.groupBy { com.choimanseon.pocketlog.auto.Categorizer.brandOf(it.merchant) }.map { (brand, list) ->
        val names = list.groupingBy { it.merchant.trim() }.eachCount()
        Place(brand, names.maxBy { it.value }.key, names.size, list.size, list.sumOf { it.amount })
    }.sortedWith(compareByDescending<Place> { it.visits }.thenByDescending { it.total }).take(10)
    return Pattern(heat, perDay(false), perDay(true), places)
}

enum class GroupBy(val label: String) { CATEGORY("카테고리별"), TAG("태그별"), PAY("결제수단별"), WEEKDAY("요일별"), HOUR("시간대별"), MERCHANT("내역별") }

data class GroupSum(val label: String, val total: Long, val filter: TxFilter)

/** The item's tag ids; empty for a line that is not an item. */
fun TxSplit.tagIds(): List<Long> = tags?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

/**
 * What [tx] adds to each of its [tags] (null = 태그 없음). A tag on the whole record counts the whole amount.
 * In an order whose items keep their own tags (screenshot, DB 8) an item's tag counts that item, with the order's
 * 배송비·할인 shared by the items in proportion to their price: the items always add up to what was paid.
 */
fun tagShares(tx: Tx, parts: List<TxSplit>?, tags: List<Long>): Map<Long?, Long> {
    if (tags.isEmpty()) return mapOf(null to tx.amount)
    val items = parts.orEmpty().filter { it.tags != null }
    val itemSum = items.sumOf { it.amount }
    if (items.isEmpty() || itemSum <= 0) return buildMap { tags.forEach { put(it, tx.amount) } }
    // a tag removed from the record later is gone from its items too
    val itemTags = items.map { s -> s.tagIds().filter { it in tags } }
    val wholeTags = tags.filter { t -> itemTags.none { t in it } }
    var left = tx.amount
    val shares = items.mapIndexed { i, s ->
        if (i == items.lastIndex) left else Math.round(tx.amount.toDouble() * s.amount / itemSum).also { left -= it }
    }
    val sums = LinkedHashMap<Long?, Long>()
    wholeTags.forEach { sums[it] = tx.amount }
    items.indices.forEach { i ->
        itemTags[i].forEach { sums.merge(it, shares[i], Long::plus) }
        if (itemTags[i].isEmpty() && wholeTags.isEmpty()) sums.merge(null, shares[i], Long::plus)
    }
    return sums
}

private val hourBuckets = listOf("새벽" to 0..5, "아침" to 6..10, "점심" to 11..13, "오후" to 14..17, "저녁" to 18..21, "밤" to 22..23)

/**
 * Totals of [base]'s transactions grouped [by]. Categories count splits (one order can land in several);
 * tags count the whole transaction once per tag, so tag totals can add up to more than the period ([tags]: txId → tag ids).
 */
fun groupSums(
    txs: List<Tx>, splits: List<TxSplit>, tags: Map<Long, List<Long>>, categories: List<Category>, pays: List<PayMethod>, base: TxFilter, by: GroupBy,
): List<GroupSum> {
    val inBase = txs.filter { base.matches(it, null, null) }
    fun <K> sum(key: (Tx) -> K) = inBase.groupBy(key).mapValues { (_, v) -> v.sumOf { it.amount } }
    val byId = categories.associateBy { it.id }
    val groups = when (by) {
        GroupBy.CATEGORY -> byTopCategory(inBase, splits, categories, base.type ?: TxType.EXPENSE).map { s ->
            GroupSum(s.category?.name ?: "미분류", s.total, s.category?.let { base.copy(category = it.id) } ?: base.copy(uncategorized = true))
        }
        GroupBy.TAG -> {
            val sums = HashMap<Long?, Long>()
            val splitsByTx = splits.groupBy { it.txId }
            inBase.forEach { tx -> tagShares(tx, splitsByTx[tx.id], tags[tx.id].orEmpty()).forEach { (t, v) -> sums.merge(t, v, Long::plus) } }
            sums.map { (id, v) ->
                val t = id?.let { byId[it] } // the tag's name alone, no category in front (TODO #42)
                GroupSum(t?.name ?: "태그 없음", v, t?.let { base.copy(tag = it.id) } ?: base.copy(untagged = true))
            }
        }
        GroupBy.PAY -> {
            val names = pays.associate { it.id to it.name }
            sum { it.paymentMethodId }.map { (id, v) -> GroupSum(id?.let { names[it] } ?: "결제수단 없음", v, if (id == null) base.copy(noPay = true) else base.copy(pay = id)) }
        }
        GroupBy.MERCHANT -> sum { it.merchant.trim() }.map { (m, v) -> GroupSum(m.ifEmpty { "(내역 없음)" }, v, base.copy(merchant = m)) }
        GroupBy.WEEKDAY -> sum { it.occurredAt.toLocalDate().dayOfWeek }.toSortedMap().map { (d, v) ->
            GroupSum(d.getDisplayName(java.time.format.TextStyle.FULL, Locale.KOREAN), v, base.copy(weekday = d.value))
        }
        GroupBy.HOUR -> hourBuckets.mapNotNull { (name, range) ->
            val v = inBase.filter { Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).hour in range }.sumOf { it.amount }
            if (v == 0L) null else GroupSum("$name ${range.first}~${range.last + 1}시", v, base.copy(hours = range))
        }
    }.filter { it.total > 0 }
    return if (by == GroupBy.WEEKDAY || by == GroupBy.HOUR) groups else groups.sortedByDescending { it.total }
}

// ---------------------------------------------------------------- category tree (drag and drop)

/**
 * [rows] is the screen order: every top category followed by its subcategories.
 * Puts [dragged] (a top keeps its subcategories) before rows[[gap]] (rows.size = at the end) under [parent] (null = top level)
 * and returns the categories whose parent, sort or color changed.
 */
fun moveCategory(rows: List<Category>, dragged: Long, gap: Int, parent: Long?): List<Category> {
    val moving = rows.first { it.id == dragged }
    val siblings = rows.filter { it.parentId == parent && it.id != dragged }
    val at = rows.take(gap).count { it.parentId == parent && it.id != dragged }
    val color = parent?.let { p -> rows.first { it.id == p }.color } ?: moving.color
    val reordered = siblings.toMutableList().apply { add(at, moving.copy(parentId = parent, color = color)) }
    val before = rows.associateBy { it.id }
    return reordered.mapIndexed { i, c -> c.copy(sort = i) }.filter { it != before[it.id] }
}

/** Spend per day of the period, for the calendar and "same point last month". */
fun dailyExpense(txs: List<Tx>): Map<LocalDate, Long> =
    txs.filter { it.countable() && it.type == TxType.EXPENSE }
        .groupBy { it.occurredAt.toLocalDate() }
        .mapValues { (_, v) -> v.sumOf { it.amount } }

// ---------------------------------------------------------------- search

private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

/** "스타벅스" → "ㅅㅌㅂㅅ"; other letters stay. */
fun initials(s: String) = s.map { c -> if (c in '가'..'힣') CHO[(c - '가') / 588] else c }.joinToString("")

/**
 * How [text] matches a search [query], spaces and case ignored (fuzzy search): 0 = it contains the query; 1 = its 초성 do
 * ("ㅅㅌㅂ" → 스타벅스); 2 = the query's letters in order, close together ("스벅", "ㅅㅂ" → 스타벅스); 3 = one letter off,
 * for queries of 3 letters or more ("스타박스" → 스타벅스). null = no match.
 */
fun matchScore(text: String, query: String): Int? {
    val t = text.lowercase().filterNot(Char::isWhitespace)
    val q = query.lowercase().filterNot(Char::isWhitespace)
    if (q.isEmpty() || q in t) return 0
    if (q.all { it in 'ㄱ'..'ㅎ' }) return initials(t).let { if (q in it) 1 else if (inOrder(it, q)) 2 else null }
    return when {
        inOrder(t, q) -> 2
        q.length >= 3 && (q.length - 1..q.length + 1).any { n -> t.windowed(n).any { oneEdit(it, q) } } -> 3
        else -> null
    }
}

/** The best [matchScore] of a record's 가맹점, 품명, 메모 and [items] (the names of its 품목). */
fun Tx.searchScore(query: String, items: List<String>): Int? =
    (listOf(merchant, memo, note) + items).mapNotNull { matchScore(it, query) }.minOrNull()

/** [q]'s letters appear in [t] in order within a stretch twice [q]'s length, so far-apart letters of a long text don't count. */
private fun inOrder(t: String, q: String) = t.indices.any { start ->
    var j = 0
    var i = start
    while (j < q.length && i < t.length && i - start < q.length * 2) { if (t[i] == q[j]) j++; i++ }
    j == q.length
}

/** [a] becomes [b] by changing, adding or dropping at most one letter. */
private fun oneEdit(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) { i++; j++; continue }
        if (++edits > 1) return false
        if (a.length >= b.length) i++
        if (a.length <= b.length) j++
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}
