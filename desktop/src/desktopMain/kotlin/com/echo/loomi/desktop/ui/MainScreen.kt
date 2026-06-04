package com.echo.loomi.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ImageBitmap
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

@Composable
fun MainScreen(
    currentUserName: String,
    currentUserImage: String,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val accentColor = if (isDark) Color.White else Color.Black
    val surfaceColor = if (isDark) Color.Black else Color.White

    // Database states
    val usersList = remember { mutableStateListOf<SnapUser>() }
    val messagesList = remember { mutableStateListOf<ChatMessage>() }

    // Selection states
    var selectedUser by remember { mutableStateOf<SnapUser?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Calling states
    var callState by remember { mutableStateOf(CallState.IDLE) }
    var activeCallData by remember { mutableStateOf<CallData?>(null) }
    var showSOSOverlay by remember { mutableStateOf(false) }

    // Sync Online status
    LaunchedEffect(Unit) {
        val uid = FirebaseClient.currentUid ?: return@LaunchedEffect
        FirebaseClient.write("users/$uid/status", "Online")
    }

    // Sync Data
    LaunchedEffect(Unit) {
        val uid = FirebaseClient.currentUid ?: return@LaunchedEffect
        val gson = Gson()

        FirebaseClient.startListener("users") { _, path, json ->
            scope.launch(Dispatchers.Main) {
                if (json == "null") return@launch
                if (path == "" || path == "/") {
                    val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
                    val data: Map<String, Map<String, Any>>? = gson.fromJson(json, type)
                    if (data != null) {
                        usersList.clear()
                        data.forEach { (key, value) -> if (key != uid) usersList.add(parseUserMap(key, value)) }
                    }
                } else {
                    val key = path.replace("/", "")
                    if (key != uid) {
                        FirebaseClient.read("users/$key") { userJson ->
                            if (userJson != null) {
                                scope.launch(Dispatchers.Main) {
                                    val valMap: Map<String, Any>? = gson.fromJson(userJson, object : TypeToken<Map<String, Any>>() {}.type)
                                    if (valMap != null) {
                                        val index = usersList.indexOfFirst { it.uid == key }
                                        val updated = parseUserMap(key, valMap)
                                        if (index != -1) usersList[index] = updated else usersList.add(updated)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FirebaseClient.startListener("calls/$uid") { _, _, json ->
            scope.launch(Dispatchers.Main) {
                if (json == "null") {
                    callState = CallState.IDLE
                    activeCallData = null
                } else {
                    val data: CallData? = gson.fromJson(json, CallData::class.java)
                    if (data != null) {
                        activeCallData = data
                        callState = when (data.status) {
                            "ringing" -> CallState.INCOMING
                            "accepted" -> CallState.ONGOING
                            else -> CallState.IDLE
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(selectedUser) {
        val uid = FirebaseClient.currentUid ?: return@LaunchedEffect
        val receiver = selectedUser
        messagesList.clear()
        if (receiver != null) {
            val chatId = if (uid < receiver.uid) "${uid}_${receiver.uid}" else "${receiver.uid}_$uid"
            FirebaseClient.startListener("chats/$chatId") { _, _, _ ->
                FirebaseClient.read("chats/$chatId") { json ->
                    if (json != null && json != "null") {
                        scope.launch(Dispatchers.Main) {
                            val data: Map<String, ChatMessage>? = Gson().fromJson(json, object : TypeToken<Map<String, ChatMessage>>() {}.type)
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

    val filteredUsers = usersList.filter { it.name.contains(searchQuery, ignoreCase = true) }
        .sortedByDescending { it.status == "Online" }

    Box(modifier = Modifier.fillMaxSize().background(surfaceColor)) {
        Row(modifier = Modifier.fillMaxSize()) {
            // SIDEBAR
            Column(
                modifier = Modifier.width(320.dp).fillMaxHeight()
                    .drawBehind {
                        drawLine(accentColor.copy(0.1f), Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                    }
            ) {
                // Header
                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileImage(currentUserImage, 40.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(currentUserName.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Black, color = accentColor, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onLogout) {
                        Icon(
                            painter = painterResource("drawable/setting_4.xml"),
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp),
                            tint = accentColor
                        )
                    }
                }

                // Modern Pill Search Bar
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        placeholder = { Text("Search nodes...", color = accentColor.copy(0.4f), fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource("drawable/search.xml"),
                                contentDescription = "Search",
                                modifier = Modifier.size(20.dp),
                                tint = accentColor.copy(0.4f)
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = accentColor.copy(0.06f),
                            unfocusedContainerColor = accentColor.copy(0.06f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = accentColor,
                            unfocusedTextColor = accentColor
                        ),
                        textStyle = TextStyle(fontSize = 14.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(26.dp)
                    )
                }

                // Users
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredUsers) { user ->
                        val isSelected = selectedUser?.uid == user.uid
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedUser = user }
                                .background(if (isSelected) accentColor.copy(0.05f) else Color.Transparent)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileImage(user.imageName, 44.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(user.name.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accentColor, fontFamily = FontFamily.Monospace)
                                val isOnline = user.status.equals("Online", ignoreCase = true)
                                Text(
                                    text = if (isOnline) "ONLINE" else "OFFLINE",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isOnline) Color(0xFF4CAF50) else accentColor.copy(0.2f),
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                                val msg = DesktopEncryptionUtils.decrypt(user.lastMessage)
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (msg.startsWith("img:")) "IMAGE" else msg.uppercase(),
                                        fontSize = 10.sp,
                                        color = accentColor.copy(0.4f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (!isOnline && user.lastSeen > 0) {
                                        Text(
                                            text = formatLastSeenShort(user.lastSeen),
                                            fontSize = 8.sp,
                                            color = accentColor.copy(0.3f),
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(start = 8.dp)
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
                        Image(
                            painter = painterResource("drawable/grop_chart.xml"),
                            contentDescription = "No node selected",
                            modifier = Modifier.size(320.dp) // 100% clear (no alpha 0.2)
                        )
                    }
                } else {
                    ChatPane(selectedUser!!, messagesList, accentColor, surfaceColor) {
                        val uid = FirebaseClient.currentUid ?: return@ChatPane
                        val callData = CallData(uid, selectedUser!!.uid, DesktopEncryptionUtils.encrypt(currentUserName), DesktopEncryptionUtils.encrypt(currentUserImage), "ringing")
                        FirebaseClient.write("calls/${selectedUser!!.uid}", callData)
                        activeCallData = callData
                        callState = CallState.OUTGOING
                    }
                }
            }
        }

        // Overlays (Simplified Nothing Style)
        if (callState != CallState.IDLE && activeCallData != null) {
            val isIncoming = callState == CallState.INCOMING
            val name = if (isIncoming) DesktopEncryptionUtils.decrypt(activeCallData!!.callerName) else selectedUser?.name ?: "NODE"
            Box(modifier = Modifier.fillMaxSize().background(accentColor).padding(40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name.uppercase(), fontSize = 32.sp, fontWeight = FontWeight.Black, color = surfaceColor, fontFamily = FontFamily.Monospace)
                    Text(callState.name, fontSize = 12.sp, color = surfaceColor.copy(0.5f), letterSpacing = 4.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(60.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (isIncoming) {
                            Button(onClick = { FirebaseClient.write("calls/${FirebaseClient.currentUid}/status", "accepted") }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor, contentColor = accentColor)) { Text("ACCEPT") }
                            Button(onClick = { FirebaseClient.delete("calls/${FirebaseClient.currentUid}") }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor.copy(0.2f), contentColor = surfaceColor)) { Text("DECLINE") }
                        } else {
                            Button(onClick = { FirebaseClient.delete("calls/${activeCallData!!.receiverId}"); callState = CallState.IDLE }, colors = ButtonDefaults.buttonColors(containerColor = surfaceColor, contentColor = accentColor)) { Text("END CALL") }
                        }
                    }
                }
            }
        }

        if (showSOSOverlay) {
            SOSOverlay(accentColor, surfaceColor) { showSOSOverlay = false }
        }
    }
}

@Composable
fun ChatPane(receiver: SnapUser, messages: List<ChatMessage>, accent: Color, surface: Color, onCall: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileImage(receiver.imageName, 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(receiver.name.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Black, color = accent, fontFamily = FontFamily.Monospace)
                val isOnline = receiver.status.equals("Online", ignoreCase = true)
                Text(
                    text = if (isOnline) "ONLINE" else "OFFLINE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOnline) Color(0xFF4CAF50) else accent.copy(0.3f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onCall) { Text("📞", color = accent) }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { msg ->
                    val isMe = msg.senderId == FirebaseClient.currentUid
                    val decrypted = DesktopEncryptionUtils.decrypt(msg.message)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start) {
                        Box(modifier = Modifier.widthIn(max = 400.dp).background(if (isMe) accent else accent.copy(0.05f), RoundedCornerShape(12.dp)).border(1.dp, accent.copy(0.1f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                            if (decrypted.startsWith("img:")) {
                                // Image logic simplified for brevity - uses ProfileImage helper style
                                Text("IMAGE NODE", color = if (isMe) surface else accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            } else {
                                Text(decrypted.uppercase(), color = if (isMe) surface else accent, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // Modern Message Input Bar
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f).height(52.dp),
                placeholder = { Text("Write a message...", color = accent.copy(0.4f), fontSize = 14.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = accent.copy(0.06f),
                    unfocusedContainerColor = accent.copy(0.06f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = accent,
                    unfocusedTextColor = accent
                ),
                textStyle = TextStyle(fontSize = 14.sp),
                shape = RoundedCornerShape(26.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        val uid = FirebaseClient.currentUid ?: return@IconButton
                        val cid = if (uid < receiver.uid) "${uid}_${receiver.uid}" else "${receiver.uid}_$uid"
                        val msg = ChatMessage(UUID.randomUUID().toString(), uid, receiver.uid, DesktopEncryptionUtils.encrypt(input.trim()), System.currentTimeMillis())
                        FirebaseClient.push("chats/$cid", msg) {}
                        input = ""
                    }
                },
                modifier = Modifier.size(52.dp).background(accent, CircleShape)
            ) {
                Icon(
                    painter = painterResource("drawable/send.xml"),
                    contentDescription = "Send",
                    modifier = Modifier.size(24.dp),
                    tint = surface
                )
            }
        }
    }
}

@Composable
fun SOSOverlay(accent: Color, surface: Color, onCancel: () -> Unit) {
    var count by remember { mutableStateOf(5) }
    LaunchedEffect(Unit) { while (count > 0) { delay(1000); count-- }; onCancel() }
    Box(modifier = Modifier.fillMaxSize().background(Color.Red).padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS BROADCAST", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White, fontFamily = FontFamily.Monospace)
            Text(count.toString(), fontSize = 120.sp, fontWeight = FontWeight.Black, color = Color.White, fontFamily = FontFamily.Monospace)
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Red)) { Text("CANCEL") }
        }
    }
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
                    // Use a stream for high-quality loading
                    val connection = URL(path).openConnection()
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
            .background(Color.Gray.copy(0.05f))
            .border(1.dp, Color.White.copy(0.1f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop // Ensures 100% fill with real quality
            )
        } else if (path.isNotEmpty() && !path.contains(":")) {
            // Local resource (Memoji/icons)
            Image(
                painter = painterResource(path),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(2.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("👤", fontSize = (size.value * 0.4).sp)
        }
    }
}

fun parseUserMap(uid: String, map: Map<String, Any>): SnapUser = SnapUser(uid, map["name"] as? String ?: "NODE", map["status"] as? String ?: "OFFLINE", (map["lastSeen"] as? Number)?.toLong() ?: 0L, map["lastMessage"] as? String ?: "", (map["lastMessageTime"] as? Number)?.toLong() ?: 0L, map["imageName"] as? String ?: "")

fun formatLastSeenShort(time: Long): String {
    val diff = System.currentTimeMillis() - time
    val min = diff / 60000
    val hr = min / 60
    return when {
        min < 1 -> "JUST NOW"
        min < 60 -> "${min}M"
        hr < 24 -> "${hr}H"
        else -> "${hr / 24}D"
    }
}
