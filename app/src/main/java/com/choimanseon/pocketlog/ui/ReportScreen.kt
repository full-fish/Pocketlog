package com.choimanseon.pocketlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.choimanseon.pocketlog.BuildConfig
import com.choimanseon.pocketlog.ai.Ai
import com.choimanseon.pocketlog.ai.MonthlyReport
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Report
import com.choimanseon.pocketlog.domain.Period
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

private fun Report.period() = Period(LocalDate.parse(start), LocalDate.parse(end))

/** AI 월간 리포트 (TODO #35): the newest first, older ones one tap away. */
@Composable
fun ReportScreen(start: String?, nav: Nav) {
    val reports by rememberFlow(emptyList()) { app.dao.reports() }
    var shown by rememberSaveable { mutableStateOf(start) }
    var making by remember { mutableStateOf(false) }
    val r = reports.firstOrNull { it.start == shown } ?: reports.firstOrNull()

    PageScaffold("월간 리포트", onBack = nav::pop) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (reports.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reports.forEach { Chip(it.period().label(), it == r) { shown = it.start } }
            }
            if (r == null) EmptyState(
                Icons.Rounded.AutoAwesome, "아직 리포트가 없어요",
                "한 달이 끝나면 다음 달 첫날 오전 9시에 지난달 리포트를 보내 드려요. 설정 → AI 사용 동의가 켜져 있어야 해요.",
            ) else Body(r)
            if (BuildConfig.DEBUG) TextButton(
                onClick = {
                    if (!Ai.usable()) { nav.toast("설정 → AI 사용 동의를 켜 주세요"); return@TextButton }
                    making = true
                    app.scope.launch {
                        val last = monthPeriod(LocalDate.now(), app.prefs.monthStartDay).shiftMonths(-1)
                        runCatching { MonthlyReport.make(last) }
                            .onSuccess { made -> if (made == null) nav.toast("지난달에는 내역이 없어요") else shown = made.start }
                            .onFailure { nav.toast("만들지 못했어요 (${it.message?.take(60)})") }
                        making = false
                    }
                },
                enabled = !making, modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text(if (making) "만드는 중이에요… (30초쯤)" else "지난달 리포트 지금 만들기 (개발용)") }
        }
    }
}

/**
 * The report (TODO #41): the AI's text between charts of the same numbers. Every number on the page comes from the
 * facts the app added up, so the charts and the text never disagree.
 */
