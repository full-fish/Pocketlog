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
import androidx.compose.material.icons.rounded.SwapHoriz
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.choimanseon.pocketlog.domain.shortWon
import com.choimanseon.pocketlog.domain.toLocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.choimanseon.pocketlog.auto.Pick
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.signedAmount
import com.choimanseon.pocketlog.domain.tops
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Collect a DAO flow; [keys] decide when to re-query (e.g. the period). */
@Composable
fun <T> rememberFlow(initial: T, vararg keys: Any?, flow: () -> Flow<T>): State<T> =
    remember(*keys) { flow() }.collectAsStateWithLifecycle(initial)

val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayTimeFmt = DateTimeFormatter.ofPattern("M/d HH:mm")
private val yearDayTimeFmt = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
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
fun EmptyState(icon: ImageVector, title: String, body: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint = pal.faint)
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
fun Chip(
    text: String, selected: Boolean = false, modifier: Modifier = Modifier,
    pinned: Boolean = false, // remembered as a rule: gold instead of the brand color
    onHold: (() -> Unit)? = null, // a 0.8 s press, so a tap that lingers doesn't make a rule
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val haptic = LocalHapticFeedback.current
    val config = LocalViewConfiguration.current
    val slow = remember(config) { object : ViewConfiguration by config { override val longPressTimeoutMillis = 800L } }
    CompositionLocalProvider(LocalViewConfiguration provides slow) {
        Box(
            modifier.clip(shape).background(if (!selected) Color.Transparent else if (pinned) pal.gold else pal.brand)
                .then(if (selected) Modifier else Modifier.border(1.dp, if (pinned) pal.gold else pal.surface2, shape))
                .combinedClickable(onLongClick = onHold?.let { { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it() } }, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) { Text(text, style = MaterialTheme.typography.labelMedium, color = if (!selected) pal.text else if (pinned) Color.Black.copy(alpha = 0.85f) else Color.White, maxLines = 1) }
    }
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

/** ‹ label › ; tapping the label opens a picker when [onLabel] is given. No arrows when there is nothing to move to. */
@Composable
fun PeriodSwitcher(label: String, onPrev: (() -> Unit)?, onNext: (() -> Unit)?, modifier: Modifier = Modifier, onLabel: (() -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (onPrev != null) IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, "이전") } else Spacer(Modifier.width(16.dp))
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).then(if (onLabel != null) Modifier.clickable(onClick = onLabel) else Modifier).padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (onLabel != null) Icon(Icons.Rounded.ArrowDropDown, "기간 고르기", tint = pal.sub)
        }
        if (onNext != null) IconButton(onClick = onNext) { Icon(Icons.Rounded.ChevronRight, "다음") }
    }
}

/** Year with arrows over a 4 × 3 grid of months. */
@Composable
fun MonthPickerDialog(current: YearMonth, onDismiss: () -> Unit, onPick: (YearMonth) -> Unit) {
    var year by remember { mutableIntStateOf(current.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) { Icon(Icons.Rounded.ChevronLeft, "이전 해") }
                Text("${year}년", Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { year++ }) { Icon(Icons.Rounded.ChevronRight, "다음 해") }
            }
        },
        text = {
            Column {
                (0 until 3).forEach { r ->
                    Row {
                        (1..4).forEach { c ->
                            val ym = YearMonth.of(year, r * 4 + c)
                            val on = ym == current
                            Box(
                                Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(12.dp)).background(if (on) pal.brand else pal.surface)
                                    .clickable { onPick(ym) }.padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("${ym.monthValue}월", style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else pal.text) }
                        }
                    }
                }
            }
        },
    )
}

/** Cells of equal width that use the whole row, so both side margins match whatever the screen width. */
@Composable
fun EvenGrid(minCell: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val cols = (constraints.maxWidth / minCell.roundToPx()).coerceAtLeast(1)
        val cell = constraints.maxWidth / cols
        val placeables = measurables.map { it.measure(Constraints(maxWidth = cell)) }
        val rows = placeables.chunked(cols)
        val heights = rows.map { r -> r.maxOf { it.height } }
        val margin = (constraints.maxWidth - cell * cols) / 2
        layout(constraints.maxWidth, heights.sum()) {
            var y = 0
            rows.forEachIndexed { i, r ->
                r.forEachIndexed { j, p -> p.placeRelative(margin + cell * j + (cell - p.width) / 2, y) }
                y += heights[i]
            }
        }
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
    ) { Icon(categoryIcon(c?.icon), c?.name, Modifier.size(size * 0.5f), tint = c?.let { Color(it.color) } ?: pal.sub) }
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
    TxSource.DUMMY -> "더미"
    TxSource.REPEAT -> "반복"
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
    tx.type == TxType.SAVING -> pal.brand
    else -> pal.text
}

