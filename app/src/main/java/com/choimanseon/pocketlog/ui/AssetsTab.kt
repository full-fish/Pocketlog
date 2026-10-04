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
import com.choimanseon.pocketlog.domain.byTopCategory
import com.choimanseon.pocketlog.domain.countable
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.total
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
    val splits by rememberFlow(emptyList(), period) { dao.splitsBetween(period.startMillis, period.endMillis) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val budgets by rememberFlow(emptyList()) { dao.budgets() }
    var editingPay by remember { mutableStateOf<com.choimanseon.pocketlog.data.PayMethod?>(null) }
    var pickMonth by remember { mutableStateOf(false) }

    val spent = total(txs, TxType.EXPENSE)
    val byCat = byTopCategory(txs, splits, cats, TxType.EXPENSE).associate { it.category?.id to it.total }
    val totalBudget = budgets.firstOrNull { it.categoryId == null }?.amount
    val catBudgets = budgets.filter { it.categoryId != null }
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
                    Text(if (offset == 0) "이번 달 예산" else "${period.label(today)} 예산", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("편집", style = MaterialTheme.typography.labelMedium, color = pal.brand)
                }
                if (totalBudget == null && catBudgets.isEmpty()) {
                    Text("예산을 정하면 남은 금액과 하루 권장 금액을 알려 드려요", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 6.dp))
                }
                if (totalBudget != null) BudgetLine("전체", spent, totalBudget)
                catBudgets.forEach { b ->
                    val c = cats.firstOrNull { it.id == b.categoryId } ?: return@forEach
                    BudgetLine(c.name, byCat[c.id] ?: 0, b.amount)
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
    val tops = cats.filter { it.type == TxType.EXPENSE && it.parentId == null && !it.hidden }

    PageScaffold("월 예산", onBack = nav::pop) {
        LazyColumn {
            item {
                val b = budgets.firstOrNull { it.categoryId == null }
                ListRow("전체 예산", "한 달에 쓸 돈 전체", trailing = { Text(b?.let { won(it.amount) } ?: "설정 안 함", color = if (b == null) pal.faint else pal.text) }) {
                    editing = null to b
                }
                GroupLabel("카테고리별 예산 (선택)")
            }
            items(tops, key = { it.id }) { c ->
                val b = budgets.firstOrNull { it.categoryId == c.id }
                ListRow(c.name, trailing = { Text(b?.let { won(it.amount) } ?: "-", color = if (b == null) pal.faint else pal.text) }) {
                    editing = c to b
                }
            }
        }
    }
    editing?.let { (c, b) ->
        InputDialog(
            title = "${c?.name ?: "전체"} 예산",
            initial = b?.amount?.toString().orEmpty(),
            hint = "금액 (비우면 삭제)",
            keyboard = KeyboardType.Number,
            onDismiss = { editing = null },
        ) { v ->
            val amount = v.filter(Char::isDigit).toLongOrNull()
            app.scope.launch {
                when {
                    amount == null || amount == 0L -> b?.let { dao.delete(it) }
                    else -> dao.upsert(b?.copy(amount = amount) ?: Budget(categoryId = c?.id, amount = amount))
                }
            }
            editing = null
        }
    }
}
