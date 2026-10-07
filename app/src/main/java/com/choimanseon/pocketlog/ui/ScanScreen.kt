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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.choimanseon.pocketlog.Notify
import com.choimanseon.pocketlog.ai.Holds
import com.choimanseon.pocketlog.ai.OrderChoice
import com.choimanseon.pocketlog.ai.Scan
import com.choimanseon.pocketlog.ai.ScanOrder
import com.choimanseon.pocketlog.ai.ScanResult
import com.choimanseon.pocketlog.ai.sourceNames
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.Categorizer
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.ScanJob
import com.choimanseon.pocketlog.data.ScanStatus
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.won
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

/** An item's tags in rows like the entry sheet's: its category's, then the shared ones (review screen, 내역 → 품목). */
@Composable
fun ItemTagRows(
    cats: List<Category>, category: Long?, picked: Set<Long>,
    pinned: Set<Long> = emptySet(), onHold: ((Category) -> Unit)? = null, // the item's rule (Categorizer.pin)
    onToggle: (Long) -> Unit,
) {
    val shared = cats.firstOrNull { it.type == TxType.EXPENSE && it.tagGroup }
    listOfNotNull(cats.firstOrNull { it.id == category }, shared).forEach { owner ->
        val tags = cats.filter { it.parentId == owner.id && !it.hidden }
        if (tags.isEmpty()) return@forEach
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (owner.tagGroup) "공통" else owner.name, style = MaterialTheme.typography.labelSmall, color = pal.sub, modifier = Modifier.widthIn(min = 36.dp))
            tags.forEach { t -> Chip("#${t.name}", t.id in picked, pinned = t.id in pinned, onHold = onHold?.let { h -> { h(t) } }) { onToggle(t.id) } }
        }
    }
}