@Composable
fun TxRow(
    tx: Tx, cat: Category?, pays: Map<Long, PayMethod>, showDate: Boolean = false,
    selected: Boolean = false, onLongClick: (() -> Unit)? = null,
    tags: List<Category>? = null, // 내역: the category and tags beside the amount
    rule: Pick? = null, // the merchant's rule: the category and tags it sets get a gold ring
    onClick: () -> Unit,
) {
    val pay = tx.paymentMethodId?.let { pays[it] }
    val sub = buildList {
        // a search reaches back years: a row from another year says which
        add(tx.occurredAt.fmt(if (!showDate) timeFmt else if (tx.occurredAt.toLocalDate().year == LocalDate.now().year) dayTimeFmt else yearDayTimeFmt))
        if (tx.type == TxType.TRANSFER) add("${pay?.name ?: "?"} → ${tx.toPaymentMethodId?.let { pays[it]?.name } ?: "?"}")
        else pay?.let { add(it.name) }
        if (tx.memo.isNotBlank()) add(tx.memo)
        if (tx.note.isNotBlank()) add(tx.note)
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().background(if (selected) pal.brandSoft else pal.bg)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(pal.brand), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, "선택됨", tint = Color.White) }
        } else if (tx.type == TxType.TRANSFER) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(pal.surface), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SwapHoriz, "이체", tint = pal.sub) }
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
        if (tags != null && tx.type != TxType.TRANSFER && (cat != null || tags.isNotEmpty())) {
            val ruled = rule != null && rule.category == cat?.id
            Column(Modifier.padding(start = 8.dp).widthIn(max = 112.dp), horizontalAlignment = Alignment.End) {
                cat?.let { Label(it.name, ruled) }
                // two tags fit; the rest as a count
                if (tags.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    tags.take(2).forEach { Label("#${it.name}", ruled && it.id in rule!!.tags, Modifier.weight(1f, fill = false)) }
                    if (tags.size > 2) Text("+${tags.size - 2}", style = MaterialTheme.typography.labelSmall, color = pal.faint)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            signedAmount(tx) + if (tx.originalAmount != null && tx.amount == 0L) " (${tx.originalAmount})" else "",
            style = MaterialTheme.typography.titleSmall, color = amountColor(tx),
            textDecoration = if (tx.status == TxStatus.CANCELED) TextDecoration.LineThrough else null,
        )
    }
}

/** A category or tag beside an amount; [ruled] = what the merchant's rule sets, in a gold ring like its chip. */
@Composable
private fun Label(text: String, ruled: Boolean, modifier: Modifier = Modifier) = Text(
    text, style = MaterialTheme.typography.labelSmall, color = pal.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
    modifier = modifier.then(if (ruled) Modifier.border(1.dp, pal.gold, RoundedCornerShape(50)) else Modifier).padding(horizontal = 6.dp, vertical = 1.dp),
)

