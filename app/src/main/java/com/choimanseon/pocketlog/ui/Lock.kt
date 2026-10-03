package com.choimanseon.pocketlog.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.choimanseon.pocketlog.Pin
import com.choimanseon.pocketlog.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun biometricAvailable(context: Context) =
    BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun askBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Pocketlog 잠금 해제")
            .setNegativeButtonText("비밀번호 입력")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
    )
}

@Composable
fun LockScreen(activity: FragmentActivity, onUnlock: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var fails by remember { mutableIntStateOf(0) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    val useBio = app.prefs.biometric && biometricAvailable(activity)
    LaunchedEffect(Unit) { if (useBio) askBiometric(activity, onUnlock) }

    Box(Modifier.fillMaxSize().background(pal.bg).systemBarsPadding()) {
        PinPad(
            title = "비밀번호를 입력해 주세요",
            error = error,
            onBiometric = if (useBio) ({ askBiometric(activity, onUnlock) }) else null,
        ) { pin ->
            if (System.currentTimeMillis() < waitUntil) { error = "잠시 후 다시 시도해 주세요"; return@PinPad false }
            if (Pin.check(pin)) { onUnlock(); true }
            else {
                fails++
                if (fails % 5 == 0) waitUntil = System.currentTimeMillis() + 30_000
                error = if (fails % 5 == 0) "5번 틀렸어요. 30초 후 다시 시도해 주세요" else "비밀번호가 달라요 ($fails/5)"
                false
            }
        }
    }
}

/** 6-digit PIN keypad. [onComplete] returns false to shake and clear. */
@Composable
fun PinPad(title: String, error: String? = null, onBiometric: (() -> Unit)? = null, onComplete: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text("🔒", fontSize = 36.sp)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = pal.danger, modifier = Modifier.padding(top = 8.dp).height(20.dp))
        Row(Modifier.padding(vertical = 24.dp).offset { IntOffset(shake.value.dp.roundToPx(), 0) }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(Pin.LENGTH) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) pal.brand else pal.surface2))
            }
        }
        Spacer(Modifier.weight(1f))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", if (onBiometric != null) "지문" else "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(64.dp).clip(CircleShape).clickable(enabled = k.isNotEmpty()) {
                            when (k) {
                                "⌫" -> pin = pin.dropLast(1)
                                "지문" -> onBiometric?.invoke()
                                else -> if (pin.length < Pin.LENGTH) {
                                    pin += k
                                    if (pin.length == Pin.LENGTH) {
                                        val entered = pin
                                        scope.launch {
                                            delay(120)
                                            if (!onComplete(entered)) {
                                                for (x in listOf(12f, -12f, 8f, -8f, 0f)) shake.animateTo(x, tween(50))
                                            }
                                            pin = ""
                                        }
                                    }
                                }
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, fontSize = if (k.length > 1) 15.sp else 26.sp, color = pal.text) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
