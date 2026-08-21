package com.echo.loomi

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.zIndex
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

@Composable
fun CallBottomSheet(
    receiverName: String,
    receiverImage: String,
    callState: CallState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    
    // This is a custom persistent overlay that looks like a bottom sheet
    // but cannot be dismissed by swipe, back, or outside tap.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1000f)
            .clickable(enabled = true, onClick = {}) // Block clicks to background
    ) {
        // Scrim removed for "no color" look

        // Top Image Overlay (Apple/Snap style update)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Image(
                painter = painterResource(id = R.drawable.msg),
                contentDescription = "call",
                modifier = Modifier.size(300.dp).padding(top = 90.dp),
                contentScale = ContentScale.Fit
            )
        }

        // "Sheet" Content
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color.Transparent) // No color, use background blur only
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 60.dp, top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle removed as requested

                Spacer(modifier = Modifier.height(24.dp))

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
                        receiverImage.replace("s96-c", "s4096-c").replace("s400-c", "s4096-c")
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
                    color = if (isDark) Color.White else Color.Black
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
                    color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.6f)
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
}

val outgoingCallReceiverUid = mutableStateOf<String?>(null)
val currentCallPartnerName = mutableStateOf("")
val currentCallPartnerImage = mutableStateOf("")
val isCallActiveGlobal = mutableStateOf(false)
val globalUserBlur = mutableStateOf(20f)
val currentCallStateGlobal = mutableStateOf(CallState.IDLE)
val activeCallDataGlobal = mutableStateOf<CallData?>(null)
var isAppInForeground = false

private var globalRingtonePlayer: MediaPlayer? = null

@Composable
fun CallOverlay() {
    val auth = FirebaseAuth.getInstance()
    val currentUid = auth.currentUser?.uid ?: return
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    val context = LocalContext.current

    var showCallSheet by remember { isCallActiveGlobal }
    var currentCallState by remember { currentCallStateGlobal }
    var activeCallData by remember { activeCallDataGlobal }

    // Global BackHandler to prevent closing activity/app during call
    BackHandler(enabled = showCallSheet) {
        // Do nothing - blocks back button during call
    }

    // Ringtone Management (Singleton-like approach to prevent double playing)
    DisposableEffect(Unit) {
        if (globalRingtonePlayer == null) {
            globalRingtonePlayer = try {
                MediaPlayer.create(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)).apply {
                    isLooping = true
                }
            } catch (e: Exception) {
                null
            }
        }
        onDispose {
            // We don't release here because other activities might still need it
        }
    }

    LaunchedEffect(currentCallState) {
        if (currentCallState == CallState.INCOMING) {
            if (globalRingtonePlayer?.isPlaying == false) {
                globalRingtonePlayer?.start()
            }
            // 90s Ringing Timeout
            delay(90000)
            if (currentCallState == CallState.INCOMING) {
                endCall(currentUid)
            }
        } else if (currentCallState == CallState.OUTGOING) {
            // Outgoing also times out after 90s if not answered
            delay(90000)
            if (currentCallState == CallState.OUTGOING) {
                outgoingCallReceiverUid.value?.let { endCall(it) }
            }
        } else {
            if (globalRingtonePlayer?.isPlaying == true) {
                globalRingtonePlayer?.pause()
                globalRingtonePlayer?.seekTo(0)
            }
        }
    }

    // Listen for incoming calls
    DisposableEffect(currentUid) {
        val incomingCallRef = database.child("calls").child(currentUid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val callData = snapshot.getValue(CallData::class.java)
                if (callData != null) {
                    activeCallData = callData
                    when (callData.status) {
                        "ringing" -> {
                            currentCallState = CallState.INCOMING
                            showCallSheet = true
                        }
                        "accepted" -> {
                            currentCallState = CallState.ONGOING
                            showCallSheet = true
                        }
                        "declined", "ended" -> {
                            showCallSheet = false
                            currentCallState = CallState.IDLE
                            activeCallData = null
                        }
                    }
                } else if (currentCallState == CallState.INCOMING || currentCallState == CallState.ONGOING) {
                    if (outgoingCallReceiverUid.value == null) {
                        showCallSheet = false
                        currentCallState = CallState.IDLE
                        activeCallData = null
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        incomingCallRef.addValueEventListener(listener)
        onDispose { incomingCallRef.removeEventListener(listener) }
    }

    // Listen for outgoing calls
    val outgoingUid = outgoingCallReceiverUid.value
    DisposableEffect(outgoingUid) {
        if (outgoingUid == null) return@DisposableEffect onDispose {}
        
        val outgoingCallRef = database.child("calls").child(outgoingUid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val callData = snapshot.getValue(CallData::class.java)
                if (callData != null) {
                    activeCallData = callData
                    when (callData.status) {
                        "ringing" -> {
                            currentCallState = CallState.OUTGOING
                            showCallSheet = true
                        }
                        "accepted" -> {
                            currentCallState = CallState.ONGOING
                            showCallSheet = true
                        }
                        "declined", "ended" -> {
                            showCallSheet = false
                            currentCallState = CallState.IDLE
                            activeCallData = null
                            outgoingCallReceiverUid.value = null
                        }
                    }
                } else {
                    showCallSheet = false
                    currentCallState = CallState.IDLE
                    activeCallData = null
                    outgoingCallReceiverUid.value = null
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        outgoingCallRef.addValueEventListener(listener)
        onDispose { outgoingCallRef.removeEventListener(listener) }
    }

    AnimatedVisibility(
        visible = showCallSheet,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val partnerName = if (currentCallState == CallState.INCOMING) {
            activeCallData?.callerName?.let { EncryptionUtils.decrypt(it) } ?: "Unknown"
        } else {
            currentCallPartnerName.value
        }
        
        val partnerImage = if (currentCallState == CallState.INCOMING) {
            activeCallData?.callerImage?.let { EncryptionUtils.decrypt(it) } ?: ""
        } else {
            currentCallPartnerImage.value
        }

        CallBottomSheet(
            receiverName = partnerName,
            receiverImage = partnerImage,
            callState = currentCallState,
            onAccept = {
                database.child("calls").child(currentUid).child("status").setValue("accepted")
                currentCallState = CallState.ONGOING
            },
            onDecline = {
                endCall(currentUid)
                showCallSheet = false
                currentCallState = CallState.IDLE
                activeCallData = null
            },
            onEnd = {
                if (currentCallState == CallState.OUTGOING) {
                    endCall(outgoingUid ?: "")
                } else {
                    endCall(currentUid)
                }
                showCallSheet = false
                currentCallState = CallState.IDLE
                activeCallData = null
                outgoingCallReceiverUid.value = null
            }
        )
    }
}

fun startCall(receiverUid: String, receiverName: String, receiverImage: String) {
    val auth = FirebaseAuth.getInstance()
    val currentUid = auth.currentUser?.uid ?: return
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference

    // Update global state for CallOverlay
    currentCallPartnerName.value = receiverName
    currentCallPartnerImage.value = receiverImage
    outgoingCallReceiverUid.value = receiverUid

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
}

fun endCall(receiverUid: String) {
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    database.child("calls").child(receiverUid).removeValue()
}
