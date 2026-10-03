package com.choimanseon.pocketlog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.signedAmount
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Collect a DAO flow; [keys] decide when to re-query (e.g. the period). */
@Composable
fun <T> rememberFlow(initial: T, vararg keys: Any?, flow: () -> Flow<T>): State<T> =
    remember(*keys) { flow() }.collectAsStateWithLifecycle(initial)

val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
fun Long.fmt(f: DateTimeFormatter): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(f)

// ---------------------------------------------------------------- layout

@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로") }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            actions()
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        bottom?.invoke()
    }
}

@Composable
fun PCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pal.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = pal.sub) }
    }
}

@Composable
fun EmptyState(emoji: String, title: String, body: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodySmall, color = pal.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = pal.brand) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = pal.text) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(pal.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = color) }
}

@Composable
fun Chip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(if (selected) pal.brandSoft else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(1.dp, pal.surface2, shape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) pal.brand else pal.text, maxLines = 1) }
}

/** Segmented pill tabs, e.g. 전체 / 지출 / 수입 / 이체. */
@Composable
fun PillTabs(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) pal.bg else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) pal.text else pal.sub)
            }
        }
    }
}

@Composable
fun PeriodSwitcher(label: String, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, "이전") }
        Text(label, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext) { Icon(Icons.Rounded.ChevronRight, "다음") }
    }
}

@Composable
fun ListRow(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = pal.sub)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) =
    ListRow(title, subtitle, trailing = { Switch(checked = checked, onCheckedChange = onChange) }, onClick = { onChange(!checked) })

@Composable
fun GroupLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = pal.sub, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp))

// ---------------------------------------------------------------- money rows

@Composable
fun CategoryIcon(c: Category?, size: Dp = 40.dp, selected: Boolean = false) {
    val color = c?.let { Color(it.color) } ?: pal.faint
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = if (pal.dark) 0.28f else 0.16f))
            .then(if (selected) Modifier.border(2.dp, pal.brand, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(c?.emoji ?: "🧾", fontSize = (size.value * 0.46f).sp) }
}

fun payIcon(kind: PayKind?): ImageVector = when (kind) {
    PayKind.CREDIT, PayKind.CHECK -> Icons.Rounded.CreditCard
    PayKind.BANK -> Icons.Rounded.AccountBalance
    PayKind.PAY_MONEY -> Icons.Rounded.AccountBalanceWallet
    PayKind.CASH, null -> Icons.Rounded.Payments
}

fun sourceLabel(s: TxSource) = when (s) {
    TxSource.SMS -> "문자"
    TxSource.PUSH -> "알림"
    TxSource.SCREENSHOT -> "스샷"
    TxSource.RECEIPT -> "영수증"
    TxSource.VOICE -> "말로"
    TxSource.IMPORT, TxSource.MANUAL -> null // imported history would put a tag on thousands of rows
}

@Composable
fun Tag(text: String, color: Color = pal.sub, bg: Color = pal.surface) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun amountColor(tx: Tx): Color = when {
    tx.status == TxStatus.CANCELED -> pal.faint
    tx.type == TxType.INCOME || (tx.type == TxType.EXPENSE && tx.amount < 0) -> pal.income
    tx.type == TxType.TRANSFER -> pal.sub
    else -> pal.text
}

@Composable
fun TxRow(
    tx: Tx, cat: Category?, pays: Map<Long, PayMethod>, showDate: Boolean = false,
    selected: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val pay = tx.paymentMethodId?.let { pays[it] }
    val sub = buildList {
        add(tx.occurredAt.fmt(if (showDate) DateTimeFormatter.ofPattern("M/d HH:mm") else timeFmt))
        if (tx.type == TxType.TRANSFER) add("${pay?.name ?: "?"} → ${tx.toPaymentMethodId?.let { pays[it]?.name } ?: "?"}")
        else pay?.let { add(it.name) }
        if (tx.memo.isNotBlank()) add(tx.memo)
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().background(if (selected) pal.brandSoft else pal.bg)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(pal.brand), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, "선택됨", tint = Color.White) }
        } else if (tx.type == TxType.TRANSFER) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(pal.surface), contentAlignment = Alignment.Center) { Text("↔", fontSize = 18.sp, color = pal.sub) }
        } else CategoryIcon(cat)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tx.merchant.ifBlank { cat?.name ?: if (tx.type == TxType.TRANSFER) "이체" else "내역" },
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                sourceLabel(tx.source)?.let { Tag(it) }
                if (tx.status == TxStatus.PENDING_REVIEW) Tag("확인 필요", pal.warn, pal.warn.copy(alpha = 0.12f))
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = pal.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            signedAmount(tx) + if (tx.originalAmount != null && tx.amount == 0L) " (${tx.originalAmount})" else "",
            style = MaterialTheme.typography.titleSmall, color = amountColor(tx),
            textDecoration = if (tx.status == TxStatus.CANCELED) TextDecoration.LineThrough else null,
        )
    }
}

