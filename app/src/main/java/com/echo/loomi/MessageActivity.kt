package com.echo.loomi

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.echo.loomi.ui.theme.LoomiTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Intent
import android.app.Activity
import android.util.Log
import android.widget.Toast

class MessageActivity : ComponentActivity() {
    private lateinit var sosManager: SOSManager
    private var showSOSOverlay = mutableStateOf(false)

    private val screenCaptureCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Activity.ScreenCaptureCallback {
            notifyScreenshot()
        }
    } else {
        null
    }

    private fun notifyScreenshot() {
        val receiverUid = intent.getStringExtra("receiverUid") ?: ""
        val auth = FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid ?: return
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        
        val chatId = if (currentUid < receiverUid) "${currentUid}_$receiverUid" else "${receiverUid}_$currentUid"
        val msgId = database.child("chats").child(chatId).push().key ?: ""
        
        val notificationTrigger = mapOf(
            "type" to "screenshot",
            "senderId" to currentUid,
            "senderName" to (auth.currentUser?.displayName ?: "Loomi User"),
            "receiverId" to receiverUid,
            "chatId" to chatId,
            "timestamp" to ServerValue.TIMESTAMP
        )
        database.child("notification_triggers").push().setValue(notificationTrigger)
        
        val systemMsg = ChatMessage(
            id = msgId,
            senderId = "system",
            receiverId = receiverUid,
            message = "📷 You took a screenshot",
            timestamp = System.currentTimeMillis()
        )
        database.child("chats").child(chatId).child(msgId).setValue(systemMsg)
        
        Toast.makeText(this, "Notification sent to friend", Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        sosManager.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let { registerScreenCaptureCallback(mainExecutor, it) }
        }
    }

    override fun onStop() {
        super.onStop()
        sosManager.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let { unregisterScreenCaptureCallback(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sosManager = SOSManager(this) {
            showSOSOverlay.value = true
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val receiverUid = intent.getStringExtra("receiverUid") ?: ""
        val receiverName = intent.getStringExtra("receiverName") ?: ""
        val receiverImage = intent.getStringExtra("receiverImage") ?: ""

        if (receiverUid.isEmpty()) {
            finish()
            return
        }

        setContent {
            LoomiTheme {
                val sosActive = showSOSOverlay.value
                val blurValue by animateDpAsState(
                    targetValue = if (sosActive) 30.dp else 0.dp,
                    animationSpec = tween(500),
                    label = "sos_blur"
                )

                Box(modifier = Modifier.fillMaxSize().blur(blurValue)) {
                    MessageScreen(
                        receiverUid = receiverUid,
                        receiverName = receiverName,
                        receiverImage = receiverImage,
                        onBack = { finish() }
                    )
                }
                
                if (sosActive) {
                    SOSOverlay(
                        onTimeout = {
                            sosManager.uploadSOSData()
                            showSOSOverlay.value = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val parts = text.split("**")
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
            } else {
                append(part)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    receiverUid: String,
    receiverName: String,
    receiverImage: String,
    onBack: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val auth = FirebaseAuth.getInstance()
    val currentUid = auth.currentUser?.uid ?: return
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    
    val chatId = if (currentUid < receiverUid) "${currentUid}_$receiverUid" else "${receiverUid}_$currentUid"
    val messagesList = remember { mutableStateListOf<ChatMessage>() }
    var receiverStatus by remember { mutableStateOf("Offline") }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Call state
    var showCallSheet by remember { mutableStateOf(false) }
    var currentCallState by remember { mutableStateOf(CallState.IDLE) }
    var activeCallData by remember { mutableStateOf<CallData?>(null) }

    val context = LocalContext.current

    DisposableEffect(receiverUid) {
        val statusRef = database.child("users").child(receiverUid).child("status")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                receiverStatus = snapshot.getValue(String::class.java) ?: "Offline"
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        statusRef.addValueEventListener(listener)
        onDispose { statusRef.removeEventListener(listener) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
            
            val msgId = database.child("chats").child(chatId).push().key ?: ""
            val message = ChatMessage(
                id = msgId,
                senderId = currentUid,
                receiverId = receiverUid,
                message = "img:$base64Image",
                timestamp = System.currentTimeMillis()
            )
            database.child("chats").child(chatId).child(msgId).setValue(message)
        }
    }

    // Transform state
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(3000)
        isReady = true
    }

    // Listen for chat messages
    DisposableEffect(chatId) {
        val messagesRef = database.child("chats").child(chatId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                messagesList.clear()
                for (msgSnapshot in snapshot.children) {
                    val msg = msgSnapshot.getValue(ChatMessage::class.java)
                    if (msg != null) messagesList.add(msg)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        messagesRef.addValueEventListener(listener)
        onDispose { messagesRef.removeEventListener(listener) }
    }

    LaunchedEffect(messagesList.size) {
        if (messagesList.isNotEmpty()) {
            listState.animateScrollToItem(messagesList.size - 1)
        }
    }

    // Listen for incoming calls for the current user
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
                        }
                        "declined", "ended" -> {
                            showCallSheet = false
                            currentCallState = CallState.IDLE
                            activeCallData = null
                        }
                    }
                } else if (currentCallState == CallState.INCOMING || currentCallState == CallState.ONGOING) {
                    showCallSheet = false
                    currentCallState = CallState.IDLE
                    activeCallData = null
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        incomingCallRef.addValueEventListener(listener)
        onDispose { incomingCallRef.removeEventListener(listener) }
    }

    // Listen for outgoing call status (on the receiver's node)
    DisposableEffect(showCallSheet, currentCallState) {
        var outgoingListener: ValueEventListener? = null
        val outgoingCallRef = database.child("calls").child(receiverUid)
        
        if (currentCallState == CallState.OUTGOING) {
            outgoingListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.child("status").getValue(String::class.java)
                    if (status == "accepted") {
                        currentCallState = CallState.ONGOING
                    } else if (status == null || status == "declined" || status == "ended") {
                        showCallSheet = false
                        currentCallState = CallState.IDLE
                        activeCallData = null
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            outgoingCallRef.addValueEventListener(outgoingListener)
        }
        onDispose {
            outgoingListener?.let { outgoingCallRef.removeEventListener(it) }
        }
    }

    val blurProgress by animateFloatAsState(
        targetValue = if (showCallSheet) 1f else 0f,
        animationSpec = tween(300),
        label = "call_blur"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Background
        val bgColor = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFFFFBF6)
        Box(modifier = Modifier.fillMaxSize().background(bgColor))

        Column(modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .blur(lerpDp(0.dp, 25.dp, blurProgress))
        ) {
            MessageTopBar(
                receiverName = receiverName,
                receiverImage = receiverImage,
                onBack = onBack,
                onCallClick = {
                    startCall(receiverUid, receiverName, receiverImage)
                    currentCallState = CallState.OUTGOING
                    showCallSheet = true
                }
            )
            
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (messagesList.isEmpty()) {
                    Image(
                        painter = painterResource(id = R.drawable.grop_chart),
                        contentDescription = "No messages",
                        modifier = Modifier.size(250.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messagesList, key = { it.id }) { msg ->
                            val isMe = msg.senderId == currentUid
                            ChatBubble(msg, isMe)
                        }
                    }
                }
            }

            FloatingBottomNavBar(
                text = input,
                onTextChange = { input = it },
                onSend = {
                    if (input.trim().isNotEmpty()) {
                        val msgId = database.child("chats").child(chatId).push().key ?: ""
                        // End-to-End Encryption
                        val encryptedMessage = EncryptionUtils.encrypt(input)
                        val message = ChatMessage(
                            id = msgId,
                            senderId = currentUid,
                            receiverId = receiverUid,
                            message = encryptedMessage,
                            timestamp = System.currentTimeMillis()
                        )
                        database.child("chats").child(chatId).child(msgId).setValue(message)
                        
                        // --- SEND FCM PUSH TRIGGER ---
                        // Note: In a production app, this should be done via Firebase Cloud Functions
                        // for security. Here we trigger it by updating a special 'notifications' node
                        // that a backend/server can listen to.
                        val notificationTrigger = mapOf(
                            "type" to "message",
                            "senderId" to currentUid,
                            "senderName" to (auth.currentUser?.displayName ?: "Loomi User"),
                            "senderImage" to (auth.currentUser?.photoUrl?.toString() ?: ""),
                            "messageText" to input, // Real text for notification
                            "receiverId" to receiverUid,
                            "chatId" to chatId,
                            "timestamp" to ServerValue.TIMESTAMP
                        )
                        database.child("notification_triggers").push().setValue(notificationTrigger)

                        input = ""
                    }
                },
                onCameraClick = {
                    cameraLauncher.launch(null)
                },
                isExpanded = isReady,
                onExpandedChange = { isReady = it },
                modifier = Modifier.padding(bottom = 20.dp)//floting nave bar hight
            )
        }

        if (showCallSheet) {
            val decryptedCallerName = activeCallData?.callerName?.let { EncryptionUtils.decrypt(it) } ?: "Unknown"
            val decryptedCallerImage = activeCallData?.callerImage?.let { EncryptionUtils.decrypt(it) } ?: receiverImage

            CallBottomSheet(
                receiverName = if (currentCallState == CallState.INCOMING) decryptedCallerName else receiverName,
                receiverImage = if (currentCallState == CallState.INCOMING) decryptedCallerImage else receiverImage,
                callState = currentCallState,
                onAccept = {
                    database.child("calls").child(currentUid).child("status").setValue("accepted")
                    currentCallState = CallState.ONGOING
                },
                onDecline = {
                    endCall(currentUid)
                    showCallSheet = false
                    currentCallState = CallState.IDLE
                },
                onEnd = {
                    if (currentCallState == CallState.OUTGOING) {
                        endCall(receiverUid)
                    } else {
                        endCall(currentUid)
                    }
                    showCallSheet = false
                    currentCallState = CallState.IDLE
                },
                onDismiss = {
                    if (currentCallState != CallState.ONGOING) {
                        if (currentCallState == CallState.OUTGOING) endCall(receiverUid)
                        else if (currentCallState == CallState.INCOMING) endCall(currentUid)
                        showCallSheet = false
                        currentCallState = CallState.IDLE
                    }
                }
            )
        }
    }
}

@Composable
fun MessageTopBar(
    receiverName: String,
    receiverImage: String,
    onBack: () -> Unit,
    onCallClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val topBarColor = if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFFFBF6)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = topBarColor,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_left),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                Spacer(Modifier.width(4.dp))
                
                val receiverImageModel = remember(receiverImage) {
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
                        .data(receiverImageModel)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .border(1.dp, if (isDark) Color.White.copy(0.2f) else Color.Black.copy(alpha = 0.05f), CircleShape),
                    contentScale = ContentScale.Crop,
                    error = painterResource(R.drawable.logo)
                )
                
                Spacer(Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = receiverName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        letterSpacing = 0.5.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCallClick) {
                        Icon(
                            painter = painterResource(R.drawable.call),
                            contentDescription = "Call",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(onClick = { /* Video Call Action */ }) {
                        Icon(
                            painter = painterResource(R.drawable.video),
                            contentDescription = "Video Call",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
fun FloatingBottomNavBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCameraClick: () -> Unit,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    val animProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "nav_morph"
    )

    val horizontalPadding = lerpDp(80.dp, 10.dp, animProgress)
    val barHeight = lerpDp(50.dp, 60.dp, animProgress)
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) {
        androidx.compose.ui.graphics.lerp(Color(0xFF1A1A1A), Color(0xFF121212).copy(0.7f), animProgress)
    } else {
        androidx.compose.ui.graphics.lerp(Color(0xFFFFF2D9), Color.White.copy(0.55f), animProgress)
    }
    val borderAlpha = androidx.compose.ui.util.lerp(0.8f, 0.3f, animProgress)

    Box(
        modifier = modifier
            .padding(horizontal = horizontalPadding)
            .height(barHeight)
            .clip(RoundedCornerShape(30.dp))
            .background(bgColor)
            .border(
                width = 2.dp,
                color = Color(0xFFFFF2D9).copy(alpha = if (isExpanded) 3f else 0.3f),
                shape = RoundedCornerShape(30.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconColor = if (isDark) Color.White else Color.Black
            if (animProgress < 0.5f) {
                // Icons Mode
                IconButton(
                    onClick = onCameraClick,
                    modifier = Modifier.size(36.dp).graphicsLayer(alpha = 1f - animProgress * 2)
                ) {
                    Icon(painterResource(R.drawable.camera), null, tint = iconColor, modifier = Modifier.size(20.dp))
                }
                
                Spacer(modifier = Modifier.width(20.dp))

                IconButton(
                    onClick = { onExpandedChange(true) },
                    modifier = Modifier.size(36.dp).graphicsLayer(alpha = 1f - animProgress * 2)
                ) {
                    Icon(painterResource(R.drawable.keyboard_keys_25dp_1f1f1f_fill0_wght400_grad0_opsz24), null, tint = iconColor, modifier = Modifier.size(22.dp))
                }

                Spacer(modifier = Modifier.width(20.dp))

                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier.size(36.dp).graphicsLayer(alpha = 1f - animProgress * 2)
                ) {
                    Icon(painterResource(R.drawable.search), null, tint = iconColor, modifier = Modifier.size(20.dp))
                }
            } else {
                // Input Mode
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Logo Button - Modern Glassmorphism
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color.White.copy(0.1f) else Color.White.copy(0.35f))
                            .clickable { onExpandedChange(false) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow___down_2),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp), // Slightly smaller for premium feel
                            tint = if (isDark) Color.White else Color.Unspecified
                        )
                    }

                    // 2. Smoothly animated TextField
                    // We use AnimatedVisibility for a "gentle" entrance
                    AnimatedVisibility(
                        visible = animProgress > 0.5f,
                        enter = fadeIn(animationSpec = tween(400)) + expandHorizontally(),
                        exit = fadeOut(animationSpec = tween(300)) + shrinkHorizontally(),
                        modifier = Modifier.weight(1f)
                    ) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                        TextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier.focusRequester(focusRequester),
                            placeholder = {
                                Text(
                                    "Ask...",
                                    color = Color.Black.copy(0.4f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = Color.Black
                            ),
                            singleLine = true
                        )
                    }

                    // 3. Send Button - Color Morphing
                    val sendButtonColor by animateColorAsState(
                        targetValue = if (text.isNotBlank()) Color.Black else Color.Black.copy(0.15f),
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "color"
                    )

                    IconButton(
                        onClick = onSend,
                        modifier = Modifier
                            .size(46.dp)
                            .graphicsLayer {
                                // Gentle scale up as the bar expands
                                val scale = lerp(0.8f, 1f, (animProgress - 0.5f).coerceAtLeast(0f) * 2)
                                scaleX = scale
                                scaleY = scale
                                alpha = (animProgress - 0.5f).coerceAtLeast(0f) * 2
                            }
                            .clip(CircleShape)
                            .background(sendButtonColor)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.send),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, isMe: Boolean) {
    val isDark = isSystemInDarkTheme()
    val bubbleShape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = if (isMe) 22.dp else 8.dp,
        bottomEnd = if (isMe) 5.dp else 22.dp
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(if (isMe) (if (isDark) Color.White.copy(0.2f) else Color(0x66C8E6C9)) else (if (isDark) Color.White.copy(0.1f) else Color(0x80FFECB3).copy(alpha = 0.45f)))
                .border(1.dp, Color.White.copy(alpha = if (isDark) 0.1f else 0.80f), bubbleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (msg.message.startsWith("img:")) {
                val base64Data = msg.message.substring(4)
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Image message",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 250.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                // End-to-End Decryption
                val decryptedMessage = EncryptionUtils.decrypt(msg.message)
                Text(
                    text = parseMarkdown(decryptedMessage),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = if (isDark) Color.White else Color(0xB3000000)
                )
            }
        }
    }
}


