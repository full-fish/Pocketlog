package com.choimanseon.pocketlog.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.choimanseon.pocketlog.domain.moveCategory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.choimanseon.pocketlog.Backup
import com.choimanseon.pocketlog.BuildConfig
import com.choimanseon.pocketlog.Pin
import com.choimanseon.pocketlog.Vault
import com.choimanseon.pocketlog.ai.Ai
import com.choimanseon.pocketlog.ai.sourceNames
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.AutoInputService
import com.choimanseon.pocketlog.auto.Pick
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.CategoryColors
import com.choimanseon.pocketlog.data.ClevImport
import com.choimanseon.pocketlog.Drive
import com.choimanseon.pocketlog.data.Dummy
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.label
import com.choimanseon.pocketlog.domain.num
import com.choimanseon.pocketlog.domain.tops
import com.choimanseon.pocketlog.domain.won
import com.choimanseon.pocketlog.domain.toLocalDate
import com.choimanseon.pocketlog.data.Repeat
import com.choimanseon.pocketlog.data.Favorite
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.StarOutline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private const val DEVELOPER_EMAIL = "manseon94@gmail.com"

@Composable
fun SettingsScreen(nav: Nav) {
    // read, not just declared: an unread `val v by` subscribes to nothing, so switches stayed put (TODO #54)
    app.prefs.version.collectAsState().value
    val p = app.prefs
    var dialog by remember { mutableStateOf<String?>(null) }
    var versionTaps by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val dummies by rememberFlow(0) { app.dao.dummyCount() }
    PageScaffold("설정", onBack = nav::pop) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ListRow("사용법", "처음이라면 여기부터 · 화면마다 자세한 설명") { nav.push(Screen.Help) }
            GroupLabel("기본")
            ListRow("카테고리 편집", "지출·수입 카테고리") { nav.push(Screen.Categories) }
            ListRow("결제수단 편집", "카드·계좌·페이머니") { nav.push(Screen.PayMethods) }
            ListRow("한 달 시작일", if (p.monthStartDay == 1) "매월 1일" else "매월 ${p.monthStartDay}일 (월급날 기준)") { dialog = "month" }
            ListRow("한 주 시작 요일", DayOfWeek.of(p.weekStart).getDisplayName(TextStyle.FULL, Locale.KOREAN)) { dialog = "week" }
            ListRow("화면 테마", mapOf("system" to "시스템 설정 따르기", "light" to "라이트", "dark" to "다크")[p.theme]) { dialog = "theme" }
            ListRow("예산", "주 · 월 · 연 예산과 카테고리별 예산") { nav.push(Screen.BudgetEdit) }
            ListRow("즐겨찾기 · 반복 기록", "자주 쓰는 내역, 매주 · 매월 자동 기록") { nav.push(Screen.Favorites) }

            GroupLabel("자동 기록")
            ListRow("카드 문자·알림 자동 기록", if (p.autoInput) "켜짐" else "꺼짐") { nav.push(Screen.AutoInputSettings) }

            GroupLabel("AI")
            ListRow("AI 서버", if (Ai.configured) "연결 설정됨" else "설정 안 됨 · 스크린샷 분석을 쓰려면 server/README.md 참고")
            SwitchRow(
                "AI 사용 동의", "스크린샷과 읽지 못한 알림 문구를 AI 서버로 보내 분석해요. 서버에는 저장하지 않아요.", p.aiConsent,
            ) { p.aiConsent = it }
            ListRow("AI 모델", Ai.models.firstOrNull { it.first == p.aiModel }?.second?.replace("\n", " · ") ?: p.aiModel) { dialog = "model" }
            SwitchRow("AI 월간 리포트", "한 달이 끝나면 다음 달 첫날 오전 9시에 지난달 리포트를 보내요. 집계 숫자만 보내고, 가장 똑똑한 모델(GPT-6 Astra, 한 번에 약 80원)로 써요", p.monthlyReport) { p.monthlyReport = it }
            ListRow("지난 리포트 보기") { nav.push(Screen.Reports()) }

            GroupLabel("보안 · 데이터")
            ListRow("앱 잠금", if (Pin.isSet) "켜짐" else "꺼짐") { nav.push(Screen.Security) }
            ListRow("백업 · 복구 · 초기화") { nav.push(Screen.Data) }

            // hidden until 버전 is tapped ten times, like Android's developer options
            if (p.devMenu) {
                GroupLabel("개발자 메뉴")
                ListRow("더미 데이터 넣기", "5년치 가짜 내역과 즐겨찾기를 새로 만들어요. 내역에 '더미' 표시가 붙어요") { dialog = "dummy" }
                if (dummies > 0) ListRow("더미 데이터 지우기", "더미 ${num(dummies.toLong())}건과 더미 즐겨찾기만 지워요. 진짜 내역은 그대로예요") { dialog = "undummy" }
            }

            GroupLabel("정보")
            ListRow("버전", BuildConfig.VERSION_NAME) {
                if (++versionTaps < 10) return@ListRow
                versionTaps = 0
                p.devMenu = !p.devMenu
                nav.toast(if (p.devMenu) "개발자 메뉴가 생겼어요" else "개발자 메뉴를 숨겼어요")
            }
            ListRow("환율", "해외 결제는 그날 환율로 바꿔 적어요 · Rates By Exchange Rate API (open.er-api.com)")
            ListRow("개발자 연락처", DEVELOPER_EMAIL) {
                runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "[Pocketlog] ")) }
                    .onFailure { nav.toast("메일 앱이 없어요. $DEVELOPER_EMAIL 로 보내 주세요") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    when (dialog) {
        "month" -> ChoiceDialog("한 달 시작일", (1..28).map { "매월 ${it}일" }, p.monthStartDay - 1, { dialog = null }) { p.monthStartDay = it + 1; dialog = null }
        "week" -> ChoiceDialog("한 주 시작 요일", DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, Locale.KOREAN) }, p.weekStart - 1, { dialog = null }) { p.weekStart = it + 1; dialog = null }
        "model" -> ChoiceDialog("AI 모델", Ai.models.map { it.second }, Ai.models.indexOfFirst { it.first == p.aiModel }, { dialog = null }) {
            p.aiModel = Ai.models[it].first; dialog = null
        }
        "dummy" -> ConfirmDialog("더미 데이터를 넣을까요?", "전에 넣은 더미는 지우고, 지금 있는 카테고리와 결제수단으로 5년치 내역, 해외 결제 몇 건, 즐겨찾기 9개(4개는 반복 기록)를 새로 만들어요. 진짜 내역은 그대로 두고, 나중에 더미만 지울 수 있어요.", "넣기", onDismiss = { dialog = null }) {
            dialog = null
            app.scope.launch { val n = Dummy.insert(); nav.toast("더미 내역 ${num(n.toLong())}건을 넣었어요") }
        }
        "undummy" -> ConfirmDialog("더미 데이터를 지울까요?", "'더미' 표시가 붙은 내역과 더미 즐겨찾기만 지워요.", "지우기", danger = true, onDismiss = { dialog = null }) {
            dialog = null
            app.scope.launch { app.dao.deleteDummy() }
            nav.toast("더미 데이터를 지웠어요")
        }
        "theme" -> {
            val keys = listOf("system", "light", "dark")
            ChoiceDialog("화면 테마", listOf("시스템 설정 따르기", "라이트", "다크"), keys.indexOf(p.theme), { dialog = null }) { p.theme = keys[it]; dialog = null }
        }
    }
}

