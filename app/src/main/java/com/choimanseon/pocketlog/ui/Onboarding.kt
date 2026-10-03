package com.choimanseon.pocketlog.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.choimanseon.pocketlog.app
import com.choimanseon.pocketlog.auto.AutoInputService

private const val STEPS = 6

/** First run (기획서 S01): what the app does, then the settings auto-recording needs. [onDone] may open a screen next. */
@Composable
fun OnboardingScreen(onDone: (Screen?) -> Unit) {
    val context = LocalContext.current
    val p = app.prefs
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(p.myName) }
    var startDay by rememberSaveable { mutableIntStateOf(p.monthStartDay) }
    var pickDay by remember { mutableStateOf(false) }
    var listenerOn by remember { mutableStateOf(AutoInputService.granted(context)) }
    LifecycleResumeEffect(Unit) {
        listenerOn = AutoInputService.granted(context)
        onPauseOrDispose { }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    BackHandler(step > 0) { step-- }

    fun finish(next: Screen? = null) {
        p.myName = name.trim()
        p.monthStartDay = startDay
        p.onboarded = true
        onDone(next)
    }

    Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterEnd) {
            if (step < 3) TextButton(onClick = { step = 3 }) { Text("건너뛰기", color = pal.sub) }
        }
        AnimatedContent(step, Modifier.weight(1f), label = "onboarding") { s ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                when (s) {
                    0 -> Page("💳", "카드 문자가 오면\n알아서 적어요", "카드·은행·페이 알림과 문자를 읽어 바로 기록해요. 같은 결제가 두 번 와도 한 번만 적고, 취소되면 지워요.")
                    1 -> Page("📸", "문자가 안 오는 결제는\n스크린샷 한 장으로", "쿠팡머니처럼 알림이 없는 결제는 주문내역 스크린샷을 공유하면 AI가 품목까지 나눠 적어요.")
                    2 -> Page("🙋", "나에게 들어오고\n나가는 돈만", "내 계좌끼리 옮긴 돈, 카드값, 페이머니 충전은 수입·지출이 아니라 적지 않아요. 할부는 매달 나눠 적어요.")
                    3 -> {
                        Page("🔔", "카드 문자 자동 기록 켜기", "알림 접근을 허용하면 결제 알림만 골라 읽어요. 결제가 아닌 메시지는 저장하지 않아요.")
                        Spacer(Modifier.height(24.dp))
                        if (listenerOn) Text("✓ 켜졌어요", style = MaterialTheme.typography.titleSmall, color = pal.income)
                        else {
                            PrimaryButton("알림 접근 허용하기", {
                                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            })
                            Text(
                                "'제한된 설정' 창이 뜨면: 휴대폰 설정 → 애플리케이션 → Pocketlog → 오른쪽 위 ⋮ → 제한된 설정 허용 후 다시 켜 주세요.",
                                style = MaterialTheme.typography.bodySmall, color = pal.sub, modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    4 -> {
                        Page("🏦", "내 이름과 한 달 시작일", "은행 알림에 내 이름이 받는 사람·보낸 사람으로 나오면 내 계좌끼리 옮긴 돈이라 적지 않아요.")
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
                    else -> Page("📦", "예전 가계부가 있나요?", "똑똑가계부 백업 파일(.db)이 있으면 내역·카테고리·결제수단을 그대로 옮겨요. 나중에 설정 → 백업 · 복구에서 해도 돼요.")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(STEPS) { i ->
                Box(Modifier.padding(3.dp).size(if (i == step) 18.dp else 6.dp, 6.dp).clip(CircleShape).background(if (i == step) pal.brand else pal.surface2))
            }
        }
        if (step < STEPS - 1) PrimaryButton("다음", { step++ })
        else {
            PrimaryButton("똑똑가계부에서 가져오기", { finish(Screen.Data) })
            TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("새로 시작하기", color = pal.sub) }
        }
    }
    if (pickDay) ChoiceDialog("한 달 시작일", (1..28).map { "매월 ${it}일" }, startDay - 1, { pickDay = false }) { startDay = it + 1; pickDay = false }
}

@Composable
private fun Page(emoji: String, title: String, body: String) {
    Text(emoji, fontSize = 48.sp)
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = pal.sub, modifier = Modifier.padding(top = 12.dp))
}
