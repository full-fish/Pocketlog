package com.choimanseon.pocketlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import com.choimanseon.pocketlog.domain.total
import com.choimanseon.pocketlog.domain.trendBuckets
import com.choimanseon.pocketlog.domain.won
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val chartColors = listOf(
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
    var tab by rememberSaveable { mutableIntStateOf(0) } // 지출 · 수입 · 합산
    var trend by rememberSaveable { mutableStateOf(false) } // 분류 차트 or 기간 차트
    var groupBy by rememberSaveable { mutableStateOf(GroupBy.CATEGORY) }
    var cumulative by rememberSaveable { mutableStateOf(false) }
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
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }

    val type = if (tab == 1) TxType.INCOME else TxType.EXPENSE
    fun inside(q: Period) = txs.filter { it.occurredAt.toLocalDate() in q }
    val inPeriod = inside(period)
    fun filter(q: Period, t: TxType? = type) = TxFilter(q.startMillis, q.endMillis, t)
    fun open(title: String, f: TxFilter) = nav.push(Screen.TxList(title, f))
    val label = period.label(unit, today)
    val good = pal.income
    val bad = pal.danger
    val brand = pal.brand
    val faint = pal.faint

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val movable = unit != PeriodUnit.ALL
                fun go(n: Long) { if (unit == PeriodUnit.CUSTOM) custom = period.shift(unit, n).let { it.start.toString() to it.end.toString() } else anchor = period.shift(unit, n).start.toString() }
                PeriodSwitcher(
                    label, if (movable) ({ go(-1) }) else null, if (movable) ({ go(1) }) else null, Modifier.weight(1f),
                    onLabel = when (unit) { PeriodUnit.ALL -> null; PeriodUnit.CUSTOM -> ({ dialog = "range" }); else -> ({ dialog = "month" }) },
                )
                Chip("${unit.label} ▾", selected = true) { dialog = "unit" }
            }
            PillTabs(listOf("지출", "수입", "합산"), tab, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { tab = it }
        }
        item { Summary(tab, inPeriod, prev?.let { inside(it) }, period, today, unit) }

        if (tab == 2) {
            val rows = buckets.map { (q, l) -> Triple(q, l, inside(q).let { total(it, TxType.INCOME) to total(it, TxType.EXPENSE) }) }
            val net = rows.map { it.third.first - it.third.second }
            val shown = if (cumulative) net.runningReduce(Long::plus) else net
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (cumulative) "누적 합산" else "기간별 합산", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Chip("누적 보기", cumulative) { cumulative = !cumulative }
                    }
                    Spacer(Modifier.height(12.dp))
                    TrendChart(shown, rows.map { it.second }, rows.lastIndex, line = cumulative) { if (it >= 0) good else bad }
                }
            }
            item { TableHeader("기간", "수입", "지출", "합계") }
            rows.zip(net).reversed().forEach { (r, n) ->
                item {
                    TableRow(bucketLabel(unit, r.first, today), num(r.third.first), num(r.third.second), num(n), if (n >= 0) pal.income else pal.danger) {
                        open(r.first.label(PeriodUnit.CUSTOM, today), filter(r.first, null))
                    }
                }
            }
        } else if (!trend) {
            val groups = groupSums(inPeriod, splits, cats, pays, filter(period), groupBy)
            val sum = groups.sumOf { it.total }
            // colors follow rank, not the category: 20 categories can't all have distinguishable colors
            val slices = groups.take(7).mapIndexed { i, g -> Slice(g.label, chartColors[i], g.total) } +
                groups.drop(7).takeIf { it.isNotEmpty() }?.let { rest -> listOf(Slice("그 외 ${rest.size}개", faint, rest.sumOf { it.total })) }.orEmpty()
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ChartSwitch(trend, groupBy, { trend = it }) { dialog = "group" }
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
            val values = buckets.map { (q, _) -> total(inside(q), type) }
            val avg = values.average().toLong()
            item {
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ChartSwitch(trend, groupBy, { trend = it }) { dialog = "group" }
                    Spacer(Modifier.height(12.dp))
                    TrendChart(values, buckets.map { it.second }, buckets.lastIndex, average = avg) { if (type == TxType.EXPENSE) brand else good }
                }
            }
            item { TableHeader("기간", if (type == TxType.EXPENSE) "지출" else "수입", "3기간 평균", "") }
            buckets.indices.reversed().forEach { i ->
                val (q, _) = buckets[i]
                val avg3 = values.subList((i - 2).coerceAtLeast(0), i + 1).average().toLong()
                item {
                    TableRow(bucketLabel(unit, q, today), num(values[i]), num(avg3), "", pal.text) {
                        open("${q.label(PeriodUnit.CUSTOM, today)} ${if (type == TxType.EXPENSE) "지출" else "수입"}", filter(q))
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
        "month" -> MonthPickerDialog(period.month(), { dialog = null }) { anchor = it.atDay(15).toString(); dialog = null }
        "rest" -> {
            val groups = groupSums(inPeriod, splits, cats, pays, filter(period), groupBy).drop(7)
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

private val previous = mapOf(PeriodUnit.WEEK to "지난주", PeriodUnit.MONTH to "지난달", PeriodUnit.QUARTER to "지난 분기", PeriodUnit.YEAR to "작년")

@Composable
private fun Summary(tab: Int, txs: List<com.choimanseon.pocketlog.data.Tx>, prevTxs: List<com.choimanseon.pocketlog.data.Tx>?, period: Period, today: LocalDate, unit: PeriodUnit) {
    PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        val income = total(txs, TxType.INCOME)
        val spent = total(txs, TxType.EXPENSE)
        if (tab == 2) {
            Text("수입 − 지출", style = MaterialTheme.typography.bodySmall, color = pal.sub)
            Text(won(income - spent), style = MaterialTheme.typography.headlineSmall, color = if (income - spent < 0) pal.danger else pal.text, modifier = Modifier.padding(vertical = 2.dp))
            Text("수입 ${num(income)} · 지출 ${num(spent)}", style = MaterialTheme.typography.bodySmall, color = pal.sub)
        } else {
            val type = if (tab == 1) TxType.INCOME else TxType.EXPENSE
            val sum = if (tab == 1) income else spent
            val prevSum = prevTxs?.let { total(it, type) } ?: 0
            val days = if (today in period) ChronoUnit.DAYS.between(period.start, today) + 1 else period.days
            Text(if (type == TxType.EXPENSE) "쓴 돈" else "번 돈", style = MaterialTheme.typography.bodySmall, color = pal.sub)
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
private fun ChartSwitch(trend: Boolean, groupBy: GroupBy, onTrend: (Boolean) -> Unit, onGroup: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("분류 차트", !trend) { onTrend(false) }
        Chip("기간 차트", trend) { onTrend(true) }
        Spacer(Modifier.weight(1f))
        if (!trend) TextButton(onClick = onGroup) { Text("${groupBy.label} ▾", color = pal.sub) }
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
private fun TableRow(label: String, a: String, b: String, c: String, cColor: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // four columns of 9-digit amounts have to fit a phone: small type, no wrapping
        val num = MaterialTheme.typography.bodySmall
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.9f), maxLines = 1)
        Text(a, style = num, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
        Text(b, style = num, color = pal.sub, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
        if (c.isNotEmpty()) Text(c, style = num.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = cColor, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
    }
}
