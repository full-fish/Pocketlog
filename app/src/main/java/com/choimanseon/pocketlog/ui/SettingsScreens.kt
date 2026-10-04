package com.choimanseon.pocketlog.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.choimanseon.pocketlog.ai.Ai
import com.choimanseon.pocketlog.ai.sourceNames
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.AutoInputService
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.CategoryColors
import com.choimanseon.pocketlog.data.ClevImport
import com.choimanseon.pocketlog.data.PayKind
import com.choimanseon.pocketlog.data.PayMethod
import com.choimanseon.pocketlog.data.Rule
import com.choimanseon.pocketlog.data.RuleKind
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.num
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SettingsScreen(nav: Nav) {
    val v by app.prefs.version.collectAsState()
    val p = app.prefs
    var dialog by remember { mutableStateOf<String?>(null) }
    PageScaffold("설정", onBack = nav::pop) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GroupLabel("기본")
            ListRow("카테고리 편집", "지출·수입 카테고리") { nav.push(Screen.Categories) }
            ListRow("결제수단 편집", "카드·계좌·페이머니") { nav.push(Screen.PayMethods) }
            ListRow("한 달 시작일", if (p.monthStartDay == 1) "매월 1일" else "매월 ${p.monthStartDay}일 (월급날 기준)") { dialog = "month" }
            ListRow("한 주 시작 요일", DayOfWeek.of(p.weekStart).getDisplayName(TextStyle.FULL, Locale.KOREAN)) { dialog = "week" }
            ListRow("화면 테마", mapOf("system" to "시스템 설정 따르기", "light" to "라이트", "dark" to "다크")[p.theme]) { dialog = "theme" }
            ListRow("예산", "월 예산과 카테고리별 예산") { nav.push(Screen.BudgetEdit) }

            GroupLabel("자동 기록")
            ListRow("카드 문자·알림 자동 기록", if (p.autoInput) "켜짐" else "꺼짐") { nav.push(Screen.AutoInputSettings) }

            GroupLabel("AI")
            ListRow("AI 서버", if (Ai.configured) "연결 설정됨" else "설정 안 됨 · 스크린샷 분석을 쓰려면 server/README.md 참고")
            SwitchRow(
                "AI 사용 동의", "스크린샷과 읽지 못한 알림 문구를 AI 서버로 보내 분석해요. 서버에는 저장하지 않아요.", p.aiConsent,
            ) { p.aiConsent = it }

            GroupLabel("보안 · 데이터")
            ListRow("앱 잠금", if (Pin.isSet) "켜짐" else "꺼짐") { nav.push(Screen.Security) }
            ListRow("백업 · 복구 · 초기화") { nav.push(Screen.Data) }

            GroupLabel("정보")
            ListRow("버전", BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(24.dp))
        }
    }
    when (dialog) {
        "month" -> ChoiceDialog("한 달 시작일", (1..28).map { "매월 ${it}일" }, p.monthStartDay - 1, { dialog = null }) { p.monthStartDay = it + 1; dialog = null }
        "week" -> ChoiceDialog("한 주 시작 요일", DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, Locale.KOREAN) }, p.weekStart - 1, { dialog = null }) { p.weekStart = it + 1; dialog = null }
        "theme" -> {
            val keys = listOf("system", "light", "dark")
            ChoiceDialog("화면 테마", listOf("시스템 설정 따르기", "라이트", "다크"), keys.indexOf(p.theme), { dialog = null }) { p.theme = keys[it]; dialog = null }
        }
    }
}

