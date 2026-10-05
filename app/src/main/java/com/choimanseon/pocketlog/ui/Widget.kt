package com.choimanseon.pocketlog.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.choimanseon.pocketlog.MainActivity
import com.choimanseon.pocketlog.Pin
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.BudgetUse
import com.choimanseon.pocketlog.domain.PeriodUnit
import com.choimanseon.pocketlog.domain.byTopCategory
import com.choimanseon.pocketlog.domain.countable
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.total
import com.choimanseon.pocketlog.domain.won
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Home screen widget (TODO #35, #61): it grows with its size, measured exactly (a Samsung home row is about 118dp high).
 * - one row: this month's spending and the budget bar; wide, also small 입력 · 촬영 · 사진
 * - two rows and more: + the budget and pace in words, big 입력 · 촬영 · 사진 at the bottom, and as the height allows
 *   today's spending, the top categories and the latest records (TODO #63)
 * With the app lock on it shows no amounts. App refreshes it whenever Tx or Budget change, Daily once a day.
 */
class PocketWidget : GlanceAppWidget() {
    companion object {
        const val ACTION = "widget_action" // entry | camera | photos, read by MainActivity
        private const val BUTTONS = 52f // dp the 입력 · 촬영 · 사진 row takes from two rows up
        suspend fun refresh(context: Context) = runCatching { PocketWidget().updateAll(context) }
    }

    override val sizeMode = SizeMode.Exact

    /** [used]: the month budget spent so far, [expected]: how much of the month has gone by (TODO #53). */
    private class Numbers(
        val spent: String, val line: String, val used: Float? = null, val expected: Float? = null, val locked: Boolean = false,
        val today: Long = 0, val tops: List<Pair<String, Long>> = emptyList(), val recent: List<Triple<String, String, Long>> = emptyList(),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val n = if (Pin.isSet) Numbers("•••원", "앱 잠금이 켜져 있어 금액을 숨겨요", locked = true) else load()
        provideContent { Content(n) }
    }

    private suspend fun load(): Numbers {
        val dao = app.dao
        val today = LocalDate.now()
        val period = monthPeriod(today, app.prefs.monthStartDay)
        val txs = dao.txBetweenOnce(period.startMillis, period.endMillis)
        val spent = total(txs, TxType.EXPENSE)
        val budget = dao.budgetsOnce().firstOrNull { it.categoryId == null && it.period == PeriodUnit.MONTH && it.amount > 0 }
        val use = budget?.let { BudgetUse(it, period, spent) }
        val line = use?.let { u ->
            if (u.left >= 0) "남은 예산 ${won(u.left)} · 오늘 ${won(u.perDay(today))}" else "예산을 ${won(-u.left)} 넘었어요"
        } ?: "남은 돈 ${won(total(txs, TxType.INCOME) - spent - total(txs, TxType.SAVING))}"
        val gone = (ChronoUnit.DAYS.between(period.start, today) + 1).toFloat() / period.days
        val now = System.currentTimeMillis()
        val spending = txs.filter { it.countable() && it.type == TxType.EXPENSE && it.occurredAt <= now }
        return Numbers(
            won(spent), line, use?.let { spent.toFloat() / it.budget.amount }, gone.takeIf { use != null },
            today = spending.filter { it.occurredAt.toLocalDate() == today }.sumOf { it.amount },
            tops = byTopCategory(txs, dao.splitsBetweenOnce(period.startMillis, period.endMillis), dao.categoriesOnce(), TxType.EXPENSE)
                .take(3).map { (it.category?.name ?: "미분류") to it.total },
            recent = spending.sortedByDescending { it.occurredAt }.take(4).map { t ->
                Triple(t.occurredAt.toLocalDate().let { "${it.monthValue}/${it.dayOfMonth}" }, t.merchant.ifBlank { "내역" }, t.amount)
            },
        )
    }

    @Composable
    private fun Content(n: Numbers) {
        val bg = ColorProvider(Color(0xFFFFFFFF), Color(0xFF1C1D22))
        val fg = ColorProvider(Color(0xFF111111), Color(0xFFF2F3F5))
        val sub = ColorProvider(Color(0xFF6B7280), Color(0xFF9AA0A6))
        val chip = ColorProvider(Color(0xFFEEF0F4), Color(0xFF2A2C33))
        val brand = ColorProvider(Color(0xFF4C5BF5), Color(0xFF6B7BFF))
        val size = LocalSize.current
        val pad = if (size.height < 150.dp) 12.dp else 16.dp
        val inner = size.width - pad * 2
        val wide = size.width >= 250.dp
        val buttons = listOf("+ 입력" to "entry", "촬영" to "camera", "사진" to "photos")
        fun open(action: String) = actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<String>(ACTION) to action))
        val small = TextStyle(color = sub, fontSize = 12.sp)

        Column(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(bg).padding(horizontal = pad, vertical = pad).clickable(actionStartActivity<MainActivity>())) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("이번 달 쓴 돈", GlanceModifier.defaultWeight(), small)
                n.used?.let { Text("예산 ${(it * 100).toInt()}%", style = TextStyle(color = ColorProvider(barColor(n), barColor(n)), fontSize = 12.sp, fontWeight = FontWeight.Bold)) }
            }
            if (size.height < 150.dp) {
                // one row: the amount, small buttons beside it when wide, the bar under it
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.spent, GlanceModifier.defaultWeight(), TextStyle(color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    if (wide && !n.locked) buttons.forEach { (label, action) ->
                        Text(
                            label, GlanceModifier.padding(start = 6.dp).cornerRadius(12.dp).background(chip).padding(horizontal = 10.dp, vertical = 6.dp).clickable(open(action)),
                            TextStyle(color = fg, fontSize = 12.sp),
                        )
                    }
                }
                if (n.used != null) BudgetBar(n, inner, fg) else Text(n.line, style = small, maxLines = 1)
                if (n.used != null && size.height >= 105.dp) Text(n.line, GlanceModifier.padding(top = 4.dp), small, maxLines = 1)
                return@Column
            }
            // a weighted column only gets the height the button row leaves, so the buttons never get pushed out
            Column(GlanceModifier.defaultWeight().fillMaxWidth()) {
                Text(n.spent, GlanceModifier.padding(vertical = 2.dp), TextStyle(color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                if (n.used != null) {
                    BudgetBar(n, inner, fg)
                    Text("지금쯤이면 ${(n.expected!! * 100).toInt()}%가 맞아요", GlanceModifier.padding(top = 4.dp), small, maxLines = 1)
                }
                Text(n.line, GlanceModifier.padding(top = 2.dp), small, maxLines = 1)
                // the buttons always keep their place at the bottom (TODO #63); the extras take only the height left above them
                var room = size.height.value - pad.value * 2 - 18 - 38 - 22 - (if (n.used != null) 38 else 0) - BUTTONS
                val today = !n.locked && room >= 26
                if (today) room -= 26
                val tops = if (n.locked) 0 else ((room - 26) / 22).toInt().coerceIn(0, n.tops.size)
                if (tops > 0) room -= 26 + tops * 22
                val recent = if (n.locked) 0 else ((room - 26) / 22).toInt().coerceIn(0, n.recent.size).takeIf { it >= 2 } ?: 0
                if (today) Text("오늘 쓴 돈 ${won(n.today)}", GlanceModifier.padding(top = 8.dp), TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                // each list in its own Column: Glance drops every child of a Column past the 10th
                if (tops > 0) Column(GlanceModifier.fillMaxWidth()) {
                    Text("많이 쓴 곳", GlanceModifier.padding(top = 8.dp, bottom = 2.dp), small)
                    n.tops.take(tops).forEach { (name, amount) -> Line(name, num(amount), fg, null) }
                }
                if (recent > 0) Column(GlanceModifier.fillMaxWidth()) {
                    Text("최근 내역", GlanceModifier.padding(top = 8.dp, bottom = 2.dp), small)
                    n.recent.take(recent).forEach { (day, merchant, amount) -> Line(merchant, num(amount), fg, day) }
                }
            }
            Row(GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
                buttons.forEachIndexed { i, (label, action) ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    // drawn here, not Glance's Button: that one came out small on the Samsung home (TODO #63)
                    Box(
                        GlanceModifier.defaultWeight().height(44.dp).cornerRadius(14.dp).background(if (i == 0) brand else chip).clickable(open(action)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (wide || i > 0) label else "입력", maxLines = 1,
                            style = TextStyle(color = if (i == 0) ColorProvider(Color.White, Color.White) else fg, fontSize = if (wide) 15.sp else 13.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Line(name: String, amount: String, fg: ColorProvider, lead: String?) {
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
            if (lead != null) Text(lead, GlanceModifier.width(40.dp), TextStyle(color = ColorProvider(Color(0xFF6B7280), Color(0xFF9AA0A6)), fontSize = 12.sp))
            Text(name, GlanceModifier.defaultWeight(), TextStyle(color = fg, fontSize = 13.sp), maxLines = 1)
            Text(amount, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }

    /** Green while behind the month's pace, orange ahead of it, red over budget. */
    private fun barColor(n: Numbers) = when {
        (n.used ?: 0f) >= 1f -> Color(0xFFF03E3E)
        (n.used ?: 0f) > (n.expected ?: 1f) -> Color(0xFFF59F00)
        else -> Color(0xFF12B886)
    }

    /**
     * The month budget as a bar (TODO #53, #61): 10dp thick on a track that shows in dark mode too; the taller line marks
     * where spending would be at an even pace (half the month gone = half the budget).
     */
    @Composable
    private fun BudgetBar(n: Numbers, width: Dp, mark: ColorProvider) {
        val used = n.used ?: return
        val expected = n.expected ?: return
        val color = barColor(n)
        Box(GlanceModifier.fillMaxWidth().height(16.dp).padding(top = 3.dp), contentAlignment = Alignment.CenterStart) {
            LinearProgressIndicator(
                used.coerceIn(0f, 1f), GlanceModifier.fillMaxWidth().height(10.dp).cornerRadius(5.dp),
                ColorProvider(color, color), ColorProvider(Color(0xFFE5E7EB), Color(0xFF3A3D45)),
            )
            Row(GlanceModifier.fillMaxSize()) {
                Spacer(GlanceModifier.width((width * expected.coerceIn(0f, 1f) - 1.5.dp).coerceAtLeast(0.dp)))
                Box(GlanceModifier.width(3.dp).fillMaxHeight().background(mark)) {}
            }
        }
    }
}

class PocketWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PocketWidget()
}
