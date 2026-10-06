package com.choimanseon.pocketlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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
import com.choimanseon.pocketlog.auto.Fx
import com.choimanseon.pocketlog.auto.Pick
import com.choimanseon.pocketlog.auto.Repeats
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.Favorite
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.RawStatus
import com.choimanseon.pocketlog.data.Repeat
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.autoInstallmentMemo
import com.choimanseon.pocketlog.domain.evalExpr
import com.choimanseon.pocketlog.domain.josa
import com.choimanseon.pocketlog.domain.label
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.tagsFor
import com.choimanseon.pocketlog.domain.won
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

// 이체 only appears when editing an old transfer: moving my own money isn't recorded (TODO #7)
private val allTypes = listOf(TxType.EXPENSE, TxType.INCOME, TxType.SAVING, TxType.TRANSFER)

/** 직접 입력 in a foreign currency (TODO #40): the ones card messages use too. */
private val currencies = listOf(
    "KRW" to "원화", "USD" to "미국 달러", "JPY" to "일본 엔", "EUR" to "유로", "CNY" to "중국 위안", "GBP" to "영국 파운드",
    "AUD" to "호주 달러", "CAD" to "캐나다 달러", "HKD" to "홍콩 달러", "SGD" to "싱가포르 달러", "VND" to "베트남 동",
    "THB" to "태국 바트", "PHP" to "필리핀 페소", "TWD" to "대만 달러",
)

/** "1234.5" → "1,234.5" */
private fun prettyDecimal(s: String) = (s.substringBefore('.').toLongOrNull()?.let(::num) ?: "0") + if ('.' in s) "." + s.substringAfter('.') else ""

/** "12000+3000" → "12,000 + 3,000" */
private fun prettyExpr(expr: String) = Regex("""\d+|[+−×÷]""").findAll(expr).joinToString(" ") { m ->
    m.value.toLongOrNull()?.let(::num) ?: m.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(entry: Entry, nav: Nav, onCamera: () -> Unit, onPhotos: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = pal.bg,
        dragHandle = null,
    ) { EntryForm(entry, nav, onCamera, onPhotos, onDismiss) }
}