@Composable
fun AutoInputSettingsScreen(nav: Nav) {
    val context = LocalContext.current
    // read, not just declared: an unread `val v by` subscribes to nothing, so switches stayed put (TODO #54)
    app.prefs.version.collectAsState().value
    val p = app.prefs
    val dao = app.dao
    val blocks by rememberFlow(emptyList()) { dao.rules(RuleKind.BLOCK) }
    val defaults by rememberFlow(emptyList()) { dao.rules(RuleKind.SOURCE_DEFAULT_PAYMENT) }
    val catRules by rememberFlow(emptyList()) { dao.rules(RuleKind.CATEGORY) }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    var granted by remember { mutableStateOf(AutoInputService.granted(context)) }
    var unrestricted by remember { mutableStateOf(AutoInputService.unrestricted(context)) }
    LifecycleResumeEffect(Unit) { granted = AutoInputService.granted(context); unrestricted = AutoInputService.unrestricted(context); onPauseOrDispose { } }
    var addBlock by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    PageScaffold("자동 기록", onBack = nav::pop) {
        LazyColumn {
            item {
                PCard(Modifier.padding(16.dp)) {
                    Text(if (granted) "알림 접근이 허용되어 있어요" else "알림 접근을 허용해 주세요", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "카드·은행·페이 앱 알림과 문자 알림에서 결제 내용만 골라 기록해요. 결제 알림이 아닌 메시지는 저장하지 않아요." +
                            if (!granted) "\n\n직접 설치한 앱은 '앱 정보 > ⋮ > 제한된 설정 허용'을 먼저 해야 할 수 있어요." else "",
                        style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(vertical = 8.dp),
                    )
                    SoftButton(if (granted) "알림 접근 설정 열기" else "알림 접근 허용하러 가기", {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    })
                }
                ListRow(
                    "배터리 사용 제한 없음", "제한되면 휴대폰이 앱을 잠재워 결제 알림을 놓칠 수 있어요",
                    trailing = { Text(if (unrestricted) "켜짐" else "켜기", style = MaterialTheme.typography.labelLarge, color = if (unrestricted) pal.income else pal.brand) },
                ) { if (unrestricted) context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) else AutoInputService.askUnrestricted(context) }
                SwitchRow("자동 기록 사용", null, p.autoInput) { p.autoInput = it }
                SwitchRow("기록할 때 알림 보내기", "\"김밥천국 8,000원 · 식비로 기록했어요\"", p.notifyOnSave) {
                    p.notifyOnSave = it
                    if (it && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SwitchRow("카테고리 자동 분류", "가맹점 이름으로 카테고리를 골라요. 입력할 때 카테고리나 태그를 꾹(0.8초) 누르면 그 가맹점의 규칙으로 기억해요", p.autoCategory) { p.autoCategory = it }
                ListRow(
                    "내 이름", "이 이름으로 오가는 돈은 내 계좌끼리 옮긴 거라 기록하지 않아요",
                    trailing = {
                        Text(p.myName.ifBlank { "설정 안 함" }, style = MaterialTheme.typography.bodyMedium, color = if (p.myName.isBlank()) pal.faint else pal.text)
                        TextButton(onClick = { editName = true }) { Text("변경") }
                    },
                ) { editName = true }
                Text(
                    "내 계좌 간 이체, 카드값 출금, 페이머니 충전, 카드 선승인은 수입·지출이 아니라서 기록하지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                GroupLabel("자동 기록 막기")
                Text(
                    "키워드가 들어간 알림, 또는 일정 금액 이하의 결제를 기록하지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(blocks, key = { it.id }) { r -> RuleRow(blockLabel(r)) { app.scope.launch { dao.delete(r) } } }
            item { TextButton(onClick = { addBlock = true }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("+ 막을 조건 추가") } }
            item {
                GroupLabel("쇼핑 앱별 기본 결제수단")
                Text(
                    "스크린샷에 결제수단이 안 보일 때 쓰는 값이에요. 스크린샷을 저장할 때 고른 결제수단을 기억해요.",
                    style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp),
                )
                if (defaults.isEmpty()) Text("아직 없어요", style = MaterialTheme.typography.bodySmall, color = pal.faint, modifier = Modifier.padding(20.dp))
            }
            items(defaults, key = { it.id }) { r ->
                val pay = pays.firstOrNull { it.id.toString() == r.value }?.name ?: "삭제된 결제수단"
                RuleRow("${sourceNames[r.pattern] ?: r.pattern} → $pay") { app.scope.launch { dao.delete(r) } }
            }
            item {
                GroupLabel("카테고리 규칙 ${catRules.size}개")
                Text(
                    "가맹점 이름에 왼쪽 글자가 들어 있으면 그 카테고리로 적어요. 여러 개 걸리면 긴 쪽이 이겨요. 입력할 때 카테고리나 태그를 꾹(0.8초) 누르면 여기에 생기고, 다시 꾹 누르거나 여기서 지우면 없어져요.",
                    style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(catRules, key = { it.id }) { r ->
                val pick = Pick.decode(r.value)
                val cat = cats.firstOrNull { it.id == pick?.category }?.name ?: "삭제된 카테고리"
                val tags = pick?.tags.orEmpty().mapNotNull { t -> cats.firstOrNull { it.id == t }?.let { " #" + it.name } }.joinToString("")
                RuleRow("${r.pattern} → $cat$tags") { app.scope.launch { dao.delete(r) } }
            }
        }
    }
    if (editName) InputDialog(
        title = "내 이름",
        initial = p.myName,
        hint = "예: 홍길동 (여러 개면 쉼표로)",
        message = "은행 알림에 받는 사람이나 보낸 사람이 이 이름이면 내 계좌끼리 옮긴 돈으로 보고 기록하지 않아요.",
        onDismiss = { editName = false },
    ) { p.myName = it; editName = false }
    if (addBlock) {
        var keyword by remember { mutableStateOf("") }
        var max by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addBlock = false },
            title = { Text("막을 조건") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(keyword, { keyword = it }, singleLine = true, label = { Text("키워드 (앱 이름, 발신자, 가맹점)") })
                    OutlinedTextField(max, { max = it.filter(Char::isDigit) }, singleLine = true, label = { Text("이 금액 이하만 (선택)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (keyword.isNotBlank() || max.isNotBlank()) app.scope.launch { dao.insert(Rule(kind = RuleKind.BLOCK, pattern = keyword.trim(), value = max)) }
                    addBlock = false
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { addBlock = false }) { Text("취소") } },
        )
    }
}

private fun blockLabel(r: Rule) = listOfNotNull(
    r.pattern.takeIf { it.isNotBlank() }?.let { "'$it' 포함" },
    r.value.toLongOrNull()?.let { "%,d원 이하".format(it) },
).joinToString(" · ")

@Composable
private fun RuleRow(text: String, onDelete: () -> Unit) =
    ListRow(text, trailing = { TextButton(onClick = onDelete) { Text("삭제", color = pal.danger) } })

// ---------------------------------------------------------------- categories

private val categoryTypes = listOf(TxType.EXPENSE, TxType.INCOME, TxType.SAVING)

/**
 * Categories, each followed by its tags, then the 공통 태그 (TODO #27). Long-press a row and drag it (TODO #8):
 * a category moves with its tags among the categories; a tag moves under any category or the 공통 태그.
 */
@Composable
fun CategoriesScreen(nav: Nav) {
    val dao = app.dao
    val cats by rememberFlow(emptyList()) { dao.categories() }
    var typeIndex by remember { mutableIntStateOf(0) }
    val type = categoryTypes[typeIndex]
    var editing by remember { mutableStateOf<Category?>(null) }
    val tops = cats.filter { it.type == type && it.parentId == null }.sortedBy { it.tagGroup } // the 공통 태그 last
    val rows = remember(cats, type) { tops.flatMap { t -> listOf(t) + cats.filter { it.parentId == t.id } } }
    fun newTag(parent: Category) = Category(type = parent.type, name = "", icon = parent.icon, color = parent.color, parentId = parent.id, sort = cats.count { it.parentId == parent.id })

    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val bounds = remember { mutableStateMapOf<Long, Pair<Float, Float>>() } // top, height inside the list
    var viewport by remember { mutableFloatStateOf(0f) }
    var drag by remember { mutableStateOf<CategoryDrag?>(null) }

    // where the dragged row would land: before rows[gap], under parent. Categories stay categories and tags stay tags.
    fun target(d: CategoryDrag): Pair<Int, Long?> {
        val group = setOf(d.id) + rows.filter { it.parentId == d.id }.map { it.id }
        val center = d.top + d.height / 2 + d.dy + (scroll.value - d.scrollAtStart)
        var gap = rows.indexOfFirst { it.id !in group && bounds[it.id]?.let { (t, h) -> t + h / 2 > center } == true }.let { if (it < 0) rows.size else it }
        if (rows.first { it.id == d.id }.parentId == null) {
            while (gap < rows.size && (rows[gap].parentId != null || rows[gap].id in group)) gap++ // not between a category and its tags
            val shared = rows.indexOfFirst { it.tagGroup }
            return (if (shared >= 0) minOf(gap, shared) else gap) to null
        }
        val prev = rows.take(gap).lastOrNull { it.id != d.id } ?: return 1 to rows.first().id
        return gap to (prev.parentId ?: prev.id)
    }

    LaunchedEffect(drag != null) {
        val edge = with(density) { 72.dp.toPx() }
        while (drag != null) {
            val d = drag!!
            val y = d.top + d.height / 2 + d.dy - d.scrollAtStart
            val step = when { y < edge -> -14f; y > viewport - edge -> 14f; else -> 0f }
            if (step != 0f) scroll.scrollBy(step)
            withFrameNanos { }
        }
    }

    PageScaffold("카테고리 편집", onBack = nav::pop, actions = {
        IconButton(onClick = { editing = Category(type = type, name = "", icon = "box", color = CategoryColors[tops.size % CategoryColors.size], sort = tops.size) }) {
            Icon(Icons.Rounded.Add, "추가")
        }
    }) {
        PillTabs(categoryTypes.map { it.label }, typeIndex, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { typeIndex = it }
        Text(
            "내역마다 카테고리는 하나, 태그(#)는 여러 개 붙일 수 있어요. 길게 눌러 끌면 순서를 바꾸고, 태그는 다른 카테고리나 공통 태그 아래로 옮길 수 있어요.",
            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it.height.toFloat() }.verticalScroll(scroll)) {
            Column {
                rows.forEachIndexed { i, c ->
                    if (c.tagGroup) key(c.id) {
                        Column(
                            Modifier.fillMaxWidth().onGloballyPositioned { bounds[c.id] = it.positionInParent().y to it.size.height.toFloat() }
                                .clickable { editing = newTag(c) }.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
                        ) {
                            Text("공통 태그", style = MaterialTheme.typography.titleSmall)
                            Text("어느 카테고리에서나 떠요. 눌러서 추가해요.", style = MaterialTheme.typography.bodySmall, color = pal.sub)
                        }
                    } else key(c.id) {
                        val d = drag
                        val dragging = d?.id == c.id
                        CategoryRow(
                            c, indent = c.parentId != null,
                            Modifier.onGloballyPositioned { bounds[c.id] = it.positionInParent().y to it.size.height.toFloat() }
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) { translationY = d!!.dy + (scroll.value - d.scrollAtStart); translationX = d.dx.coerceIn(-40f, 120f); shadowElevation = 12f }
                                    alpha = if (d != null && c.parentId == d.id) 0.4f else 1f
                                }
                                .pointerInput(c.id, rows) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val (t, h) = bounds[c.id] ?: (0f to 0f)
                                            drag = CategoryDrag(c.id, t, h, scroll.value)
                                        },
                                        onDrag = { change, amount -> change.consume(); drag?.let { it.dy += amount.y; it.dx += amount.x } },
                                        onDragEnd = {
                                            drag?.let { dd ->
                                                val (gap, parent) = target(dd)
                                                val changes = moveCategory(rows, dd.id, gap, parent)
                                                if (changes.isNotEmpty()) app.scope.launch { changes.forEach { dao.upsert(it) } }
                                            }
                                            drag = null
                                        },
                                        onDragCancel = { drag = null },
                                    )
                                },
                        ) { editing = c }
                    }
                    // after a category's last tag: a visible way to add one (TODO #27)
                    val owner = c.parentId ?: c.id
                    if (rows.getOrNull(i + 1)?.let { it.parentId ?: it.id } != owner) key("add$owner") {
                        val parent = tops.first { it.id == owner }
                        Text(
                            if (parent.tagGroup) "+ 공통 태그 추가" else "+ 태그 추가", style = MaterialTheme.typography.bodyMedium, color = pal.brand,
                            modifier = Modifier.fillMaxWidth().clickable { editing = newTag(parent) }.padding(start = 64.dp, top = 6.dp, bottom = 10.dp),
                        )
                    }
                }
            }
            drag?.let { d ->
                val (gap, parent) = target(d)
                val y = rows.getOrNull(gap)?.let { bounds[it.id]?.first } ?: rows.lastOrNull()?.let { bounds[it.id]?.let { (t, h) -> t + h } } ?: 0f
                Box(
                    Modifier.offset { IntOffset(0, y.toInt()) }.padding(start = if (parent == null) 20.dp else 56.dp, end = 20.dp)
                        .fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(pal.brand),
                )
            }
        }
    }
    editing?.let { c -> CategoryEditDialog(c, cats, onDismiss = { editing = null }, onAddChild = { editing = newTag(c) }) }
}

