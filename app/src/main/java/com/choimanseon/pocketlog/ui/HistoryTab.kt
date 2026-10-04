package com.choimanseon.pocketlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.Period
import com.choimanseon.pocketlog.domain.copyNow
import com.choimanseon.pocketlog.domain.countable
import com.choimanseon.pocketlog.domain.dailyExpense
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.shortWon
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.total
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val typeFilters = listOf<TxType?>(null, TxType.EXPENSE, TxType.INCOME, TxType.TRANSFER)

@Composable
fun HistoryTab(nav: Nav) {
    val settings by app.prefs.version.collectAsState()
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val period = remember(settings, offset) { monthPeriod(today, app.prefs.monthStartDay).shiftMonths(offset.toLong()) }
    val dao = app.dao
    val txs by rememberFlow(emptyList(), period) { dao.txBetween(period.startMillis, period.endMillis) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }
    val shown = txs.filter { typeFilters[filter] == null || it.type == typeFilters[filter] }

    // 다중 선택 (기획서 §4.4): long-press a row, then change the category of or delete them all
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var pickCategory by remember { mutableStateOf<TxType?>(null) }
    val selecting = selected.isNotEmpty()
    LaunchedEffect(offset, filter, calendar, day) { selected = emptySet() }
    BackHandler(selecting) { selected = emptySet() }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun move(months: Int) { offset += months; day = null }
    fun deleteSelected() {
        val ids = selected.toList()
        selected = emptySet()
        app.scope.launch { ids.forEach { app.dao.softDelete(it) } }
        nav.undo("${ids.size}건을 삭제했어요") { ids.forEach { app.dao.undoDelete(it) } }
    }
    fun recategorize(categoryId: Long?) {
        val chosen = txs.filter { it.id in selected }
        selected = emptySet()
        // not learned as merchant rules: a batch is usually about the occasion (a trip), not the merchants
        app.scope.launch { chosen.forEach { app.dao.setCategory(it.id, categoryId) } }
        nav.undo("${chosen.size}건의 카테고리를 바꿨어요") { chosen.forEach { app.dao.setCategory(it.id, it.categoryId) } }
    }

    // swipe left/right anywhere a row doesn't take the drag itself (rows swipe to delete / duplicate)
    val swipe = with(LocalDensity.current) { 72.dp.toPx() }
    Column(Modifier.fillMaxSize().statusBarsPadding().pointerInput(selecting) {
        if (selecting) return@pointerInput
        var dx = 0f
        detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (kotlin.math.abs(dx) > swipe) move(if (dx < 0) 1 else -1) }) { _, d -> dx += d }
    }) {
        if (selecting) Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "선택 취소") }
            Text("${selected.size}건 선택", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val types = txs.filter { it.id in selected }.map { it.type }.toSet()
                if (types.size == 1 && TxType.TRANSFER !in types) pickCategory = types.single()
                else nav.toast(if (TxType.TRANSFER in types) "이체는 카테고리가 없어요" else "지출과 수입은 따로 골라 주세요")
            }) { Text("카테고리") }
            TextButton(onClick = ::deleteSelected) { Text("삭제", color = pal.danger) }
        } else Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PeriodSwitcher(period.label(today), { move(-1) }, { move(1) }, Modifier.weight(1f))
            IconButton(onClick = { nav.push(Screen.Search) }) { Icon(Icons.Rounded.Search, "검색") }
            IconButton(onClick = { calendar = !calendar }) {
                Icon(if (calendar) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.CalendarMonth, if (calendar) "리스트로 보기" else "달력으로 보기")
            }
        }
        val spent = total(txs, TxType.EXPENSE)
        val income = total(txs, TxType.INCOME)
        Text(
            "지출 ${num(spent)} · 수입 ${num(income)} · 남은 돈 ${num(income - spent)}",
            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp),
        )
        PillTabs(listOf("전체", "지출", "수입", "이체"), filter, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { filter = it }

        if (calendar) {
            val daily = remember(txs) { dailyExpense(txs) }
            LazyColumn(Modifier.weight(1f)) {
                item { Calendar(period, daily, day, DayOfWeek.of(app.prefs.weekStart)) { day = if (day == it) null else it } }
                val list = shown.filter { day == null || it.occurredAt.toLocalDate() == day }
                if (day != null) item { DayHeader(day!!, list) }
                items(list, key = { it.id }) { tx -> SwipeTxRow(tx, catMap, payMap, nav, day == null, tx.id in selected, selecting) { toggle(tx.id) } }
            }
        } else {
            val groups = remember(shown) { shown.groupBy { it.occurredAt.toLocalDate() }.toSortedMap(compareByDescending { it }) }
            if (groups.isEmpty()) EmptyState(Icons.Rounded.Inbox, "이 기간에는 내역이 없어요", "+ 버튼으로 기록해 보세요")
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                groups.forEach { (date, list) ->
                    item(key = "h$date") { DayHeader(date, list) }
                    items(list, key = { it.id }) { tx -> SwipeTxRow(tx, catMap, payMap, nav, false, tx.id in selected, selecting) { toggle(tx.id) } }
                }
            }
        }
    }
    pickCategory?.let { type -> CategoryPickerSheet(type, cats, null, onDismiss = { pickCategory = null }) { pickCategory = null; recategorize(it) } }
}

