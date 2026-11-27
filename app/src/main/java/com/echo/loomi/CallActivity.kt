package com.echo.loomi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.loomi.ui.theme.LoomiTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {
    private var proximitySensorManager: ProximitySensorManager? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("CallActivity", "Microphone permission granted for live voice calls")
        } else {
            Log.e("CallActivity", "Microphone permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request microphone permission for live audio streaming via LiveKit
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        proximitySensorManager = ProximitySensorManager(this)
        proximitySensorManager?.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        enableEdgeToEdge()

        val receiverUid = intent.getStringExtra("receiverUid") ?: ""
        val receiverName = intent.getStringExtra("receiverName") ?: ""
        val receiverImage = intent.getStringExtra("receiverImage") ?: ""
        val isIncoming = intent.getBooleanExtra("isIncoming", false)

        setContent {
            LoomiTheme {
                CallScreenContent(
                    receiverUid = receiverUid,
                    receiverName = receiverName,
                    receiverImage = receiverImage,
                    isIncoming = isIncoming,
                    onFinish = { 
                        NotificationHelper.cancelCallNotification(this@CallActivity)
                        proximitySensorManager?.stop()
                        finish() 
                    },
                    onAnswer = {
                        NotificationHelper.cancelCallNotification(this@CallActivity)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        proximitySensorManager?.stop()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreenContent(
    receiverUid: String,
    receiverName: String,
    receiverImage: String,
    isIncoming: Boolean,
    onFinish: () -> Unit,
    onAnswer: () -> Unit
) {
    val auth = FirebaseAuth.getInstance()
    val currentUid = auth.currentUser?.uid ?: return
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    
    var callState by remember { mutableStateOf(if (isIncoming) CallState.INCOMING else CallState.OUTGOING) }
    var callDuration by remember { mutableStateOf(0L) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // LiveKit room name — deterministic from both UIDs so both sides join same room
    val roomName = remember { generateRoomName(currentUid, receiverUid) }

    // Create LiveKitManager
    val liveKitManager = remember {
        LiveKitManager(context, object : LiveKitManager.LiveKitCallListener {
            override fun onConnected() {
                Log.d("CallActivity", "LiveKit connected — audio streaming active via microphone")
            }

            override fun onDisconnected() {
                Log.d("CallActivity", "LiveKit disconnected")
            }

            override fun onRemoteAudioReceived() {
                Log.d("CallActivity", "Remote audio track received — audio audible")
            }

            override fun onParticipantLeft() {
                Log.d("CallActivity", "Remote participant left the LiveKit room")
            }

            override fun onError(message: String) {
                Log.e("CallActivity", "LiveKit error: $message")
            }
        })
    }

    // Cleanup LiveKit on dispose
    DisposableEffect(Unit) {
        onDispose {
            liveKitManager.destroy()
        }
    }

    // Call Duration Timer & Cloud Timing Update
    LaunchedEffect(callState) {
        if (callState == CallState.ONGOING) {
            while (true) {
                delay(1000)
                callDuration++
                // Update live call timing/duration on cloud
                database.child("calls").child(currentUid).child("duration").setValue(callDuration)
            }
        }
    }

    // Auto-timeout for incoming calls (Receiver side)
    LaunchedEffect(callState) {
        if (callState == CallState.INCOMING) {
            delay(40000)
            if (callState == CallState.INCOMING) {
                Log.d("CallActivity", "Incoming call timed out after 40s")
                endCall(currentUid)
                onFinish()
            }
        }
    }

    fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }

    // Listen for call changes via Firebase signaling
    LaunchedEffect(Unit) {
        val targetUid = if (isIncoming) currentUid else receiverUid
        val callRef = database.child("calls").child(targetUid)
        
        callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Log.d("CallActivity", "Call data removed from cloud, finishing call UI.")
                    liveKitManager.disconnect()
                    onFinish()
                } else {
                    val status = snapshot.child("status").getValue(String::class.java)

                    Log.d("CallActivity", "Call status: $status, State: $callState")

                    if (status == "accepted" && callState != CallState.ONGOING) {
                        callState = CallState.ONGOING
                        onAnswer()
                        // Both sides connect to LiveKit room when call is accepted
                        liveKitManager.connect(
                            roomName = roomName,
                            participantIdentity = currentUid,
                            participantName = auth.currentUser?.displayName ?: "User"
                        )
                    } else if (status == "ended" || status == "declined") {
                        Log.d("CallActivity", "Call $status, finishing and cleaning up cloud data.")
                        liveKitManager.disconnect()
                        database.child("calls").child(currentUid).removeValue()
                        database.child("calls").child(receiverUid).removeValue()
                        onFinish()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("CallActivity", "Database error: ${error.message}")
            }
        })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val decryptedName = if (isIncoming) EncryptionUtils.decrypt(receiverName) else receiverName
        val decryptedImage = if (isIncoming) EncryptionUtils.decrypt(receiverImage) else receiverImage

        CallBottomSheet(
            receiverName = decryptedName,
            receiverImage = decryptedImage,
            callState = callState,
            onAccept = {
                database.child("calls").child(currentUid).child("status").setValue("accepted")
                callState = CallState.ONGOING
                onAnswer()
                // Connect to LiveKit room
                liveKitManager.connect(
                    roomName = roomName,
                    participantIdentity = currentUid,
                    participantName = auth.currentUser?.displayName ?: "User"
                )
            },
            onDecline = {
                endCall(currentUid)
                database.child("calls").child(currentUid).removeValue()
                database.child("calls").child(receiverUid).removeValue()
                liveKitManager.disconnect()
                onFinish()
            },
            onEnd = {
                if (isIncoming) endCall(currentUid) else endCall(receiverUid)
                database.child("calls").child(currentUid).removeValue()
                database.child("calls").child(receiverUid).removeValue()
                liveKitManager.disconnect()
                onFinish()
            },
            onDismiss = {
                // Do nothing on dismiss to keep call active
            }
        )
        
        if (callState == CallState.ONGOING) {
            androidx.compose.material3.Text(
                text = formatDuration(callDuration),
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(top = 100.dp),
                fontSize = 16.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