// ---------------------------------------------------------------- pickers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    type: TxType, categories: List<Category>, selected: Long?, onDismiss: () -> Unit,
    noneLabel: String = "카테고리 없음", onManage: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null, // below the grid, e.g. the tags on the screenshot review screen
    onPick: (Long?) -> Unit,
) {
    val tops = categories.tops(type).filter { !it.hidden }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.bg) {
        Text("카테고리", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyVerticalGrid(
            // with something below, three rows and a scroll, so the sheet still fits a small phone
            columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = if (extra != null) 288.dp else 380.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(tops) { c ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(c.id) }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CategoryIcon(c, 48.dp, selected = c.id == selected)
                    Text(c.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { onPick(null) }) { Text(noneLabel, color = pal.sub) }
            Spacer(Modifier.weight(1f))
            if (onManage != null) TextButton(onClick = onManage) { Text("카테고리 추가·관리") }
        }
        extra?.invoke(this)
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayPickerSheet(
    pays: List<PayMethod>, selected: Long?, title: String = "결제수단", onDismiss: () -> Unit,
    noneLabel: String = "선택 안 함", onManage: (() -> Unit)? = null, onPick: (Long?) -> Unit,
) {
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
                Text(noneLabel, color = pal.sub)
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
    singleLine: Boolean = true, // false: a few lines, e.g. a 메모
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
                    value = value, onValueChange = { value = it }, singleLine = singleLine, maxLines = if (singleLine) 1 else 5, placeholder = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** The "+ 메모" chip, or its first line cut short once written. */
fun noteLabel(note: String) = note.lineSequence().first().let { if (it.isBlank()) "+ 메모" else if (it.length > 14) it.take(14) + "…" else it }

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

data class Slice(val label: String, val color: Color, val total: Long)

private fun donutRadius(w: Float, h: Float, density: Density) = with(density) {
    minOf(w / 2 - 98.dp.toPx(), h / 2 - 12.dp.toPx()).coerceAtLeast(48.dp.toPx())
}

/** Donut with each slice's name and share beside it (slices under 3% go unlabeled). Tapping a slice calls [onSlice]. */
@Composable
fun LabeledDonut(slices: List<Slice>, modifier: Modifier = Modifier, onSlice: (Int) -> Unit = {}, inside: @Composable () -> Unit = {}) {
    val total = slices.sumOf { it.total.coerceAtLeast(0) }.coerceAtLeast(1).toFloat()
    val measurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.labelMedium.copy(color = pal.text)
    val pctStyle = MaterialTheme.typography.labelSmall.copy(color = pal.sub)
    val lineColor = pal.faint
    val density = LocalDensity.current
    val thick = with(density) { 26.dp.toPx() }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slices) { progress.snapTo(0f); progress.animateTo(1f, tween(700)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(slices) {
            detectTapGestures { pos ->
                val r = donutRadius(size.width.toFloat(), size.height.toFloat(), this)
                val d = pos - Offset(size.width / 2f, size.height / 2f)
                if (d.getDistance() !in (r - thick - 8.dp.toPx())..(r + 8.dp.toPx())) return@detectTapGestures
                val angle = (Math.toDegrees(atan2(d.y, d.x).toDouble()) + 450) % 360
                var acc = 0.0
                slices.forEachIndexed { i, sl ->
                    acc += 360.0 * sl.total.coerceAtLeast(0) / total
                    if (angle < acc) { onSlice(i); return@detectTapGestures }
                }
            }
        }) {
            val r = donutRadius(size.width, size.height, this)
            val ring = r - thick / 2
            drawCircle(lineColor.copy(alpha = 0.2f), ring, style = Stroke(thick))
            var start = -90f
            val gap = if (slices.size > 1) 1.2f else 0f
            class Label(val right: Boolean, val angle: Double, var y: Float, val name: TextLayoutResult, val pct: TextLayoutResult) { val h get() = name.size.height + pct.size.height }
            val labels = mutableListOf<Label>()
            val labelW = 84.dp.toPx().toInt()
            slices.forEach { sl ->
                val sweep = 360f * sl.total.coerceAtLeast(0) / total
                if (sweep * progress.value > gap) drawArc(sl.color, start + gap / 2, sweep * progress.value - gap, false, center - Offset(ring, ring), Size(ring * 2, ring * 2), style = Stroke(thick))
                if (sweep >= 360f * 0.03f) {
                    val mid = Math.toRadians((start + sweep / 2).toDouble())
                    val name = measurer.measure(sl.label, nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelW))
                    val pct = measurer.measure("${(sl.total * 100 / total).roundToInt()}%", pctStyle)
                    labels += Label(cos(mid) >= 0, mid, center.y + (r + 14.dp.toPx()) * sin(mid).toFloat(), name, pct)
                }
                start += sweep
            }
            // spread labels on each side so they never overlap, keeping them inside the canvas
            labels.groupBy { it.right }.values.forEach { side ->
                val sorted = side.sortedBy { it.y }
                var bottom = 0f
                sorted.forEach { l -> l.y = maxOf(l.y - l.h / 2, bottom); bottom = l.y + l.h + 2.dp.toPx() }
                val over = bottom - size.height
                if (over > 0) sorted.forEach { it.y = (it.y - over).coerceAtLeast(0f) }
            }
            labels.forEach { l ->
                val alpha = progress.value
                val anchor = center + Offset((r * cos(l.angle)).toFloat(), (r * sin(l.angle)).toFloat())
                val cy = l.y + l.name.size.height / 2f
                val elbow = Offset(center.x + (if (l.right) 1 else -1) * (r + 10.dp.toPx()) * abs(cos(l.angle)).toFloat().coerceAtLeast(0.35f), cy)
                val endX = center.x + (if (l.right) 1 else -1) * (r + 18.dp.toPx())
                drawLine(lineColor.copy(alpha = alpha), anchor, elbow, 1.dp.toPx())
                drawLine(lineColor.copy(alpha = alpha), elbow, Offset(endX, cy), 1.dp.toPx())
                val x = if (l.right) endX + 4.dp.toPx() else endX - 4.dp.toPx() - maxOf(l.name.size.width, l.pct.size.width)
                drawText(l.name, topLeft = Offset(x, l.y), alpha = alpha)
                drawText(l.pct, topLeft = Offset(if (l.right) x else x + maxOf(l.name.size.width, l.pct.size.width) - l.pct.size.width, l.y + l.name.size.height), alpha = alpha)
            }
        }
        inside()
    }
}

/** Bars (or a line) per period; negative values hang below zero, [average] and [budget] are dashed lines. */
@Composable
fun TrendChart(
    values: List<Long>, labels: List<String>, highlight: Int, modifier: Modifier = Modifier,
    average: Long? = null, budget: Long? = null, line: Boolean = false, onStep: ((Int) -> Unit)? = null, color: (Long) -> Color,
) {
    val anim = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(values, line) { if (dragging) anim.snapTo(1f) else { anim.snapTo(0f); anim.animateTo(1f, tween(600)) } }
    // dragging sideways moves the period one bar at a time: right shows earlier, left later (TODO #23)
    val step by rememberUpdatedState(onStep)
    val drag = if (onStep == null) Modifier else Modifier.pointerInput(values.size) {
        var dx = 0f
        detectHorizontalDragGestures(
            onDragStart = { dx = 0f; dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
        ) { change, d ->
            change.consume()
            dx += d
            val slot = size.width / values.size.coerceAtLeast(1)
            val n = (dx / slot).toInt()
            if (n != 0) { dx -= n * slot; step?.invoke(-n) }
        }
    }
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.typography.labelSmall.copy(color = pal.sub)
    val avgColor = pal.warn
    val budgetColor = pal.danger
    val grid = pal.divider
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = "기간별 차트" }.then(drag)) {
            if (values.isEmpty()) return@Canvas
            val top = maxOf(values.max(), average ?: 0, budget ?: 0, 0)
            val bottom = minOf(values.min(), 0)
            val span = (top - bottom).coerceAtLeast(1).toFloat()
            val pad = 14.dp.toPx()
            fun y(v: Long) = pad + (size.height - pad * 2) * (top - v) / span
            val slot = size.width / values.size
            drawLine(grid, Offset(0f, y(0)), Offset(size.width, y(0)), 1.dp.toPx())
            drawText(measurer.measure(shortWon(top), axis), topLeft = Offset(0f, 0f))
            if (line) {
                val pts = values.mapIndexed { i, v -> Offset(slot * i + slot / 2, y(0) + (y(v) - y(0)) * anim.value) }
                val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(path, color(values.last()), style = Stroke(2.5.dp.toPx()))
                pts.forEachIndexed { i, o -> drawCircle(color(values[i]), if (i == highlight) 5.dp.toPx() else 3.dp.toPx(), o) }
            } else {
                val barW = minOf(slot * 0.6f, 28.dp.toPx())
                values.forEachIndexed { i, v ->
                    val y0 = y(0)
                    val y1 = y0 + (y(v) - y0) * anim.value
                    val h = abs(y1 - y0).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color(v).copy(alpha = if (i == highlight) 1f else 0.45f),
                        topLeft = Offset(slot * i + (slot - barW) / 2, minOf(y0, y1)), size = Size(barW, h), cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
            }
            if (average != null && average != 0L) {
                val ay = y(average)
                drawLine(avgColor, Offset(0f, ay), Offset(size.width, ay), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                val t = measurer.measure("평균 ${shortWon(average)}", axis.copy(color = avgColor))
                drawText(t, topLeft = Offset(size.width - t.size.width, (ay - t.size.height).coerceAtLeast(0f)))
            }
            if (budget != null) {
                val by = y(budget)
                drawLine(budgetColor, Offset(0f, by), Offset(size.width, by), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)))
                val t = measurer.measure("예산 ${shortWon(budget)}", axis.copy(color = budgetColor))
                // left end, under the line when the top axis label is in the way
                drawText(t, topLeft = Offset(0f, if (by - t.size.height < t.size.height) by + 2.dp.toPx() else by - t.size.height))
            }
        }
        val every = (labels.size + 13) / 14
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                Text(
                    if (i % every == 0 || i == highlight) l else "", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall, color = if (i == highlight) pal.text else pal.sub,
                    fontWeight = if (i == highlight) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private val hues = (0..6).map { Color.hsv(minOf(it * 60f, 359.9f), 1f, 1f) }

/** The rainbow swatch at the end of the preset colors. */
@Composable
fun RainbowSwatch(selected: Color?, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.sweepGradient(hues)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (selected != null) Box(Modifier.size(size * 0.5f).clip(CircleShape).background(selected).border(2.dp, Color.White, CircleShape)) }
}

/** Saturation × brightness square and a hue bar. */
@Composable
fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val color = Color.hsv(h, sat, v)
    // typed fields (TODO #21): dragging rewrites them all; typing one updates the others once it is a valid color
    fun hexOf(argb: Int) = "%06X".format(argb and 0xFFFFFF)
    fun rgbOf(argb: Int) = listOf(argb shr 16, argb shr 8, argb).map { (it and 0xFF).toString() }
    var hex by remember { mutableStateOf(hexOf(initial.toArgb())) }
    val rgb = remember { mutableStateListOf(*rgbOf(initial.toArgb()).toTypedArray()) }
    fun setHsv(argb: Int) = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }.let { h = it[0]; sat = it[1]; v = it[2] }
    fun sync() = Color.hsv(h, sat, v).toArgb().let { hex = hexOf(it); rgbOf(it).forEachIndexed { i, c -> rgb[i] = c } }
    fun typeHex(text: String) {
        hex = text.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
        if (hex.length == 6) (hex.toInt(16) or 0xFF000000.toInt()).let { setHsv(it); rgbOf(it).forEachIndexed { i, c -> rgb[i] = c } }
    }
    fun typeChannel(i: Int, text: String) {
        rgb[i] = text.filter(Char::isDigit).take(3)
        val (r, g, b) = rgb.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return }
        android.graphics.Color.rgb(r, g, b).let { setHsv(it); hex = hexOf(it) }
    }
    fun Modifier.drag(set: PointerInputScope.(Offset) -> Unit) = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            set(down.position); sync()
            drag(down.id) { set(it.position); sync(); it.consume() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상 고르기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Canvas(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).drag {
                    sat = (it.x / size.width).coerceIn(0f, 1f); v = 1 - (it.y / size.height).coerceIn(0f, 1f)
                }) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(h, 1f, 1f))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    drawCircle(Color.White, 9.dp.toPx(), Offset(sat * size.width, (1 - v) * size.height), style = Stroke(3.dp.toPx()))
                }
                Canvas(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).drag { h = (it.x / size.width).coerceIn(0f, 1f) * 359.9f }) {
                    drawRect(Brush.horizontalGradient(hues))
                    drawCircle(Color.White, 10.dp.toPx(), Offset(h / 359.9f * size.width, size.height / 2), style = Stroke(3.dp.toPx()))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(color))
                    OutlinedTextField(
                        hex, ::typeHex, Modifier.padding(start = 12.dp).weight(1f), singleLine = true, label = { Text("HEX") }, prefix = { Text("#") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    "RGB".forEachIndexed { i, c ->
                        OutlinedTextField(
                            rgb[i], { typeChannel(i, it) }, Modifier.weight(1f), singleLine = true, label = { Text(c.toString()) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }
        },
        // the typed hex is exactly what the user sees; the HSV round trip can be one step off
        confirmButton = { TextButton(onClick = { onPick(if (hex.length == 6) Color(hex.toInt(16) or 0xFF000000.toInt()) else color) }) { Text("선택") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
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