@Composable
fun AutoInputSettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val v by app.prefs.version.collectAsState()
    val p = app.prefs
    val dao = app.dao
    val blocks by rememberFlow(emptyList()) { dao.rules(RuleKind.BLOCK) }
    val defaults by rememberFlow(emptyList()) { dao.rules(RuleKind.SOURCE_DEFAULT_PAYMENT) }
    val pays by rememberFlow(emptyList()) { dao.payMethods() }
    var granted by remember { mutableStateOf(AutoInputService.granted(context)) }
    LifecycleResumeEffect(Unit) { granted = AutoInputService.granted(context); onPauseOrDispose { } }
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
                SwitchRow("자동 기록 사용", null, p.autoInput) { p.autoInput = it }
                SwitchRow("기록할 때 알림 보내기", "\"김밥천국 8,000원 · 식비로 기록했어요\"", p.notifyOnSave) {
                    p.notifyOnSave = it
                    if (it && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SwitchRow("카테고리 자동 분류", "가맹점 이름으로 카테고리를 골라요. 바꾼 카테고리는 기억해요", p.autoCategory) { p.autoCategory = it }
                ListRow(
                    "내 이름",
                    (if (p.myName.isBlank()) "설정 안 함" else p.myName) + " · 이 이름으로 오가는 돈은 내 계좌끼리 옮긴 거라 기록하지 않아요",
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

@Composable
fun CategoriesScreen(nav: Nav) {
    val dao = app.dao
    val cats by rememberFlow(emptyList()) { dao.categories() }
    var typeIndex by remember { mutableIntStateOf(0) }
    val type = if (typeIndex == 0) TxType.EXPENSE else TxType.INCOME
    var editing by remember { mutableStateOf<Category?>(null) }
    val tops = cats.filter { it.type == type && it.parentId == null }

    PageScaffold("카테고리 편집", onBack = nav::pop, actions = {
        IconButton(onClick = { editing = Category(type = type, name = "", icon = "box", color = CategoryColors[tops.size % CategoryColors.size], sort = tops.size) }) {
            Icon(Icons.Rounded.Add, "추가")
        }
    }) {
        PillTabs(listOf("지출", "수입"), typeIndex, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { typeIndex = it }
        LazyColumn(Modifier.weight(1f)) {
            tops.forEach { c ->
                item(key = c.id) { CategoryRow(c, indent = false) { editing = c } }
                items(cats.filter { it.parentId == c.id }, key = { it.id }) { s -> CategoryRow(s, indent = true) { editing = s } }
            }
        }
    }
    editing?.let { c -> CategoryEditDialog(c, cats, onDismiss = { editing = null }, onAddChild = {
        editing = Category(type = c.type, name = "", icon = c.icon, color = c.color, parentId = c.id, sort = cats.count { it.parentId == c.id })
    }) }
}

@Composable
private fun CategoryRow(c: Category, indent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = if (indent) 56.dp else 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(c, if (indent) 32.dp else 40.dp)
        Text(c.name, style = MaterialTheme.typography.bodyLarge, color = if (c.hidden) pal.faint else pal.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (c.hidden) Tag("숨김")
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
    val isNew = c.id == 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) (if (c.parentId != null) "하위 카테고리 추가" else "카테고리 추가") else "카테고리 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryIcon(c.copy(icon = icon, color = color), 48.dp)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("이름") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryColors.forEach { col ->
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(Color(col))
                                .clickable { color = col }.then(if (col == color) Modifier.background(Color.White.copy(alpha = 0.4f)) else Modifier),
                        )
                    }
                }
                // a lazy grid here never settles inside AlertDialog's layout
                FlowRow(Modifier.height(160.dp).verticalScroll(rememberScrollState())) {
                    CategoryIcons.keys.forEach { k ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (k == icon) Color(color).copy(alpha = 0.16f) else Color.Transparent).clickable { icon = k },
                            contentAlignment = Alignment.Center,
                        ) { Icon(CategoryIcons.getValue(k), k, tint = if (k == icon) Color(color) else pal.sub) }
                    }
                }
                if (!isNew) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(hidden, { hidden = it })
                        Text("숨기기 (기록은 그대로 남아요)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (c.parentId == null) Chip("하위 추가", onClick = onAddChild)
                        Chip("위로") { app.scope.launch { move(c, all, -1) } }
                        Chip("아래로") { app.scope.launch { move(c, all, 1) } }
                        Chip("합치기") { merging = true }
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
    if (merging) {
        val targets = all.filter { it.type == c.type && it.id != c.id && it.parentId != c.id }
        ChoiceDialog("'${c.name}'을(를) 어디로 합칠까요?", targets.map { t -> (t.parentId?.let { p -> all.firstOrNull { it.id == p }?.name + " › " } ?: "") + t.name }, -1, { merging = false }) { i ->
            app.scope.launch { dao.mergeCategory(c.id, targets[i].id) }
            merging = false
            onDismiss()
        }
    }
}

