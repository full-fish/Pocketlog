package com.choimanseon.pocketlog

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.choimanseon.pocketlog.data.Category
import com.choimanseon.pocketlog.data.Tx
import com.choimanseon.pocketlog.domain.josa
import com.choimanseon.pocketlog.domain.won

object Notify {
    const val CH_SAVED = "saved"
    const val CH_BUDGET = "budget"
    const val EXTRA_TX = "txId"

    private fun allowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, n: Notification) {
        if (!allowed()) return
        try {
            NotificationManagerCompat.from(app).notify(id, n)
        } catch (_: SecurityException) {
            // permission revoked between the check and the call
        }
    }

    fun cancel(txId: Long) = NotificationManagerCompat.from(app).cancel(txId.toInt())

    private fun open(txId: Long?): PendingIntent {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (txId != null) putExtra(EXTRA_TX, txId) }
        return PendingIntent.getActivity(app, (txId ?: 0L).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun saved(tx: Tx, category: Category?, needsReview: Boolean) {
        if (!allowed() || !app.prefs.notifyOnSave) return
        val what = tx.merchant.ifBlank { "결제" }
        val text = when {
            needsReview -> "확인이 필요해요. 눌러서 확인해 주세요."
            category != null -> "${category.name.josa("으로", "로")} 기록했어요"
            else -> "기록했어요. 눌러서 카테고리를 정해 주세요."
        }
        val n = NotificationCompat.Builder(app, CH_SAVED)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${category?.emoji ?: "🧾"} $what ${won(tx.amount)}")
            .setContentText(text)
            .setContentIntent(open(tx.id))
            .setAutoCancel(true)
            .build()
        post(tx.id.toInt(), n)
    }

    fun budget(percent: Int, remaining: Long) {
        if (!allowed()) return
        val text = if (percent >= 100) "이번 달 예산을 ${won(-remaining)} 넘었어요" else "남은 예산은 ${won(remaining)}이에요"
        val n = NotificationCompat.Builder(app, CH_BUDGET)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("예산의 $percent%를 썼어요")
            .setContentText(text)
            .setContentIntent(open(null))
            .setAutoCancel(true)
            .build()
        post(-1, n)
    }
}
