package com.choimanseon.pocketlog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.AutoInputService
import kotlinx.coroutines.launch

private const val STEPS = 8
private const val PRIVACY_URL = "https://full-fish.github.io/privacy/pocketlog/"

/**
 * First run (기획서 S01): what the app does, then what it needs. 다음 waits for each permission and the AI consent; the
 * Google Drive backup can be skipped. The settings that need no permission are on from the start (Prefs).
 */
@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val p = app.prefs
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(p.myName) }
    var startDay by rememberSaveable { mutableIntStateOf(p.monthStartDay) }
    var pickDay by remember { mutableStateOf(false) }
    // 기록할 때 · 예산 알림 (Android 13+)
    fun canPost() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var listenerOn by remember { mutableStateOf(AutoInputService.granted(context)) }
    var postOn by remember { mutableStateOf(canPost()) }
    var unrestricted by remember { mutableStateOf(AutoInputService.unrestricted(context)) }
    var aiConsent by remember { mutableStateOf(p.aiConsent) }
    var backupOn by remember { mutableStateOf(p.driveAuto) }
    LifecycleResumeEffect(Unit) {
        listenerOn = AutoInputService.granted(context)
        postOn = canPost()
        unrestricted = AutoInputService.unrestricted(context)
        onPauseOrDispose { }
    }
    val listenerSettings = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    // turned down once or twice, Android stops asking: the app's notification settings instead
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        postOn = granted
        if (!granted) runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        else if (!listenerOn) listenerSettings()
    }
    val scope = rememberCoroutineScope()
    val driveToken = rememberDriveToken()
    var askPassword by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val toast = { text: String -> android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() }
    BackHandler(step > 0) { step-- }

    fun finish() {
        p.myName = name.trim()
        p.monthStartDay = startDay
        p.onboarded = true // Root recomposes on settings change
    }

    Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp)) {
        Spacer(Modifier.height(48.dp)) // no 건너뛰기: the name and notification steps come right after the intro (TODO #14)
        AnimatedContent(step, Modifier.weight(1f), label = "onboarding") { s ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                when (s) {
                    0 -> Page(Icons.Rounded.CreditCard, "카드 문자가 오면\n알아서 적어요", "카드·은행·페이 알림과 문자를 읽어 바로 기록해요. 같은 결제가 두 번 와도 한 번만 적고, 취소되면 지워요.")
                    1 -> Page(Icons.Rounded.PhotoCamera, "문자가 안 오는 결제는\n스크린샷 한 장으로", "쇼핑·배달·페이 앱 어디든 주문내역 스크린샷이나 영수증 사진을 보내면 AI가 품목까지 나눠 적어요.")
                    2 -> Page(Icons.Rounded.PersonOutline, "나에게 들어오고\n나가는 돈만", "내 계좌끼리 옮긴 돈, 카드값, 페이머니 충전은 수입·지출이 아니라 적지 않아요. 할부는 매달 나눠 적어요.")
                    3 -> {
                        Page(Icons.Rounded.NotificationsActive, "카드 문자 자동 기록 켜기", "알림 접근을 허용하면 결제 알림만 골라 읽어요. 결제가 아닌 메시지는 저장하지 않아요. 기록할 때와 예산을 넘을 때는 알림으로 알려 드려요.")
                        Spacer(Modifier.height(24.dp))
                        Done("결제 알림 읽기", listenerOn)
                        if (Build.VERSION.SDK_INT >= 33) Done("기록 · 예산 알림 보내기", postOn)
                        if (!listenerOn || !postOn) {
                            PrimaryButton("알림 허용하기", { if (!postOn) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else listenerSettings() }, Modifier.padding(top = 12.dp))
                            Text(
                                "'제한된 설정' 창이 뜨면: 휴대폰 설정 → 애플리케이션 → Pocketlog → 오른쪽 위 ⋮ → 제한된 설정 허용 후 다시 켜 주세요.",
                                style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    4 -> {
                        Page(Icons.Rounded.BatteryChargingFull, "결제 알림을 놓치지 않게", "휴대폰은 배터리를 아끼려고 한동안 안 쓴 앱을 잠재워요. 그때 온 카드 문자는 놓칠 수 있어서, 배터리 사용을 '제한 없음'으로 둬야 해요. 알림이 올 때만 잠깐 깨어나서 배터리는 거의 쓰지 않아요.")
                        Spacer(Modifier.height(24.dp))
                        if (unrestricted) Done("제한 없음이에요", true)
                        else PrimaryButton("배터리 제한 없음으로 하기", { AutoInputService.askUnrestricted(context) })
                    }
                    5 -> {
                        // asked once here, before anything is sent: the overseas transfer needs the user's yes (PRIVACY.md 3, 4)
                        Page(
                            Icons.Rounded.AutoAwesome, "AI 분석에 동의해 주세요",
                            "스크린샷 · 영수증 분석, 읽지 못한 결제 알림 다시 읽기, 처음 보는 가맹점 분류, 월간 리포트에 AI를 써요. " +
                                "이때 이미지와 문구를 AI 서버(Cloudflare)를 거쳐 OpenAI(미국)로 보내요. 서버는 내용을 남기지 않고, OpenAI는 학습에 쓰지 않고 30일 안에 지워요.",
                        )
                        Spacer(Modifier.height(24.dp))
                        if (aiConsent) Done("동의했어요", true)
                        else PrimaryButton("동의하고 AI 쓰기", { p.aiConsent = true; aiConsent = true })
                        Text(
                            "나중에 설정 → AI에서 끌 수 있어요. 끄면 직접 입력과 카드 문자 자동 기록만 돼요.",
                            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 12.dp),
                        )
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) } }, contentPadding = PaddingValues(0.dp)) {
                            Text("개인정보처리방침 보기")
                        }
                    }
                    6 -> {
                        Page(Icons.Rounded.CloudUpload, "Google 드라이브에 매일 백업할까요?", "Google 계정에 연결하고 백업 비밀번호를 정하면 하루 한 번 내 드라이브에 암호화해서 올려요. 휴대폰을 바꾸거나 앱을 지워도 되살릴 수 있어요.")
                        Spacer(Modifier.height(24.dp))
                        if (backupOn) Done("매일 자동 백업이 켜졌어요", true)
                        else PrimaryButton("Google 계정 연결하기", {
                            scope.launch {
                                connecting = true
                                runCatching { driveToken() }.fold({ if (it == null) toast("Google 계정 연결을 취소했어요") else askPassword = true }, { toast(driveError(it)) })
                                connecting = false
                            }
                        }, enabled = !connecting)
                        Text(
                            "건너뛰어도 돼요. 설정 → 백업 · 복구에서 언제든 켤 수 있어요.",
                            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    else -> {
                        Page(Icons.Rounded.AccountBalance, "내 이름과 한 달 시작일", "은행 알림에 내 이름이 받는 사람·보낸 사람으로 나오면 내 계좌끼리 옮긴 돈이라 적지 않아요.")
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, singleLine = true, label = { Text("내 이름 (통장에 찍히는 실명)") },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        )
                        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("한 달 시작일", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Chip(if (startDay == 1) "매월 1일" else "매월 ${startDay}일 (월급날)", true) { pickDay = true }
                        }
                        Text(
                            "결제수단은 카드 문자가 오면 자동으로 만들어져요. 설정에서 언제든 바꿀 수 있어요.",
                            style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(STEPS) { i ->
                Box(Modifier.padding(3.dp).size(if (i == step) 18.dp else 6.dp, 6.dp).clip(CircleShape).background(if (i == step) pal.brand else pal.surface2))
            }
        }
        // no 다음 before what the step asks for; only the backup can be skipped
        val waiting = when (step) {
            3 -> "알림을 허용하면 다음으로 가요".takeIf { !listenerOn || !postOn }
            4 -> "제한 없음으로 하면 다음으로 가요".takeIf { !unrestricted }
            5 -> "동의하면 다음으로 가요".takeIf { !aiConsent }
            else -> null
        }
        if (step < STEPS - 1) PrimaryButton(waiting ?: if (step == 6 && !backupOn) "건너뛰기" else "다음", { step++ }, enabled = waiting == null)
        else PrimaryButton("시작하기", { finish() })
    }
    if (askPassword) AutoBackupPassword(driveToken, toast) { askPassword = false; backupOn = p.driveAuto }
    if (pickDay) ChoiceDialog("한 달 시작일", (1..28).map { "매월 ${it}일" }, startDay - 1, { pickDay = false }) { startDay = it + 1; pickDay = false }
}

@Composable
private fun Done(text: String, on: Boolean) = Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Rounded.CheckCircle, null, Modifier.padding(end = 6.dp).size(20.dp), tint = if (on) pal.income else pal.surface2)
    Text(text, style = MaterialTheme.typography.titleSmall, color = if (on) pal.income else pal.sub)
}

@Composable
private fun Page(icon: ImageVector, title: String, body: String) {
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(pal.brandSoft), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(32.dp), tint = pal.brand)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = pal.sub, modifier = Modifier.padding(top = 12.dp))
}
