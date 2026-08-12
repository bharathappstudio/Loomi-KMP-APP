package com.echo.loomi.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.*
import com.echo.loomi.desktop.network.FirebaseClient
import com.echo.loomi.desktop.utils.DesktopEncryptionUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Base64
import java.util.UUID

// Data models
data class SnapUser(
    val uid: String,
    val name: String,
    val status: String = "Offline",
    val lastSeen: Long = 0,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0,
    val imageName: String = ""
)

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val message: String = "",
    val timestamp: Long = 0
)

data class CallData(
    val callerId: String = "",
    val receiverId: String = "",
    val callerName: String = "",
    val callerImage: String = "",
    val status: String = "ringing",
    val timestamp: Long = System.currentTimeMillis()
)

enum class CallState {
    IDLE, INCOMING, OUTGOING, ONGOING
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    currentUserName: String,
    currentUserImage: String,
    onLogout: () -> Unit,
    onProfileClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    
    // Apple-style color palette
    val surfaceColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val sidebarColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFFFFFFF)
    val accentColor = if (isDark) Color.White else Color.Black
    val blueColor = Color(0xB3494444)

    // Database states
    val usersList = remember { mutableStateListOf<SnapUser>() }
    val messagesList = remember { mutableStateListOf<ChatMessage>() }

    // Selection states
    var selectedUser by remember { mutableStateOf<SnapUser?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Calling states
    var callState by remember { mutableStateOf(CallState.IDLE) }
    var activeCallData by remember { mutableStateOf<CallData?>(null) }
    var callTicks by remember { mutableLongStateOf(0L) }
    var showSOSOverlay by remember { mutableStateOf(false) }

    // Sync Online status & Heartbeat
    LaunchedEffect(Unit) {
        val uid = FirebaseClient.currentUid ?: return@LaunchedEffect
        while (true) {
            // Heartbeat without aggressive logout
            FirebaseClient.write("users/$uid/status", "Online")
            FirebaseClient.write("users/$uid/lastSeen", System.currentTimeMillis())
            delay(20000)
        }
    }

    // Sync Data
    LaunchedEffect(Unit) {
        val uid = FirebaseClient.currentUid ?: return@LaunchedEffect
        val gson = Gson()
        delay(1000) // Increase delay to allow token stability

        FirebaseClient.startListener("users", onAuthError = { 
            // Silent auth retry logic is now in FirebaseClient, so we just log here
            println("MainScreen: Auth issue on 'users' listener. Waiting for retry...")
        }) { _, path, json ->
            scope.launch(Dispatchers.Main) {
                if (json == "null") return@launch
                try {
                    if (path == "" || path == "/") {
                        val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
                        val data: Map<String, Map<String, Any>>? = gson.fromJson(json, type)
                        if (data != null) {
                            usersList.clear()
                            data.forEach { (key, value) -> if (key != uid) usersList.add(parseUserMap(key, value)) }
                        }
                    } else {
                        val key = path.split("/").firstOrNull { it.isNotEmpty() } ?: return@launch
                        if (key != uid) {
                            FirebaseClient.read("users/$key") { userJson ->
                                if (userJson != null) {
                                    scope.launch(Dispatchers.Main) {
                                        try {
                                            val valMap: Map<String, Any>? = gson.fromJson(userJson, object : TypeToken<Map<String, Any>>() {}.type)
                                            if (valMap != null) {
                                                val index = usersList.indexOfFirst { it.uid == key }
                                                val updated = parseUserMap(key, valMap)
                                                if (index != -1) usersList[index] = updated else usersList.add(updated)
                                            }
                                        } catch (e: Exception) {
                                            println("MainScreen: Error parsing individual user update: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("MainScreen: JSON structure mismatch on 'users' listener. Path: $path. Error: ${e.message}")
                }
            }
        }
    }

    // Call Timer Logic
    LaunchedEffect(callState) {
        if (callState == CallState.ONGOING) {
            callTicks = 0
            while (callState == CallState.ONGOING) {
                delay(1000)
                callTicks++
            }
        }
    }

    // Stable Call Listener (Handles Incoming calls and Cleanup)
    LaunchedEffect(Unit) {
        val uid = FirebaseClient.currentUid ?: return@LaunchedEffect
        val gson = Gson()
        delay(1000) // Longer stagger for calls
        
        FirebaseClient.startListener("calls/$uid", onAuthError = { 
            println("MainScreen: Auth error on 'calls' listener. Ignoring to prevent kick-out.")
        }) { _, _, json ->
            scope.launch(Dispatchers.Main) {
                if (json == "null") {
                    // Only go IDLE if we were in an INCOMING or ONGOING state where we were the receiver
                    if (callState == CallState.INCOMING || (callState == CallState.ONGOING && activeCallData?.receiverId == uid)) {
                        callState = CallState.IDLE
                        activeCallData = null
                        callTicks = 0
                    }
                } else {
                    try {
                        val data: CallData? = gson.fromJson(json, CallData::class.java)
                        if (data != null && data.callerId.isNotEmpty()) {
                            // Removed strict time-sync check which was blocking calls on different device clocks
                            if (data.status == "ringing") {
                                activeCallData = data
                                callState = CallState.INCOMING
                            } else if (data.status == "accepted") {
                                activeCallData = data
                                callState = CallState.ONGOING
                            }
                        }
                    } catch (e: Exception) {
                        // Handle partial updates or string updates by reading full data once
                        FirebaseClient.read("calls/$uid") { fullJson ->
                            if (fullJson != null && fullJson != "null") {
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        val fullData = gson.fromJson(fullJson, CallData::class.java)
                                        if (fullData != null && (System.currentTimeMillis() - fullData.timestamp < 30000 || fullData.status == "accepted")) {
                                            activeCallData = fullData
                                            if (fullData.status == "ringing") callState = CallState.INCOMING
                                            else if (fullData.status == "accepted") callState = CallState.ONGOING
                                        }
                                    } catch (ex: Exception) {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Outgoing Call Monitor (Only active during OUTGOING or ONGOING as caller)
    LaunchedEffect(callState) {
        val uid = FirebaseClient.currentUid
        if (callState == CallState.OUTGOING) {
            // Auto-cancel call after 40 seconds if not answered
            scope.launch {
                delay(40000)
                if (callState == CallState.OUTGOING) {
                    println("MainScreen: Call timed out after 40s")
                    activeCallData?.let { data ->
                        FirebaseClient.delete("calls/${data.receiverId}")
                    }
                    callState = CallState.IDLE
                    activeCallData = null
                }
            }
        }

        if (callState == CallState.OUTGOING || (callState == CallState.ONGOING && activeCallData?.callerId == uid)) {
            val receiverUid = activeCallData?.receiverId ?: return@LaunchedEffect
            val gson = Gson()
            
            FirebaseClient.startListener("calls/$receiverUid", onAuthError = { 
                println("MainScreen: Auth error on outgoing 'calls' listener. Ignoring.")
            }) { _, _, json ->
                scope.launch(Dispatchers.Main) {
                    if (json == "null") {
                        callState = CallState.IDLE
                        activeCallData = null
                    } else if (json != "null") {
                        try {
                            if (json.contains("\"accepted\"") || json.contains("accepted")) {
                                if (callState == CallState.OUTGOING) callState = CallState.ONGOING
                            } else if (json.contains("\"declined\"") || json.contains("\"ended\"")) {
                                callState = CallState.IDLE
                                activeCallData = null
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    var activeChatListenerPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedUser) {
        // 1. Immediately clear and stop old listener for instant UI response
        activeChatListenerPath?.let { FirebaseClient.stopListener(it) }
        messagesList.clear()
        
        val uid = FirebaseClient.currentUid ?: return@LaunchedEffect
        val receiver = selectedUser ?: return@LaunchedEffect
        
        val chatId = if (uid < receiver.uid) "${uid}_${receiver.uid}" else "${receiver.uid}_$uid"
        val path = "chats/$chatId"
        activeChatListenerPath = path

        // 2. Start listener and use the direct stream data (no extra HTTP GET)
        FirebaseClient.startListener(path, onAuthError = { 
            println("MainScreen: Auth error on 'chats' listener. Ignoring.")
        }) { event, childPath, json ->
            scope.launch(Dispatchers.Default) {
                if (json == "null") return@launch
                try {
                    val gson = Gson()
                    if (childPath == "" || childPath == "/") {
                        // Initial full load
                        val type = object : TypeToken<Map<String, ChatMessage>>() {}.type
                        val data: Map<String, ChatMessage>? = gson.fromJson(json, type)
                        withContext(Dispatchers.Main) {
                            messagesList.clear()
                            if (data != null) {
                                messagesList.addAll(data.values.sortedBy { it.timestamp })
                            }
                        }
                    } else {
                        // Incremental update (new message)
                        val newMsg = gson.fromJson(json, ChatMessage::class.java)
                        withContext(Dispatchers.Main) {
                            if (newMsg != null) {
                                val index = messagesList.indexOfFirst { it.id == newMsg.id || it.timestamp == newMsg.timestamp }
                                if (index != -1) {
                                    messagesList[index] = newMsg
                                } else {
                                    messagesList.add(newMsg)
                                    messagesList.sortBy { it.timestamp }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fallback: if incremental parsing fails, do a quick one-time read
                    FirebaseClient.read(path) { fullJson ->
                        if (fullJson != null && fullJson != "null") {
                            scope.launch(Dispatchers.Main) {
                                val data: Map<String, ChatMessage>? = Gson().fromJson(fullJson, object : TypeToken<Map<String, ChatMessage>>() {}.type)
                                if (data != null) {
                                    messagesList.clear()
                                    messagesList.addAll(data.values.sortedBy { it.timestamp })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val filteredUsers = usersList.filter { it.name.contains(searchQuery, ignoreCase = true) }
        .sortedByDescending { it.status.equals("Online", ignoreCase = true) }

    val isCallActive = callState != CallState.IDLE && activeCallData != null
    val screenBlur by animateDpAsState(if (isCallActive) 60.dp else 0.dp)

    Box(modifier = Modifier.fillMaxSize().background(surfaceColor)) {
        Row(modifier = Modifier.fillMaxSize().blur(screenBlur)) {
            // SIDEBAR (macOS Style)
            Surface(
                modifier = Modifier.width(320.dp).fillMaxHeight(),
                color = sidebarColor,
                tonalElevation = 2.dp
            ) {
                Column {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource("drawable/logo.xml"),
                            contentDescription = "Loomi",
                            modifier = Modifier.height(28.dp),
                            contentScale = ContentScale.Fit,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(accentColor)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .shadow(2.dp, CircleShape)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { onProfileClick() },
                                    onLongClick = { onLogout() }
                                )
                        ) {
                            ProfileImage(currentUserImage, 34.dp)
                        }
                    }

                    // Search Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .height(36.dp)
                            .background(if (isDark) Color(0xFF3A3A3C) else Color(0xFFE3E3E8), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Icon(
                                painter = painterResource("drawable/search.xml"),
                                contentDescription = "Search",
                                modifier = Modifier.size(16.dp),
                                tint = accentColor.copy(0.4f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(fontSize = 14.sp, color = accentColor),
                                cursorBrush = SolidColor(Color(0xFF007AFF)),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                "Search",
                                                color = accentColor.copy(0.4f),
                                                fontSize = 14.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Users List
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filteredUsers) { user ->
                            val isOnline = user.status.equals("Online", ignoreCase = true) && 
                                          (System.currentTimeMillis() - user.lastSeen < 60000)
                            val isSelected = selectedUser?.uid == user.uid

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedUser = user },
                                color = if (isSelected) blueColor else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProfileImage(user.imageName, 44.dp)

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            user.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) Color.White else accentColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = if (isOnline) "Online" else "Offline",
                                                fontSize = 11.sp,
                                                color = if (isSelected) Color.White.copy(0.6f) else accentColor.copy(0.4f),
                                                fontWeight = FontWeight.Medium
                                            )
                                            
                                            val msg = DesktopEncryptionUtils.decrypt(user.lastMessage)
                                            if (msg.isNotEmpty()) {
                                                Text(
                                                    text = " • " + (if (msg.startsWith("img:")) "Image" else msg),
                                                    fontSize = 11.sp,
                                                    color = if (isSelected) Color.White.copy(0.6f) else accentColor.copy(0.4f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(start = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    
                                    if (user.lastSeen > 0 && !isOnline) {
                                        Text(
                                            text = formatLastSeenShort(user.lastSeen),
                                            fontSize = 10.sp,
                                            color = if (isSelected) Color.White.copy(0.6f) else accentColor.copy(0.3f),
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }


            // CHAT PANE
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (selectedUser == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource("drawable/grop_chart.xml"),
                                contentDescription = "Select a conversation",
                                modifier = Modifier.size(440.dp)
                            )
                        }
                    }
                } else {
                    ChatPane(selectedUser!!, messagesList, accentColor, surfaceColor, isDark) {
                        val uid = FirebaseClient.currentUid ?: return@ChatPane
                        val callData = CallData(uid, selectedUser!!.uid, DesktopEncryptionUtils.encrypt(currentUserName), DesktopEncryptionUtils.encrypt(currentUserImage), "ringing")
                        FirebaseClient.write("calls/${selectedUser!!.uid}", callData)
                        activeCallData = callData
                        callState = CallState.OUTGOING
                    }
                }
            }
        }

        // Call Overlay (FaceTime style)
        if (isCallActive) {
            val uid = FirebaseClient.currentUid
            val isIStartedIt = activeCallData?.callerId == uid
            val isIncoming = callState == CallState.INCOMING
            
            // The person on the other end of the line (always show the peer)
            val peerImage = if (isIStartedIt) {
                selectedUser?.imageName ?: ""
            } else {
                DesktopEncryptionUtils.decrypt(activeCallData?.callerImage ?: "")
            }
            
            val peerName = if (isIStartedIt) {
                selectedUser?.name ?: "Contact"
            } else {
                DesktopEncryptionUtils.decrypt(activeCallData?.callerName ?: "")
            }
            
            Box(modifier = Modifier.fillMaxSize()) {
                // Background is the main UI blurred (via screenBlur on the Row below)

                // Call Content (Sharp Image, Name & Round Buttons)
                Column(
                    modifier = Modifier.fillMaxSize().padding(vertical = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Peer Name at Top
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = peerName.uppercase(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = accentColor,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when (callState) {
                                CallState.INCOMING -> "INCOMING CALL..."
                                CallState.OUTGOING -> "CALLING..."
                                CallState.ONGOING -> {
                                    val mins = callTicks / 60
                                    val secs = callTicks % 60
                                    "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
                                }
                                else -> ""
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor.copy(0.4f),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    }

                    // Sharp Profile Image in Middle
                    Box(modifier = Modifier.shadow(60.dp, CircleShape)) {
                        ProfileImage(peerImage, 240.dp)
                    }

                    Row(
                        modifier = Modifier.padding(bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(100.dp)
                    ) {
                        if (isIncoming) {
                            // Accept (Answer)
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .shadow(30.dp, CircleShape)
                                    .background(Color(0xFF34C759), CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { FirebaseClient.write("calls/${FirebaseClient.currentUid}/status", "accepted") },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource("drawable/call.xml"),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
                                )
                            }
                            // Decline (Cut)
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .shadow(30.dp, CircleShape)
                                    .background(Color(0xFFFF3B30), CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { FirebaseClient.delete("calls/${FirebaseClient.currentUid}") },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource("drawable/call.xml"),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).graphicsLayer(rotationZ = 135f),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
                                )
                            }
                        } else {
                            // End Call (Cut)
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .shadow(30.dp, CircleShape)
                                    .background(Color(0xFFFF3B30), CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { 
                                        activeCallData?.let { data ->
                                            // The call node is always at the receiver's UID
                                            FirebaseClient.delete("calls/${data.receiverId}")
                                        }
                                        callState = CallState.IDLE 
                                        activeCallData = null
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource("drawable/call.xml"),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).graphicsLayer(rotationZ = 135f),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatPane(receiver: SnapUser, messages: List<ChatMessage>, accent: Color, surface: Color, isDark: Boolean, onCall: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    val sendMessage = {
        if (input.isNotBlank()) {
            val uid = FirebaseClient.currentUid
            if (uid != null) {
                val cid = if (uid < receiver.uid) "${uid}_${receiver.uid}" else "${receiver.uid}_$uid"
                val msg = ChatMessage(
                    UUID.randomUUID().toString(),
                    uid,
                    receiver.uid,
                    DesktopEncryptionUtils.encrypt(input.trim()),
                    System.currentTimeMillis()
                )
                FirebaseClient.push("chats/$cid", msg) {}
                input = ""
            }
        }
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(modifier = Modifier.fillMaxSize()) {
        // iOS Style Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .drawBehind {
                    drawLine(accent.copy(0.05f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileImage(receiver.imageName, 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    receiver.name.uppercase(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                val isOnline = receiver.status.equals("Online", ignoreCase = true) && 
                              (System.currentTimeMillis() - receiver.lastSeen < 60000)
                Text(
                    text = if (isOnline) "ONLINE" else "OFFLINE",
                    fontSize = 9.sp,
                    color = accent.copy(0.4f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onCall) {
                Icon(
                    painter = painterResource("drawable/call.xml"),
                    contentDescription = "Call",
                    tint = Color(0xFF007AFF),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Messages Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { msg ->
                    val isMe = msg.senderId == FirebaseClient.currentUid
                    val isDark = isSystemInDarkTheme()
                    val decrypted = DesktopEncryptionUtils.decrypt(msg.message)
                    
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
                                .widthIn(max = 480.dp)
                                .clip(bubbleShape)
                                .background(
                                    if (isMe) (if (isDark) Color.White.copy(0.2f) else Color(0xFFC8E6C9).copy(alpha = 0.4f)) 
                                    else (if (isDark) Color.White.copy(0.1f) else Color(0xFFFFECB3).copy(alpha = 0.45f))
                                )
                                .border(1.dp, Color.White.copy(alpha = if (isDark) 0.1f else 0.80f), bubbleShape)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (decrypted.startsWith("img:")) {
                                val base64Data = decrypted.substring(4)
                                var imageBitmap by remember(base64Data) { mutableStateOf<ImageBitmap?>(null) }
                                
                                LaunchedEffect(base64Data) {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val bytes = Base64.getDecoder().decode(base64Data.replace("\\s".toRegex(), ""))
                                            imageBitmap = loadImageBitmap(bytes.inputStream())
                                        } catch (e: Exception) {}
                                    }
                                }

                                if (imageBitmap != null) {
                                    Image(
                                        bitmap = imageBitmap!!,
                                        contentDescription = "Image message",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 350.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text("LOADING IMAGE...", fontSize = 11.sp, color = accent.copy(0.4f))
                                }
                            } else {
                                Text(
                                    text = decrypted,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    color = if (isMe) (if (isDark) Color.White else accent) else (if (isDark) Color.White else accent.copy(0.7f))
                                )
                            }
                        }
                    }
                }
            }
        }

        // Nothing-Style Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isDark) Color(0xFF2C2C2E) else accent.copy(0.04f), RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(0.08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent {
                            if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                                sendMessage()
                                true
                            } else false
                        },
                    textStyle = TextStyle(fontSize = 14.sp, color = accent, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp),
                    cursorBrush = SolidColor(Color(0x80FF3B30)), // Nothing Red cursor
                    decorationBox = { innerTextField ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    "INPUT DATA TO NODE...", 
                                    color = accent.copy(0.2f), 
                                    fontSize = 12.sp, 
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(
                onClick = { sendMessage() },
                modifier = Modifier
                    .size(48.dp)
                    .background(if (input.isNotBlank()) Color(0xFFFF3B30) else accent.copy(0.1f), CircleShape)
            ) {
                Icon(
                    painter = painterResource("drawable/send.xml"),
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp),
                    tint = if (input.isNotBlank()) Color.White else accent.copy(0.3f)
                )
            }
        }
    }
}

fun formatMessageTime(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val sdf = java.text.SimpleDateFormat("HH:mm")
    return sdf.format(date)
}

@Composable
fun ProfileImage(path: String, size: androidx.compose.ui.unit.Dp) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            try {
                if (path.startsWith("data:image")) {
                    val bytes = Base64.getDecoder().decode(path.substringAfter("base64,").replace("\\s".toRegex(), ""))
                    bitmap = loadImageBitmap(bytes.inputStream())
                } else if (path.startsWith("http")) {
                    // Request high quality from Google
                    val highResUrl = if (path.contains("googleusercontent.com")) {
                        if (path.contains("=")) {
                            path.substringBeforeLast("=") + "=s512-c"
                        } else if (path.contains("/s96-c/")) {
                            path.replace("/s96-c/", "/s512-c/")
                        } else {
                            "$path=s512-c"
                        }
                    } else path
                    
                    val connection = URL(highResUrl).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    bitmap = loadImageBitmap(connection.getInputStream())
                }
            } catch (e: Exception) {}
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (isSystemInDarkTheme()) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High
            )
        } else if (path.isNotEmpty() && !path.contains(":")) {
            Image(
                painter = painterResource(path),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(2.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("👤", fontSize = (size.value * 0.4).sp, color = if (isSystemInDarkTheme()) Color.White else Color.Black)
        }
    }
}

fun parseUserMap(uid: String, map: Map<String, Any>): SnapUser = SnapUser(uid, map["name"] as? String ?: "Contact", map["status"] as? String ?: "Offline", (map["lastSeen"] as? Number)?.toLong() ?: 0L, map["lastMessage"] as? String ?: "", (map["lastMessageTime"] as? Number)?.toLong() ?: 0L, map["imageName"] as? String ?: "")

fun formatLastSeenShort(time: Long): String {
    val diff = System.currentTimeMillis() - time
    val min = diff / 60000
    val hr = min / 60
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        hr < 24 -> "${hr}h"
        else -> "${hr / 24}d"
    }
}
