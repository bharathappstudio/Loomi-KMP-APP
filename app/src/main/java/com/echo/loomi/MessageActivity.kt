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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

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
                val selectedImage = remember { mutableStateOf<String?>(null) }
                
                // Block screenshots when viewing high-quality image
                LaunchedEffect(selectedImage.value) {
                    if (selectedImage.value != null) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                val blurValue by animateDpAsState(
                    targetValue = if (sosActive) 30.dp else if (selectedImage.value != null) 20.dp else 0.dp,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    label = "sos_blur"
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .blur(blurValue)
                    ) {
                        MessageScreen(
                            receiverUid = receiverUid,
                            receiverName = receiverName,
                            receiverImage = receiverImage,
                            onBack = { finish() },
                            onImageClick = { base64 -> selectedImage.value = base64 }
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

                    // Full Screen HD Image Viewer (Smooth & Fast Transition)
                    AnimatedVisibility(
                        visible = selectedImage.value != null,
                        enter = fadeIn(tween(300)) + scaleIn(tween(300, easing = FastOutSlowInEasing), initialScale = 0.92f),
                        exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.92f)
                    ) {
                        selectedImage.value?.let { base64 ->
                            val bitmap = remember(base64) {
                                try {
                                    val imageBytes = Base64.decode(base64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { selectedImage.value = null },
                                contentAlignment = Alignment.Center
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "Full Screen Image",
                                        modifier = Modifier
                                            .fillMaxWidth(0.92f)
                                            .clip(RoundedCornerShape(24.dp))
                                            .border(2.dp, Color.White.copy(alpha = 8f), RoundedCornerShape(24.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
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
    onBack: () -> Unit,
    onImageClick: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val auth = FirebaseAuth.getInstance()
    val currentUid = auth.currentUser?.uid ?: return
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    
    val chatId = if (currentUid < receiverUid) "${currentUid}_$receiverUid" else "${receiverUid}_$currentUid"
    val messagesList = remember { mutableStateListOf<ChatMessage>() }
    var receiverStatus by remember { mutableStateOf("Offline") }
    var isReceiverTyping by remember { mutableStateOf(false) }
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

    DisposableEffect(receiverUid) {
        val typingRef = database.child("users").child(receiverUid).child("typingWith")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val typingWith = snapshot.getValue(String::class.java)
                isReceiverTyping = typingWith == currentUid
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        typingRef.addValueEventListener(listener)
        onDispose { typingRef.removeEventListener(listener) }
    }

    LaunchedEffect(input) {
        if (input.isNotEmpty()) {
            database.child("users").child(currentUid).child("typingWith").setValue(receiverUid)
            delay(3000)
            database.child("users").child(currentUid).child("typingWith").removeValue()
        } else {
            database.child("users").child(currentUid).child("typingWith").removeValue()
        }
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

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val bitmap = BitmapFactory.decodeStream(inputStream)
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
            .blur(lerpDp(0.dp, 25.dp, blurProgress))
        ) {
            MessageTopBar(
                receiverName = receiverName,
                receiverImage = receiverImage,
                isTyping = isReceiverTyping,
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
                            ChatBubble(msg, isMe, onImageClick)
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
                onGalleryClick = {
                    galleryLauncher.launch("image/*")
                },
                modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)
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
    isTyping: Boolean = false,
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
                    if (isTyping) {
                        Text(
                            text = "typing...",
                            fontSize = 12.sp,
                            color = Color(0xFF5856D6),
                            fontWeight = FontWeight.Medium
                        )
                    }
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
    onGalleryClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFFFBF6)

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (isDark) Color.White.copy(0.1f) else Color.Black.copy(0.05f),
                shape = RoundedCornerShape(28.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera Button (Circular Blue)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF66BB6A))
                    .clickable { onCameraClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.camera),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // TextField
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        " Message...",
                        color = if (isDark) Color.White.copy(0.4f) else Color.Black.copy(0.4f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = if (isDark) Color.White else Color.Black,
                    focusedTextColor = if (isDark) Color.White else Color.Black,
                    unfocusedTextColor = if (isDark) Color.White else Color.Black
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )

            // Right Side Icons
            val tint = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.9f)
            
            IconButton(onClick = onGalleryClick) {
                Icon(
                    painter = painterResource(R.drawable.image),
                    contentDescription = "Gallery",
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }

            if (text.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                // Send Button (Circular Black)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .clickable { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.send),
                        contentDescription = "Send",
                        modifier = Modifier.size(22.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, isMe: Boolean, onImageClick: (String) -> Unit) {
    val isDark = isSystemInDarkTheme()
    val isImage = msg.message.startsWith("img:")
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
            modifier = if (isImage) {
                Modifier
                    .widthIn(max = 180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onImageClick(msg.message.substring(4)) }
            } else {
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(bubbleShape)
                    .background(if (isMe) (if (isDark) Color.White.copy(0.2f) else Color(0x66C8E6C9)) else (if (isDark) Color.White.copy(0.1f) else Color(0x80FFECB3).copy(alpha = 0.45f)))
                    .border(1.dp, Color.White.copy(alpha = if (isDark) 0.1f else 0.80f), bubbleShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            }
        ) {
            if (isImage) {
                val base64Data = msg.message.substring(4)
                val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Image message",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        contentScale = ContentScale.FillWidth
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


