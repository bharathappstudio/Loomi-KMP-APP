package com.echo.loomi

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

class SecurityWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        
        if (user == null) {
            NotificationHelper.showSecurityNotification(
                applicationContext,
                "Security Alert: Encryption inactive. Login required for E2E."
            )
            return Result.success()
        }

        // 1. Real Encryption Verification
        val testPlain = "loomi_secure_scan_${System.currentTimeMillis()}"
        val testEncrypted = EncryptionUtils.encrypt(testPlain)
        val testDecrypted = EncryptionUtils.decrypt(testEncrypted)
        val isEncryptionWorking = testDecrypted == testPlain

        // 2. Check Permissions
        val hasLocation = ContextCompat.checkSelfPermission(
            applicationContext, 
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // 3. Status Notification Logic
        when {
            !isEncryptionWorking -> {
                NotificationHelper.showSecurityNotification(
                    applicationContext,
                    "⚠️ Security Hazard: End-to-End Encryption Protocol failure!"
                )
            }
            !hasLocation -> {
                NotificationHelper.showSecurityNotification(
                    applicationContext,
                    "Privacy Scan: Encryption active, but SOS features are restricted."
                )
            }
            else -> {
                NotificationHelper.showSecurityNotification(
                    applicationContext,
                    "Your end-to-end is verified."
                )
            }
        }

        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SecurityWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "SecurityScan",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
