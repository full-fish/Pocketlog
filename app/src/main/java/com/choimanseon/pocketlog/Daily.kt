package com.choimanseon.pocketlog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.choimanseon.pocketlog.auto.Repeats
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Once a day around 9:00, also when the app is closed: 반복 기록, 월간 리포트, 드라이브 자동 백업. App start runs it too. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Daily.run()
        return Result.success()
    }
}

object Daily {
    suspend fun run() {
        runCatching { Repeats.runDue() }
        runCatching { com.choimanseon.pocketlog.ai.MonthlyReport.makeIfDue() } // offline: tried again at the next run or app start
        runCatching { Drive.autoBackup(app) }
        com.choimanseon.pocketlog.ui.PocketWidget.refresh(app) // "오늘 써도 되는 금액" changes with the day
    }

    /** KEEP: opening the app doesn't move the schedule. ponytail: periodic work drifts by minutes to hours, fine for once a day. */
    fun schedule(context: Context) = runCatching {
        val now = LocalDateTime.now()
        val nine = now.toLocalDate().atTime(9, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS).setInitialDelay(Duration.between(now, nine).toMillis(), TimeUnit.MILLISECONDS).build(),
        )
    }
}
