package com.echo.loomi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream

object NotificationHelper {
    private const val CHANNEL_ID = "loomi_messages"
    private const val CHANNEL_NAME = "Loomi Messages"
    private const val CALL_CHANNEL_ID = "loomi_calls"
    private const val CALL_CHANNEL_NAME = "Loomi Calls"
    private const val SERVICE_CHANNEL_ID = "loomi_system_sync"
    private const val SERVICE_CHANNEL_NAME = "Sync Process"
    private const val SOS_CHANNEL_ID = "loomi_sos_alerts"
    private const val SOS_CHANNEL_NAME = "Emergency SOS Alerts"
    const val KEY_TEXT_REPLY = "key_text_reply"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 1. Channel for messages
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new messages in Loomi"
            }
            manager.createNotificationChannel(channel)

            // 2. Channel for calls
            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                CALL_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming calls"
                setSound(null, null) 
                enableVibration(true)
            }
            manager.createNotificationChannel(callChannel)

            // 3. Invisible background sync channel (Root Persistence)
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                SERVICE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN // MIN importance is safer for keeping service alive while staying quiet
            ).apply {
                description = "System Synchronization"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(serviceChannel)

            // 4. SOS Alerts Channel (High Importance)
            val sosChannel = NotificationChannel(
                SOS_CHANNEL_ID,
                SOS_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency SOS Alerts"
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = android.graphics.Color.RED
            }
            manager.createNotificationChannel(sosChannel)
        }
    }

    fun showSecurityNotification(context: Context, body: String) {
        val notification = NotificationCompat.Builder(context, SOS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("") // Empty title
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2002, notification)
    }

    fun showSOSNotification(
        context: Context,
        senderName: String,
        email: String,
        battery: String,
        deviceModel: String,
        lat: Double,
        lon: Double,
        timestamp: Long
    ) {
        val notificationId = 3003
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        val bigText = """
        Name: $senderName
        Mail: $email
        Battery: $battery
        Device: $deviceModel
        Time: $timeStr
        """.trimIndent()
        
        val mapIntent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("google.navigation:q=$lat,$lon")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, SOS_CHANNEL_ID)
            .setSmallIcon(R.drawable.heart)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(bigText)
                .setSummaryText("Emergency Required"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true) // Now closes when tapped
            .setOngoing(false)   // Now can be swiped away/closed
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setLights(0xFFFF0000.toInt(), 3000, 3000)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent) // Tapping the notification opens maps
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    fun getServiceNotification(context: Context): android.app.Notification {
        val builder = NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setLocalOnly(true)
            .setOngoing(true)
            .setContentTitle(null) 
            .setContentText(null)
            .setGroup("loomi_sync_group") // Grouping helps hide it further
            .setGroupSummary(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // This is the key for Android 12+: hides notification for the first 10 seconds, 
            // and if the task is stable, it often stays hidden/minimized.
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        }

        return builder.build()
    }

    fun showMessageNotification(
        context: Context,
        senderId: String,
        senderName: String,
        senderImage: String,
        messageText: String,
        chatId: String
    ) {
        val notificationId = senderId.hashCode()

        CoroutineScope(Dispatchers.IO).launch {
            val largeIcon = getLargeIcon(context, senderImage)
            
            // Decrypt message if it's E2E encrypted
            val displayText = if (messageText.startsWith("e2e:")) {
                EncryptionUtils.decrypt(messageText)
            } else {
                messageText
            }

            val intent = Intent(context, MessageActivity::class.java).apply {
                putExtra("receiverUid", senderId)
                putExtra("receiverName", senderName)
                putExtra("receiverImage", senderImage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).run {
                setLabel("Type your message...")
                build()
            }

            val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra("receiverUid", senderId)
                putExtra("chatId", chatId)
                putExtra("notificationId", notificationId)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context, notificationId, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.send, "Reply", replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            val user = Person.Builder()
                .setName(senderName)
                .apply {
                    largeIcon?.let { setIcon(IconCompat.createWithBitmap(it)) }
                }
                .build()

            val messagingStyle = NotificationCompat.MessagingStyle(user)
                .addMessage(displayText, System.currentTimeMillis(), user)
                .setConversationTitle(senderName)
                .setGroupConversation(false)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setStyle(messagingStyle)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, false)
                .setContentIntent(pendingIntent)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notification)
        }
    }

    fun showCallNotification(
        context: Context,
        callerId: String,
        callerName: String,
        callerImage: String
    ) {
        val notificationId = 1001

        val intent = Intent(context, CallActivity::class.java).apply {
            putExtra("receiverUid", callerId)
            putExtra("receiverName", callerName)
            putExtra("receiverImage", callerImage)
            putExtra("isIncoming", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.call)
            .setContentTitle("Incoming Call")
            .setContentText("$callerName is calling you...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private suspend fun getLargeIcon(context: Context, senderImage: String): Bitmap? {
        return try {
            if (senderImage.startsWith("http")) {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(senderImage)
                    .allowHardware(false)
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
            } else {
                val inputStream: InputStream = context.assets.open(senderImage)
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }
    }
}
