package com.choimanseon.pocketlog.auto

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.choimanseon.pocketlog.app
import kotlinx.coroutines.launch

data class Incoming(val title: String, val body: String, val time: Long)

/**
 * Messages in one notification. SMS apps use MessagingStyle: once several texts pile up, the plain
 * text becomes "새 메시지 2개", so each recent message is read on its own. Older messages in the
 * conversation history are skipped; they were handled when they arrived.
 */
fun incomingMessages(n: Notification, postTime: Long): List<Incoming> {
    val extras = n.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages.orEmpty()
        .filter { it.timestamp >= postTime - 10 * 60_000 && !it.text.isNullOrBlank() }
    if (messages.isNotEmpty()) return messages.takeLast(5).map {
        Incoming(it.person?.name?.toString() ?: title, it.text.toString(), it.timestamp.takeIf { t -> t > 0 } ?: postTime)
    }
    val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    return if (body.isBlank()) emptyList() else listOf(Incoming(title, body, postTime))
}

/** Reads card / bank / pay notifications (including SMS notifications) while the user has granted notification access. */
class AutoInputService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!app.prefs.autoInput || sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val pkg = sbn.packageName
        val messages = incomingMessages(n, sbn.postTime)
        if (messages.isEmpty()) return
        // this runs for every notification on the phone: one odd message must not take the whole app down
        app.scope.launch { messages.forEach { m -> runCatching { AutoInput.handle(pkg, m.title, m.body, m.time) }.onFailure { Log.w("AutoInput", it) } } }
    }

    override fun onListenerConnected() {
        connected = this
        catchUp()
    }

    override fun onListenerDisconnected() {
        connected = null
    }

    companion object {
        private var connected: AutoInputService? = null

        /**
         * Reads what is still in the notification shade (TODO #48). A notification can slip by while the system has the app
         * frozen or unbound; one already read is skipped by its body, so this is safe to run any time.
         */
        fun catchUp() = connected?.let { s -> runCatching { s.activeNotifications?.forEach(s::onNotificationPosted) } }

        fun granted(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** 배터리 '제한 없음' (TODO #46): phones put idle apps to sleep, and a sleeping listener can miss a notification. */
        fun unrestricted(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

        fun askUnrestricted(context: Context) = runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }.recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

        /** Some OEMs unbind listeners after app updates; ask the system to bind again. */
        fun rebind(context: Context) {
            if (granted(context)) requestRebind(ComponentName(context, AutoInputService::class.java))
        }
    }
}
