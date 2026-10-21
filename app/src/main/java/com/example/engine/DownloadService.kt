package com.example.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DownloadService : Service() {
    private val channelId = "mugen_download"
    private val notificationId = 1001
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MUGEN Engine Data")
            .setContentText("Preparing to download...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notificationId, builder.build())
        }

        scope.launch {
            EngineDataDownloader.progressMessage.collectLatest { msg ->
                val state = EngineDataDownloader.downloadState.value
                if (state == DownloadState.SUCCESS || state == DownloadState.ERROR || state == DownloadState.IDLE) {
                    stopForeground(true)
                    stopSelf()
                } else {
                    val progress = EngineDataDownloader.progressPercentage.value
                    val max = 100
                    val current = (progress * 100).toInt()
                    val isIndeterminate = progress <= 0f
                    
                    builder.setContentText(msg)
                    if (state == DownloadState.EXTRACTING) {
                        builder.setProgress(0, 0, true)
                    } else {
                        builder.setProgress(max, current, isIndeterminate)
                    }
                    
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(notificationId, builder.build())
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