// ---------------------------------------------------------------- pickers

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerSheet(type: TxType, categories: List<Category>, selected: Long?, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val tops = categories.filter { it.type == type && it.parentId == null && !it.hidden }
    var open by remember { mutableStateOf(categories.firstOrNull { it.id == selected }?.let { it.parentId ?: it.id }) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.bg) {
        Text("카테고리", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = 380.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(tops) { c ->
                val hasChildren = categories.any { it.parentId == c.id && !it.hidden }
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { if (hasChildren) open = c.id else onPick(c.id) }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CategoryIcon(c, 48.dp, selected = c.id == selected || c.id == open)
                    Text(c.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        open?.let { pid ->
            val parent = categories.firstOrNull { it.id == pid }
            val subs = categories.filter { it.parentId == pid && !it.hidden }
            if (subs.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("${parent?.name} 전체", selected = selected == pid) { onPick(pid) }
                subs.forEach { s -> Chip(s.name, selected = selected == s.id) { onPick(s.id) } }
            }
        }
        TextButton(onClick = { onPick(null) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("카테고리 없음", color = pal.sub) }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayPickerSheet(pays: List<PayMethod>, selected: Long?, title: String = "결제수단", onDismiss: () -> Unit, onManage: (() -> Unit)? = null, onPick: (Long?) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pal.bg) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
            pays.filter { !it.hidden }.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(payIcon(p.kind), null, tint = pal.sub)
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 14.dp))
                    if (p.id == selected) Icon(Icons.Rounded.Check, null, tint = pal.brand)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(horizontal = 24.dp, vertical = 14.dp)) {
                Text("선택 안 함", color = pal.sub)
            }
        }
        if (onManage != null) TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 12.dp)) { Text("결제수단 추가·관리") }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun InputDialog(
    title: String,
    initial: String = "",
    hint: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    message: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true, placeholder = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "확인", danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) pal.danger else pal.brand) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Text(o)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

// ---------------------------------------------------------------- charts

@Composable
fun Donut(slices: List<Pair<Color, Long>>, modifier: Modifier = Modifier, thickness: Dp = 22.dp, center: @Composable () -> Unit = {}) {
    val total = slices.sumOf { it.second.coerceAtLeast(0) }.coerceAtLeast(1).toFloat()
    val track = pal.surface2
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slices) { progress.snapTo(0f); progress.animateTo(1f, tween(700)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            var start = -90f
            val gap = if (slices.size > 1) 1.2f else 0f
            slices.forEach { (color, v) ->
                val sweep = 360f * v.coerceAtLeast(0) / total * progress.value
                if (sweep > gap) drawArc(color, start + gap / 2, sweep - gap, false, topLeft, arc, style = Stroke(stroke))
                start += sweep
            }
        }
        center()
    }
}

@Composable
fun Bars(values: List<Long>, labels: List<String>, highlight: Int, budget: Long?, modifier: Modifier = Modifier) {
    val brand = pal.brand
    val track = pal.surface2
    val line = pal.danger
    val max = maxOf(values.maxOrNull() ?: 0L, budget ?: 0L).coerceAtLeast(1L).toFloat()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(values) { anim.snapTo(0f); anim.animateTo(1f, tween(600)) }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / values.size.coerceAtLeast(1)
            val barW = minOf(slot * 0.5f, 32.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = (size.height * (v.coerceAtLeast(0) / max) * anim.value).coerceAtLeast(4.dp.toPx())
                drawRoundRect(
                    if (i == highlight) brand else track,
                    topLeft = Offset(slot * i + (slot - barW) / 2, size.height - h),
                    size = Size(barW, h), cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
            if (budget != null && budget > 0) {
                val y = size.height * (1 - budget / max)
                drawLine(line.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    l, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = if (i == highlight) pal.text else pal.sub, fontWeight = if (i == highlight) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val color = when {
        fraction >= 1f -> pal.danger
        fraction >= 0.9f -> pal.warn
        fraction >= 0.7f -> pal.warn.copy(alpha = 0.8f)
        else -> pal.income
    }
    Box(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(pal.surface2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(color))
    }
}
