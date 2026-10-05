package com.choimanseon.pocketlog.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.choimanseon.pocketlog.ai.Scan
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.data.TxType
import com.choimanseon.pocketlog.domain.TxFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Settings : Screen
    data object Categories : Screen
    data object PayMethods : Screen
    data object AutoInputSettings : Screen
    data object Security : Screen
    data object Data : Screen
    data object BudgetEdit : Screen
    data object Favorites : Screen
    data class Reports(val start: String? = null) : Screen
    data object Review : Screen
    data object Search : Screen
    data class Detail(val id: Long) : Screen
    data class ScanResult(val jobId: Long) : Screen
    data class TxList(val title: String, val filter: TxFilter) : Screen
}

/**
 * Opens the entry sheet: new, edit (editId), or prefilled from a failed message.
 * With [favorite] the same sheet adds or edits a 즐겨찾기 instead of a transaction (TODO #38).
 */
data class Entry(val editId: Long? = null, val type: TxType = TxType.EXPENSE, val prefill: Tx? = null, val favorite: com.choimanseon.pocketlog.data.Favorite? = null)

/** ponytail: hand-rolled back stack; Navigation Compose when deep links or saved stacks matter. */
class Nav {
    val stack = mutableStateListOf<Screen>()
    var tab by mutableIntStateOf(0)
    var entry by mutableStateOf<Entry?>(null)
    var askScanConsent by mutableStateOf<List<Uri>?>(null)
    var launch by mutableStateOf<String?>(null) // "camera" | "photos" from the widget, run once Root is up
    val snackbar = SnackbarHostState()
    private val ui = MainScope()

    fun push(s: Screen) { stack += s }
    fun pop() { stack.removeLastOrNull() }

    fun toast(message: String) { ui.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) } }

    fun undo(message: String, undo: suspend () -> Unit) {
        ui.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = "되돌리기", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                app.scope.launch { undo() }
            }
        }
    }

    /** Screenshots go to the AI only after the one-time consent (기획서 §8.4). */
    fun scan(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!app.prefs.aiConsent) { askScanConsent = uris; return }
        ui.launch {
            runCatching { withContext(Dispatchers.IO) { Scan.start(context.applicationContext, uris) } }
                .onSuccess { push(Screen.ScanResult(it)) }
                .onFailure { toast(it.message ?: "이미지를 읽을 수 없어요") }
        }
    }
}

@Composable
fun Root(nav: Nav) {
    app.prefs.version.collectAsState().value // recompose when onboarding finishes
    if (!app.prefs.onboarded) {
        OnboardingScreen()
        return
    }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { nav.scan(context, it) }
    val pickScreenshots = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    // 촬영: the camera app writes into our cache through a FileProvider; Scan.start copies it right away
    val photoUri = remember {
        val file = java.io.File(context.cacheDir, "camera/photo.jpg").apply { parentFile?.mkdirs() }
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) nav.scan(context, listOf(photoUri)) }
    val takePhoto = { runCatching { camera.launch(photoUri) }.onFailure { nav.toast("카메라 앱을 열 수 없어요") }; Unit }
    LaunchedEffect(nav.launch) {
        when (nav.launch) { "camera" -> takePhoto(); "photos" -> pickScreenshots() }
        nav.launch = null
    }

    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (nav.tab) {
                    0 -> HomeTab(nav)
                    1 -> HistoryTab(nav)
                    2 -> StatsTab(nav)
                    else -> AssetsTab(nav)
                }
            }
            BottomBar(nav.tab, onTab = { nav.tab = it }, onAdd = { nav.entry = Entry() })
        }
        AnimatedContent(
            targetState = nav.stack.lastOrNull(),
            transitionSpec = {
                val forward = targetState != null && (initialState == null || nav.stack.size > 1 && nav.stack.contains(initialState))
                if (forward) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
                else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            },
            label = "screens",
        ) { screen ->
            if (screen != null) Box(Modifier.fillMaxSize().background(pal.bg)) { ScreenContent(screen, nav, pickScreenshots) }
        }
        SnackbarHost(nav.snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

    nav.entry?.let { EntrySheet(it, nav, onCamera = { nav.entry = null; takePhoto() }, onPhotos = { nav.entry = null; pickScreenshots() }, onDismiss = { nav.entry = null }) }

    nav.askScanConsent?.let { uris ->
        ConfirmDialog(
            title = "스크린샷을 AI로 분석할까요?",
            text = "선택한 이미지가 AI 분석을 위해 OpenAI로 전송돼요. Pocketlog 서버에는 저장하지 않아요. OpenAI는 API로 받은 데이터를 학습에 쓰지 않고, 악용 감시를 위해 최대 30일 보관한 뒤 지워요. " +
                "이미지에 주소·이름이 보이면 잘라서 보내도 돼요.\n\n설정 > AI에서 언제든 끌 수 있어요.",
            confirm = "동의하고 분석",
            onDismiss = { nav.askScanConsent = null },
            onConfirm = {
                app.prefs.aiConsent = true
                nav.askScanConsent = null
                nav.scan(context, uris)
            },
        )
    }
}

@Composable
private fun ScreenContent(screen: Screen, nav: Nav, pickScreenshots: () -> Unit) {
    when (screen) {
        Screen.Settings -> SettingsScreen(nav)
        Screen.Categories -> CategoriesScreen(nav)
        Screen.PayMethods -> PayMethodsScreen(nav)
        Screen.AutoInputSettings -> AutoInputSettingsScreen(nav)
        Screen.Security -> SecurityScreen(nav)
        Screen.Data -> DataScreen(nav)
        Screen.BudgetEdit -> BudgetEditScreen(nav)
        Screen.Favorites -> FavoritesScreen(nav)
        is Screen.Reports -> ReportScreen(screen.start, nav)
        Screen.Review -> ReviewScreen(nav)
        Screen.Search -> SearchScreen(nav)
        is Screen.Detail -> DetailScreen(screen.id, nav)
        is Screen.ScanResult -> ScanScreen(screen.jobId, nav, pickScreenshots)
        is Screen.TxList -> TxListScreen(screen.title, screen.filter, nav)
    }
}

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.background(pal.bg)) {
        HorizontalDivider(color = pal.divider)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            TabItem(Icons.Rounded.Home, "홈", tab == 0) { onTab(0) }
            TabItem(Icons.AutoMirrored.Rounded.ReceiptLong, "내역", tab == 1) { onTab(1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(pal.brand).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "기록하기", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
            TabItem(Icons.Rounded.PieChart, "분석", tab == 2) { onTab(2) }
            TabItem(Icons.Rounded.AccountBalanceWallet, "자산", tab == 3) { onTab(3) }
        }
    }
}

@Composable
private fun RowScope.TabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val color = if (selected) pal.text else pal.faint
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 2.dp))
    }
}
