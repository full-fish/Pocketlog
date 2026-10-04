package com.choimanseon.pocketlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.AutoInput
import com.choimanseon.pocketlog.auto.Categorizer
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.RawStatus
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.autoInstallmentMemo
import com.choimanseon.pocketlog.domain.evalExpr
import com.choimanseon.pocketlog.domain.num
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

// 이체 only appears when editing an old transfer: moving my own money isn't recorded (TODO #7)
private val allTypes = listOf(TxType.EXPENSE, TxType.INCOME, TxType.TRANSFER)

/** "12000+3000" → "12,000 + 3,000" */
private fun prettyExpr(expr: String) = Regex("""\d+|[+−×÷]""").findAll(expr).joinToString(" ") { m ->
    m.value.toLongOrNull()?.let(::num) ?: m.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(entry: Entry, nav: Nav, onScan: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = pal.bg,
        dragHandle = null,
    ) { EntryForm(entry, nav, onScan, onDismiss) }
}

/** The sheet's content, separate so it can be rendered on its own (ScreenshotTest). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun EntryForm(entry: Entry, nav: Nav, onScan: () -> Unit, onDismiss: () -> Unit) {
    val dao = app.dao
    val zone = ZoneId.systemDefault()
    val focus = LocalFocusManager.current
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }

    var original by remember { mutableStateOf<Tx?>(null) } // month 1 of an installment plan
    var planMonths by remember { mutableIntStateOf(0) } // rows the original purchase has
    var type by remember { mutableStateOf(entry.type) }
    var expr by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var payId by remember { mutableStateOf<Long?>(null) }
    var toPayId by remember { mutableStateOf<Long?>(null) }
    var installment by remember { mutableIntStateOf(0) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var pickedCategory by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var picker by remember { mutableStateOf<String?>(null) } // cat | pay | to | date | time | installment

    LaunchedEffect(entry) {
        // an installment month opens as its whole purchase: month 1's date, the full price
        val rows = entry.editId?.let { dao.purchaseRows(it) }.orEmpty()
        val src = rows.firstOrNull()?.copy(amount = rows.sumOf { it.amount }) ?: entry.prefill
        if (src != null) {
            if (entry.editId != null) { original = rows.first(); planMonths = rows.size }
            type = src.type
            expr = if (src.amount != 0L) abs(src.amount).toString() else ""
            merchant = src.merchant
            memo = src.memo.takeUnless { autoInstallmentMemo.matches(it) }.orEmpty()
            categoryId = src.categoryId
            payId = src.paymentMethodId
            toPayId = src.toPaymentMethodId
            installment = src.installmentMonths
            val dt = Instant.ofEpochMilli(src.occurredAt).atZone(zone)
            date = dt.toLocalDate()
            time = dt.toLocalTime().withSecond(0).withNano(0)
        } else {
            payId = dao.recentTx(30).first().firstOrNull { it.source == TxSource.MANUAL && it.paymentMethodId != null }?.paymentMethodId
        }
    }
    LaunchedEffect(merchant, typing) {
        if (!typing || merchant.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(150)
        suggestions = dao.merchantsLike(merchant.trim()).filter { it != merchant }
    }

    suspend fun fillFromMerchant(m: String) {
        val last = dao.lastWithMerchant(m)
        if (!pickedCategory) categoryId = last?.categoryId ?: Categorizer.categorize(m, type) ?: categoryId
        if (payId == null) payId = last?.paymentMethodId
    }
    val scope = rememberCoroutineScope()

    fun save() {
        val amount = evalExpr(expr) ?: 0L
        if (amount <= 0) { nav.toast("금액을 입력해 주세요"); return }
        if (type == TxType.TRANSFER && (payId == null || toPayId == null)) { nav.toast("보내는 곳과 받는 곳을 골라 주세요"); return }
        val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        val o = original
        val prefill = entry.prefill
        app.scope.launch {
            val base = o ?: prefill ?: Tx(amount = 0, occurredAt = at)
            val tx = base.copy(
                type = type,
                amount = if (o != null && o.amount < 0 && type == TxType.EXPENSE) -amount else amount,
                occurredAt = at,
                merchant = merchant.trim(),
                memo = memo.trim(),
                categoryId = if (type == TxType.TRANSFER) null else categoryId,
                paymentMethodId = payId,
                toPaymentMethodId = if (type == TxType.TRANSFER) toPayId else null,
                installmentMonths = if (type == TxType.EXPENSE) installment else 0,
                status = if (base.status == TxStatus.PENDING_REVIEW) TxStatus.CONFIRMED else base.status,
                updatedAt = System.currentTimeMillis(),
            )
            if (o == null) {
                val id = dao.insertPurchase(tx.copy(id = 0))
                prefill?.rawMessageId?.let { rid -> dao.rawMessage(rid).first()?.let { dao.update(it.copy(status = RawStatus.PARSED, txId = id)) } }
            } else if (planMonths > 1 || tx.installmentMonths != o.installmentMonths) dao.replacePurchase(tx)
            else dao.update(tx.copy(memo = tx.memo.ifBlank { o.memo.takeIf { autoInstallmentMemo.matches(it) }.orEmpty() }))
            if (pickedCategory && type != TxType.TRANSFER) Categorizer.learn(merchant, categoryId)
            if (type == TxType.EXPENSE) AutoInput.checkBudget()
        }
        nav.toast(if (o != null) "수정했어요" else "기록했어요")
        onDismiss()
    }

    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }
    val cat = categoryId?.let { catMap[it] }

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val types = if (type == TxType.TRANSFER) allTypes else allTypes.dropLast(1)
            PillTabs(listOf("지출", "수입", "이체").take(types.size), types.indexOf(type), Modifier.weight(1f)) {
                if (type != types[it]) { type = types[it]; if (!pickedCategory) categoryId = null }
            }
            if (original == null) TextButton(onClick = onScan) { Icon(Icons.Rounded.PhotoCamera, null, Modifier.padding(end = 4.dp).size(18.dp)); Text("스샷") }
        }

        // amount
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp).clickable { focus.clearFocus(); typing = false },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val value = evalExpr(expr)
            Text(
                if (expr.isEmpty()) "0원" else prettyExpr(expr) + if (expr.any { it in "+−×÷" }) "" else "원",
                style = MaterialTheme.typography.displaySmall, color = if (expr.isEmpty()) pal.faint else pal.text,
                textAlign = TextAlign.Center, maxLines = 2,
            )
            if (expr.any { it in "+−×÷" } && value != null) Text("= ${num(value)}원", style = MaterialTheme.typography.titleSmall, color = pal.brand)
        }

        // merchant + suggestions
        Field(merchant, { merchant = it }, if (type == TxType.INCOME) "어디서 받았나요" else "어디에 썼나요", Modifier.onFocusChanged {
            typing = it.isFocused
            if (!it.isFocused && merchant.isNotBlank()) scope.launch { fillFromMerchant(merchant.trim()) }
        })
        if (suggestions.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s -> Chip(s) { merchant = s; suggestions = emptyList(); scope.launch { fillFromMerchant(s) } } }
        }

        FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (type == TxType.TRANSFER) {
                Chip("보내는 곳: ${payId?.let { payMap[it]?.name } ?: "선택"}", payId != null) { picker = "pay" }
                Chip("받는 곳: ${toPayId?.let { payMap[it]?.name } ?: "선택"}", toPayId != null) { picker = "to" }
            } else {
                val parent = cat?.parentId?.let { catMap[it] }
                Chip(cat?.let { "${parent?.let { p -> "${p.name} › " } ?: ""}${it.name}" } ?: "카테고리", cat != null) { picker = "cat" }
                Chip(payId?.let { payMap[it]?.name } ?: "결제수단", payId != null) { picker = "pay" }
                if (type == TxType.EXPENSE && payId?.let { payMap[it]?.kind } == PayKind.CREDIT) {
                    Chip(if (installment == 0) "일시불" else "${installment}개월", installment > 0) { picker = "installment" }
                }
            }
            val today = LocalDate.now()
            val dayLabel = when (date) {
                today -> "오늘"
                today.minusDays(1) -> "어제"
                else -> "${date.monthValue}월 ${date.dayOfMonth}일"
            }
            Chip("$dayLabel ${time.format(timeFmt)}", date != today) { picker = "date" }
        }
        Field(memo, { memo = it }, "메모", Modifier.onFocusChanged { if (it.isFocused) typing = false })

        if (!typing) Keypad(
            onKey = { k ->
                expr = when (k) {
                    "⌫" -> expr.dropLast(1)
                    "+", "−", "×", "÷" -> if (expr.isEmpty()) expr else if (expr.last() in "+−×÷") expr.dropLast(1) + k else expr + k
                    else -> if (expr.length >= 24 || (expr.isEmpty() && k.startsWith("0"))) expr else expr + k
                }
            },
            onClear = { expr = "" },
        )
        PrimaryButton(if (original != null) "수정하기" else "저장하기", ::save, Modifier.padding(top = 12.dp))
    }

    when (picker) {
        "cat" -> CategoryPickerSheet(type, cats, categoryId, onDismiss = { picker = null }, onManage = { picker = null; onDismiss(); nav.push(Screen.Categories) }) {
            categoryId = it; pickedCategory = true; picker = null
        }
        "pay" -> PayPickerSheet(pays, payId, if (type == TxType.TRANSFER) "보내는 곳" else "결제수단", onDismiss = { picker = null },
            onManage = { picker = null; onDismiss(); nav.push(Screen.PayMethods) }) { payId = it; picker = null }
        "to" -> PayPickerSheet(pays, toPayId, "받는 곳", onDismiss = { picker = null }) { toPayId = it; picker = null }
        "installment" -> ChoiceDialog("할부", listOf("일시불") + (2..12).map { "${it}개월" }, if (installment == 0) 0 else installment - 1, { picker = null }) {
            installment = if (it == 0) 0 else it + 1; picker = null
        }
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        picker = "time"
                    }) { Text("다음") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("취소") } },
            ) { DatePicker(state) }
        }
        "time" -> {
            val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picker = null },
                confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); picker = null }) { Text("확인") } },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("건너뛰기") } },
                text = { TimePicker(state) },
            )
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = pal.text),
        cursorBrush = SolidColor(pal.brand),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = pal.faint)
            inner()
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Keypad(onKey: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("+", "−", "×", "÷", "000").forEach { k ->
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(pal.surface).clickable { onKey(k) },
                    contentAlignment = Alignment.Center,
                ) { Text(k, style = MaterialTheme.typography.titleMedium, color = if (k == "000") pal.text else pal.brand) }
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫")).forEach { row ->
            Row {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { onKey(k) }, onLongClick = { if (k == "⌫") onClear() }),
                        contentAlignment = Alignment.Center,
                    ) { Text(k, fontSize = 24.sp, color = pal.text) }
                }
            }
        }
    }
}
