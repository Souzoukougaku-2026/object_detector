package com.example.wasuremono_prj.joint

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.wasuremono_prj.R
import kotlinx.coroutines.delay

const val TAG = "MLWorker"

class MLWorker(
    private val context: Context, parameters: WorkerParameters
) :
    CoroutineWorker(context, parameters) {
    private val notificationManager by lazy {
        context.getSystemService(NotificationManager::class.java) as NotificationManager
    }

    private val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("落とし物解析を実行中")
        .setContentText(null)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setProgress(100, 0, false)


    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())
        Log.d(TAG, "MLWorkerが開始されました")
        
        try {
            sampleWork()
        } catch (e: Exception) {
            Log.d(TAG, e.message ?: "Exception Occurred")
            return Result.failure()
        }
        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createChannel(context)

        val notification = notificationBuilder.build()

        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "物体検出の完了通知",
                NotificationManager.IMPORTANCE_HIGH,
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "ml_worker"
        private const val NOTIFICATION_ID = 1001
    }

    // 動作確認用の関数
    private suspend fun sampleWork() {
        val minutes = 35;
        repeat(minutes) { min ->
            delay(60_000L)

            setProgress(
                workDataOf(
                    "progress" to ((min + 1) * 100 / 3)
                )
            )

            val updatedNotification = notificationBuilder
                .setProgress(100, ((min + 1) * 100 / minutes), false)
                .build()

            notificationManager.notify(NOTIFICATION_ID, updatedNotification)
        }
    }
}
