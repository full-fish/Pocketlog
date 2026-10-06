package com.choimanseon.pocketlog.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.choimanseon.pocketlog.ai.Ai
import com.choimanseon.pocketlog.ai.Scan
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.AutoInput
import com.choimanseon.pocketlog.auto.CardParser
import com.choimanseon.pocketlog.data.RawMessage
import com.choimanseon.pocketlog.data.RawStatus
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxSource
import com.choimanseon.pocketlog.data.TxSplit
import com.choimanseon.pocketlog.data.TxStatus
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.copyNow
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.tagIds
import com.choimanseon.pocketlog.domain.label
import com.choimanseon.pocketlog.domain.signedAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

@Composable
fun DetailScreen(id: Long, nav: Nav) {
    val dao = app.dao
    val tx by rememberFlow<Tx?>(null, id) { dao.tx(id) }
    val splits by rememberFlow(emptyList(), id) { dao.splitsOf(id) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val raw by rememberFlow<RawMessage?>(null, tx?.rawMessageId) { dao.rawMessage(tx?.rawMessageId ?: -1) }
    val job by rememberFlow(null, tx?.scanJobId) { dao.scanJob(tx?.scanJobId ?: -1) }
    val plan by rememberFlow(emptyList(), id) { dao.purchaseRowsFlow(id) }
    val tagIds by rememberFlow(emptyList(), id) { dao.tagsOf(id) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TxSplit?>(null) } // the item whose category and tags are being changed
    val t = tx

    PageScaffold("내역", onBack = nav::pop) {
        if (t == null || t.deletedAt != null) {
            EmptyState(Icons.Rounded.DeleteOutline, "삭제된 내역이에요")
            return@PageScaffold
        }
        val catMap = cats.associateBy { it.id }
        val payMap = pays.associateBy { it.id }
        val cat = t.categoryId?.let { catMap[it] }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CategoryIcon(cat, 64.dp)
                Text(t.merchant.ifBlank { cat?.name ?: "내역" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(
                    signedAmount(t) + "원", style = MaterialTheme.typography.displaySmall, color = amountColor(t),
                    textDecoration = if (t.status == TxStatus.CANCELED) TextDecoration.LineThrough else null,
                )
                when (t.status) {
                    TxStatus.CANCELED -> Tag("결제 취소됨", pal.sub)
                    TxStatus.PENDING_REVIEW -> Tag("확인 필요", pal.warn, pal.warn.copy(alpha = 0.12f))
                    else -> Unit
                }
            }
            PCard(Modifier.padding(horizontal = 16.dp)) {
                InfoRow("날짜", t.occurredAt.fmt(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E) HH:mm", java.util.Locale.KOREAN)))
                InfoRow("종류", t.type.label)
                if (t.type != TxType.TRANSFER) InfoRow("카테고리", cat?.name ?: "미분류")
                if (tagIds.isNotEmpty()) InfoRow("태그", tagIds.mapNotNull { catMap[it]?.name }.joinToString(" · "))
                InfoRow(if (t.type == TxType.TRANSFER) "보낸 곳" else "결제수단", t.paymentMethodId?.let { payMap[it]?.name } ?: "-")
                if (t.type == TxType.TRANSFER) InfoRow("받은 곳", t.toPaymentMethodId?.let { payMap[it]?.name } ?: "-")
                if (t.installmentMonths > 0) InfoRow("할부", "${t.installmentMonths}개월" + if (plan.size > 1) " · 전체 ${num(plan.sumOf { it.amount })}원" else "")
                t.originalAmount?.let { InfoRow("외화", it) }
                if (t.memo.isNotBlank()) InfoRow("품명", t.memo)
                if (t.note.isNotBlank()) InfoRow("메모", t.note)
                InfoRow("기록 방법", if (t.source == TxSource.IMPORT) "똑똑가계부에서 가져옴" else if (t.source == TxSource.DUMMY) "테스트용 더미 데이터" else sourceLabel(t.source)?.let { "$it 자동 기록" } ?: "직접 입력")
            }
            if (splits.isNotEmpty()) {
                // each item's own category and tags change here (TODO #67); the record's come from 수정
                val adjust = Scan.adjustment(splits)
                val editable = t.type == TxType.EXPENSE
                SectionHeader("품목 ${splits.size}개" + if (editable) " · 눌러서 태그 바꾸기" else "")
                PCard(Modifier.padding(horizontal = 16.dp)) {
                    splits.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = editable && s != adjust) { editing = s }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryIcon(s.categoryId?.let { catMap[it] }, 28.dp)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(s.name + if (s.quantity > 1) " ×${s.quantity}" else "", style = MaterialTheme.typography.bodyMedium)
                                val tags = s.tagIds().mapNotNull { catMap[it]?.name }
                                if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = pal.sub)
                            }
                            Text(num(s.amount), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            raw?.let { m ->
                SectionHeader("원문")
                PCard(Modifier.padding(horizontal = 16.dp)) {
                    if (m.title.isNotBlank()) Text(m.title, style = MaterialTheme.typography.labelMedium, color = pal.sub)
                    Text(m.body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            job?.let { j ->
                SectionHeader("스크린샷")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Scan.imageFiles(j).forEach { Thumb(it, Modifier.width(120.dp).height(220.dp)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("삭제", { confirmDelete = true }, Modifier.weight(1f), pal.danger)
            SoftButton("복제", {
                app.scope.launch {
                    val now = System.currentTimeMillis()
                    dao.insert(t.copyNow(now))
                }
                nav.toast("지금 시간으로 복제했어요")
            }, Modifier.weight(1f))
            SoftButton("수정", { nav.entry = Entry(editId = t.id) }, Modifier.weight(1f), pal.brand)
        }
        if (t.status == TxStatus.PENDING_REVIEW) PrimaryButton("확인했어요", {
            app.scope.launch { dao.setStatus(t.id, TxStatus.CONFIRMED) }
        }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
    }
    editing?.let { s ->
        var cat by remember(s.id) { mutableStateOf(s.categoryId) }
        var picked by remember(s.id) { mutableStateOf(s.tagIds().toSet()) }
        val done = {
            app.scope.launch { Scan.editItem(id, s.id, cat, picked) }
            editing = null
        }
        CategoryPickerSheet(TxType.EXPENSE, cats, cat, onDismiss = done, extra = {
            ItemTagRows(cats, cat, picked) { tag -> picked = if (tag in picked) picked - tag else picked + tag }
            PrimaryButton("완료", done, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }) { c ->
            cat = c
            picked = Scan.tagsOn(c, picked, cats).toSet()
        }
    }
    if (confirmDelete) ConfirmDialog(
        "이 내역을 삭제할까요?",
        (if (plan.size > 1) "할부 ${plan.size}개월이 모두 삭제돼요. " else "") + "삭제 후 바로 되돌릴 수 있어요.",
        "삭제", danger = true, onDismiss = { confirmDelete = false },
    ) {
        confirmDelete = false
        app.scope.launch { dao.softDelete(id) }
        nav.pop()
        nav.undo("삭제했어요") { dao.undoDelete(id) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = pal.sub, modifier = Modifier.width(88.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Thumb(file: File, modifier: Modifier = Modifier) {
    var bmp by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
                val opts = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 640).coerceAtLeast(1) }
                BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(pal.surface)) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** 확인 필요: low-confidence transactions and messages the parsers could not read. */
@Composable
fun ReviewScreen(nav: Nav) {
    val dao = app.dao
    val pending by rememberFlow(emptyList()) { dao.pendingTx() }
    val failed by rememberFlow(emptyList()) { dao.failedMessages() }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }

    PageScaffold("확인 필요", onBack = nav::pop) {
        if (pending.isEmpty() && failed.isEmpty()) EmptyState(Icons.Rounded.TaskAlt, "확인할 내역이 없어요")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (pending.isNotEmpty()) item { SectionHeader("확인이 필요한 기록") }
            items(pending, key = { it.id }) { tx ->
                Column {
                    TxRow(tx, tx.categoryId?.let { catMap[it] }, payMap, showDate = true) { nav.push(Screen.Detail(tx.id)) }
                    Row(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("맞아요") { app.scope.launch { dao.setStatus(tx.id, TxStatus.CONFIRMED) } }
                        Chip("수정") { nav.entry = Entry(editId = tx.id) }
                        Chip("삭제") { app.scope.launch { dao.softDelete(tx.id) }; nav.undo("삭제했어요") { dao.undoDelete(tx.id) } }
                    }
                }
            }
            if (failed.isNotEmpty()) item { SectionHeader("읽지 못한 알림") }
            items(failed, key = { "m${it.id}" }) { m ->
                PCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(m.receivedAt.fmt(DateTimeFormatter.ofPattern("M/d HH:mm")) + " · " + m.title, style = MaterialTheme.typography.labelMedium, color = pal.sub)
                    Text(m.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("직접 입력") {
                            val p = CardParser.parse(m.title, m.body, m.receivedAt)
                            nav.entry = Entry(prefill = Tx(
                                amount = p?.amount ?: 0, occurredAt = p?.at ?: m.receivedAt, merchant = p?.merchant.orEmpty(),
                                source = if (m.channel == "SMS") TxSource.SMS else TxSource.PUSH, rawMessageId = m.id,
                            ))
                        }
                        if (Ai.usable()) Chip("AI로 다시 읽기") {
                            app.scope.launch {
                                val ok = AutoInput.retry(m)
                                nav.toast(if (ok) "기록했어요" else "AI도 읽지 못했어요. 직접 입력해 주세요")
                            }
                        }
                        Chip("무시") { app.scope.launch { dao.update(m.copy(status = RawStatus.IGNORED)) } }
                    }
                }
            }
        }
    }
}