@Composable
private fun Body(r: Report) {
    val json = remember(r.json) { JSONObject(r.json) }
    val text = json.getJSONObject("text")
    val facts = json.getJSONObject("facts")
    val now = facts.getJSONObject("this_month")
    val before = facts.getJSONObject("last_month")
    val period = r.period()
    fun JSONObject.list(key: String) = optJSONArray(key)?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(period.label() + " 리포트", style = MaterialTheme.typography.labelLarge, color = pal.sub)
        Text(text.getString("headline"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        Text(text.getString("summary"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
    }
    PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            listOf("지출" to "spending", "수입" to "income", "저축" to "saving", "남은 돈" to "left").forEach { (label, key) ->
                val diff = now.getLong(key) - before.getLong(key)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = pal.sub)
                    Text(num(now.getLong(key)), style = MaterialTheme.typography.titleSmall)
                    if (diff != 0L) Text((if (diff > 0) "+" else "") + num(diff), style = MaterialTheme.typography.labelSmall, color = pal.faint)
                }
            }
        }
        Text("아래 숫자는 지난달 대비예요", style = MaterialTheme.typography.labelSmall, color = pal.faint, modifier = Modifier.padding(top = 8.dp))
        // this month against last month, side by side
        val most = maxOf(now.getLong("spending"), before.getLong("spending"), now.getLong("income"), before.getLong("income"), 1L).toFloat()
        listOf("지출" to "spending", "수입" to "income").forEach { (label, key) ->
            Text(label, style = MaterialTheme.typography.labelMedium, color = pal.sub, modifier = Modifier.padding(top = 14.dp))
            BarRow("이번 달", num(now.getLong(key)), now.getLong(key) / most, if (key == "spending") pal.brand else pal.income)
            BarRow("지난달", num(before.getLong(key)), before.getLong(key) / most, pal.faint)
        }
    }

    facts.optJSONArray("daily")?.let { a ->
        val daily = List(a.length()) { a.getLong(it) }
        val peak = daily.indices.maxByOrNull { daily[it] } ?: 0
        PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("날마다 쓴 돈", style = MaterialTheme.typography.titleSmall)
            Text(
                "가장 많이 쓴 날 ${period.start.plusDays(peak.toLong()).let { "${it.monthValue}월 ${it.dayOfMonth}일" }} · ${num(daily[peak])}원",
                style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            val brand = pal.brand
            // 30 bars leave no room for each day's number: the first and last day sit under the ends instead
            TrendChart(daily, daily.map { "" }, peak, average = daily.average().toLong()) { brand }
            Row {
                listOf(period.start, period.end.minusDays(1)).forEachIndexed { i, d ->
                    if (i == 1) Spacer(Modifier.weight(1f))
                    Text("${d.monthValue}.${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = pal.sub)
                }
            }
        }
    }

    val highlights = text.getJSONArray("highlights")
    SectionHeader("이번 달에 눈에 띈 것")
    for (i in 0 until highlights.length()) {
        val h = highlights.getJSONObject(i)
        PCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(h.getString("title"), style = MaterialTheme.typography.titleSmall)
            Text(h.getString("detail"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }

    val categories = facts.list("categories")
    if (categories.isNotEmpty()) {
        SectionHeader("카테고리")
        PCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val sum = categories.sumOf { it.getLong("amount") }
            val slices = categories.take(7).mapIndexed { i, c -> Slice(c.getString("name"), chartColors[i], c.getLong("amount")) } +
                categories.drop(7).takeIf { it.isNotEmpty() }?.let { rest -> listOf(Slice("그 외", pal.faint, rest.sumOf { it.getLong("amount") })) }.orEmpty()
            LabeledDonut(slices, Modifier.fillMaxWidth().height(240.dp)) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("합계", style = MaterialTheme.typography.labelSmall, color = pal.sub)
                    Text(num(sum), style = MaterialTheme.typography.labelLarge)
                }
            }
            // the faint bar behind each one is last month
            val most = categories.maxOf { maxOf(it.getLong("amount"), it.getLong("last_month")) }.coerceAtLeast(1).toFloat()
            categories.forEachIndexed { i, c ->
                val amount = c.getLong("amount")
                val last = c.getLong("last_month")
                BarRow(
                    c.getString("name"), num(amount), amount / most, chartColors.getOrElse(i) { pal.faint },
                    note = "${amount * 100 / sum.coerceAtLeast(1)}% · " + change(amount, last), back = last / most,
                )
            }
            Text("옅은 막대는 지난달이에요", style = MaterialTheme.typography.labelSmall, color = pal.faint, modifier = Modifier.padding(top = 6.dp))
        }
    }

    val tags = facts.list("tags")
    if (tags.isNotEmpty()) {
        SectionHeader("태그")
        PCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val most = tags.maxOf { it.getLong("amount") }.coerceAtLeast(1).toFloat()
            tags.forEach { t -> BarRow(t.getString("tag"), num(t.getLong("amount")), t.getLong("amount") / most, pal.brand) }
        }
    }

    val places = facts.list("places")
    if (places.isNotEmpty()) {
        SectionHeader("자주 간 곳")
        PCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val most = places.maxOf { it.getLong("amount") }.coerceAtLeast(1).toFloat()
            places.forEach { pl -> BarRow(pl.getString("name"), num(pl.getLong("amount")), pl.getLong("amount") / most, pal.income, note = "${pl.getInt("visits")}번") }
        }
    }

    facts.optJSONObject("daily_average")?.let { avg ->
        SectionHeader("언제 썼나")
        PCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val weekday = avg.getLong("weekday")
            val weekend = avg.getLong("weekend")
            val most = maxOf(weekday, weekend, 1L).toFloat()
            BarRow("평일 하루 평균", num(weekday), weekday / most, pal.brand)
            BarRow("주말 하루 평균", num(weekend), weekend / most, pal.warn)
            facts.optString("busiest_time").takeIf { it.isNotEmpty() && it != "null" }?.let {
                Text("가장 많이 쓴 때: $it", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }

    val biggest = facts.list("biggest")
    if (biggest.isNotEmpty()) {
        SectionHeader("큰 지출")
        PCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            biggest.forEach { b ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.getString("merchant").ifBlank { b.getString("category") }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text("${LocalDate.parse(b.getString("date")).let { "${it.monthValue}월 ${it.dayOfMonth}일" }} · ${b.getString("category")}", style = MaterialTheme.typography.bodySmall, color = pal.sub)
                    }
                    Text(num(b.getLong("amount")), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }

    SectionHeader("예산")
    val budgets = facts.list("budgets")
    if (budgets.isNotEmpty()) PCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        budgets.forEach { b ->
            val spent = b.getLong("spent")
            val budget = b.getLong("budget")
            Row(Modifier.padding(top = 8.dp)) {
                Text(b.getString("category"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${num(spent)} / ${num(budget)}", style = MaterialTheme.typography.bodySmall, color = if (spent > budget) pal.danger else pal.sub)
            }
            ProgressBar(if (budget > 0) spent.toFloat() / budget else 0f, Modifier.padding(top = 6.dp))
        }
    }
    Text(text.getString("budget"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    val tips = text.getJSONArray("suggestions")
    SectionHeader("다음 달에 해 볼 것")
    for (i in 0 until tips.length()) Text("· " + tips.getString(i), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp))
    SectionHeader("잘한 점")
    Text(text.getString("praise"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
}

/** "▲12%" against last month, "지난달 없음" when there was nothing then. */
private fun change(now: Long, before: Long) = when {
    before == 0L -> if (now > 0) "지난달 없음" else ""
    else -> ((now - before) * 100 / before).let { if (it >= 0) "▲$it%" else "▼${-it}%" }
}

/** A name, its amount and a bar; [back] draws a second, faint bar behind (last month). */
@Composable
private fun BarRow(label: String, value: String, fraction: Float, color: Color, note: String? = null, back: Float? = null) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = pal.sub, modifier = Modifier.padding(end = 10.dp))
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
        Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(pal.surface2)) {
            if (back != null) Box(Modifier.fillMaxWidth(back.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.3f)))
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}
