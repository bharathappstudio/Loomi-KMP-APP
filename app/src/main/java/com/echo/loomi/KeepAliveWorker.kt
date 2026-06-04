package com.echo.loomi

import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serviceIntent = Intent(applicationContext, MessageListenerService::class.java)
        try {
            // Start as a normal service, not a foreground service, to avoid notifications
            applicationContext.startService(serviceIntent)
        } catch (e: Exception) {
            // Ignore
        }
        return Result.success()
    }

    // Removed getForegroundInfo() to avoid showing a notification during work execution

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "LoomiKeepAlive",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
