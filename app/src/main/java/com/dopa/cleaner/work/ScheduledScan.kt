package com.dopa.cleaner.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dopa.cleaner.DopaApp
import com.dopa.cleaner.R
import com.dopa.cleaner.config.ScanSchedule
import com.dopa.cleaner.source.local.LocalStorageSource
import com.dopa.cleaner.ui.MainActivity
import java.util.concurrent.TimeUnit

object ScanScheduler {
    private const val WORK_NAME = "periodic_scan"

    fun apply(context: Context, schedule: ScanSchedule) {
        val wm = WorkManager.getInstance(context)
        val days = when (schedule) {
            ScanSchedule.OFF -> {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            ScanSchedule.DAILY -> 1L
            ScanSchedule.WEEKLY -> 7L
        }
        val request = PeriodicWorkRequestBuilder<ScheduledScanWorker>(days, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class ScheduledScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, DopaApp.CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Varredura agendada em andamento")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as DopaApp).container
        if (container.engine.state.value.running) return Result.success()
        val settings = container.settings.current()
        if (settings.schedule == ScanSchedule.OFF) return Result.success()
        val built = container.buildSources(settings, interactive = false)
        val sources = built.sources.filter { it.id != LocalStorageSource.ID || Environment.isExternalStorageManager() }
        if (sources.isEmpty()) return Result.success()
        runCatching { setForeground(foregroundInfo()) }
        val started = runCatching { container.engine.start(sources, settings.threshold, settings.options.speed) }.getOrDefault(false)
        if (!started) return Result.success()
        val final = try {
            container.engine.awaitIdle()
        } catch (e: kotlinx.coroutines.CancellationException) {
            container.engine.cancel()
            throw e
        }
        notifyResult(final.flagged, final.sourceErrors.size)
        return Result.success()
    }

    private fun notifyResult(flagged: Int, problems: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            applicationContext, 2, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append(if (flagged == 0) "Nada para revisar" else "$flagged itens para revisar")
            if (problems > 0) append(" · $problems fontes com erro")
        }
        val n = NotificationCompat.Builder(applicationContext, DopaApp.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Varredura concluída")
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_RESULT, n)
        } catch (e: SecurityException) {
            Unit
        }
    }

    companion object {
        private const val NOTIF_PROGRESS = 43
        private const val NOTIF_RESULT = 44
    }
}
