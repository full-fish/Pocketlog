package com.choimanseon.pocketlog.ai

import com.choimanseon.pocketlog.Notify
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Report
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.GroupBy
import com.choimanseon.pocketlog.domain.Period
import com.choimanseon.pocketlog.domain.PeriodUnit
import com.choimanseon.pocketlog.domain.TxFilter
import com.choimanseon.pocketlog.domain.budgetUses
import com.choimanseon.pocketlog.domain.byTopCategory
import com.choimanseon.pocketlog.domain.countable
import com.choimanseon.pocketlog.domain.groupSums
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.pattern
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.total
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * AI 월간 리포트 (TODO #35): on the first day of a month period, from 9:00, the period that just ended.
 * The app adds everything up; the AI only writes about those numbers, so no total in the report is the AI's arithmetic.
 * Only sums leave the phone: categories, tags, places, budgets. No raw messages, names or card numbers.
 */
object MonthlyReport {
    const val MODEL = "gpt-6-astra" // once a month, so always the strongest model (사용자 결정 2026-10-05)

    suspend fun makeIfDue(now: LocalDateTime = LocalDateTime.now()) {
        if (!app.prefs.monthlyReport || !Ai.usable() || now.hour < 9) return
        val last = monthPeriod(now.toLocalDate(), app.prefs.monthStartDay).shiftMonths(-1)
        if (app.dao.report(last.start.toString()) != null) return
        make(last)?.let(Notify::report)
    }

    /** Null when the period has no records (e.g. before the app was used). */
    suspend fun make(period: Period): Report? {
        val facts = facts(period) ?: return null
        val text = Ai.report(facts, MODEL)
        return Report(period.start.toString(), period.end.toString(), JSONObject().put("facts", facts).put("text", text).toString())
            .also { app.dao.upsert(it) }
    }

    suspend fun facts(period: Period): JSONObject? {
        val dao = app.dao
        val prev = period.shiftMonths(-1)
        val all = dao.txBetweenOnce(prev.startMillis, period.endMillis)
        val now = all.filter { it.occurredAt.toLocalDate() in period }
        val before = all.filter { it.occurredAt.toLocalDate() in prev }
        if (now.none { it.countable() }) return null
        val splits = dao.splitsBetweenOnce(prev.startMillis, period.endMillis)
        val tags = dao.tagsBetween(period.startMillis, period.endMillis).first().groupBy({ it.txId }, { it.tagId })
        val cats = dao.categoriesOnce()
        val weekStart = DayOfWeek.of(app.prefs.weekStart)
        val lastDay = period.end.minusDays(1)

        fun sums(t: List<com.choimanseon.pocketlog.data.Tx>): JSONObject {
            val e = total(t, TxType.EXPENSE); val i = total(t, TxType.INCOME); val s = total(t, TxType.SAVING)
            return JSONObject().put("spending", e).put("income", i).put("saving", s).put("left", i - e - s)
        }
        val nowIds = now.mapTo(HashSet()) { it.id }
        val beforeIds = before.mapTo(HashSet()) { it.id }
        val prevByCat = byTopCategory(before, splits.filter { it.txId in beforeIds }, cats, TxType.EXPENSE).associate { it.category?.name to it.total }
        val categories = byTopCategory(now, splits.filter { it.txId in nowIds }, cats, TxType.EXPENSE).take(12).map { c ->
            JSONObject().put("name", c.category?.name ?: "미분류").put("amount", c.total).put("last_month", prevByCat[c.category?.name] ?: 0)
        }
        val tagSums = groupSums(now, splits.filter { it.txId in nowIds }, tags, cats, emptyList(), TxFilter(period.startMillis, period.endMillis, TxType.EXPENSE), GroupBy.TAG)
            .filter { !it.filter.untagged }.take(10).map { JSONObject().put("tag", it.label).put("amount", it.total) }
        val pat = pattern(now, TxType.EXPENSE, period, lastDay, weekStart)
        val peak = pat.heat.withIndex().flatMap { (r, row) -> row.withIndex().map { (c, v) -> Triple(r, c, v) } }.maxBy { it.third }
        val days = (0 until 7).map { weekStart.plus(it.toLong()).getDisplayName(TextStyle.FULL, Locale.KOREAN) }
        val biggest = now.filter { it.countable() && it.type == TxType.EXPENSE }.sortedByDescending { it.amount }.take(5).map { t ->
            JSONObject().put("merchant", t.merchant).put("amount", t.amount).put("category", cats.firstOrNull { it.id == t.categoryId }?.name ?: "미분류")
                .put("date", t.occurredAt.toLocalDate().toString())
        }
        val budgets = budgetUses(dao.budgetsOnce().filter { it.period == PeriodUnit.MONTH }, now, splits.filter { it.txId in nowIds }, cats, period.start, app.prefs.monthStartDay, weekStart)
            .map { u -> JSONObject().put("category", u.budget.categoryId?.let { id -> cats.firstOrNull { it.id == id }?.name } ?: "전체").put("budget", u.budget.amount).put("spent", u.spent) }

        return JSONObject()
            .put("period", period.label())
            .put("days", period.days)
            .put("this_month", sums(now))
            .put("last_month", sums(before))
            .put("categories", JSONArray(categories))
            .put("tags", JSONArray(tagSums))
            .put("places", JSONArray(pat.places.map { JSONObject().put("name", it.name).put("visits", it.visits).put("amount", it.total) }))
            .put("daily_average", JSONObject().put("weekday", pat.weekdayPerDay).put("weekend", pat.weekendPerDay))
            .put("busiest_time", if (peak.third > 0) "${days[peak.first]} ${peak.second * 3}~${peak.second * 3 + 3}시 (${peak.third}원)" else JSONObject.NULL)
            .put("biggest", JSONArray(biggest))
            .put("budgets", JSONArray(budgets))
            // spending per day, for the report's chart (TODO #41)
            .put("daily", JSONArray((0 until period.days).map { d -> total(now.filter { it.occurredAt.toLocalDate() == period.start.plusDays(d) }, TxType.EXPENSE) }))
    }
}
