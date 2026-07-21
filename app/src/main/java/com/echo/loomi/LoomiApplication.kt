package com.echo.loomi

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class LoomiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("LoomiApp", "LoomiApplication onCreate started")
        NotificationHelper.createNotificationChannel(this)
        
        try {
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").setPersistenceEnabled(true)
                Log.d("LoomiApp", "Firebase Database persistence enabled")
            } else {
                Log.e("LoomiApp", "FirebaseApp not initialized. Check google-services.json")
            }
        } catch (e: Exception) {
            Log.e("LoomiApp", "Firebase initialization error: ${e.message}")
        }
    }
}
