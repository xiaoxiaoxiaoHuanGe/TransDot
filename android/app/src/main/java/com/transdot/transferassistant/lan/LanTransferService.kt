package com.transdot.transferassistant.lan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.transdot.transferassistant.R
import com.transdot.transferassistant.ui.LanForegroundController
import com.transdot.transferassistant.ui.LanTransferDirection

class AndroidLanForegroundController(context: Context) : LanForegroundController {
    private val appContext = context.applicationContext

    override fun start(direction: LanTransferDirection, filename: String, progress: Int, onCancel: () -> Unit) {
        LanTransferService.cancelHandler = onCancel
        ContextCompat.startForegroundService(appContext, LanTransferService.intent(appContext, direction, filename, progress))
    }

    override fun update(direction: LanTransferDirection, filename: String, progress: Int) {
        appContext.startService(LanTransferService.intent(appContext, direction, filename, progress))
    }

    override fun stop() {
        LanTransferService.cancelHandler = null
        appContext.stopService(Intent(appContext, LanTransferService::class.java))
    }
}

class LanTransferService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "局域网文件传输", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelHandler?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val filename = intent?.getStringExtra(EXTRA_FILENAME).orEmpty().ifBlank { "文件" }
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0)?.coerceIn(0, 100) ?: 0
        val direction = intent?.getStringExtra(EXTRA_DIRECTION).orEmpty()
        val label = if (direction == LanTransferDirection.Receiving.name) "正在从电脑接收" else "正在发送到电脑"
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, LanTransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label)
            .setContentText(filename)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "取消", cancelIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile internal var cancelHandler: (() -> Unit)? = null
        private const val CHANNEL_ID = "lan_transfer_active"
        private const val NOTIFICATION_ID = 2101
        private const val ACTION_CANCEL = "com.transdot.transferassistant.lan.CANCEL"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_PROGRESS = "progress"

        fun intent(context: Context, direction: LanTransferDirection, filename: String, progress: Int) =
            Intent(context, LanTransferService::class.java)
                .putExtra(EXTRA_DIRECTION, direction.name)
                .putExtra(EXTRA_FILENAME, filename)
                .putExtra(EXTRA_PROGRESS, progress)
    }
}
