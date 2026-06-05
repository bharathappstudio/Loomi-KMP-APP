package com.echo.loomi

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class DirectReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)?.toString()
        val receiverUid = intent.getStringExtra("receiverUid") ?: return
        val chatId = intent.getStringExtra("chatId") ?: return
        val notificationId = intent.getIntExtra("notificationId", 0)

        if (!replyText.isNullOrBlank()) {
            val auth = FirebaseAuth.getInstance()
            val currentUid = auth.currentUser?.uid ?: return
            val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
            
            // End-to-End Encryption
            val encryptedMessage = EncryptionUtils.encrypt(replyText)
            
            val msgId = database.child("chats").child(chatId).push().key ?: ""
            val message = ChatMessage(
                id = msgId,
                senderId = currentUid,
                receiverId = receiverUid,
                message = encryptedMessage,
                timestamp = System.currentTimeMillis()
            )
            
            database.child("chats").child(chatId).child(msgId).setValue(message)
                .addOnSuccessListener {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(notificationId)
                }
        }
    }
}
