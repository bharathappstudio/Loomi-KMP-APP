package com.echo.loomi

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class LoomiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("LoomiApp", "LoomiApplication onCreate started")
        NotificationHelper.createNotificationChannel(this)
        
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var activityCount = 0

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                isAppInForeground = true
            }
            override fun onActivityResumed(activity: Activity) {
                isAppInForeground = true
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount <= 0) {
                    isAppInForeground = false
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        try {
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").setPersistenceEnabled(false)
                Log.d("LoomiApp", "Firebase Database persistence disabled")
            }
        } catch (e: Exception) {
            Log.e("LoomiApp", "Firebase initialization error: ${e.message}")
        }
    }
}
