package com.echo.loomi

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class LoomiFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token: $token")
        // Update token in database for the current user
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            val dbRef = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
            // Only update token if user exists in DB to avoid creating ghost users
            dbRef.child("users").child(uid).child("name").get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    dbRef.child("users").child(uid).child("fcmToken").setValue(token)
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        Log.d("FCM", "Message received from: ${message.from}")

        // Ensure CPU is awake to process the message
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Loomi:FCMWakeLock")
        wakeLock.acquire(5000)

        try {
            // Handle data messages even when app is closed
            val data = message.data
            if (data.isNotEmpty()) {
                Log.d("FCM", "Data payload: $data")
                val type = data["type"] ?: "message"
                
                if (type == "call") {
                    val callerId = data["callerId"] ?: ""
                    val callerName = data["callerName"] ?: "Unknown"
                    val callerImage = data["callerImage"] ?: ""
                    
                    NotificationHelper.showCallNotification(
                        applicationContext,
                        callerId,
                        callerName,
                        callerImage
                    )
                } else if (type == "screenshot") {
                    val senderName = data["senderName"] ?: "Someone"
                    NotificationHelper.showMessageNotification(
                        applicationContext,
                        data["senderId"] ?: "system",
                        senderName,
                        "",
                        "📷 $senderName took a screenshot!",
                        data["chatId"] ?: ""
                    )
                } else if (type == "sos") {
                    val senderName = data["senderName"] ?: "Someone"
                    val email = data["email"] ?: "No Email"
                    val battery = data["battery"] ?: "N/A"
                    val deviceModel = data["deviceModel"] ?: "Unknown"
                    val lat = data["latitude"]?.toDoubleOrNull() ?: 0.0
                    val lon = data["longitude"]?.toDoubleOrNull() ?: 0.0
                    val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
                    
                    NotificationHelper.showSOSNotification(
                        applicationContext,
                        senderName,
                        email,
                        battery,
                        deviceModel,
                        lat,
                        lon,
                        timestamp
                    )
                } else {
                    val senderName = data["senderName"] ?: data["title"] ?: "New Message"
                    val messageText = data["messageText"] ?: data["body"] ?: ""
                    val senderId = data["senderId"] ?: ""
                    val senderImage = data["senderImage"] ?: ""
                    val chatId = data["chatId"] ?: ""

                    if (senderId.isNotEmpty()) {
                        NotificationHelper.showMessageNotification(
                            applicationContext,
                            senderId,
                            senderName,
                            senderImage,
                            messageText,
                            chatId
                        )
                    }
                }
            } else {
                // Fallback for notification-only payloads
                message.notification?.let { notification ->
                    Log.d("FCM", "Notification payload: ${notification.title}")
                    NotificationHelper.showMessageNotification(
                        applicationContext,
                        "system",
                        notification.title ?: "Loomi",
                        "",
                        notification.body ?: "",
                        "system_chat"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("FCM", "Error processing FCM message: ${e.message}")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
