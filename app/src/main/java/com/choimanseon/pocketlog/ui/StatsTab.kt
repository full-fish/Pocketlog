package com.choimanseon.pocketlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.GroupBy
import com.choimanseon.pocketlog.domain.Period
import com.choimanseon.pocketlog.domain.PeriodUnit
import com.choimanseon.pocketlog.domain.TxFilter
import com.choimanseon.pocketlog.domain.groupSums
import com.choimanseon.pocketlog.domain.label
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.periodOf
import com.choimanseon.pocketlog.domain.shift
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.tops
import com.choimanseon.pocketlog.domain.total
import com.choimanseon.pocketlog.domain.pattern
import com.choimanseon.pocketlog.domain.trendBuckets
import com.choimanseon.pocketlog.domain.won
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

val chartColors = listOf(
    Color(0xFF4C5BF5), Color(0xFF22B8CF), Color(0xFFFF9F1C), Color(0xFFF06595),
    Color(0xFF12B886), Color(0xFF845EF7), Color(0xFFFAB005),
)

/**
 * 분석 (TODO #17, #19): 지출 · 수입 · 합산 for a week, month, quarter, year, all time or chosen dates.
 * A chart on top, its numbers below; every slice and row opens the transactions behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsTab(nav: Nav) {
    val settings by app.prefs.version.collectAsState()
    val p = app.prefs
    val today = LocalDate.now()
    val dao = app.dao
    var unit by rememberSaveable { mutableStateOf(PeriodUnit.MONTH) }
    var anchor by rememberSaveable { mutableStateOf(today.toString()) } // a day inside the period shown
    var custom by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) } // 지출 · 수입 · 저축 · 합산
    var trend by rememberSaveable { mutableStateOf(false) } // 분류 차트 or 기간 차트
    var pattern by rememberSaveable { mutableStateOf(false) } // 패턴: when and where (TODO #35)
    var groupBy by rememberSaveable { mutableStateOf(GroupBy.CATEGORY) }
    var cumulative by rememberSaveable { mutableStateOf(false) }
    var narrow by rememberSaveable { mutableStateOf<Long?>(null) } // the 기간 차트 for one category or tag, e.g. #야식
    var dialog by remember { mutableStateOf<String?>(null) }

    val first by rememberFlow<Long?>(null) { dao.firstTxAt() }
    val weekStart = DayOfWeek.of(p.weekStart)
    val period = remember(settings, unit, anchor, custom, first) {
        when (unit) {
            PeriodUnit.ALL -> Period(first?.toLocalDate() ?: today, today.plusDays(1))
            PeriodUnit.CUSTOM -> custom?.let { (a, b) -> Period(LocalDate.parse(a), LocalDate.parse(b)) } ?: monthPeriod(today, p.monthStartDay)
            else -> periodOf(unit, LocalDate.parse(anchor), p.monthStartDay, weekStart)
        }
    }
    val buckets = remember(period, unit, settings) { trendBuckets(unit, period, p.monthStartDay, weekStart) }
    val prev = if (unit == PeriodUnit.ALL) null else period.shift(unit, -1)
    val from = listOfNotNull(buckets.firstOrNull()?.first?.start, prev?.start, period.start).min()
    val txs by rememberFlow(emptyList(), from, period) { dao.txBetween(Period(from, period.end).startMillis, period.endMillis) }
    val splits by rememberFlow(emptyList(), period) { dao.splitsBetween(period.startMillis, period.endMillis) }
    val tagRows by rememberFlow(emptyList(), from, period) { dao.tagsBetween(Period(from, period.end).startMillis, period.endMillis) }
    val tags = remember(tagRows) { tagRows.groupBy({ it.txId }, { it.tagId }) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val budgets by rememberFlow(emptyList()) { dao.budgets() }

    val type = tabTypes.getOrElse(tab) { TxType.EXPENSE }
    fun inside(q: Period) = txs.filter { it.occurredAt.toLocalDate() in q }
    val inPeriod = inside(period)
    fun filter(q: Period, t: TxType? = type) = TxFilter(q.startMillis, q.endMillis, t)
    fun open(title: String, f: TxFilter) = nav.push(Screen.TxList(title, f))
    val label = period.label(unit, today)
    val good = pal.income
    val bad = pal.danger
    val brand = pal.brand
    val faint = pal.faint
    val narrowTo = narrow?.let { id -> cats.firstOrNull { it.id == id && it.type == type } }
    val narrowTop = narrowTo?.let { t -> if (t.parentId == null) t else cats.firstOrNull { it.id == t.parentId } } // 식비 for #야식
    val movable = unit != PeriodUnit.ALL
    fun go(n: Long) { if (unit == PeriodUnit.CUSTOM) custom = period.shift(unit, n).let { it.start.toString() to it.end.toString() } else anchor = period.shift(unit, n).start.toString() }
    val drag: ((Int) -> Unit)? = if (movable) ({ go(it.toLong()) }) else null

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PeriodSwitcher(
                    label, if (movable) ({ go(-1) }) else null, if (movable) ({ go(1) }) else null, Modifier.weight(1f),
                    onLabel = when (unit) { PeriodUnit.ALL -> null; PeriodUnit.CUSTOM -> ({ dialog = "range" }); else -> ({ dialog = "month" }) },
                )
                Chip("${unit.label} ▾", selected = true) { dialog = "unit" }
                Chip("리포트", modifier = Modifier.padding(start = 6.dp)) { nav.push(Screen.Reports()) }
            }
            PillTabs(tabTypes.map { it.label } + "합산", tab, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { tab = it }
        }
        item { Summary(tab, inPeriod, prev?.let { inside(it) }, period, today, unit) }

        if (tab == NET) {
            // 남은 돈 = 수입 − 지출 − 저축 (TODO #28): saved money is not spent, but it is not left over either
            val rows = buckets.map { (q, l) -> Triple(q, l, inside(q).let { t -> tabTypes.map { total(t, it) } }) }
            val net = rows.map { (_, _, s) -> s[1] - s[0] - s[2] }
            val shown = if (cumulative) net.runningReduce(Long::plus) else net
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (cumulative) "누적 합산" else "기간별 합산", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Chip("누적 보기", cumulative) { cumulative = !cumulative }
                    }
                    Spacer(Modifier.height(12.dp))
                    TrendChart(shown, rows.map { it.second }, rows.lastIndex, line = cumulative, onStep = drag) { if (it >= 0) good else bad }
                }
            }
            item { TableHeader("기간", "수입", "지출", "저축", "남은 돈") }
            rows.zip(net).reversed().forEach { (r, n) ->
                item {
                    TableRow(bucketLabel(unit, r.first, today), listOf(r.third[1], r.third[0], r.third[2], n).map(::num), if (n >= 0) pal.income else pal.danger) {
                        open(r.first.label(PeriodUnit.CUSTOM, today), filter(r.first, null))
                    }
                }
            }
        } else if (pattern) {
            val pat = pattern(inPeriod, type, period, today, weekStart)
            val max = pat.heat.flatten().maxOrNull()?.coerceAtLeast(1) ?: 1
            val days = (0 until 7).map { weekStart.plus(it.toLong()) }
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ChartSwitch(trend, pattern, groupBy, { trend = it == 1; pattern = it == 2 }) { dialog = "group" }
                    Text("요일 × 시간대", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.padding(top = 8.dp, start = 24.dp)) {
                        (0 until 8).forEach { Text("${it * 3}", style = MaterialTheme.typography.labelSmall, color = pal.faint, modifier = Modifier.weight(1f)) }
                    }
                    days.forEachIndexed { r, d ->
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN), style = MaterialTheme.typography.labelSmall, color = pal.sub, modifier = Modifier.width(24.dp))
                            (0 until 8).forEach { c ->
                                val v = pat.heat[r][c]
                                Box(
                                    Modifier.weight(1f).height(22.dp).padding(horizontal = 1.5.dp).clip(RoundedCornerShape(4.dp))
                                        .background(if (v == 0L) pal.surface2 else brand.copy(alpha = 0.15f + 0.85f * v / max))
                                        .clickable(enabled = v > 0) { open("$label · ${d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN)} ${c * 3}~${c * 3 + 3}시", filter(period).copy(weekday = d.value, hours = c * 3..c * 3 + 2)) },
                                )
                            }
                        }
                    }
                    Text("진할수록 많이 쓴 때예요. 반복 기록과 시간을 모르는 스샷 내역은 빼고 그려요.", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 10.dp))
                }
            }
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("하루 평균", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.padding(top = 10.dp)) {
                        MiniStat("평일", num(pat.weekdayPerDay), pal.text, Modifier.weight(1f))
                        MiniStat("주말", num(pat.weekendPerDay), pal.text, Modifier.weight(1f))
                    }
                }
            }
            item { SectionHeader("자주 가는 곳") }
            if (pat.places.isEmpty()) item { EmptyState(Icons.Rounded.BarChart, "이 기간에는 내역이 없어요") }
            pat.places.forEachIndexed { i, pl ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { open("$label · ${pl.name}", filter(period).copy(brand = pl.brand)) }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = pal.faint, modifier = Modifier.width(24.dp))
                        Text(pl.name + if (pl.branches > 1) " 외 ${pl.branches - 1}곳" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${pl.visits}번", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(end = 12.dp))
                        Text(num(pl.total), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        } else if (!trend && groupBy == GroupBy.TAG) {
            // tags overlap (one meal can be #외식 and #야식), so each tag is its own share of the whole, not a slice of a pie
            val groups = groupSums(inPeriod, splits, tags, cats, pays, filter(period), groupBy).sortedBy { it.filter.untagged }
            val whole = total(inPeriod, type)
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ChartSwitch(trend, pattern, groupBy, { trend = it == 1; pattern = it == 2 }) { dialog = "group" }
                    if (groups.isEmpty()) EmptyState(Icons.Rounded.BarChart, "이 기간에는 내역이 없어요")
                    else Text(
                        "한 내역에 태그가 여러 개면 태그마다 따로 더해요. 비율은 이 기간 ${type.label} ${num(whole)}원 중 그 태그가 붙은 금액이라, 모두 더하면 100%를 넘을 수 있어요.",
                        style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            groups.forEach { g ->
                val share = g.total.toFloat() / whole.coerceAtLeast(1)
                item {
                    Column(Modifier.fillMaxWidth().clickable { open("$label · ${g.label}", g.filter) }.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.label, style = MaterialTheme.typography.bodyMedium, color = if (g.filter.untagged) pal.sub else pal.text, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(end = 12.dp))
                            Text(num(g.total), style = MaterialTheme.typography.titleSmall)
                        }
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.surface2)) {
                            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (g.filter.untagged) faint else brand))
                        }
                    }
                }
            }
        } else if (!trend) {
            val groups = groupSums(inPeriod, splits, tags, cats, pays, filter(period), groupBy)
            val sum = groups.sumOf { it.total }
            // colors follow rank, not the category: 20 categories can't all have distinguishable colors
            val slices = groups.take(7).mapIndexed { i, g -> Slice(g.label, chartColors[i], g.total) } +
                groups.drop(7).takeIf { it.isNotEmpty() }?.let { rest -> listOf(Slice("그 외 ${rest.size}개", faint, rest.sumOf { it.total })) }.orEmpty()
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ChartSwitch(trend, pattern, groupBy, { trend = it == 1; pattern = it == 2 }) { dialog = "group" }
                    if (groups.isEmpty()) EmptyState(Icons.Rounded.BarChart, "이 기간에는 내역이 없어요")
                    else LabeledDonut(slices, Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp), onSlice = { i ->
                        if (i < 7) open("$label · ${groups[i].label}", groups[i].filter) else dialog = "rest"
                    }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("합계", style = MaterialTheme.typography.labelSmall, color = pal.sub)
                            Text(num(sum), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            groups.forEachIndexed { i, g ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { open("$label · ${g.label}", g.filter) }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(chartColors.getOrNull(i) ?: pal.faint))
                        Text(g.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
                        Text("${(g.total * 100.0 / sum.coerceAtLeast(1)).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(end = 12.dp))
                        Text(num(g.total), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        } else {
            fun counts(tx: com.choimanseon.pocketlog.data.Tx) = narrowTo == null || tx.categoryId == narrowTo.id || narrowTo.id in tags[tx.id].orEmpty()
            fun narrowed(f: TxFilter) = when {
                narrowTo == null -> f
                narrowTo.parentId == null -> f.copy(category = narrowTo.id)
                else -> f.copy(tag = narrowTo.id)
            }
            val values = buckets.map { (q, _) -> total(inside(q).filter(::counts), type) }
            val avg = values.average().toLong()
            // the budget of what one bar covers, for the whole or one category (TODO #44). With 통일 on any bar has one:
            // a quarter or a day is its share of the year (TODO #55)
            val barsPerYear = when (unit) {
                PeriodUnit.WEEK -> 52; PeriodUnit.MONTH -> 12; PeriodUnit.QUARTER -> 4; PeriodUnit.YEAR, PeriodUnit.ALL -> 1
                PeriodUnit.CUSTOM -> if (period.days <= 31) 365 else if (period.days <= 120) 52 else 12
            }
            val own = budgets.filter { type == TxType.EXPENSE && narrowTo?.parentId == null && it.categoryId == narrowTo?.id && it.amount > 0 }
            val exact = mapOf(52 to PeriodUnit.WEEK, 12 to PeriodUnit.MONTH, 1 to PeriodUnit.YEAR)[barsPerYear]?.let { u -> own.firstOrNull { it.period == u }?.amount }
            val budget = exact ?: own.firstOrNull { p.budgetLinked && it.period == PeriodUnit.MONTH }?.let { Math.round(it.amount * 12.0 / barsPerYear / 100) * 100 }
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ChartSwitch(trend, pattern, groupBy, { trend = it == 1; pattern = it == 2 }) { dialog = "group" }
                    // a category first, then one of its tags (TODO #43)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip((narrowTop?.name ?: "${type.label} 전체") + " ▾", narrowTo != null) { dialog = "narrow" }
                        if (narrowTop != null) Chip((narrowTo.takeIf { it.parentId != null }?.name ?: "태그 전체") + " ▾", narrowTo.parentId != null) { dialog = "tags:${narrowTop.id}" }
                    }
                    Spacer(Modifier.height(12.dp))
                    TrendChart(values, buckets.map { it.second }, buckets.lastIndex, average = avg, budget = budget, onStep = drag) { if (type == TxType.EXPENSE) brand else good }
                }
            }
            item { TableHeader("기간", type.label, "3기간 평균") }
            buckets.indices.reversed().forEach { i ->
                val (q, _) = buckets[i]
                val avg3 = values.subList((i - 2).coerceAtLeast(0), i + 1).average().toLong()
                item {
                    TableRow(bucketLabel(unit, q, today), listOf(num(values[i]), num(avg3)), pal.text) {
                        open("${q.label(PeriodUnit.CUSTOM, today)} ${narrowTo?.name ?: type.label}", narrowed(filter(q)))
                    }
                }
            }
        }
    }

    when (dialog) {
        "unit" -> ChoiceDialog("기간 단위", PeriodUnit.entries.map { it.label }, unit.ordinal, { dialog = null }) { i ->
            unit = PeriodUnit.entries[i]
            dialog = if (unit == PeriodUnit.CUSTOM && custom == null) "range" else null
        }
        "group" -> ChoiceDialog("차트 기준", GroupBy.entries.map { it.label }, groupBy.ordinal, { dialog = null }) { groupBy = GroupBy.entries[it]; dialog = null }
        "narrow" -> {
            // categories only; 공통 태그 leads straight to its tags
            val shared = cats.firstOrNull { it.type == type && it.tagGroup }?.takeIf { g -> cats.any { it.parentId == g.id && !it.hidden } }
            val options = listOf<com.choimanseon.pocketlog.data.Category?>(null) + cats.tops(type).filter { !it.hidden } + listOfNotNull(shared)
            ChoiceDialog("기간 차트로 볼 것", options.map { it?.name ?: "${type.label} 전체" }, options.indexOf(narrowTop), { dialog = null }) {
                val c = options[it]
                if (c != null && c.tagGroup) dialog = "tags:${c.id}" else { narrow = c?.id; dialog = null }
            }
        }
        "month" -> MonthPickerDialog(period.month(), { dialog = null }) { anchor = it.atDay(15).toString(); dialog = null }
        "rest" -> {
            val groups = groupSums(inPeriod, splits, tags, cats, pays, filter(period), groupBy).drop(7)
            ChoiceDialog("그 외", groups.map { "${it.label} · ${num(it.total)}" }, -1, { dialog = null }) { dialog = null; open("$label · ${groups[it].label}", groups[it].filter) }
        }
        "range" -> {
            val state = rememberDateRangePickerState(
                initialSelectedStartDateMillis = period.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                initialSelectedEndDateMillis = period.end.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { dialog = null; if (custom == null) unit = PeriodUnit.MONTH },
                confirmButton = {
                    TextButton(onClick = {
                        val a = state.selectedStartDateMillis
                        val b = state.selectedEndDateMillis ?: a
                        if (a != null && b != null) {
                            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            custom = day(a).toString() to day(b).plusDays(1).toString()
                            unit = PeriodUnit.CUSTOM
                        }
                        dialog = null
                    }) { Text("확인") }
                },
                dismissButton = { TextButton(onClick = { dialog = null; if (custom == null) unit = PeriodUnit.MONTH }) { Text("취소") } },
            ) { DateRangePicker(state, Modifier.weight(1f), title = { Text("기간 지정", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
        }
        else -> if (dialog?.startsWith("tags:") == true) {
            val owner = cats.firstOrNull { it.id == dialog!!.substringAfter(':').toLongOrNull() }
            val options = listOfNotNull(owner?.takeIf { !it.tagGroup }) + cats.filter { it.parentId == owner?.id && !it.hidden }
            ChoiceDialog("${owner?.name} 태그", options.map { if (it.parentId == null) "태그 전체" else it.name }, options.indexOf(narrowTo), { dialog = null }) {
                narrow = options[it].id; dialog = null
            }
        }
    }
}

/** A table row's name for one bar of the trend chart: "10월", "2025년", "9.28 ~ 10.4". */
private fun bucketLabel(unit: PeriodUnit, q: Period, today: LocalDate): String {
    val u = when (unit) {
        PeriodUnit.ALL -> PeriodUnit.YEAR
        PeriodUnit.CUSTOM -> if (q.days <= 7) PeriodUnit.WEEK else PeriodUnit.MONTH
        else -> unit
    }
    return if (u == PeriodUnit.MONTH) q.month().let { if (it.year == today.year) "${it.monthValue}월" else "${it.year % 100}년 ${it.monthValue}월" }
    else q.label(u, today)
}