private class CategoryDrag(val id: Long, val top: Float, val height: Float, val scrollAtStart: Int) {
    var dy by mutableFloatStateOf(0f)
    var dx by mutableFloatStateOf(0f)
}

@Composable
private fun CategoryRow(c: Category, indent: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().background(pal.bg).clickable(onClick = onClick).padding(start = if (indent) 56.dp else 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indent) Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Sell, "태그", Modifier.size(18.dp), tint = Color(c.color)) }
        else CategoryIcon(c, 40.dp)
        Text(c.name, style = MaterialTheme.typography.bodyLarge, color = if (c.hidden) pal.faint else pal.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (c.hidden) Tag("숨김")
        Icon(Icons.Rounded.DragHandle, null, tint = pal.faint, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}

@Composable
private fun CategoryEditDialog(c: Category, all: List<Category>, onDismiss: () -> Unit, onAddChild: () -> Unit) {
    val dao = app.dao
    var name by remember(c) { mutableStateOf(c.name) }
    var icon by remember(c) { mutableStateOf(c.icon) }
    var color by remember(c) { mutableLongStateOf(c.color) }
    var hidden by remember(c) { mutableStateOf(c.hidden) }
    var merging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var customColor by remember { mutableStateOf(false) }
    val isNew = c.id == 0L
    val isTag = c.parentId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (isTag) "태그" else "카테고리") + if (isNew) " 추가" else " 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isTag) CategoryIcon(c.copy(icon = icon, color = color), 48.dp)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("이름") })
                }
                // tags take their category's color and need no icon
                if (!isTag) {
                    // preset colors, then the rainbow for any color (TODO #9)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        CategoryColors.forEach { col ->
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(Color(col)).clickable { color = col }
                                    .then(if (col == color) Modifier.border(2.dp, pal.text, CircleShape) else Modifier),
                            )
                        }
                        RainbowSwatch(Color(color).takeIf { color !in CategoryColors }, 22.dp) { customColor = true }
                    }
                    // equal side margins at any width (TODO #10); a lazy grid here never settles inside AlertDialog's layout
                    EvenGrid(36.dp, Modifier.height(168.dp).verticalScroll(rememberScrollState())) {
                        CategoryIcons.forEach { (k, vector) ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(if (k == icon) Color(color).copy(alpha = 0.16f) else Color.Transparent).clickable { icon = k },
                                contentAlignment = Alignment.Center,
                            ) { Icon(vector, k, Modifier.size(22.dp), tint = if (k == icon) Color(color) else pal.sub) }
                        }
                    }
                }
                if (!isNew) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(hidden, { hidden = it })
                        Text("숨기기 (기록은 그대로 남아요)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isTag) Chip("태그 추가", onClick = onAddChild)
                        Chip("합치기") { merging = true }
                        if (isTag) Chip("삭제") { deleting = true }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) app.scope.launch { dao.upsert(c.copy(name = name.trim(), icon = icon, color = color, hidden = hidden)) }
                onDismiss()
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    if (customColor) ColorPickerDialog(Color(color), { customColor = false }) { color = it.toArgb().toLong() and 0xFFFFFFFFL; customColor = false }
    if (merging) {
        // a category into another category, a tag into another tag
        val targets = if (isTag) all.filter { it.type == c.type && it.parentId != null && it.id != c.id } else all.tops(c.type).filter { it.id != c.id }
        ChoiceDialog("'${c.name}'을(를) 어디로 합칠까요?", targets.map { t -> (t.parentId?.let { p -> all.firstOrNull { it.id == p }?.name + " › " } ?: "") + t.name }, -1, { merging = false }) { i ->
            app.scope.launch { dao.mergeCategory(c.id, targets[i].id) }
            merging = false
            onDismiss()
        }
    }
    if (deleting) ConfirmDialog("'${c.name}' 태그를 지울까요?", "붙어 있던 내역에서 이 태그만 빠지고, 내역은 그대로 남아요.", "지우기", danger = true, onDismiss = { deleting = false }) {
        app.scope.launch { dao.deleteTag(c.id) }
        deleting = false
        onDismiss()
    }
}