@Composable
private fun DayHeader(date: LocalDate, list: List<Tx>) {
    val spent = list.filter { it.countable() && it.type == TxType.EXPENSE }.sumOf { it.amount }
    val income = list.filter { it.countable() && it.type == TxType.INCOME }.sumOf { it.amount }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
        Text("${date.monthValue}월 ${date.dayOfMonth}일 ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}요일", style = MaterialTheme.typography.labelMedium, color = pal.sub, modifier = Modifier.weight(1f))
        if (income > 0) Text("+${num(income)}  ", style = MaterialTheme.typography.labelMedium, color = pal.income)
        if (spent != 0L) Text("-${num(spent)}", style = MaterialTheme.typography.labelMedium, color = pal.sub)
    }
}

/** Swipe left to delete (with undo), right to duplicate as a new entry right now. Long-press starts selecting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeTxRow(
    tx: Tx, cats: Map<Long, Category>, pays: Map<Long, PayMethod>, nav: Nav,
    showDate: Boolean, selected: Boolean, selecting: Boolean, onSelect: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        gesturesEnabled = !selecting,
        backgroundContent = {
            val deleting = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (deleting) pal.danger else pal.brand).padding(horizontal = 24.dp),
                contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Text(if (deleting) "삭제" else "복제", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge) }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    app.scope.launch { app.dao.softDelete(tx.id) }
                    nav.undo(if (tx.installmentMonths > 1) "할부 ${tx.installmentMonths}개월을 모두 삭제했어요" else "삭제했어요") { app.dao.undoDelete(tx.id) }
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    app.scope.launch { app.dao.insert(tx.copyNow()) }
                    nav.toast("지금 시간으로 복제했어요")
                }
                else -> Unit
            }
            scope.launch { state.snapTo(SwipeToDismissBoxValue.Settled) }
        },
    ) {
        TxRow(tx, tx.categoryId?.let { cats[it] }, pays, showDate, selected, onLongClick = onSelect) {
            if (selecting) onSelect() else nav.push(Screen.Detail(tx.id))
        }
    }
}

@Composable
private fun Calendar(period: Period, daily: Map<LocalDate, Long>, selected: LocalDate?, weekStart: DayOfWeek, onPick: (LocalDate) -> Unit) {
    val max = daily.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val first = period.start.minusDays(((period.start.dayOfWeek.value - weekStart.value + 7) % 7).toLong())
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(first, period.end) + 6) / 7).toInt()
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row {
            (0 until 7).forEach { i ->
                val dow = weekStart.plus(i.toLong())
                Text(
                    dow.getDisplayName(TextStyle.SHORT, Locale.KOREAN), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = if (dow == DayOfWeek.SUNDAY) pal.danger else pal.sub,
                )
            }
        }
        repeat(weeks) { w ->
            Row {
                (0 until 7).forEach { d ->
                    val date = first.plusDays((w * 7 + d).toLong())
                    val inPeriod = date in period
                    val spent = daily[date] ?: 0L
                    val intensity = if (spent > 0) 0.08f + 0.5f * spent / max else 0f
                    Column(
                        Modifier.weight(1f).height(56.dp).padding(2.dp).clip(RoundedCornerShape(10.dp))
                            .background(pal.brand.copy(alpha = intensity))
                            .then(if (date == selected) Modifier.border(2.dp, pal.brand, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable(enabled = inPeriod) { onPick(date) }
                            .padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${date.dayOfMonth}", style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
                            color = if (inPeriod) pal.text else pal.faint.copy(alpha = 0.4f),
                        )
                        if (spent > 0 && inPeriod) Text(shortWon(spent), style = MaterialTheme.typography.labelSmall, color = pal.sub, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: Nav) {
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<Long?>(null) }
    var min by rememberSaveable { mutableStateOf("") }
    var max by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val dao = app.dao
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val results by rememberFlow(emptyList(), q, cat, min, max) { dao.search(q.trim(), cat, min.toLongOrNull(), max.toLongOrNull()) }
    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }

    PageScaffold("검색", onBack = nav::pop) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("가맹점, 메모") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(14.dp),
        )
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(cat?.let { catMap[it]?.name } ?: "카테고리 전체", selected = cat != null) { picking = true }
            OutlinedTextField(
                value = min, onValueChange = { min = it.filter(Char::isDigit) }, singleLine = true, placeholder = { Text("최소 금액") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = max, onValueChange = { max = it.filter(Char::isDigit) }, singleLine = true, placeholder = { Text("최대 금액") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(120.dp),
            )
        }
        if (q.isBlank() && cat == null && min.isBlank() && max.isBlank()) EmptyState(Icons.Rounded.Search, "가맹점이나 메모로 찾아보세요", "전체 기간에서 찾아요")
        else LazyColumn(Modifier.weight(1f)) {
            item { Text("${results.size}건", style = MaterialTheme.typography.labelMedium, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            items(results, key = { it.id }) { tx ->
                TxRow(tx, tx.categoryId?.let { catMap[it] }, payMap, showDate = true) { nav.push(Screen.Detail(tx.id)) }
            }
        }
    }
    if (picking) CategoryPickerSheet(TxType.EXPENSE, cats, cat, onDismiss = { picking = false }) { cat = it; picking = false }
}
