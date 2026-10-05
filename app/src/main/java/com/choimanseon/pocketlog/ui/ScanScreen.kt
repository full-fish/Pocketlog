package com.choimanseon.pocketlog.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.choimanseon.pocketlog.Notify
import com.choimanseon.pocketlog.ai.OrderChoice
import com.choimanseon.pocketlog.ai.Scan
import com.choimanseon.pocketlog.ai.ScanOrder
import com.choimanseon.pocketlog.ai.ScanResult
import com.choimanseon.pocketlog.ai.sourceNames
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.ScanJob
import com.choimanseon.pocketlog.data.ScanStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.won
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(jobId: Long, nav: Nav, pickScreenshots: () -> Unit) {
    val job by rememberFlow<ScanJob?>(null, jobId) { app.dao.scanJob(jobId) }
    val j = job
    LifecycleResumeEffect(jobId) {
        Scan.viewing = jobId
        Notify.cancelScan(jobId)
        onPauseOrDispose { if (Scan.viewing == jobId) Scan.viewing = null }
    }
    PageScaffold("스크린샷 분석", onBack = nav::pop) {
        when (j?.status) {
            null, ScanStatus.RUNNING -> Analyzing(j)
            ScanStatus.FAILED -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(48.dp), tint = pal.faint)
                Text(j.error ?: "분석에 실패했어요", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
                PrimaryButton("다시 시도", { app.scope.launch { Scan.run(jobId) } })
                TextButton(onClick = { nav.pop(); nav.entry = Entry() }) { Text("직접 입력하기") }
            }
            ScanStatus.DONE, ScanStatus.SAVED -> {
                val result = remember(j.resultJson) { runCatching { Scan.parse(j.resultJson.orEmpty()) }.getOrNull() }
                if (result == null || result.orders.isEmpty()) {
                    EmptyState(Icons.Rounded.SearchOff, "결제 내역을 찾지 못했어요", "주문 목록이나 결제 내역이 보이는 화면을 캡처해 주세요")
                    PrimaryButton("다른 스크린샷 고르기", { nav.pop(); pickScreenshots() }, Modifier.padding(16.dp))
                } else ReviewOrders(j, result, nav)
            }
        }
    }
}

@Composable
private fun Analyzing(job: ScanJob?) {
    val messages = listOf("스크린샷을 읽고 있어요", "주문과 품목을 찾고 있어요", "카테고리를 고르고 있어요", "거의 다 됐어요")
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2500); i = (i + 1).coerceAtMost(messages.lastIndex) } }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (job != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scan.imageFiles(job).forEach { Thumb(it, Modifier.width(110.dp).height(200.dp).alpha(pulse)) }
        }
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(color = pal.brand)
        Text(messages[i], style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        Text("보통 5~20초 걸려요. 다른 화면으로 가도 계속 분석하고, 끝나면 알림으로 알려 드려요.", style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 4.dp))
        repeat(3) {
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(pal.surface).alpha(pulse))
        }
    }
}

private enum class Dup { MERGE, NEW, SKIP }