// ---------------------------------------------------------------- payment methods

private val kindNames = linkedMapOf(
    PayKind.CREDIT to "신용카드", PayKind.CHECK to "체크카드", PayKind.BANK to "계좌", PayKind.PAY_MONEY to "페이머니", PayKind.CASH to "현금",
)

@Composable
fun PayMethodsScreen(nav: Nav) {
    val dao = app.dao
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    var editing by remember { mutableStateOf<PayMethod?>(null) }
    PageScaffold("결제수단 편집", onBack = nav::pop, actions = {
        IconButton(onClick = { editing = PayMethod(kind = PayKind.CREDIT, name = "", sort = pays.size) }) { Icon(Icons.Rounded.Add, "추가") }
    }) {
        Text(
            "카드 문자가 오면 자동으로 추가돼요. '쿠팡머니'처럼 문자가 안 오는 페이머니는 직접 추가하고, 다른 이름(쿠팡페이, 쿠페이)을 함께 적어 두면 충전 출금을 이체로 처리해요.",
            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(pays, key = { it.id }) { p ->
                ListRow(
                    p.name,
                    listOfNotNull(kindNames[p.kind], p.last4.takeIf { it.isNotEmpty() }?.let { "끝자리 $it" }, p.aliases.takeIf { it.isNotBlank() }?.let { "다른 이름: $it" }, if (p.hidden) "숨김" else null).joinToString(" · "),
                    trailing = { Icon(payIcon(p.kind), null, tint = pal.sub) },
                ) { editing = p }
            }
        }
    }
    editing?.let { p -> PayMethodDialog(p) { editing = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayMethodDialog(p: PayMethod, onDismiss: () -> Unit) {
    var name by remember(p) { mutableStateOf(p.name) }
    var kind by remember(p) { mutableStateOf(p.kind) }
    var last4 by remember(p) { mutableStateOf(p.last4) }
    var aliases by remember(p) { mutableStateOf(p.aliases) }
    var balance by remember(p) { mutableStateOf(p.balance?.toString().orEmpty()) }
    var billingDay by remember(p) { mutableStateOf(p.billingDay?.toString().orEmpty()) }
    var hidden by remember(p) { mutableStateOf(p.hidden) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.id == 0L) "결제수단 추가" else "결제수단 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("이름 (예: 쿠팡머니)") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    kindNames.forEach { (k, label) -> Chip(label, kind == k) { kind = k } }
                }
                if (kind == PayKind.CREDIT || kind == PayKind.CHECK) OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("카드 끝 4자리") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(aliases, { aliases = it }, singleLine = true, label = { Text("다른 이름 (쉼표로 구분)") })
                if (kind != PayKind.CREDIT) OutlinedTextField(balance, { balance = it.filter { c -> c.isDigit() || c == '-' } }, singleLine = true, label = { Text("잔액 (선택)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                if (kind == PayKind.CREDIT) OutlinedTextField(billingDay, { billingDay = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("결제일 (선택)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                if (p.id != 0L) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(hidden, { hidden = it })
                    Text("숨기기")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) app.scope.launch {
                    app.dao.upsert(p.copy(
                        name = name.trim(), kind = kind, last4 = last4, aliases = aliases.trim(),
                        balance = if (kind == PayKind.CREDIT) null else balance.toLongOrNull(),
                        billingDay = billingDay.toIntOrNull()?.takeIf { it in 1..31 }, hidden = hidden,
                    ))
                }
                onDismiss()
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

// ---------------------------------------------------------------- security

@Composable
fun SecurityScreen(nav: Nav) {
    val context = LocalContext.current
    // read, not just declared: an unread `val v by` subscribes to nothing, so switches stayed put (TODO #54)
    app.prefs.version.collectAsState().value
    var step by remember { mutableStateOf<String?>(null) } // new | confirm
    var first by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    if (step != null) {
        Box(Modifier.fillMaxSize().background(pal.bg).systemBarsPadding()) {
            PinPad(if (step == "new") "새 비밀번호 6자리" else "한 번 더 입력해 주세요", error) { pin ->
                if (step == "new") { first = pin; step = "confirm"; error = null; true }
                else if (pin == first) { Pin.set(pin); step = null; nav.toast("앱 잠금을 켰어요"); true }
                else { error = "비밀번호가 달라요. 처음부터 다시 입력해 주세요"; step = "new"; false }
            }
        }
        androidx.activity.compose.BackHandler { step = null }
        return
    }
    PageScaffold("앱 잠금", onBack = nav::pop) {
        if (!Pin.isSet) {
            ListRow("앱 잠금 켜기", "앱을 열 때와 1분 넘게 다른 앱을 쓰다 돌아올 때 비밀번호를 물어봐요") { step = "new" }
        } else {
            ListRow("비밀번호 바꾸기") { step = "new" }
            if (biometricAvailable(context)) SwitchRow("지문·얼굴로 잠금 해제", null, app.prefs.biometric) { on ->
                if (on) askBiometric(context as FragmentActivity) { app.prefs.biometric = true } else app.prefs.biometric = false
            }
            ListRow("앱 잠금 끄기", trailing = { Text("끄기", color = pal.danger) }) { Pin.clear(); nav.toast("앱 잠금을 껐어요") }
        }
    }
}

// ---------------------------------------------------------------- data

/** Google 드라이브 (TODO #34): Play services asks for the account and consent the first time, then just hands out tokens. Null = 취소. */
@Composable
fun rememberDriveToken(): suspend () -> String? {
    val context = LocalContext.current
    val wait = remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<String?>?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        wait.value?.complete(runCatching { Drive.client(context).getAuthorizationResultFromIntent(r.data).accessToken }.getOrNull())
    }
    return remember(consent) {
        suspend { // on the main thread: it may open the consent screen
            val result = Drive.authorize(context)
            val ask = result.pendingIntent
            if (ask == null) result.accessToken
            else kotlinx.coroutines.CompletableDeferred<String?>().also {
                wait.value = it
                consent.launch(androidx.activity.result.IntentSenderRequest.Builder(ask.intentSender).build())
            }.await()
        }
    }
}

fun driveError(e: Throwable) = when {
    e is com.google.android.gms.common.api.ApiException -> "Google 드라이브에 연결하지 못했어요 (${e.statusCode}). 연결 설정이 필요할 수 있어요"
    e is java.net.UnknownHostException || e is java.net.ConnectException -> "인터넷 연결을 확인해 주세요"
    else -> e.message ?: "실패했어요"
}

@Composable
fun BusyDialog(text: String) = AlertDialog(
    onDismissRequest = {},
    confirmButton = {},
    text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(text, modifier = Modifier.padding(start = 16.dp))
        }
    },
)

/**
 * 매일 자동 백업 being turned on (백업 · 복구, the first run), once the Google account is connected: the password twice, then
 * the first backup goes up. The password is sealed by this phone's keystore (Vault) so the daily job can use it.
 */
@Composable
fun AutoBackupPassword(driveToken: suspend () -> String?, toast: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") } // "" = first entry, else asking again
    var busy by remember { mutableStateOf(false) }
    if (busy) BusyDialog("첫 백업을 올리는 중이에요…")
    else key(first) {
        InputDialog(
            title = if (first.isEmpty()) "자동 백업 비밀번호 정하기" else "한 번 더 입력해 주세요", hint = "비밀번호", password = true,
            message = if (first.isEmpty()) "매일 올리는 백업을 이 비밀번호로 잠가요. 복구할 때 필요하고, 잊어버리면 백업을 열 수 없어요." else null,
            onDismiss = onClose,
        ) { pw ->
            when {
                first.isEmpty() && pw.length < 4 -> toast("비밀번호는 4자 이상으로 정해 주세요")
                first.isEmpty() -> first = pw
                pw != first -> { first = ""; toast("비밀번호가 달라요. 처음부터 다시 정해 주세요") }
                else -> scope.launch {
                    busy = true
                    val result = runCatching {
                        val secret = Vault.seal(pw)
                        driveToken()?.also { withContext(Dispatchers.IO) { Drive.upload(it, Backup.sealed(context, pw)) } }?.let { secret }
                    }
                    result.fold({ secret ->
                        if (secret == null) toast("Google 계정 연결을 취소했어요")
                        else {
                            app.prefs.driveSecret = secret
                            app.prefs.driveLast = java.time.LocalDateTime.now().withNano(0).toString()
                            app.prefs.driveAuto = true
                            toast("매일 자동 백업을 켰어요. 첫 백업도 올렸어요")
                        }
                    }, { toast(driveError(it)) })
                    onClose()
                }
            }
        }
    }
}

@Composable
fun DataScreen(nav: Nav) {
    val context = LocalContext.current
    app.prefs.version.collectAsState().value // 매일 자동 백업 switch and its last time
    var pending by remember { mutableStateOf<Pair<String, Uri>?>(null) } // "export"|"import"|"plain" to file
    var confirmReset by remember { mutableIntStateOf(0) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { pending = "export" to it } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pending = (if (runCatching { Backup.isPlainDb(context, it) }.getOrDefault(false)) "plain" else "import") to it }
    }
    val scope = rememberCoroutineScope()
    var clev by remember { mutableStateOf<Pair<java.io.File, ClevImport.Summary>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val driveToken = rememberDriveToken() // Google 드라이브 (TODO #34)
    var driveBackup by remember { mutableStateOf(false) } // asking the password for a new Drive backup
    var autoPassword by remember { mutableStateOf(false) } // 매일 자동 백업 being turned on
    var driveFiles by remember { mutableStateOf<List<Drive.File>?>(null) }
    var driveRestore by remember { mutableStateOf<Drive.File?>(null) } // asking the password for this one
    val pickClev = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { ClevImport.copy(context, uri).let { it to ClevImport.peek(it) } } }
                .onSuccess { clev = it }
                .onFailure { nav.toast(it.message ?: "읽을 수 없는 파일이에요") }
        }
    }

    PageScaffold("백업 · 복구", onBack = nav::pop) { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "휴대폰 설정의 Google 백업이 켜져 있으면 내역과 설정이 하루 한 번쯤 자동으로 백업되고, 앱을 다시 설치하면 돌아와요 (스크린샷 원본은 빼고). 아래 백업 파일과 드라이브 백업은 비밀번호로 암호화하고, 내역 · 자동 분류 규칙 · 설정까지 모두 담아요 (스크린샷 원본과 앱 잠금은 빼고).",
            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        ListRow("백업 파일 만들기", "암호화된 .plbak 파일로 저장해요") { create.launch("pocketlog-${LocalDate.now()}.plbak") }
        ListRow("백업 파일에서 복구", "백업 파일(.plbak)이나 Pocketlog 데이터 파일(.db)을 골라 주세요. 지금 데이터는 그 내용으로 바뀌어요") { open.launch(arrayOf("*/*")) }
        GroupLabel("Google 드라이브")
        ListRow("Google 드라이브에 백업", "암호화한 백업 파일을 내 드라이브의 Pocketlog 폴더에 올려요. 최근 5개만 남기고 오래된 것부터 지워요") { driveBackup = true }
        val p = app.prefs
        SwitchRow(
            "매일 자동 백업",
            when {
                !p.driveAuto -> "켜면 Google 계정에 연결하고 백업 비밀번호를 정해요. 그다음부터 하루 한 번 알아서 올려요"
                p.driveLast.isNotEmpty() -> "마지막 백업 ${java.time.LocalDateTime.parse(p.driveLast).format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm"))} · 하루 한 번 올려요"
                else -> "하루 한 번 올려요"
            },
            p.driveAuto,
        ) { on ->
            // on: the Google account first, then the password the daily backups are locked with; off forgets that password
            if (!on) { p.driveAuto = false; p.driveSecret = "" }
            else scope.launch {
                busy = "Google 계정을 확인하는 중이에요…"
                val token = runCatching { driveToken() }
                busy = null
                token.fold({ if (it == null) nav.toast("Google 계정 연결을 취소했어요") else autoPassword = true }, { nav.toast(driveError(it)) })
            }
        }
        ListRow("Google 드라이브에서 복구", "드라이브에 올린 백업 중 하나를 골라요. 지금 데이터는 그 내용으로 바뀌어요") {
            scope.launch {
                busy = "Google 드라이브를 확인하는 중이에요…"
                val result = runCatching { driveToken()?.let { withContext(Dispatchers.IO) { Drive.list(it) } } }
                busy = null
                result.onSuccess { files ->
                    when {
                        files == null -> nav.toast("Google 계정 연결을 취소했어요")
                        files.isEmpty() -> nav.toast("드라이브에 Pocketlog 백업이 없어요")
                        else -> driveFiles = files
                    }
                }.onFailure { nav.toast(driveError(it)) }
            }
        }
        GroupLabel("다른 앱에서 가져오기")
        ListRow("똑똑가계부에서 가져오기", "똑똑가계부 백업 파일(.db)을 골라 주세요. 카테고리·결제수단까지 그대로 옮겨요") { pickClev.launch(arrayOf("*/*")) }
        GroupLabel("주의")
        ListRow("데이터 초기화", "모든 내역·카테고리·결제수단을 지워요", trailing = { Text("초기화", color = pal.danger) }) { confirmReset = 1 }
    } }
    pending?.takeIf { it.first == "plain" }?.let { (_, uri) ->
        ConfirmDialog(
            title = "이 데이터 파일로 바꿀까요?",
            text = "지금 Pocketlog에 있는 내역·카테고리·결제수단·예산이 이 파일 내용으로 바뀌고 앱이 다시 켜져요.",
            confirm = "바꾸기", danger = true,
            onDismiss = { pending = null },
        ) {
            pending = null
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { Backup.restore(context, uri, null) } }.onFailure { nav.toast(it.message ?: "실패했어요") }
            }
        }
    }
    pending?.takeIf { it.first != "plain" }?.let { (mode, uri) ->
        InputDialog(
            title = if (mode == "export") "백업 비밀번호 정하기" else "백업 비밀번호",
            hint = "비밀번호", password = true,
            message = if (mode == "export") "복구할 때 필요해요. 잊어버리면 백업을 열 수 없어요." else null,
            onDismiss = { pending = null },
        ) { pw ->
            pending = null
            if (pw.length < 4) { nav.toast("비밀번호는 4자 이상으로 정해 주세요"); return@InputDialog }
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { if (mode == "export") Backup.export(context, uri, pw) else Backup.restore(context, uri, pw) } }
                nav.toast(result.fold({ "백업 파일을 만들었어요" }, { it.message ?: "실패했어요" }))
            }
        }
    }
    clev?.let { (file, sum) ->
        ConfirmDialog(
            title = "똑똑가계부 데이터를 가져올까요?",
            text = "지출 ${num(sum.expenses.toLong())}건 · 수입 ${num(sum.incomes.toLong())}건" +
                (if (sum.first != null && sum.last != null) " (${sum.first} ~ ${sum.last})" else "") +
                "\n카테고리 ${sum.categories}개 · 결제수단 ${sum.payMethods}개\n\n" +
                "지금 Pocketlog에 있는 내역·카테고리·결제수단·예산은 지워지고 이 데이터로 바뀌어요. 앱 잠금, 자동 기록 차단 조건 같은 설정은 그대로예요.",
            confirm = "가져오기",
            onDismiss = { clev = null; file.delete() },
        ) {
            clev = null
            busy = "가져오는 중이에요…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ClevImport.run(file) }.also { file.delete() } }
                busy = null
                nav.toast(result.fold(
                    { "가져왔어요 · 지출 ${num(it.expenses.toLong())}건 · 수입 ${num(it.incomes.toLong())}건 · 자동 분류 규칙 ${it.rules}개" },
                    { it.message ?: "가져오지 못했어요" },
                ))
            }
        }
    }
    if (driveBackup) InputDialog(
        title = "백업 비밀번호 정하기", hint = "비밀번호", password = true,
        message = "복구할 때 필요해요. 잊어버리면 백업을 열 수 없어요.",
        onDismiss = { driveBackup = false },
    ) { pw ->
        driveBackup = false
        if (pw.length < 4) { nav.toast("비밀번호는 4자 이상으로 정해 주세요"); return@InputDialog }
        scope.launch {
            busy = "Google 드라이브에 올리는 중이에요…"
            val result = runCatching { driveToken()?.also { withContext(Dispatchers.IO) { Drive.upload(it, Backup.sealed(context, pw)) } } }
            // 매일 자동 백업 keeps the password it was turned on with
            if (result.getOrNull() != null) app.prefs.driveLast = java.time.LocalDateTime.now().withNano(0).toString()
            busy = null
            nav.toast(result.fold({ if (it == null) "Google 계정 연결을 취소했어요" else "Google 드라이브에 백업했어요" }, ::driveError))
        }
    }
    if (autoPassword) AutoBackupPassword(driveToken, nav::toast) { autoPassword = false }
    driveFiles?.let { files ->
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm").withZone(java.time.ZoneId.systemDefault())
        ChoiceDialog("어느 백업으로 복구할까요?", files.map { "${fmt.format(it.modified)} · ${"%.1f".format(it.size / 1_048_576.0)}MB" }, -1, { driveFiles = null }) {
            driveRestore = files[it]
            driveFiles = null
        }
    }
    driveRestore?.let { file ->
        InputDialog(
            title = "백업 비밀번호", hint = "비밀번호", password = true,
            message = "지금 Pocketlog에 있는 데이터가 이 백업 내용으로 바뀌고 앱이 다시 켜져요.",
            onDismiss = { driveRestore = null },
        ) { pw ->
            driveRestore = null
            scope.launch {
                busy = "복구하는 중이에요…"
                val result = runCatching { driveToken()?.let { withContext(Dispatchers.IO) { Backup.restore(context, Drive.download(it, file.id), pw) } } }
                busy = null
                result.onSuccess { if (it == null) nav.toast("Google 계정 연결을 취소했어요") }.onFailure { nav.toast(driveError(it)) }
            }
        }
    }
    busy?.let { BusyDialog(it) }
    if (confirmReset == 1) ConfirmDialog("정말 초기화할까요?", "모든 기록이 지워지고 되돌릴 수 없어요. 먼저 백업 파일을 만들어 두는 걸 권해요.", "다음", danger = true, onDismiss = { confirmReset = 0 }) { confirmReset = 2 }
    if (confirmReset == 2) ConfirmDialog("마지막 확인", "지금 초기화합니다.", "초기화", danger = true, onDismiss = { confirmReset = 0 }) {
        confirmReset = 0
        app.scope.launch { Backup.reset() }
        nav.toast("초기화했어요")
    }
}

