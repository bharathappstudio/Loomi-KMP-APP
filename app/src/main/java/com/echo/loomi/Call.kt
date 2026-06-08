package com.echo.loomi

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import java.util.Locale

enum class CallState {
    IDLE, INCOMING, OUTGOING, ONGOING, ENDED
}

data class CallData(
    val callerId: String = "",
    val receiverId: String = "",
    val callerName: String = "",
    val callerImage: String = "",
    val status: String = "ringing", // ringing, accepted, declined, ended
    val timestamp: Long = System.currentTimeMillis(),
    val sdp: String? = null,
    val type: String? = null, // offer, answer
    val iceCandidates: Map<String, Map<String, Any>>? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallBottomSheet(
    receiverName: String,
    receiverImage: String,
    callState: CallState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { false } // Prevent dismissal by swipe
    )

    // Trigger FCM wakeup for the call (on receiver's side, logic should be in startCall)

    ModalBottomSheet(
        onDismissRequest = { /* Do nothing to prevent dismissal on outside tap */ },
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 60.dp, top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // User Image
            val imageModel = remember(receiverImage) {
                if (receiverImage.startsWith("data:image")) {
                    try {
                        val base64Data = receiverImage.substringAfter("base64,")
                        Base64.decode(base64Data, Base64.DEFAULT)
                    } catch (e: Exception) {
                        receiverImage
                    }
                } else if (receiverImage.startsWith("http")) {
                    receiverImage
                } else if (receiverImage.isNotEmpty()) {
                    "file:///android_asset/$receiverImage"
                } else {
                    R.drawable.logo
                }
            }

            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageModel)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (isDark) Color.White.copy(0.2f) else Color.Black.copy(alpha = 0.1f), CircleShape),
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.logo)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // User Name
            Text(
                text = receiverName,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Call Status & Timer
            var ticks by remember { mutableLongStateOf(0L) }
            if (callState == CallState.ONGOING) {
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        ticks++
                    }
                }
            }

            Text(
                text = when (callState) {
                    CallState.INCOMING -> "Incoming call..."
                    CallState.OUTGOING -> "Calling..."
                    CallState.ONGOING -> {
                        val minutes = ticks / 60
                        val seconds = ticks % 60
                        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                    }
                    CallState.ENDED -> "Call ended"
                    CallState.IDLE -> ""
                },
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (callState == CallState.INCOMING) {
                    // Decline Button
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5252))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.call),
                            contentDescription = "Decline",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Accept Button
                    IconButton(
                        onClick = onAccept,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.call),
                            contentDescription = "Accept",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else if (callState == CallState.OUTGOING || callState == CallState.ONGOING) {
                    // End Call Button
                    IconButton(
                        onClick = onEnd,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5252))
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.call),
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

fun startCall(receiverUid: String, receiverName: String, receiverImage: String) {
    val auth = FirebaseAuth.getInstance()
    val currentUid = auth.currentUser?.uid ?: return
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference

    // Encrypt metadata for privacy
    val encryptedName = EncryptionUtils.encrypt(auth.currentUser?.displayName ?: "User")
    val encryptedImage = EncryptionUtils.encrypt(receiverImage)

    val callData = CallData(
        callerId = currentUid,
        receiverId = receiverUid,
        callerName = encryptedName,
        callerImage = encryptedImage,
        status = "ringing"
    )

    database.child("calls").child(receiverUid).setValue(callData)
    
    // --- SEND FCM CALL PUSH TRIGGER ---
    val callTrigger = mapOf(
        "type" to "call",
        "callerId" to currentUid,
        "callerName" to (auth.currentUser?.displayName ?: "Loomi User"),
        "callerImage" to (auth.currentUser?.photoUrl?.toString() ?: ""),
        "receiverId" to receiverUid,
        "timestamp" to ServerValue.TIMESTAMP
    )
    database.child("notification_triggers").push().setValue(callTrigger)
}

fun endCall(receiverUid: String) {
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    database.child("calls").child(receiverUid).removeValue()
}
