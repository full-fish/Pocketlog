package com.choimanseon.pocketlog.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.byTopCategory
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.total
import com.choimanseon.pocketlog.domain.won
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private data class Slice(val label: String, val color: Color, val total: Long)

private val chartColors = listOf(
    Color(0xFF4C5BF5), Color(0xFF22B8CF), Color(0xFFFF9F1C), Color(0xFFF06595),
    Color(0xFF12B886), Color(0xFF845EF7), Color(0xFFFAB005),
)

@Composable
fun StatsTab(nav: Nav) {
    val settings by app.prefs.version.collectAsState()
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var typeIndex by rememberSaveable { mutableIntStateOf(0) }
    val type = if (typeIndex == 0) TxType.EXPENSE else TxType.INCOME
    val today = LocalDate.now()
    val period = remember(settings, offset) { monthPeriod(today, app.prefs.monthStartDay).shiftMonths(offset.toLong()) }
    val sixAgo = period.shiftMonths(-5)
    val dao = app.dao
    // one query for the last 6 periods; per-period sums are done in memory
    val txs by rememberFlow(emptyList(), period) { dao.txBetween(sixAgo.startMillis, period.endMillis) }
    val splits by rememberFlow(emptyList(), period) { dao.splitsBetween(period.startMillis, period.endMillis) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val budgets by rememberFlow(emptyList()) { dao.budgets() }

    val inPeriod = txs.filter { it.occurredAt.toLocalDate() in period }
    val sum = total(inPeriod, type)
    val prevPeriod = period.shiftMonths(-1)
    val prevSum = total(txs.filter { it.occurredAt.toLocalDate() in prevPeriod }, type)
    val sums = byTopCategory(inPeriod, splits, cats, type)
    val days = if (today in period) ChronoUnit.DAYS.between(period.start, today) + 1 else period.days
    val months = (5 downTo 0).map { period.shiftMonths(-it.toLong()) }
    val monthly = months.map { p -> total(txs.filter { it.occurredAt.toLocalDate() in p }, type) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PeriodSwitcher(period.label(today), { offset-- }, { offset++ })
            }
            PillTabs(listOf("지출", "수입"), typeIndex, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { typeIndex = it }
        }
        item {
            PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(if (type == TxType.EXPENSE) "쓴 돈" else "번 돈", style = MaterialTheme.typography.bodySmall, color = pal.sub)
                Text(won(sum), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 2.dp))
                Row {
                    if (prevSum > 0) {
                        val pct = (sum - prevSum) * 100 / prevSum
                        Text(
                            "지난 기간보다 ${if (pct >= 0) "+" else ""}$pct%", style = MaterialTheme.typography.bodySmall,
                            color = if ((pct > 0) == (type == TxType.EXPENSE)) pal.danger else pal.income,
                        )
                        Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = pal.faint)
                    }
                    Text("하루 평균 ${won(sum / days.coerceAtLeast(1))}", style = MaterialTheme.typography.bodySmall, color = pal.sub)
                }
            }
        }
        item {
            PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("카테고리별", style = MaterialTheme.typography.titleSmall)
                if (sums.isEmpty()) EmptyState(Icons.Rounded.BarChart, "이 기간에는 내역이 없어요")
                else {
                    // colors follow rank, not the category: 20 categories can't all have distinguishable colors
                    val positive = sums.filter { it.total > 0 }
                    val rows = positive.take(7).mapIndexed { i, s -> Slice(s.category?.name ?: "미분류", chartColors[i], s.total) } +
                        positive.drop(7).takeIf { it.isNotEmpty() }?.let { rest -> listOf(Slice("그 외 ${rest.size}개", pal.faint, rest.sumOf { it.total })) }.orEmpty()
                    val all = rows.sumOf { it.total }.coerceAtLeast(1)
                    Donut(
                        rows.map { it.color to it.total },
                        Modifier.padding(vertical = 20.dp).size(200.dp).align(Alignment.CenterHorizontally),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("합계", style = MaterialTheme.typography.labelMedium, color = pal.sub)
                            Text(num(sum), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    rows.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                            Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
                            Text("${s.total * 100 / all}%", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(end = 12.dp))
                            Text(num(s.total), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        item {
            PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("최근 6개월", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${months.last().label(today)} ${won(monthly.last())}" + if (type == TxType.EXPENSE && budgets.any { it.categoryId == null }) " · 점선은 예산" else "",
                    style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(bottom = 16.dp),
                )
                Bars(
                    monthly, months.map { "${it.start.monthValue}월" }, highlight = 5,
                    budget = if (type == TxType.EXPENSE) budgets.firstOrNull { it.categoryId == null }?.amount else null,
                )
            }
        }
    }
}
