package com.echo.loomi

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class LoomiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        try {
            FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").setPersistenceEnabled(true)
        } catch (e: Exception) {
            // Already initialized
        }
    }
}