/** The sheet's content, separate so it can be rendered on its own (ScreenshotTest). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun EntryForm(entry: Entry, nav: Nav, onCamera: () -> Unit, onPhotos: () -> Unit, onDismiss: () -> Unit) {
    val dao = app.dao
    val zone = ZoneId.systemDefault()
    val focus = LocalFocusManager.current
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val favorites by rememberFlow(emptyList()) { dao.favorites() }

    var original by remember { mutableStateOf<Tx?>(null) } // month 1 of an installment plan
    var planMonths by remember { mutableIntStateOf(0) } // rows the original purchase has
    var type by remember { mutableStateOf(entry.type) }
    var expr by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") } // 품명
    var note by remember { mutableStateOf("") } // 메모
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var tags by remember { mutableStateOf(emptySet<Long>()) }
    var payId by remember { mutableStateOf<Long?>(null) }
    var toPayId by remember { mutableStateOf<Long?>(null) }
    var installment by remember { mutableIntStateOf(0) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var pickedCategory by remember { mutableStateOf(false) }
    var pickedTags by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var picker by remember { mutableStateOf<String?>(null) } // cat | pay | to | date | time | installment | currency | note
    var addTagTo by remember { mutableStateOf<Category?>(null) }
    // 즐겨찾기 list and a new 즐겨찾기 swap the content of this one sheet (TODO #59): a second sheet on top stayed off-screen
    // on the phone, its invisible scrim closing it at the first tap
    var showFavorites by remember { mutableStateOf(false) }
    var favoriteDraft by remember { mutableStateOf(entry.favorite) }
    val forFavorite = favoriteDraft != null
    // a 반복 기록 (TODO #60) is a favorite with a day; only the 즐겨찾기 screen's 반복 기록 tab makes one
    var repeat by remember { mutableStateOf(entry.favorite?.repeat ?: Repeat.NONE) }
    var repeatDay by remember { mutableIntStateOf(entry.favorite?.repeatDay ?: 1) }
    var pendingRepeat by remember { mutableStateOf(Repeat.MONTHLY) }
    var currency by remember { mutableStateOf("KRW") }
    val foreign = currency != "KRW"
    var rate by remember { mutableStateOf<Double?>(null) } // won for one unit of [currency]
    var rateFailed by remember { mutableStateOf(false) }
    LaunchedEffect(currency) {
        rate = null
        rateFailed = false
        if (foreign) { rate = Fx.wonPer(currency); rateFailed = rate == null }
    }
    /** In a foreign currency [expr] is a plain decimal number and the won follows from the day's rate. */
    fun wonAmount(): Long? = if (!foreign) evalExpr(expr) else expr.toDoubleOrNull()?.let { v -> rate?.let { Math.round(v * it) } }

    LaunchedEffect(entry) {
        // an installment month opens as its whole purchase: month 1's date, the full price
        val rows = entry.editId?.let { dao.purchaseRows(it) }.orEmpty()
        val src = rows.firstOrNull()?.copy(amount = rows.sumOf { it.amount }) ?: entry.prefill
        if (src != null) {
            if (entry.editId != null) { original = rows.first(); planMonths = rows.size; tags = dao.tagsOfOnce(rows.first().id).toSet() }
            type = src.type
            expr = if (src.amount != 0L) abs(src.amount).toString() else ""
            merchant = src.merchant
            memo = src.memo.takeUnless { autoInstallmentMemo.matches(it) }.orEmpty()
            note = src.note
            categoryId = src.categoryId
            payId = src.paymentMethodId
            toPayId = src.toPaymentMethodId
            installment = src.installmentMonths
            val dt = Instant.ofEpochMilli(src.occurredAt).atZone(zone)
            date = dt.toLocalDate()
            time = dt.toLocalTime().withSecond(0).withNano(0)
        } else if (entry.favorite != null) {
            entry.favorite.takeIf { it.id != 0L }?.let { f ->
                type = f.type
                expr = f.amount.toString()
                merchant = f.merchant
                memo = f.memo
                categoryId = f.categoryId
                tags = f.tags.split(',').mapNotNull { it.toLongOrNull() }.toSet()
                payId = f.paymentMethodId
            }
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
        if (!pickedCategory) (last?.categoryId?.let { Pick(it, dao.tagsOfOnce(last.id)) } ?: Categorizer.categorize(m, type))?.let { pick ->
            categoryId = pick.category
            if (!pickedTags) tags = pick.tags.toSet()
        }
        if (payId == null) payId = last?.paymentMethodId
    }
    val scope = rememberCoroutineScope()

    fun save() {
        if (foreign && rate == null) { nav.toast(if (rateFailed) "환율을 받지 못했어요. 인터넷 연결을 확인해 주세요" else "환율을 받는 중이에요. 잠시 뒤 다시 눌러 주세요"); return }
        val amount = wonAmount() ?: 0L
        if (amount <= 0) { nav.toast("금액을 입력해 주세요"); return }
        if (type == TxType.TRANSFER && (payId == null || toPayId == null)) { nav.toast("보내는 곳과 받는 곳을 골라 주세요"); return }
        val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        val o = original
        val prefill = entry.prefill
        app.scope.launch {
            val base = o ?: prefill ?: Tx(amount = 0, occurredAt = at)
            val tx = base.copy(
                type = type,
                amount = if (o != null && o.amount < 0 && (type == TxType.EXPENSE || type == TxType.SAVING)) -amount else amount,
                occurredAt = at,
                merchant = merchant.trim(),
                memo = memo.trim(),
                note = note.trim(),
                categoryId = if (type == TxType.TRANSFER) null else categoryId,
                paymentMethodId = payId,
                toPaymentMethodId = if (type == TxType.TRANSFER) toPayId else null,
                installmentMonths = if (type == TxType.EXPENSE) installment else 0,
                originalAmount = if (foreign) "$currency $expr" else base.originalAmount,
                status = if (base.status == TxStatus.PENDING_REVIEW) TxStatus.CONFIRMED else base.status,
                updatedAt = System.currentTimeMillis(),
            )
            val id = if (o == null) {
                dao.insertPurchase(tx.copy(id = 0)).also { id ->
                    prefill?.rawMessageId?.let { rid -> dao.rawMessage(rid).first()?.let { dao.update(it.copy(status = RawStatus.PARSED, txId = id)) } }
                }
            } else {
                if (planMonths > 1 || tx.installmentMonths != o.installmentMonths) dao.replacePurchase(tx)
                else dao.update(tx.copy(memo = tx.memo.ifBlank { o.memo.takeIf { autoInstallmentMemo.matches(it) }.orEmpty() }))
                o.id
            }
            dao.setTags(id, if (type == TxType.TRANSFER) emptySet() else tags)
            if ((pickedCategory || pickedTags) && type != TxType.TRANSFER) Categorizer.learn(merchant, categoryId?.let { Pick(it, tags.toList()) })
            if (type == TxType.EXPENSE) AutoInput.checkBudget()
        }
        nav.toast(if (o != null) "수정했어요" else "기록했어요")
        onDismiss()
    }

    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }
    val cat = categoryId?.let { catMap[it] }

    fun useFavorite(f: Favorite) {
        type = f.type
        currency = "KRW"
        expr = f.amount.toString()
        merchant = f.merchant
        memo = f.memo
        categoryId = f.categoryId?.takeIf { it in catMap }
        tags = f.tags.split(',').mapNotNull { it.toLongOrNull() }.filter { it in catMap }.toSet()
        f.paymentMethodId?.let { payId = it }
        pickedCategory = false
        pickedTags = false
    }
    fun clearForm() {
        type = TxType.EXPENSE; currency = "KRW"; expr = ""; merchant = ""; memo = ""; note = ""
        categoryId = null; tags = emptySet(); payId = null; pickedCategory = false; pickedTags = false
    }
    /** Back to the 즐겨찾기 list after adding one from it; the 즐겨찾기 screen's sheet just closes. */
    fun leaveFavorite() {
        if (entry.favorite != null) { onDismiss(); return }
        favoriteDraft = null
        clearForm()
        showFavorites = true
    }
    /** The form as a 즐겨찾기: a new one from a +, or one being edited (TODO #38, #51). */
    fun saveFavorite() {
        val amount = wonAmount()
        if (amount == null || amount <= 0) { nav.toast("금액을 먼저 입력해 주세요"); return }
        val f = (favoriteDraft ?: Favorite(amount = 0)).copy(
            type = type, amount = amount, merchant = merchant.trim(), categoryId = categoryId, tags = tags.joinToString(","), paymentMethodId = payId, memo = memo.trim(),
        )
        if (f.id == 0L && repeat == Repeat.NONE && favorites.any { it.repeat == Repeat.NONE && it.type == f.type && it.amount == f.amount && it.merchant == f.merchant && it.categoryId == f.categoryId }) {
            nav.toast("이미 있는 즐겨찾기예요"); return
        }
        // a new or moved schedule starts after today, so it never records twice on one day
        val scheduled = f.copy(
            repeat = repeat, repeatDay = repeatDay,
            nextAt = if (repeat == Repeat.NONE) null else if (f.id == 0L || repeat != f.repeat || repeatDay != f.repeatDay) Repeats.firstAt(repeat, repeatDay) else f.nextAt,
        )
        app.scope.launch { dao.upsert(if (f.id == 0L) scheduled.copy(sort = (dao.minFavoriteSort() ?: 0) - 1) else scheduled) }
        val what = if (repeat == Repeat.NONE) "즐겨찾기" else "반복 기록"
        nav.toast(if (f.id == 0L) "${what}에 넣었어요" else "${what.josa("을", "를")} 고쳤어요")
        leaveFavorite()
    }

    if (showFavorites) {
        BackHandler { showFavorites = false }
        FavoritesList(
            onPick = { useFavorite(it); showFavorites = false },
            onAdd = { clearForm(); favoriteDraft = Favorite(amount = 0); showFavorites = false },
            onBack = { showFavorites = false },
        )
        return
    }
    if (forFavorite && entry.favorite == null) BackHandler(onBack = ::leaveFavorite)

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val types = if (type == TxType.TRANSFER) allTypes else allTypes.dropLast(1)
            PillTabs(types.map { it.label }, types.indexOf(type), Modifier.weight(1f)) {
                if (type != types[it]) { type = types[it]; categoryId = null; tags = emptySet() }
            }
            if (forFavorite && entry.favorite == null) TextButton(onClick = ::leaveFavorite) { Text("취소") }
            // a receipt photo or a screenshot goes through the same AI analysis
            if (original == null && !forFavorite) {
                TextButton(onClick = onCamera, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.PhotoCamera, null, Modifier.padding(end = 4.dp).size(18.dp)); Text("촬영") }
                TextButton(onClick = onPhotos, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.Image, null, Modifier.padding(end = 4.dp).size(18.dp)); Text("사진") }
            }
        }

        // amount
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp).clickable { focus.clearFocus(); typing = false },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val value = evalExpr(expr)
            Text(
                when {
                    foreign -> (if (expr.isEmpty()) "0" else prettyDecimal(expr)) + " $currency"
                    expr.isEmpty() -> "0원"
                    else -> prettyExpr(expr) + if (expr.any { it in "+−×÷" }) "" else "원"
                },
                style = MaterialTheme.typography.displaySmall, color = if (expr.isEmpty()) pal.faint else pal.text,
                textAlign = TextAlign.Center, maxLines = 2,
            )
            if (foreign) Text(
                rate?.let { r -> "≈ ${num(wonAmount() ?: 0)}원 · 1 $currency = ${"%,.2f".format(r)}원" } ?: if (rateFailed) "환율을 받지 못했어요" else "환율을 받는 중이에요…",
                style = MaterialTheme.typography.titleSmall, color = pal.brand,
            )
            else if (expr.any { it in "+−×÷" } && value != null) Text("= ${num(value)}원", style = MaterialTheme.typography.titleSmall, color = pal.brand)
            // 직접 입력 in a foreign currency, converted at the day's rate (TODO #40)
            if (original == null && !forFavorite && type != TxType.TRANSFER) Text(
                if (foreign) "$currency ▾" else "원화 ▾", style = MaterialTheme.typography.labelMedium, color = pal.sub,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).clickable { picker = "currency" }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // merchant + suggestions; 즐겨찾기 beside it opens the saved ones (TODO #47)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(merchant, { merchant = it }, when (type) { TxType.INCOME -> "어디서 받았나요"; TxType.SAVING -> "어디에 넣었나요"; else -> "어디에 썼나요" }, Modifier.weight(1f).onFocusChanged {
                typing = it.isFocused
                if (!it.isFocused && merchant.isNotBlank()) scope.launch { fillFromMerchant(merchant.trim()) }
            })
            if (!forFavorite && type != TxType.TRANSFER) Box(
                Modifier.padding(start = 8.dp).clip(RoundedCornerShape(14.dp)).background(pal.warn.copy(alpha = 0.15f))
                    .clickable { focus.clearFocus(); typing = false; showFavorites = true }.padding(horizontal = 12.dp, vertical = 14.dp),
            ) { Text("즐겨찾기", style = MaterialTheme.typography.labelLarge, color = pal.warn) }
        }
        if (suggestions.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s -> Chip(s) { merchant = s; suggestions = emptyList(); scope.launch { fillFromMerchant(s) } } }
        }

        FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (type == TxType.TRANSFER) {
                Chip("보내는 곳: ${payId?.let { payMap[it]?.name } ?: "선택"}", payId != null) { picker = "pay" }
                Chip("받는 곳: ${toPayId?.let { payMap[it]?.name } ?: "선택"}", toPayId != null) { picker = "to" }
            } else {
                Chip(cat?.name ?: "카테고리", cat != null) { picker = "cat" }
                Chip(payId?.let { payMap[it]?.name } ?: if (type == TxType.SAVING) "출금 계좌" else "결제수단", payId != null) { picker = "pay" }
                if (type == TxType.EXPENSE && !forFavorite && payId?.let { payMap[it]?.kind } == PayKind.CREDIT) {
                    Chip(if (installment == 0) "일시불" else "${installment}개월", installment > 0) { picker = "installment" }
                }
            }
            val today = LocalDate.now()
            val dayLabel = when (date) {
                today -> "오늘"
                today.minusDays(1) -> "어제"
                else -> "${date.monthValue}월 ${date.dayOfMonth}일"
            }
            if (!forFavorite) Chip("$dayLabel ${time.format(timeFmt)}", date != today) { picker = "date" }
            // 메모 apart from the 품명 below; a 즐겨찾기 keeps none
            if (!forFavorite) Chip(noteLabel(note), note.isNotBlank()) { picker = "note" }
            if (forFavorite && repeat != Repeat.NONE) Chip(repeatLabel(repeat, repeatDay) + " ▾", true) { picker = "repeat" }
        }
        // any number of tags (TODO #27): the category's own on one line, the shared ones on the next, each with "+ 태그"
        if (type != TxType.TRANSFER) {
            val shared = cats.firstOrNull { it.type == type && it.tagGroup }
            listOfNotNull(cat, shared).forEach { owner ->
                Row(Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (owner.tagGroup) "공통" else owner.name, style = MaterialTheme.typography.labelSmall, color = pal.sub, modifier = Modifier.widthIn(min = 36.dp))
                    cats.filter { it.parentId == owner.id && !it.hidden }.forEach { t ->
                        Chip("#${t.name}", t.id in tags) { tags = if (t.id in tags) tags - t.id else tags + t.id; pickedTags = true }
                    }
                    Chip("+ 태그") { addTagTo = owner }
                }
            }
        }
        Field(memo, { memo = it }, "품명", Modifier.onFocusChanged { if (it.isFocused) typing = false })

        if (!typing) Keypad(
            dot = foreign,
            onKey = { k -> expr = typeKey(expr, k, foreign) },
            onClear = { expr = "" },
        )
        // a new 즐겨찾기 comes from the + in the 즐겨찾기 list (TODO #51), so the entry sheet keeps one button
        if (forFavorite) PrimaryButton(
            (if (repeat == Repeat.NONE) "즐겨찾기" else "반복 기록") + if (favoriteDraft?.id == 0L) " 저장" else " 고치기", ::saveFavorite, Modifier.padding(top = 12.dp),
        )
        else PrimaryButton(if (original != null) "수정하기" else "저장하기", ::save, Modifier.padding(top = 12.dp))
    }


    addTagTo?.let { owner ->
        InputDialog(if (owner.tagGroup) "공통 태그 추가" else "${owner.name} 태그 추가", hint = "태그 이름", onDismiss = { addTagTo = null }) { name ->
            addTagTo = null
            if (name.isNotBlank()) scope.launch {
                val id = dao.upsert(Category(type = owner.type, name = name.trim(), icon = owner.icon, color = owner.color, parentId = owner.id, sort = cats.count { it.parentId == owner.id }))
                tags = tags + id
                pickedTags = true
            }
        }
    }

    when (picker) {
        "cat" -> CategoryPickerSheet(type, cats, categoryId, onDismiss = { picker = null }, onManage = { picker = null; onDismiss(); nav.push(Screen.Categories) }) {
            categoryId = it; pickedCategory = true; picker = null
            tags = tags.filter { id -> cats.tagsFor(it, type).any { t -> t.id == id } }.toSet()
        }
        "note" -> InputDialog("메모", note, singleLine = false, onDismiss = { picker = null }) { note = it; picker = null }
        "pay" -> PayPickerSheet(pays, payId, if (type == TxType.TRANSFER) "보내는 곳" else "결제수단", onDismiss = { picker = null },
            onManage = { picker = null; onDismiss(); nav.push(Screen.PayMethods) }) { payId = it; picker = null }
        "to" -> PayPickerSheet(pays, toPayId, "받는 곳", onDismiss = { picker = null }) { toPayId = it; picker = null }
        "repeat" -> ChoiceDialog("반복", listOf("매주", "매월"), if (repeat == Repeat.WEEKLY) 0 else 1, { picker = null }) {
            pendingRepeat = if (it == 0) Repeat.WEEKLY else Repeat.MONTHLY
            picker = "repeatDay"
        }
        "repeatDay" -> {
            val days = if (pendingRepeat == Repeat.WEEKLY) (1..7).map { repeatLabel(Repeat.WEEKLY, it) } else (1..31).map { repeatLabel(Repeat.MONTHLY, it) + if (it > 28) " (없는 달은 말일)" else "" }
            ChoiceDialog(if (pendingRepeat == Repeat.WEEKLY) "무슨 요일에" else "며칠에", days, if (repeat == pendingRepeat) repeatDay - 1 else -1, { picker = null }) {
                repeat = pendingRepeat; repeatDay = it + 1; picker = null
            }
        }
        "currency" -> ChoiceDialog("통화", currencies.map { (code, name) -> "$code  $name" }, currencies.indexOfFirst { it.first == currency }, { picker = null }) {
            if (currencies[it].first != currency) { currency = currencies[it].first; expr = "" }
            picker = null
        }
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
private fun Keypad(dot: Boolean, onKey: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // a foreign amount has cents, not sums: the last key types the decimal point
            listOf("+", "−", "×", "÷", if (dot) "." else "000").forEach { k ->
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(pal.surface).clickable { onKey(k) },
                    contentAlignment = Alignment.Center,
                ) { Text(k, style = MaterialTheme.typography.titleMedium, color = if (k == "000" || k == ".") pal.text else if (dot) pal.faint else pal.brand) }
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

/** [expr] after one [Keypad] key: an operator replaces a trailing one; [dot] = a foreign amount, a decimal number without sums. */
private fun typeKey(expr: String, k: String, dot: Boolean = false) = when (k) {
    "⌫" -> expr.dropLast(1)
    "+", "−", "×", "÷" -> if (dot || expr.isEmpty()) expr else if (expr.last() in "+−×÷") expr.dropLast(1) + k else expr + k
    "." -> if ('.' in expr) expr else expr.ifEmpty { "0" } + "."
    else -> if (expr.length >= 24 || (expr.isEmpty() && k.startsWith("0"))) expr else expr + k
}

/**
 * An amount on the entry sheet's calculator keys, e.g. the total on the screenshot review screen. A digit first starts a new
 * amount, an operator first works on [initial] ("12,000 + 3,000").
 */
@Composable
fun AmountDialog(title: String, initial: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var expr by remember { mutableStateOf(initial.toString()) }
    var fresh by remember { mutableStateOf(true) }
    val value = evalExpr(expr)
    val sum = expr.any { it in "+−×÷" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (expr.isEmpty()) "0원" else prettyExpr(expr) + if (sum) "" else "원",
                    style = MaterialTheme.typography.headlineMedium, color = if (expr.isEmpty()) pal.faint else pal.text, textAlign = TextAlign.Center, maxLines = 2,
                )
                if (sum && value != null) Text("= ${num(value)}원", style = MaterialTheme.typography.titleSmall, color = pal.brand)
                Keypad(dot = false, onKey = { k ->
                    expr = typeKey(if (fresh && k[0].isDigit()) "" else expr, k)
                    fresh = false
                }, onClear = { expr = ""; fresh = false })
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null && value >= 0) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** "매주 토요일", "매월 25일" */
private fun repeatLabel(r: Repeat, day: Int) =
    if (r == Repeat.WEEKLY) "매주 " + java.time.DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN) else "매월 ${day}일"

private val favoriteSorts = listOf("custom" to "사용자 정의", "name" to "이름 오름차순", "nameDesc" to "이름 내림차순", "newest" to "최신순", "oldest" to "오래된순")

/**
 * 즐겨찾기 list inside the entry sheet (TODO #47, #59): tap one to fill the form, + to make a new one. Search, five orders
 * (remembered), and in 사용자 정의 a long press drags a row to a new place. Deleting asks first.
 */
@Composable
private fun FavoritesList(onPick: (Favorite) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    val dao = app.dao
    // 반복 기록 live in their own tab in settings and don't show here (TODO #60)
    val all by rememberFlow(emptyList()) { dao.favorites().map { list -> list.filter { it.repeat == Repeat.NONE } } }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val catMap = cats.associateBy { it.id }
    var sort by remember { mutableStateOf(app.prefs.favoriteSort) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Favorite?>(null) }
    fun name(f: Favorite) = f.merchant.ifBlank { f.categoryId?.let { catMap[it]?.name } ?: f.type.label }
    val collator = remember { java.text.Collator.getInstance(java.util.Locale.KOREAN) }
    val sorted = when (sort) {
        "name" -> all.sortedWith { a, b -> collator.compare(name(a), name(b)) }
        "nameDesc" -> all.sortedWith { a, b -> collator.compare(name(b), name(a)) }
        "newest" -> all.sortedByDescending { it.createdAt }
        "oldest" -> all.sortedBy { it.createdAt }
        else -> all // the database keeps 사용자 정의 order
    }
    val draggable = sort == "custom" && query.isBlank()
    // rows move here while dragging and are saved on release; one state for good, since the drag gesture holds on to it
    var order by remember { mutableStateOf(emptyList<Favorite>()) }
    LaunchedEffect(sorted) { order = sorted }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 68.dp.toPx() }
    val shown = order.filter { query.isBlank() || name(it).contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(top = 8.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "입력으로 돌아가기") }
            Text("즐겨찾기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) { Text(favoriteSorts.first { it.first == sort }.second + " ▾", color = pal.sub) }
                DropdownMenu(menu, { menu = false }) {
                    favoriteSorts.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = if (key == sort) pal.brand else pal.text) },
                            onClick = { sort = key; app.prefs.favoriteSort = key; menu = false },
                        )
                    }
                }
            }
            IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, "즐겨찾기 추가", tint = pal.brand) }
        }
        if (all.size > 5) Field(query, { query = it }, "이름으로 찾기", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (all.isEmpty()) EmptyState(Icons.Rounded.StarOutline, "아직 즐겨찾기가 없어요", "위의 +를 눌러 자주 쓰는 내역을 넣어 보세요")
        else if (draggable && all.size > 1) Text("길게 눌러 끌면 순서를 바꿔요", style = MaterialTheme.typography.labelSmall, color = pal.faint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState(), enabled = dragId == null).padding(bottom = 16.dp)) {
            shown.forEach { f ->
                key(f.id) {
                    val what = listOfNotNull(
                        f.type.takeIf { it != TxType.EXPENSE }?.label,
                        f.categoryId?.let { catMap[it]?.name },
                        f.tags.split(',').mapNotNull { it.toLongOrNull()?.let(catMap::get)?.name }.joinToString(" ") { "#$it" }.ifBlank { null },
                        f.paymentMethodId?.let { id -> pays.firstOrNull { it.id == id }?.name },
                        "더미".takeIf { f.dummy },
                    ).joinToString(" · ")
                    Row(
                        Modifier.fillMaxWidth().height(68.dp)
                            .zIndex(if (dragId == f.id) 1f else 0f)
                            .graphicsLayer { translationY = if (dragId == f.id) dragY else 0f }
                            .background(if (dragId == f.id) pal.surface else pal.bg)
                            .pointerInput(draggable) {
                                if (draggable) detectDragGesturesAfterLongPress(
                                    onDragStart = { dragId = f.id; dragY = 0f },
                                    onDragEnd = {
                                        dragId = null
                                        dragY = 0f
                                        val moved = order
                                        app.scope.launch { moved.forEachIndexed { i, x -> if (x.sort != i) dao.upsert(x.copy(sort = i)) } }
                                    },
                                    onDragCancel = { dragId = null; dragY = 0f },
                                ) { change, d ->
                                    change.consume()
                                    dragY += d.y
                                    val from = order.indexOfFirst { it.id == f.id }
                                    val to = (from + (dragY / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                    if (to != from) {
                                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                                        dragY -= (to - from) * rowPx
                                    }
                                }
                            }
                            .clickable { onPick(f) }
                            .padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (draggable) Icon(Icons.Rounded.DragIndicator, null, Modifier.padding(end = 10.dp).size(18.dp), tint = pal.faint)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name(f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                Text(won(f.amount), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
                            }
                            if (what.isNotEmpty()) Text(what, style = MaterialTheme.typography.bodySmall, color = pal.sub, maxLines = 1)
                        }
                        IconButton(onClick = { deleting = f }) { Icon(Icons.Rounded.Close, "삭제", tint = pal.faint, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
    deleting?.let { f ->
        ConfirmDialog(
            "즐겨찾기 삭제", "${name(f)} ${won(f.amount)}",
            "삭제", danger = true, onDismiss = { deleting = null },
        ) { deleting = null; app.scope.launch { dao.delete(f) } }
    }
}
