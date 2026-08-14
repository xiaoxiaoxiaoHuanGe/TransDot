package com.transdot.transferassistant.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.transdot.transferassistant.R

interface TransferNotifier {
    fun uploadFinished(successCount: Int, failureCount: Int)
    fun downloadFinished(filename: String, success: Boolean)

    data object None : TransferNotifier {
        override fun uploadFinished(successCount: Int, failureCount: Int) = Unit
        override fun downloadFinished(filename: String, success: Boolean) = Unit
    }
}

class SystemTransferNotifier(
    context: Context,
    private val preferences: AppPreferences,
) : TransferNotifier {
    private val applicationContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(applicationContext)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "文件传输结果", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    override fun uploadFinished(successCount: Int, failureCount: Int) {
        val text = if (failureCount == 0) "已上传 $successCount 个文件" else "已上传 $successCount 个，$failureCount 个失败"
        notify("上传完成", text)
    }

    override fun downloadFinished(filename: String, success: Boolean) {
        notify(if (success) "文件已保存" else "文件保存失败", filename)
    }

    private fun notify(title: String, text: String) {
        if (!preferences.load().notificationsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(
            nextNotificationId++,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "transfer_results"
        var nextNotificationId = 1000
    }
}
