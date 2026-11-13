package com.dopa.cleaner.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dopa.cleaner.DopaApp
import com.dopa.cleaner.R
import com.dopa.cleaner.scan.ScanState
import com.dopa.cleaner.ui.MainActivity
import kotlinx.coroutines.launch

class ScanService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dopa:scan").apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) {
            (application as DopaApp).container.engine.cancel()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            build(ScanState(running = true)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()
        val engine = (application as DopaApp).container.engine
        lifecycleScope.launch {
            engine.state.collect { s ->
                if (s.running) {
                    if (canNotify()) {
                        try {
                            NotificationManagerCompat.from(this@ScanService).notify(NOTIF_ID, build(s))
                        } catch (e: SecurityException) {
                            Unit
                        }
                    }
                } else {
                    releaseWakeLock()
                    ServiceCompat.stopForeground(this@ScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun build(s: ScanState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, DopaApp.CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Varrendo ${s.currentSource ?: "…"}")
            .setContentText("${s.scanned + s.cached} verificados · ${s.flagged} sinalizados · roda com a tela apagada")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .addAction(0, "Cancelar", cancel)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val WAKE_TIMEOUT_MS = 6L * 60 * 60 * 1000
        private const val ACTION_CANCEL = "com.dopa.cleaner.CANCEL"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScanService::class.java))
        }
    }
}
