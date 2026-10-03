package com.choimanseon.pocketlog

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.choimanseon.pocketlog.auto.AutoInputService
import com.choimanseon.pocketlog.ui.LockScreen
import com.choimanseon.pocketlog.ui.Nav
import com.choimanseon.pocketlog.ui.PocketTheme
import com.choimanseon.pocketlog.ui.Root
import com.choimanseon.pocketlog.ui.Screen

/** FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val nav = Nav()
    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = Pin.isSet
        AutoInputService.rebind(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            app.prefs.version.collectAsState().value // recompose on settings change
            val dark = when (app.prefs.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(bars, bars)
            }
            PocketTheme(dark) {
                if (locked) LockScreen(this, onUnlock = { locked = false }) else Root(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // lock again after a minute in the background (기획서 §4.10)
        if (Pin.isSet && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 60_000) locked = true
    }

    private fun handle(intent: Intent) {
        intent.getLongExtra(Notify.EXTRA_TX, -1).takeIf { it > 0 }?.let { nav.push(Screen.Detail(it)) }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        nav.scan(this, uris)
    }
}
