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
    const val CH_SCAN = "scan"
    const val CH_REPORT = "report"
    const val EXTRA_REPORT = "reportStart"
    const val EXTRA_TX = "txId"
    const val EXTRA_SCAN = "scanJobId"

    private fun scanNoticeId(jobId: Long) = -1000 - jobId.toInt() // tx ids are positive, budget is -1

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

    private fun open(txId: Long?, scanId: Long? = null): PendingIntent {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (txId != null) putExtra(EXTRA_TX, txId) }
            .apply { if (scanId != null) putExtra(EXTRA_SCAN, scanId) }
        val code = scanId?.let(::scanNoticeId) ?: (txId ?: 0L).toInt()
        return PendingIntent.getActivity(app, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A screenshot analysis finished while the user was elsewhere (TODO #20). */
    fun scan(jobId: Long, title: String, text: String) {
        val n = NotificationCompat.Builder(app, CH_SCAN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(null, jobId))
            .setAutoCancel(true)
            .build()
        post(scanNoticeId(jobId), n)
    }

    /** AI 월간 리포트 is ready: opens it. */
    fun report(r: com.choimanseon.pocketlog.data.Report) {
        if (!allowed()) return
        val headline = runCatching { org.json.JSONObject(r.json).getJSONObject("text").getString("headline") }.getOrDefault("")
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_REPORT, r.start)
        val n = NotificationCompat.Builder(app, CH_REPORT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${java.time.LocalDate.parse(r.start).let { com.choimanseon.pocketlog.domain.Period(it, java.time.LocalDate.parse(r.end)).label() }} 리포트가 왔어요")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(headline))
            .setContentIntent(PendingIntent.getActivity(app, -2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        post(-2, n)
    }

    fun cancelScan(jobId: Long) = NotificationManagerCompat.from(app).cancel(scanNoticeId(jobId))

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
            .setContentTitle("$what ${won(tx.amount)}" + if (tx.installmentMonths > 1) " · ${tx.installmentMonths}개월 할부" else "")
            .setContentText(text)
            .setContentIntent(open(tx.id))
            .setAutoCancel(true)
            .build()
        post(tx.id.toInt(), n)
    }

    /** [label]: 이번 주 · 이번 달 · 올해 */
    fun budget(label: String, percent: Int, remaining: Long) {
        if (!allowed()) return
        val text = if (percent >= 100) "$label 예산을 ${won(-remaining)} 넘었어요" else "남은 예산은 ${won(remaining)}이에요"
        val n = NotificationCompat.Builder(app, CH_BUDGET)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$label 예산의 $percent%를 썼어요")
            .setContentText(text)
            .setContentIntent(open(null))
            .setAutoCancel(true)
            .build()
        post(-1, n)
    }
}
