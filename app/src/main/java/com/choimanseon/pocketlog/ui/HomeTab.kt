package com.choimanseon.pocketlog.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.AutoInputService
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.TxFilter
import com.choimanseon.pocketlog.domain.byTopCategory
import com.choimanseon.pocketlog.domain.josa
import com.choimanseon.pocketlog.domain.monthPeriod
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.domain.total
import com.choimanseon.pocketlog.domain.won
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun HomeTab(nav: Nav) {
    val context = LocalContext.current
    val settings by app.prefs.version.collectAsState()
    val today = LocalDate.now()
    val period = remember(settings, today) { monthPeriod(today, app.prefs.monthStartDay) }
    val prev = period.shiftMonths(-1)
    val dao = app.dao

    val txs by rememberFlow(emptyList(), period) { dao.txBetween(period.startMillis, period.endMillis) }
    val prevTxs by rememberFlow(emptyList(), period) { dao.txBetween(prev.startMillis, prev.endMillis) }
    val splits by rememberFlow(emptyList(), period) { dao.splitsBetween(period.startMillis, period.endMillis) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val budgets by rememberFlow(emptyList()) { dao.budgets() }
    val pending by rememberFlow(emptyList()) { dao.pendingTx() }
    val failed by rememberFlow(emptyList()) { dao.failedMessages() }
    val recent by rememberFlow(emptyList()) { dao.recentTx(6) }

    var listenerOn by remember { mutableStateOf(AutoInputService.granted(context)) }
    LifecycleResumeEffect(Unit) {
        listenerOn = AutoInputService.granted(context)
        onPauseOrDispose { }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    val spent = total(txs, TxType.EXPENSE)
    val income = total(txs, TxType.INCOME)
    val elapsed = ChronoUnit.DAYS.between(period.start, today) + 1
    val prevToDate = total(prevTxs.filter { it.occurredAt.toLocalDate().isBefore(prev.start.plusDays(elapsed)) }, TxType.EXPENSE)
    val budget = budgets.firstOrNull { it.categoryId == null }?.amount
    val catMap = cats.associateBy { it.id }
    val payMap = pays.associateBy { it.id }
    val top = byTopCategory(txs, splits, cats, TxType.EXPENSE).firstOrNull()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(period.label(today), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.push(Screen.Search) }) { Icon(Icons.Rounded.Search, "검색") }
                IconButton(onClick = { nav.push(Screen.Settings) }) { Icon(Icons.Rounded.Settings, "설정") }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("이번 달 쓴 돈", style = MaterialTheme.typography.bodyMedium, color = pal.sub)
                val shown by animateIntAsState(spent.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(), tween(600), label = "spent")
                Text(won(shown.toLong()), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 4.dp))
                val diff = spent - prevToDate
                if (prevTxs.isNotEmpty()) Text(
                    when {
                        diff > 0 -> "지난달 이맘때보다 ${won(diff)} 더 썼어요"
                        diff < 0 -> "지난달 이맘때보다 ${won(-diff)} 덜 썼어요"
                        else -> "지난달 이맘때와 똑같이 썼어요"
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (diff > 0) pal.sub else pal.income,
                )
                Row(Modifier.padding(top = 16.dp)) {
                    MiniStat("수입", "+" + num(income), pal.income, Modifier.weight(1f))
                    MiniStat("남은 돈", num(income - spent), if (income - spent < 0) pal.danger else pal.text, Modifier.weight(1f))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (budget != null && budget > 0) {
                    val left = budget - spent
                    val daysLeft = (period.days - elapsed + 1).coerceAtLeast(1)
                    PCard(onClick = { nav.tab = 3 }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("예산", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${(spent * 100 / budget)}%", style = MaterialTheme.typography.titleSmall, color = pal.sub)
                        }
                        ProgressBar(spent.toFloat() / budget, Modifier.padding(vertical = 10.dp))
                        Text(
                            if (left >= 0) "남은 ${won(left)} · 오늘은 ${won(left / daysLeft)}까지 괜찮아요"
                            else "예산을 ${won(-left)} 넘었어요",
                            style = MaterialTheme.typography.bodySmall, color = if (left >= 0) pal.sub else pal.danger,
                        )
                    }
                } else {
                    PCard(onClick = { nav.push(Screen.BudgetEdit) }) {
                        Text("이번 달 예산을 정해 보세요", style = MaterialTheme.typography.titleSmall)
                        Text("하루에 얼마까지 써도 되는지 알려 드려요", style = MaterialTheme.typography.bodySmall, color = pal.sub)
                    }
                }
                if (!listenerOn) PCard(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    Text("카드 문자 자동 기록 켜기", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "알림 접근을 허용하면 카드·은행·페이 알림과 문자를 읽어 자동으로 적어요. 결제 알림이 아닌 메시지는 저장하지 않아요.",
                        style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val reviewCount = pending.size + failed.size
                if (reviewCount > 0) PCard(onClick = { nav.push(Screen.Review) }) {
                    Text("확인이 필요한 내역 ${reviewCount}건", style = MaterialTheme.typography.titleSmall, color = pal.warn)
                    Text("자동 기록 중 확실하지 않은 건이에요. 눌러서 확인해 주세요.", style = MaterialTheme.typography.bodySmall, color = pal.sub)
                }
                if (top != null && spent > 0) PCard(onClick = {
                    val f = TxFilter(period.startMillis, period.endMillis, TxType.EXPENSE)
                    nav.push(Screen.TxList("이번 달 · ${top.category?.name ?: "미분류"}", top.category?.let { f.copy(category = it.id) } ?: f.copy(uncategorized = true)))
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryIcon(top.category, 36.dp)
                        Text(
                            "이번 달 가장 많이 쓴 곳은 ${(top.category?.name ?: "미분류").josa("이에요", "예요")} (${top.total * 100 / spent}%)",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
        item { SectionHeader("최근 내역", "전체 보기") { nav.tab = 1 } }
        if (recent.isEmpty()) item {
            EmptyState(Icons.Rounded.EditNote, "아직 기록이 없어요", "아래 + 버튼으로 직접 적거나, 쇼핑 앱 주문내역 스크린샷을 공유해 보세요")
        }
        items(recent, key = { it.id }) { tx ->
            TxRow(tx, tx.categoryId?.let { catMap[it] }, payMap, showDate = true) { nav.push(Screen.Detail(tx.id)) }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = pal.sub)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), color = color)
    }
}
