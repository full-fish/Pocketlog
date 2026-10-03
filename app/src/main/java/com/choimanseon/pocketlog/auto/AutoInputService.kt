package com.choimanseon.pocketlog.auto

import android.app.Notification
import android.content.ComponentName
import android.content.Context
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

    companion object {
        fun granted(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Some OEMs unbind listeners after app updates; ask the system to bind again. */
        fun rebind(context: Context) {
            if (granted(context)) requestRebind(ComponentName(context, AutoInputService::class.java))
        }
    }
}