/**
 * 즐겨찾기 · 반복 기록 as two tabs (TODO #60): a 즐겨찾기 fills the entry form on a tap, a 반복 기록 records itself on its day.
 * Both are added and edited in the entry sheet (TODO #38); a 반복 기록 there also has its 매주 · 매월 chip.
 */
@Composable
fun FavoritesScreen(nav: Nav) {
    val dao = app.dao
    val all by rememberFlow(emptyList()) { dao.favorites() }
    val cats by rememberFlow(emptyList()) { dao.categories() }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    val catMap = cats.associateBy { it.id }
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 즐겨찾기, 1 반복 기록
    var menu by remember { mutableStateOf<Favorite?>(null) }
    val weekdays = DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, Locale.KOREAN) }
    val dateFmt = java.time.format.DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    val shown = all.filter { (it.repeat == Repeat.NONE) == (tab == 0) }

    fun repeatText(f: Favorite) = when (f.repeat) {
        Repeat.NONE -> ""
        Repeat.WEEKLY -> "매주 ${weekdays[f.repeatDay - 1]}"
        Repeat.MONTHLY -> "매월 ${f.repeatDay}일"
    } + (f.nextAt?.let { " · 다음 ${it.toLocalDate().format(dateFmt)} 오전 9시" } ?: "")

    val add = {
        nav.entry = Entry(favorite = if (tab == 0) Favorite(amount = 0) else Favorite(amount = 0, repeat = Repeat.MONTHLY, repeatDay = LocalDate.now().dayOfMonth))
    }
    PageScaffold("즐겨찾기 · 반복 기록", onBack = nav::pop, actions = { TextButton(onClick = add) { Text("+ 추가") } }) {
        PillTabs(listOf("즐겨찾기", "반복 기록"), tab, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { tab = it }
        LazyColumn {
            item {
                Text(
                    if (tab == 0) "입력 화면에서 '즐겨찾기'를 누르면 여기 있는 것만 나와요. 하나를 누르면 금액 · 가맹점 · 카테고리 · 태그 · 결제수단이 한 번에 채워져요."
                    else "정한 날 오전 9시에 알아서 기록해요(출처 '반복'). 폰이 꺼져 있어 놓친 날은 나중에 그 날짜로 채워요. 입력 화면의 즐겨찾기에는 나오지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (shown.isEmpty()) EmptyState(
                    if (tab == 0) Icons.Rounded.StarOutline else Icons.Rounded.Repeat,
                    if (tab == 0) "아직 즐겨찾기가 없어요" else "아직 반복 기록이 없어요", "오른쪽 위 '+ 추가'를 눌러 보세요",
                )
            }
            items(shown, key = { it.id }) { f ->
                val what = listOfNotNull(
                    f.type.takeIf { it != TxType.EXPENSE }?.label,
                    f.categoryId?.let { catMap[it]?.name },
                    f.tags.split(',').mapNotNull { it.toLongOrNull()?.let(catMap::get)?.name }.joinToString(" ") { "#$it" }.ifBlank { null },
                    f.paymentMethodId?.let { id -> pays.firstOrNull { it.id == id }?.name },
                    "더미".takeIf { f.dummy },
                ).joinToString(" · ")
                ListRow("${f.merchant.ifBlank { f.type.label }} ${won(f.amount)}", listOf(what, repeatText(f)).filter { it.isNotBlank() }.joinToString("\n")) { menu = f }
            }
        }
    }
    menu?.let { f ->
        ChoiceDialog("${f.merchant.ifBlank { f.type.label }} ${won(f.amount)}", listOf("수정", "삭제"), -1, { menu = null }) {
            menu = null
            if (it == 0) nav.entry = Entry(favorite = f) else app.scope.launch { dao.delete(f) }
        }
    }
}