@Composable
private fun ColumnScope.ReviewOrders(job: ScanJob, result: ScanResult, nav: Nav) {
    val dao = app.dao
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }
    val saved = job.status == ScanStatus.SAVED

    val include = remember(job.id) { mutableStateListOf(*result.orders.map { it.active }.toTypedArray()) }
    val payIds = remember(job.id) { mutableStateListOf<Long?>(*arrayOfNulls(result.orders.size)) }
    val totals = remember(job.id) { mutableStateListOf(*result.orders.map { it.total }.toTypedArray()) }
    val itemCats = remember(job.id) { result.orders.map { o -> mutableStateListOf(*o.items.map { it.categoryId }.toTypedArray()) } }
    val dups = remember(job.id) { mutableStateListOf<com.choimanseon.pocketlog.data.Tx?>(*arrayOfNulls(result.orders.size)) }
    val dupChoice = remember(job.id) { mutableStateListOf(*Array(result.orders.size) { Dup.MERGE }) }
    var separate by remember { mutableStateOf(false) }
    var pickCat by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pickPay by remember { mutableStateOf<Int?>(null) }
    var editTotal by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(job.id) {
        val payList = dao.payMethodsOnce()
        val defaults = dao.rulesOnce(RuleKind.SOURCE_DEFAULT_PAYMENT)
        result.orders.forEachIndexed { i, o ->
            payIds[i] = Scan.suggestPay(o, result.sourceApp, payList, defaults)
            val dup = if (saved) null else Scan.duplicateOf(o)
            dups[i] = dup
            // the same order from an earlier screenshot: skip; from a card SMS: attach the items to it
            if (dup != null) dupChoice[i] = if (dup.scanJobId != null) Dup.SKIP else Dup.MERGE
        }
    }

    val chosen = result.orders.indices.filter { include[it] && !(dups[it] != null && dupChoice[it] == Dup.SKIP) }
    val newCount = chosen.count { dups[it] == null || dupChoice[it] == Dup.NEW }
    val mergeCount = chosen.size - newCount

    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("${sourceNames[result.sourceApp] ?: "스크린샷"}에서 ${result.orders.size}건을 찾았어요", style = MaterialTheme.typography.titleMedium)
                if (saved) Text("이미 저장한 스크린샷이에요", style = MaterialTheme.typography.bodySmall, color = pal.warn)
                if (result.warnings.isNotEmpty()) Text(
                    result.warnings.joinToString(" · ") { w -> when (w) {
                        "image_cut_off" -> "화면이 잘려 있어요"
                        "blurry" -> "글씨가 흐려요"
                        "total_mismatch" -> "합계가 맞지 않는 주문이 있어요"
                        else -> w
                    } },
                    style = MaterialTheme.typography.bodySmall, color = pal.warn,
                )
            }
        }
        itemsIndexed(result.orders) { i, o ->
            OrderCard(
                o, include[i], payIds[i]?.let { payMap[it]?.name }, totals[i], itemCats[i].map { it?.let { id -> catMap[id] } },
                o.items.mapIndexed { k, item -> Scan.tagsFor(itemCats[i][k], listOf(item), cats).mapNotNull { catMap[it]?.name } },
                dups[i], dupChoice[i],
                onInclude = { include[i] = it },
                onPay = { pickPay = i },
                onTotal = { editTotal = i },
                onItemCategory = { item -> pickCat = i to item },
                onDup = { dupChoice[i] = it },
            )
        }
        item {
            if (result.orders.any { it.items.size > 1 }) SwitchRow("품목별로 따로 저장", "끄면 주문 1건으로 저장하고, 통계는 품목별 카테고리로 나눠요", separate) { separate = it }
        }
    }
    PrimaryButton(
        when {
            saved -> "저장 완료"
            chosen.isEmpty() -> "저장할 주문을 골라 주세요"
            else -> "저장하기 · " + listOfNotNull(
                if (newCount > 0) "새 내역 ${newCount}건 ${won(chosen.filter { dups[it] == null || dupChoice[it] == Dup.NEW }.sumOf { totals[it] })}" else null,
                if (mergeCount > 0) "품목 연결 ${mergeCount}건" else null,
            ).joinToString(" + ")
        },
        {
            val choices = result.orders.indices.map { i ->
                OrderChoice(
                    include = i in chosen, payId = payIds[i], categories = itemCats[i].toList(), total = totals[i],
                    mergeInto = if (dupChoice[i] == Dup.MERGE) dups[i] else null,
                )
            }
            app.scope.launch { Scan.save(job, result, choices, separate) }
            nav.toast("저장했어요")
            nav.pop()
        },
        Modifier.padding(16.dp),
        enabled = !saved && chosen.isNotEmpty(),
    )

    pickCat?.let { (o, item) ->
        CategoryPickerSheet(TxType.EXPENSE, cats, itemCats[o][item], onDismiss = { pickCat = null }) { itemCats[o][item] = it; pickCat = null }
    }
    pickPay?.let { o ->
        PayPickerSheet(pays, payIds[o], onDismiss = { pickPay = null }) { payIds[o] = it; pickPay = null }
    }
    editTotal?.let { o ->
        InputDialog("결제 금액", totals[o].toString(), keyboard = KeyboardType.Number, onDismiss = { editTotal = null }) { v ->
            v.filter(Char::isDigit).toLongOrNull()?.let { totals[o] = it }
            editTotal = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderCard(
    o: ScanOrder,
    include: Boolean,
    payName: String?,
    total: Long,
    itemCats: List<com.choimanseon.pocketlog.data.Category?>,
    itemTags: List<List<String>>,
    dup: com.choimanseon.pocketlog.data.Tx?,
    dupChoice: Dup,
    onInclude: (Boolean) -> Unit,
    onPay: () -> Unit,
    onTotal: () -> Unit,
    onItemCategory: (Int) -> Unit,
    onDup: (Dup) -> Unit,
) {
    PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).alpha(if (include) 1f else 0.5f), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = include, onCheckedChange = onInclude)
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(o.date?.let { "${it.monthValue}월 ${it.dayOfMonth}일" }, o.merchant.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!o.active) Text(when (o.status) { "canceled" -> "취소된 주문"; "refunded" -> "환불된 주문"; else -> o.status }, style = MaterialTheme.typography.labelMedium, color = pal.sub)
            }
            Text(num(total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = onTotal))
        }
        Row(Modifier.padding(top = 4.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(payName ?: "결제수단 선택", payName != null, onClick = onPay)
            if (o.paymentHint == null && payName != null) Text("(기본값)", style = MaterialTheme.typography.labelSmall, color = pal.faint, modifier = Modifier.align(Alignment.CenterVertically))
        }
        o.items.forEachIndexed { k, item ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.name + if (item.quantity > 1) " ×${item.quantity}" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                Chip((itemCats.getOrNull(k)?.name ?: "카테고리") + itemTags[k].joinToString("") { " #$it" }, itemCats.getOrNull(k) != null) { onItemCategory(k) }
                Text(num(item.amount), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).widthIn(min = 64.dp), textAlign = TextAlign.End)
            }
        }
        if (o.shippingFee > 0 || o.discount > 0) Text(
            listOfNotNull(if (o.shippingFee > 0) "배송비 ${num(o.shippingFee)}" else null, if (o.discount > 0) "할인 -${num(o.discount)}" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        )
        if (o.mismatch) Text("품목 합계가 결제 금액과 달라요. 금액을 눌러 고칠 수 있어요", style = MaterialTheme.typography.bodySmall, color = pal.warn, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        if (o.confidence < 0.7) Text("잘 안 보이는 부분이 있어요. 한 번 확인해 주세요", style = MaterialTheme.typography.bodySmall, color = pal.warn, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        if (dup != null && include) {
            Column(Modifier.padding(start = 12.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.warn.copy(alpha = 0.1f)).padding(12.dp)) {
                Text(
                    "이미 있는 내역과 같아 보여요: ${dup.occurredAt.fmt(java.time.format.DateTimeFormatter.ofPattern("M/d"))} ${dup.merchant} ${num(dup.amount)}원",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("품목만 붙이기", dupChoice == Dup.MERGE) { onDup(Dup.MERGE) }
                    Chip("따로 저장", dupChoice == Dup.NEW) { onDup(Dup.NEW) }
                    Chip("건너뛰기", dupChoice == Dup.SKIP) { onDup(Dup.SKIP) }
                }
            }
        }
    }
}