@Composable
private fun ColumnScope.ReviewOrders(job: ScanJob, result: ScanResult, nav: Nav) {
    val dao = app.dao
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }
    val saved = job.status == ScanStatus.SAVED
    val n = result.orders.size

    val include = remember(job.id) { mutableStateListOf(*result.orders.map { it.active }.toTypedArray()) }
    val payIds = remember(job.id) { mutableStateListOf<Long?>(*arrayOfNulls(n)) }
    val totals = remember(job.id) { mutableStateListOf(*result.orders.map { it.total }.toTypedArray()) }
    val itemCats = remember(job.id) { result.orders.map { o -> mutableStateListOf(*o.items.map { it.categoryId }.toTypedArray()) } }
    val picks = remember(job.id) { result.orders.map { o -> mutableStateListOf(*o.items.map { it.tags.toSet() }.toTypedArray()) } }
    val names = remember(job.id) { result.orders.map { o -> mutableStateListOf(*o.items.map { it.name }.toTypedArray()) } }
    val notes = remember(job.id) { mutableStateListOf(*Array(n) { "" }) }
    // an existing record for the same payment, with what it already holds
    val dups = remember(job.id) { mutableStateListOf<Tx?>(*arrayOfNulls(n)) }
    val dupSplits = remember(job.id) { mutableStateListOf(*Array(n) { emptyList<TxSplit>() }) }
    val dupTags = remember(job.id) { mutableStateListOf(*Array(n) { emptyList<Long>() }) }
    val dupChoice = remember(job.id) { mutableStateListOf(*Array(n) { Dup.MERGE }) }
    var separate by remember { mutableStateOf(false) }
    var pickCat by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pickPay by remember { mutableStateOf<Int?>(null) }
    var editTotal by remember { mutableStateOf<Int?>(null) }
    var editName by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editNote by remember { mutableStateOf<Int?>(null) }
    // an item's category and tags are for this scan only; a 0.8 s press pins them as the rule for the item's name
    val scope = rememberCoroutineScope()
    val rules by rememberFlow(emptyList()) { dao.rules(RuleKind.CATEGORY) }
    fun ruleOf(o: Int, k: Int) = Categorizer.fromRules(Scan.itemKey(names[o][k]), rules)?.takeIf { it.category == itemCats[o][k] }
    fun pin(o: Int, k: Int, tag: Category? = null) {
        val c = itemCats[o][k] ?: return
        if (tag != null && tag.parentId == c && tag.id !in picks[o][k]) picks[o][k] = picks[o][k] + tag.id
        scope.launch { nav.toast(Categorizer.pin(Scan.itemKey(names[o][k]), c, tag)) }
    }

    LaunchedEffect(job.id) {
        val payList = dao.payMethodsOnce()
        val defaults = dao.rulesOnce(RuleKind.SOURCE_DEFAULT_PAYMENT)
        result.orders.forEachIndexed { i, o ->
            payIds[i] = Scan.suggestPay(o, result.sourceApp, payList, defaults)
            val dup = (if (saved) null else Scan.duplicateOf(o)) ?: return@forEachIndexed
            dupSplits[i] = dao.splitsOf(dup.id).first()
            dupTags[i] = dao.tagsOfOnce(dup.id)
            dups[i] = dup
            // a card SMS without the items: put them in; a record with items already (an earlier screenshot): leave it unless asked
            dupChoice[i] = if (dupSplits[i].isEmpty() && dup.scanJobId == null) Dup.MERGE else Dup.SKIP
        }
    }

    // follows the edits: once the names, categories and tags match the record there is nothing left to put in
    val holds = result.orders.indices.map { i ->
        dups[i]?.let { d ->
            val planned = Scan.splitsFor(Scan.named(result.orders[i], names[i]), itemCats[i], totals[i])
            val cat = planned.maxByOrNull { it.amount }?.categoryId ?: d.categoryId
            Scan.holds(d, dupSplits[i], dupTags[i], planned, Scan.orderTags(cat, itemCats[i], picks[i], cats))
        }
    }
    val choice = result.orders.indices.map { i -> if (holds[i] == Holds.NOTHING_NEW && dupChoice[i] == Dup.MERGE) Dup.SKIP else dupChoice[i] }
    val chosen = result.orders.indices.filter { include[it] && !(dups[it] != null && choice[it] == Dup.SKIP) }
    val newCount = chosen.count { dups[it] == null || choice[it] == Dup.NEW }
    val fills = chosen.count { choice[it] == Dup.MERGE && holds[it] == Holds.MISSING }
    val swaps = chosen.count { choice[it] == Dup.MERGE && holds[it] == Holds.DIFFERENT }

    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("${sourceNames[result.sourceApp] ?: "스크린샷"}에서 ${n}건을 찾았어요", style = MaterialTheme.typography.titleMedium)
                if (saved) Text("이미 저장한 스크린샷이에요", style = MaterialTheme.typography.bodySmall, color = pal.warn)
                else Text("금액 · 품목 이름 · 카테고리를 누르면 고칠 수 있어요", style = MaterialTheme.typography.bodySmall, color = pal.sub)
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
                o.items.indices.map { k -> Scan.tagsOn(itemCats[i][k], picks[i][k], cats).mapNotNull { catMap[it]?.name } },
                dups[i], dupSplits[i], holds[i], choice[i], names[i], notes[i],
                onInclude = { include[i] = it },
                onPay = { pickPay = i },
                onTotal = { editTotal = i },
                onItemCategory = { item -> pickCat = i to item },
                itemPinned = o.items.indices.map { k -> ruleOf(i, k) != null },
                onItemHold = { k -> pin(i, k) },
                onDup = { dupChoice[i] = it },
                onItemName = { item -> editName = i to item },
                onNote = { editNote = i },
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
                if (newCount > 0) "새 내역 ${newCount}건 ${won(chosen.filter { dups[it] == null || choice[it] == Dup.NEW }.sumOf { totals[it] })}" else null,
                if (fills > 0) "품목 넣기 ${fills}건" else null,
                if (swaps > 0) "품목 바꾸기 ${swaps}건" else null,
            ).joinToString(" + ")
        },
        {
            val choices = result.orders.indices.map { i ->
                OrderChoice(
                    include = i in chosen, payId = payIds[i], categories = itemCats[i].toList(), total = totals[i],
                    mergeInto = if (choice[i] == Dup.MERGE) dups[i] else null,
                    names = names[i].toList(), note = notes[i], tags = picks[i].toList(),
                )
            }
            app.scope.launch { Scan.save(job, result, choices, separate) }
            nav.toast("저장했어요")
            nav.pop()
        },
        Modifier.padding(16.dp),
        enabled = !saved && chosen.isNotEmpty(),
    )

    pickCat?.let { (o, k) ->
        // the sheet stays open: a category, then its tags and the shared ones in rows like the entry sheet's, then 완료
        CategoryPickerSheet(TxType.EXPENSE, cats, itemCats[o][k], onDismiss = { pickCat = null }, extra = {
            ItemTagRows(cats, itemCats[o][k], picks[o][k], ruleOf(o, k)?.tags.orEmpty().toSet(), onHold = { t -> pin(o, k, t) }) { t ->
                picks[o][k] = if (t in picks[o][k]) picks[o][k] - t else picks[o][k] + t
            }
            PrimaryButton("완료", { pickCat = null }, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }) { id ->
            itemCats[o][k] = id
            picks[o][k] = Scan.tagsOn(id, picks[o][k], cats).toSet()
        }
    }
    pickPay?.let { o ->
        PayPickerSheet(pays, payIds[o], onDismiss = { pickPay = null }) { payIds[o] = it; pickPay = null }
    }
    editName?.let { (o, item) ->
        InputDialog("무엇을 샀나요", names[o][item], onDismiss = { editName = null }) { v ->
            if (v.isNotBlank()) names[o][item] = v.trim()
            editName = null
        }
    }
    editNote?.let { o ->
        InputDialog("메모", notes[o], singleLine = false, onDismiss = { editNote = null }) { v -> notes[o] = v; editNote = null }
    }
    editTotal?.let { o ->
        AmountDialog("결제 금액", totals[o], onDismiss = { editTotal = null }) { totals[o] = it; editTotal = null }
    }
}

