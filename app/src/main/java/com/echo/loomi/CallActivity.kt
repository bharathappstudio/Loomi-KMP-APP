package com.echo.loomi

import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.blur
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.loomi.ui.theme.LoomiTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.webrtc.*
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {
    private var proximitySensorManager: ProximitySensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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

    val rtcManager = remember {
        RTCManager(context, object : RTCManager.RTCEventListener {
            override fun onOfferCreated(sdp: SessionDescription) {
                val targetUid = if (isIncoming) currentUid else receiverUid
                database.child("calls").child(targetUid).updateChildren(mapOf(
                    "sdp" to sdp.description,
                    "type" to "offer"
                ))
            }

            override fun onAnswerCreated(sdp: SessionDescription) {
                database.child("calls").child(currentUid).updateChildren(mapOf(
                    "sdp" to sdp.description,
                    "type" to "answer"
                ))
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                val targetUid = if (isIncoming) currentUid else receiverUid
                val candMap = mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "sdp" to candidate.sdp
                )
                database.child("calls").child(targetUid).child("iceCandidates").push().setValue(candMap)
            }

            override fun onDataChannel(p0: DataChannel?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d("CallActivity", "Remote track added. Should be audible now.")
                // Most modern WebRTC implementations handle audio routing automatically 
                // if JavaAudioDeviceModule is properly initialized (which we fixed).
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        })
    }

    // Call Duration Timer
    LaunchedEffect(callState) {
        if (callState == CallState.ONGOING) {
            while (true) {
                delay(1000)
                callDuration++
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

    // Listen for call changes
    LaunchedEffect(Unit) {
        val targetUid = if (isIncoming) currentUid else receiverUid
        val callRef = database.child("calls").child(targetUid)
        
        callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    // If call data is removed, end UI for both users immediately
                    Log.d("CallActivity", "Call data removed, finishing call UI.")
                    rtcManager.endCall()
                    onFinish()
                } else {
                    val status = snapshot.child("status").getValue(String::class.java)
                    val sdp = snapshot.child("sdp").getValue(String::class.java)
                    val type = snapshot.child("type").getValue(String::class.java)

                    Log.d("CallActivity", "Call status: $status, State: $callState")

                    if (status == "accepted" && callState != CallState.ONGOING) {
                        callState = CallState.ONGOING
                        rtcManager.startLocalAudio()
                        onAnswer() // Stop ringtone
                        if (!isIncoming) {
                            rtcManager.createOffer()
                        }
                    } else if (status == "ended" || status == "declined") {
                        Log.d("CallActivity", "Call $status, finishing.")
                        rtcManager.endCall()
                        onFinish()
                    }

                    if (sdp != null && type != null) {
                        if (isIncoming && type == "offer" && callState == CallState.ONGOING) {
                            rtcManager.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
                            rtcManager.createAnswer()
                        } else if (!isIncoming && type == "answer" && callState == CallState.ONGOING) {
                            rtcManager.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("CallActivity", "Database error: ${error.message}")
                // Don't finish on error to keep screen alive
            }
        })

        // Listen for ICE candidates
        callRef.child("iceCandidates").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val sdpMid = snapshot.child("sdpMid").getValue(String::class.java) ?: ""
                val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: ""
                rtcManager.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
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
                rtcManager.startLocalAudio()
                onAnswer() // Stop ringtone
            },
            onDecline = {
                endCall(currentUid)
                rtcManager.endCall()
                onFinish()
            },
            onEnd = {
                if (isIncoming) endCall(currentUid) else endCall(receiverUid)
                rtcManager.endCall()
                onFinish()
            },
            onDismiss = {
                // Do nothing on dismiss (tapping outside) to keep the call active
            }
        )
        
        // Timer display overlay (since we can't change CallBottomSheet UI)
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