/** Swap sort order with the neighbour among siblings. */
private suspend fun move(c: Category, all: List<Category>, dir: Int) {
    val siblings = all.filter { it.type == c.type && it.parentId == c.parentId }.sortedWith(compareBy({ it.sort }, { it.id }))
    val i = siblings.indexOfFirst { it.id == c.id }
    val j = i + dir
    if (i < 0 || j !in siblings.indices) return
    siblings.forEachIndexed { k, s ->
        val sort = when (k) { i -> j; j -> i; else -> k }
        if (s.sort != sort) app.dao.upsert(s.copy(sort = sort))
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
    val v by app.prefs.version.collectAsState()
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

@Composable
fun DataScreen(nav: Nav) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, Uri>?>(null) } // "export"|"import" to file
    var confirmReset by remember { mutableIntStateOf(0) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { pending = "export" to it } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pending = "import" to it } }
    val scope = rememberCoroutineScope()
    var clev by remember { mutableStateOf<Pair<java.io.File, ClevImport.Summary>?>(null) }
    var importing by remember { mutableStateOf(false) }
    val pickClev = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { ClevImport.copy(context, uri).let { it to ClevImport.peek(it) } } }
                .onSuccess { clev = it }
                .onFailure { nav.toast(it.message ?: "읽을 수 없는 파일이에요") }
        }
    }

    PageScaffold("백업 · 복구", onBack = nav::pop) {
        Text(
            "휴대폰 설정의 Google 백업이 켜져 있으면 내역과 설정이 하루 한 번쯤 자동으로 백업되고, 앱을 다시 설치하면 돌아와요 (스크린샷 원본은 빼고). 아래 백업 파일은 비밀번호로 암호화해서 원하는 곳에 저장해요.",
            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        ListRow("백업 파일 만들기", "암호화된 .plbak 파일로 저장해요") { create.launch("pocketlog-${LocalDate.now()}.plbak") }
        ListRow("백업 파일에서 복구", "지금 데이터는 백업 파일 내용으로 바뀌어요") { open.launch(arrayOf("*/*")) }
        GroupLabel("다른 앱에서 가져오기")
        ListRow("똑똑가계부에서 가져오기", "똑똑가계부 백업 파일(.db)을 골라 주세요. 카테고리·결제수단까지 그대로 옮겨요") { pickClev.launch(arrayOf("*/*")) }
        GroupLabel("주의")
        ListRow("데이터 초기화", "모든 내역·카테고리·결제수단을 지워요", trailing = { Text("초기화", color = pal.danger) }) { confirmReset = 1 }
    }
    pending?.let { (mode, uri) ->
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
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ClevImport.run(file) }.also { file.delete() } }
                importing = false
                nav.toast(result.fold(
                    { "가져왔어요 · 지출 ${num(it.expenses.toLong())}건 · 수입 ${num(it.incomes.toLong())}건 · 자동 분류 규칙 ${it.rules}개" },
                    { it.message ?: "가져오지 못했어요" },
                ))
            }
        }
    }
    if (importing) AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("가져오는 중이에요…", modifier = Modifier.padding(start = 16.dp))
            }
        },
    )
    if (confirmReset == 1) ConfirmDialog("정말 초기화할까요?", "모든 기록이 지워지고 되돌릴 수 없어요. 먼저 백업 파일을 만들어 두는 걸 권해요.", "다음", danger = true, onDismiss = { confirmReset = 0 }) { confirmReset = 2 }
    if (confirmReset == 2) ConfirmDialog("마지막 확인", "지금 초기화합니다.", "초기화", danger = true, onDismiss = { confirmReset = 0 }) {
        confirmReset = 0
        app.scope.launch { Backup.reset() }
        nav.toast("초기화했어요")
    }
}