/** Looks like the entry sheet's input fields, so a value that can be changed reads as one; choices stay [Chip]s. */
@Composable
private fun EditBox(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(pal.bg).clickable(onClick = onClick).padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = style, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Rounded.Edit, "고치기", Modifier.padding(start = 4.dp).size(14.dp), tint = pal.faint)
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
    dup: Tx?,
    dupSplits: List<TxSplit>,
    holds: Holds?,
    dupChoice: Dup,
    names: List<String>,
    note: String,
    onInclude: (Boolean) -> Unit,
    onPay: () -> Unit,
    onTotal: () -> Unit,
    onItemCategory: (Int) -> Unit,
    itemPinned: List<Boolean>,
    onItemHold: (Int) -> Unit,
    onDup: (Dup) -> Unit,
    onItemName: (Int) -> Unit,
    onNote: () -> Unit,
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
            EditBox(won(total), onTotal, Modifier.padding(start = 8.dp), MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.padding(top = 4.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(payName ?: "결제수단 선택", payName != null, onClick = onPay)
            if (o.paymentHint == null && payName != null) Text("(기본값)", style = MaterialTheme.typography.labelSmall, color = pal.faint, modifier = Modifier.align(Alignment.CenterVertically))
            Chip(noteLabel(note), note.isNotBlank(), onClick = onNote)
        }
        o.items.forEachIndexed { k, item ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 6.dp)) {
                    EditBox(names.getOrElse(k) { item.name } + if (item.quantity > 1) " ×${item.quantity}" else "", { onItemName(k) })
                }
                // a long tag list would squeeze the name: the first tag and a count
                val tags = itemTags[k].firstOrNull()?.let { " #$it" + if (itemTags[k].size > 1) " +${itemTags[k].size - 1}" else "" }.orEmpty()
                Chip(
                    (itemCats.getOrNull(k)?.name ?: "카테고리") + tags, itemCats.getOrNull(k) != null,
                    pinned = itemPinned.getOrElse(k) { false }, onHold = { onItemHold(k) },
                ) { onItemCategory(k) }
                Text(num(item.amount), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).widthIn(min = 56.dp), textAlign = TextAlign.End)
            }
        }
        if (o.shippingFee > 0 || o.discount > 0) Text(
            listOfNotNull(if (o.shippingFee > 0) "배송비 ${num(o.shippingFee)}" else null, if (o.discount > 0) "할인 -${num(o.discount)}" else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        )
        if (o.mismatch) Text("품목 합계가 결제 금액과 달라요. 금액을 눌러 고칠 수 있어요", style = MaterialTheme.typography.bodySmall, color = pal.warn, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        if (o.confidence < 0.7) Text("잘 안 보이는 부분이 있어요. 한 번 확인해 주세요", style = MaterialTheme.typography.bodySmall, color = pal.warn, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        if (dup != null && holds != null && include) {
            Column(Modifier.padding(start = 12.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.warn.copy(alpha = 0.1f)).padding(12.dp)) {
                Text(
                    "이미 기록된 결제예요: ${dup.occurredAt.fmt(java.time.format.DateTimeFormatter.ofPattern("M/d"))} ${dup.merchant} ${num(dup.amount)}원",
                    style = MaterialTheme.typography.bodySmall,
                )
                val existing = dupSplits.firstOrNull()?.name?.let { if (dupSplits.size > 1) "$it 외 ${dupSplits.size - 1}개" else it }
                when (holds) {
                    Holds.MISSING -> "그 내역에는 무엇을 샀는지가 없어요. 품목 넣기를 고르면 금액 · 날짜 · 결제수단은 그대로 두고, 이 화면의 품목과 카테고리 · 태그를 넣어요."
                    Holds.DIFFERENT -> "그 내역에는 다른 품목이 들어 있어요(${existing.orEmpty()}). 품목 바꾸기를 고르면 이 화면의 품목과 카테고리 · 태그로 바꿔요."
                    Holds.NOTHING_NEW -> if (o.items.isEmpty()) null else "품목과 카테고리까지 이미 똑같이 들어 있어서 더 넣을 게 없어요."
                }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 4.dp)) }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (holds == Holds.MISSING) Chip("품목 넣기", dupChoice == Dup.MERGE) { onDup(Dup.MERGE) }
                    if (holds == Holds.DIFFERENT) Chip("품목 바꾸기", dupChoice == Dup.MERGE) { onDup(Dup.MERGE) }
                    Chip("따로 저장", dupChoice == Dup.NEW) { onDup(Dup.NEW) }
                    Chip("건너뛰기", dupChoice == Dup.SKIP) { onDup(Dup.SKIP) }
                }
            }
        }
    }
}
