package com.choimanseon.pocketlog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Budget
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.PeriodUnit
import com.choimanseon.pocketlog.domain.budgetSpan
import com.choimanseon.pocketlog.domain.budgetUses
import com.choimanseon.pocketlog.domain.linkedBudgets
import com.choimanseon.pocketlog.domain.countable
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.tops
import com.choimanseon.pocketlog.domain.won
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun AssetsTab(nav: Nav) {
    val settings by app.prefs.version.collectAsState()
    val today = LocalDate.now()
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val period = remember(settings, today, offset) { monthPeriod(today, app.prefs.monthStartDay).shiftMonths(offset.toLong()) }
    val dao = app.dao
    val txs by rememberFlow(emptyList(), period) { dao.txBetween(period.startMillis, period.endMillis) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val budgets by rememberFlow(emptyList()) { dao.budgets() }
    var editingPay by remember { mutableStateOf<com.choimanseon.pocketlog.data.PayMethod?>(null) }
    var pickMonth by remember { mutableStateOf(false) }

    // this week's, month's and year's budgets; a past month shows only its month budgets
    val weekStart = java.time.DayOfWeek.of(app.prefs.weekStart)
    val budgetDay = if (offset == 0) today else period.start
    val shown = (if (offset == 0) budgets else budgets.filter { it.period == PeriodUnit.MONTH }).filter(app.prefs::shows)
    val span = remember(shown, budgetDay, settings) { budgetSpan(shown, budgetDay, app.prefs.monthStartDay, weekStart) }
    val spanTxs by rememberFlow(emptyList(), span) { dao.txBetween(span.startMillis, span.endMillis) }
    val spanSplits by rememberFlow(emptyList(), span) { dao.splitsBetween(span.startMillis, span.endMillis) }
    val uses = budgetUses(shown, spanTxs, spanSplits, cats, budgetDay, app.prefs.monthStartDay, weekStart)
    val balances = pays.mapNotNull { it.balance }.sum()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            // same header as 내역 and 분석: the month switcher on the left (TODO #15)
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PeriodSwitcher(period.label(today), { offset-- }, { offset++ }, onLabel = { pickMonth = true })
            }
        }
        item {
            PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onClick = { nav.push(Screen.BudgetEdit) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (offset == 0) "예산" else "${period.label(today)} 예산", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("편집", style = MaterialTheme.typography.labelMedium, color = pal.brand)
                }
                if (uses.isEmpty()) {
                    Text("예산을 정하면 남은 금액과 하루 권장 금액을 알려 드려요", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 6.dp))
                }
                uses.groupBy { it.budget.period }.forEach { (_, group) ->
                    if (offset == 0) Text(group.first().label, style = MaterialTheme.typography.labelMedium, color = pal.sub, modifier = Modifier.padding(top = 14.dp))
                    group.forEach { u ->
                        val name = u.budget.categoryId?.let { id -> cats.firstOrNull { it.id == id }?.name ?: return@forEach } ?: "전체"
                        BudgetLine(name, u.spent, u.budget.amount)
                    }
                }
            }
        }
        item {
            SectionHeader("결제수단 · 잔액", "관리") { nav.push(Screen.PayMethods) }
            if (balances != 0L) Text("알고 있는 잔액 합계 ${won(balances)}", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp))
        }
        items(pays.filter { !it.hidden }, key = { it.id }) { p ->
            val used = txs.filter { it.countable() && it.type == TxType.EXPENSE && it.paymentMethodId == p.id }.sumOf { it.amount }
            Row(
                Modifier.fillMaxWidth().clickable { editingPay = p }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(payIcon(p.kind), null, tint = pal.sub)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${if (offset == 0) "이번 달" else period.label(today)} ${num(used)}원 사용", style = MaterialTheme.typography.bodySmall, color = pal.sub)
                }
                p.balance?.let { Text(won(it), style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
    if (pickMonth) MonthPickerDialog(period.month(), { pickMonth = false }) { ym ->
        offset += java.time.temporal.ChronoUnit.MONTHS.between(period.month(), ym).toInt()
        pickMonth = false
    }
    editingPay?.let { p ->
        InputDialog(
            title = "${p.name} 잔액",
            initial = p.balance?.toString().orEmpty(),
            hint = "비우면 잔액을 표시하지 않아요",
            keyboard = KeyboardType.Number,
            message = if (p.kind == PayKind.CREDIT) "신용카드는 보통 잔액이 없어요" else null,
            onDismiss = { editingPay = null },
        ) { v ->
            app.scope.launch { app.dao.upsert(p.copy(balance = v.filter { it.isDigit() || it == '-' }.toLongOrNull())) }
            editingPay = null
        }
    }
}

@Composable
private fun BudgetLine(label: String, spent: Long, budget: Long) {
    Column(Modifier.padding(top = 14.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${num(spent)} / ${num(budget)}", style = MaterialTheme.typography.bodySmall, color = if (spent > budget) pal.danger else pal.sub)
        }
        ProgressBar(if (budget > 0) spent.toFloat() / budget else 0f, Modifier.padding(top = 6.dp))
    }
}

@Composable
fun BudgetEditScreen(nav: Nav) {
    val dao = app.dao
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val budgets by rememberFlow(emptyList()) { dao.budgets() }
    var editing by remember { mutableStateOf<Pair<Category?, Budget?>?>(null) }
    val tops = cats.tops(TxType.EXPENSE).filter { !it.hidden }
    // a category can have a week, a month and a year budget at once; each tab edits one of them
    val units = listOf(PeriodUnit.WEEK, PeriodUnit.MONTH, PeriodUnit.YEAR)
    var unit by rememberSaveable { mutableStateOf(PeriodUnit.MONTH) }
    val mine = budgets.filter { it.period == unit }
    val p = app.prefs
    p.version.collectAsState().value
    val unitName = mapOf(PeriodUnit.WEEK to "주", PeriodUnit.YEAR to "연").getOrDefault(unit, "월")

    PageScaffold("예산", onBack = nav::pop) {
        PillTabs(listOf("주", "월", "연"), units.indexOf(unit), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { unit = units[it] }
        LazyColumn {
            item {
                SwitchRow("주 · 월 · 연 통일", "하나를 정하면 나머지를 맞춰요 (1년 = 12달 = 52주)", p.budgetLinked) { on ->
                    p.budgetLinked = on
                    if (on && budgets.isNotEmpty()) {
                        app.scope.launch { linkedBudgets(budgets, unit).forEach { dao.upsert(it) } }
                        nav.toast("$unitName 예산에 맞춰 나머지를 바꿨어요")
                    }
                }
                val hidden = !p.shows(Budget(amount = 0, period = unit))
                SwitchRow("$unitName 예산 보이기", "끄면 홈 · 자산 탭과 예산 알림에서 빠져요", !hidden) { show ->
                    p.budgetHidden = p.budgetHidden.split(',').filter { it.isNotEmpty() && it != unit.name }.plus(if (show) emptyList() else listOf(unit.name)).joinToString(",")
                }
                val b = mine.firstOrNull { it.categoryId == null }
                val span = when (unit) { PeriodUnit.WEEK -> "한 주에"; PeriodUnit.YEAR -> "한 해에"; else -> "한 달에" }
                ListRow("전체 예산", "$span 쓸 돈 전체", trailing = { Text(b?.let { won(it.amount) } ?: "설정 안 함", color = if (b == null) pal.faint else pal.text) }) {
                    editing = null to b
                }
                GroupLabel("카테고리별 예산 (선택)")
            }
            items(tops, key = { it.id }) { c ->
                val b = mine.firstOrNull { it.categoryId == c.id }
                ListRow(c.name, trailing = { Text(b?.let { won(it.amount) } ?: "-", color = if (b == null) pal.faint else pal.text) }) {
                    editing = c to b
                }
            }
        }
    }
    editing?.let { (c, b) ->
        InputDialog(
            title = "${c?.name ?: "전체"} $unitName 예산",
            initial = b?.amount?.toString().orEmpty(),
            hint = "금액 (비우면 삭제)",
            keyboard = KeyboardType.Number,
            message = if (p.budgetLinked) "통일이 켜져 있어 다른 기간 예산도 함께 바뀌어요" else null,
            onDismiss = { editing = null },
        ) { v ->
            val amount = v.filter(Char::isDigit).toLongOrNull()
            // with 통일 on the same category's other periods follow (TODO #37)
            val own = if (p.budgetLinked) budgets.filter { it.categoryId == c?.id } else listOfNotNull(b)
            app.scope.launch {
                when {
                    amount == null || amount == 0L -> own.forEach { dao.delete(it) }
                    else -> {
                        val edited = b?.copy(amount = amount) ?: Budget(categoryId = c?.id, amount = amount, period = unit)
                        dao.upsert(edited)
                        if (p.budgetLinked) linkedBudgets(own.filter { it.period != unit } + edited, unit).forEach { dao.upsert(it) }
                    }
                }
            }
            editing = null
        }
    }
}