private val tabTypes = listOf(TxType.EXPENSE, TxType.INCOME, TxType.SAVING)
private const val NET = 3 // the 합산 tab, after the three types

private val previous = mapOf(PeriodUnit.WEEK to "지난주", PeriodUnit.MONTH to "지난달", PeriodUnit.QUARTER to "지난 분기", PeriodUnit.YEAR to "작년")

@Composable
private fun Summary(tab: Int, txs: List<com.choimanseon.pocketlog.data.Tx>, prevTxs: List<com.choimanseon.pocketlog.data.Tx>?, period: Period, today: LocalDate, unit: PeriodUnit) {
    PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (tab == NET) {
            val (spent, income, saved) = tabTypes.map { total(txs, it) }
            val left = income - spent - saved
            Text("남은 돈 (수입 − 지출 − 저축)", style = MaterialTheme.typography.bodySmall, color = pal.sub)
            Text(won(left), style = MaterialTheme.typography.headlineSmall, color = if (left < 0) pal.danger else pal.text, modifier = Modifier.padding(vertical = 2.dp))
            Text("수입 ${num(income)} · 지출 ${num(spent)} · 저축 ${num(saved)}", style = MaterialTheme.typography.bodySmall, color = pal.sub)
        } else {
            val type = tabTypes[tab]
            val sum = total(txs, type)
            val prevSum = prevTxs?.let { total(it, type) } ?: 0
            val days = if (today in period) ChronoUnit.DAYS.between(period.start, today) + 1 else period.days
            Text(when (type) { TxType.INCOME -> "번 돈"; TxType.SAVING -> "모은 돈"; else -> "쓴 돈" }, style = MaterialTheme.typography.bodySmall, color = pal.sub)
            Text(won(sum), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 2.dp))
            Row {
                if (prevSum > 0) {
                    val pct = (sum - prevSum) * 100 / prevSum
                    Text(
                        "${previous[unit] ?: "지난 기간"}보다 ${if (pct >= 0) "+" else ""}$pct%", style = MaterialTheme.typography.bodySmall,
                        color = if ((pct > 0) == (type == TxType.EXPENSE)) pal.danger else pal.income,
                    )
                    Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = pal.faint)
                }
                Text("하루 평균 ${won(sum / days.coerceAtLeast(1))}", style = MaterialTheme.typography.bodySmall, color = pal.sub)
            }
        }
        if (unit != PeriodUnit.MONTH && unit != PeriodUnit.ALL) Text(period.label(PeriodUnit.CUSTOM, today), style = MaterialTheme.typography.labelSmall, color = pal.faint, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
/** [onView]: 0 분류 차트, 1 기간 차트, 2 패턴 */
private fun ChartSwitch(trend: Boolean, pattern: Boolean, groupBy: GroupBy, onView: (Int) -> Unit, onGroup: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("분류", !trend && !pattern) { onView(0) }
        Chip("기간", trend && !pattern) { onView(1) }
        Chip("패턴", pattern) { onView(2) }
        Spacer(Modifier.weight(1f))
        if (!trend && !pattern) TextButton(onClick = onGroup) { Text("${groupBy.label} ▾", color = pal.sub) }
    }
}

@Composable
private fun TableHeader(vararg cols: String) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)) {
        cols.forEachIndexed { i, c ->
            if (i == 0) Text(c, style = MaterialTheme.typography.labelMedium, color = pal.sub, modifier = Modifier.weight(0.9f))
            else if (c.isNotEmpty()) Text(c, style = MaterialTheme.typography.labelMedium, color = pal.sub, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun TableRow(label: String, values: List<String>, lastColor: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // up to four columns of 9-digit amounts have to fit a phone: small type, no wrapping
        val num = if (values.size > 3) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.9f), maxLines = 1)
        values.forEachIndexed { i, v ->
            val last = i == values.lastIndex && values.size > 2 // the result column of 합산
            Text(
                v, style = if (last) num.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else num,
                color = if (last) lastColor else if (i == 0) pal.text else pal.sub, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1,
            )
        }
    }
}
