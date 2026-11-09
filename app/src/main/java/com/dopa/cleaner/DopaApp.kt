package com.dopa.cleaner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DopaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            },
        )
    }

    companion object {
        const val CHANNEL_SCAN = "scan"
    }
}
