package com.echo.loomi

import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.edit
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.text.format.DateUtils
import android.util.Base64
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.echo.loomi.ui.theme.LoomiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import android.util.Log
import android.os.Environment
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var googleAuthClient: GoogleAuthClient
    private lateinit var sosManager: SOSManager
    private var showSOSOverlay = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            // Must be set before any other database usage
            FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").setPersistenceEnabled(false)
        } catch (ignored: Exception) {}

        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sosManager = SOSManager(this) {
            showSOSOverlay.value = true
        }
        sosManager.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // --- AUTO-CLEANUP APK CACHE ---
        try {
            val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
                Log.d("Loomi_Cleanup", "Leftover update APK deleted from storage")
                Toast.makeText(this, "Old Update Removed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Loomi_Cleanup", "Failed to clear APK cache", e)
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
                    LaunchedEffect(Unit) {
                        KeepAliveWorker.schedule(applicationContext)
                        checkBatteryOptimizations()
                    }
                    SnapStyleScreen(
                        onLogout = {
                            val auth = FirebaseAuth.getInstance()
                            val uid = auth.currentUser?.uid
                            
                            // 1. Stop SOS sensor immediately to prevent accidental triggers during logout
                            if (::sosManager.isInitialized) {
                                sosManager.stop()
                            }

                            // 2. Stop the background service immediately
                            val serviceIntent = Intent(this@MainActivity, MessageListenerService::class.java)
                            stopService(serviceIntent)

                            if (uid != null) {
                                val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
                                // Only update status if user already exists to avoid ghost users
                                database.child("users").child(uid).child("name").get().addOnSuccessListener { s ->
                                    if (s.exists()) {
                                        database.child("users").child(uid).child("status").setValue("Offline")
                                        database.child("users").child(uid).child("lastSeen").setValue(ServerValue.TIMESTAMP)
                                    }
                                }
                            }
                            googleAuthClient.signOut()
                            getSharedPreferences("echo_prefs", MODE_PRIVATE).edit { 
                                clear() 
                                putBoolean("profile_done", false)
                            }

                            val intent = Intent(this@MainActivity, LoginActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        },
                        onAddAccount = {
                            if (::sosManager.isInitialized) {
                                sosManager.stop()
                            }
                            googleAuthClient.signIn(forcePicker = true)
                        }
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

        try {
            // Disabled persistence to prevent ghost user loops and stale presence data
            FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").setPersistenceEnabled(false)
        } catch (ignored: Exception) {}

        googleAuthClient = GoogleAuthClient(this) { success ->
            if (success) {
                getSharedPreferences("echo_prefs", MODE_PRIVATE).edit {
                    putBoolean("profile_done", true)
                }
                recreate()
            }
        }

        val auth = FirebaseAuth.getInstance()
        val prefs = getSharedPreferences("echo_prefs", MODE_PRIVATE)

        if (auth.currentUser == null) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                val uid = auth.currentUser?.uid
                if (uid != null) {
                    val dbRef = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
                    // Only update fcmToken if user exists in DB to avoid creating ghost users
                    dbRef.child("users").child(uid).child("name").get().addOnSuccessListener { s ->
                        if (s.exists()) {
                            dbRef.child("users").child(uid).child("fcmToken").setValue(token)
                        }
                    }
                }
            }
        }

        if (!prefs.getBoolean("profile_done", false)) {
            val db = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
            db.child("users").child(auth.currentUser!!.uid).get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.exists() && snapshot.hasChild("name") && snapshot.hasChild("imageName")) {
                        prefs.edit { putBoolean("profile_done", true) }
                        startMainServices()
                    } else {
                        val intent = Intent(this, WelcomeActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                }
                .addOnFailureListener {
                    val intent = Intent(this, WelcomeActivity::class.java)
                    startActivity(intent)
                    finish()
                }
        } else {
            startMainServices()
        }
    }

    private fun startMainServices() {
        val serviceIntent = Intent(this, MessageListenerService::class.java)
        startService(serviceIntent)
        KeepAliveWorker.schedule(this)
        SecurityWorker.schedule(this)
    }

    private fun checkBatteryOptimizations() {
        val packageName = packageName
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent().apply {
                    action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to battery settings if direct request fails
                try {
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (ex: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sosManager.isInitialized) {
            sosManager.stop()
        }
    }
}

@Immutable
data class SnapUser(
    val uid: String,
    val name: String,
    val email: String = "",
    val status: String = "Offline",
    val lastSeen: Long = 0,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0,
    val isPinned: Boolean = false,
    val imageName: String
)

data class Story(
    val id: String = "",
    val uid: String = "",
    val image: String = "", // Base64
    val songId: Long = 0,
    val songName: String = "",
    val timestamp: Long = 0,
    var userName: String = "",
    var userProfileImage: String = ""
)

fun formatLastSeen(lastSeen: Long): String {
    if (lastSeen <= 0) return "Never"
    val now = System.currentTimeMillis()
    val diff = now - lastSeen
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${days}d ago"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnapStyleScreen(onLogout: () -> Unit, onAddAccount: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = 1) { 2 }
    val scope = rememberCoroutineScope()
    var isShortsVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            when (page) {
                0 -> CameraScreen(
                    isActive = pagerState.currentPage == 0,
                    onBack = {
                        scope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    }
                )
                1 -> MainContent(
                    onLogout = onLogout,
                    onAddAccount = onAddAccount,
                    onCameraClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                    onShortsClick = {
                        isShortsVisible = true
                    }
                )
            }
        }

        if (isShortsVisible) {
            Box(modifier = Modifier.fillMaxSize().zIndex(100f)) {
                ShortsScreen(onBack = { isShortsVisible = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainContent(onLogout: () -> Unit, onAddAccount: () -> Unit, onCameraClick: () -> Unit, onShortsClick: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val usersList = remember { mutableStateListOf<SnapUser>() }
    val storiesList = remember { mutableStateListOf<Story>() }
    var selectedStoryForSheet by remember { mutableStateOf<Story?>(null) }
    var longPressedUser by remember { mutableStateOf<SnapUser?>(null) }
    val pullRefreshState = rememberPullToRefreshState()
    var isStoryReadyToShow by remember { mutableStateOf(false) }
    
    LaunchedEffect(selectedStoryForSheet) {
        if (selectedStoryForSheet != null) {
            delay(150)
            isStoryReadyToShow = true
        } else {
            isStoryReadyToShow = false
        }
    }
    val songUrlCache = remember { mutableStateMapOf<Long, String>() }

    var isSearchVisible by remember { mutableStateOf(false) }
    var isStoriesVisible by remember { mutableStateOf(false) }
    var isOffline by remember { mutableStateOf(false) }
    var animatingUserUid by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val isKeyboardVisible = WindowInsets.isImeVisible
    var wasKeyboardOpened by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchVisible || isStoriesVisible || selectedStoryForSheet != null || longPressedUser != null) {
        when {
            longPressedUser != null -> longPressedUser = null
            selectedStoryForSheet != null -> selectedStoryForSheet = null
            isSearchVisible -> isSearchVisible = false
            isStoriesVisible -> isStoriesVisible = false
        }
    }

    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible) {
            wasKeyboardOpened = true
        } else if (wasKeyboardOpened && isSearchVisible) {
            isSearchVisible = false
            wasKeyboardOpened = false
        }
    }

    LaunchedEffect(isSearchVisible) {
        if (!isSearchVisible) {
            wasKeyboardOpened = false
            searchQuery = ""
        }
    }

    val animProgress by animateFloatAsState(
        targetValue = if (isSearchVisible) 1f else 0f,
        animationSpec = tween(200, easing = LinearOutSlowInEasing),
        label = "search_anim"
    )

    val context = LocalContext.current
    val pinnedPrefs = remember { context.getSharedPreferences("pinned_users", android.content.Context.MODE_PRIVATE) }
    var pinnedUids by remember { mutableStateOf(pinnedPrefs.getStringSet("uids", emptySet()) ?: emptySet()) }

    val filteredUsersList = remember {
        derivedStateOf {
            val list = if (searchQuery.isEmpty()) {
                usersList.toList()
            } else {
                usersList.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
            list.map { it.copy(isPinned = pinnedUids.contains(it.uid)) }
                .sortedWith(
                    compareByDescending<SnapUser> { it.isPinned }
                        .thenByDescending { it.lastMessageTime }
                        .thenByDescending { it.status == "Online" }
                )
        }
    }

    val blurProgress by animateFloatAsState(
        targetValue = if (selectedStoryForSheet != null || longPressedUser != null) 1f else 0f,
        animationSpec = tween(200),
        label = "sheet_blur"
    )

    LaunchedEffect(isOffline) {
        // Offline logic removed
    }

    val currentUser = FirebaseAuth.getInstance().currentUser
    var currentUserImage by remember { mutableStateOf("") }
    var isLoadingProfile by remember { mutableStateOf(true) }

    val googleColors = listOf(
        Color(0xFF8AB4F8), Color(0xFFF28B82), Color(0xFFFDD663),
        Color(0xFF81C995), Color(0xFF669DF6)
    )
    var colorIndex1 by remember { mutableIntStateOf(0) }
    var colorIndex2 by remember { mutableIntStateOf(1) }
    var colorIndex3 by remember { mutableIntStateOf(2) }

    LaunchedEffect(currentUserImage) {
        if (currentUserImage.isNotEmpty()) {
            delay(3000) // Slower loading logic (3 seconds)
            isLoadingProfile = false
        }
    }

    LaunchedEffect(Unit) {
        delay(6000) // Fallback timeout extended
        if (isLoadingProfile) isLoadingProfile = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500) // Slower color transition (1.5s)
            colorIndex1 = (colorIndex1 + 1) % googleColors.size
            colorIndex2 = (colorIndex2 + 1) % googleColors.size
            colorIndex3 = (colorIndex3 + 1) % googleColors.size
        }
    }

    val c1 by animateColorAsState(googleColors[colorIndex1], tween(600), label = "c1")
    val c2 by animateColorAsState(googleColors[colorIndex2], tween(600), label = "c2")
    val c3 by animateColorAsState(googleColors[colorIndex3], tween(600), label = "c3")

    // --- Dynamic System Bars Support ---
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as androidx.activity.ComponentActivity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    LaunchedEffect(Unit) {
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        val uid = currentUser?.uid ?: return@LaunchedEffect

        database.child("users").child(uid).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || !snapshot.hasChild("name")) {
                    // If it's a ghost user (exists but no name), remove it to clean up DB
                    if (snapshot.exists()) {
                        snapshot.ref.removeValue()
                    }
                    onLogout()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        val connectedRef = database.child(".info/connected")

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                isOffline = !connected

                if (connected) {
                    // Check if user exists in database before setting online status
                    database.child("users").child(uid).child("name").get().addOnSuccessListener { userSnapshot ->
                        if (userSnapshot.exists()) {
                            // Firebase special logic: marks user Offline automatically if they lose connection
                            val statusRef = database.child("users").child(uid).child("status")
                            val lastSeenRef = database.child("users").child(uid).child("lastSeen")

                            statusRef.onDisconnect().setValue("Offline")
                            lastSeenRef.onDisconnect().setValue(ServerValue.TIMESTAMP)

                            // Mark as Online now that we are connected
                            statusRef.setValue("Online")
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        database.child("users").child(uid).child("imageName")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    currentUserImage = snapshot.getValue(String::class.java) ?: ""
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        database.child("users").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usersList.clear()
                for (userSnapshot in snapshot.children) {
                    val otherUid = userSnapshot.child("uid").getValue(String::class.java) ?: ""
                    if (otherUid != uid) {
                        val name = userSnapshot.child("name").getValue(String::class.java) ?: "Unknown"
                        val email = userSnapshot.child("email").getValue(String::class.java) ?: ""
                        val imageName = userSnapshot.child("imageName").getValue(String::class.java) ?: ""
                        val status = userSnapshot.child("status").getValue(String::class.java) ?: "Offline"
                        val lastSeen = userSnapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                        val user = SnapUser(otherUid, name, email, status, lastSeen, imageName = imageName)
                        usersList.add(user)

                        val chatId = if (uid < otherUid) "${uid}_$otherUid" else "${otherUid}_$uid"
                        database.child("chats").child(chatId).limitToLast(1)
                            .addValueEventListener(object : ValueEventListener {
                                override fun onDataChange(chatSnapshot: DataSnapshot) {
                                    if (chatSnapshot.exists()) {
                                        val lastMsgObj = chatSnapshot.children.firstOrNull()
                                        val lastMsgText = lastMsgObj?.child("message")?.getValue(String::class.java) ?: ""
                                        val lastMsgTime = lastMsgObj?.child("timestamp")?.getValue(Long::class.java) ?: 0L
                                        val index = usersList.indexOfFirst { it.uid == otherUid }
                                        if (index != -1) {
                                            usersList[index] = usersList[index].copy(lastMessage = lastMsgText, lastMessageTime = lastMsgTime)
                                        }
                                    }
                                }
                                override fun onCancelled(error: DatabaseError) {}
                            })
                    }
                }
                usersList.sortWith(compareByDescending<SnapUser> { it.lastMessageTime }.thenByDescending { it.status == "Online" })
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Fetch Stories for Top Row
        database.child("stories").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentTime = System.currentTimeMillis()
                val twentyFourHoursAgo = currentTime - TimeUnit.HOURS.toMillis(24)
                val newStoriesList = mutableListOf<Story>()

                for (child in snapshot.children) {
                    val story = child.getValue(Story::class.java)
                    if (story != null) {
                        // Protocol: Only keep stories less than 24 hours old
                        if (story.timestamp > twentyFourHoursAgo || story.timestamp == 0L) {
                            newStoriesList.add(story)
                        } else {
                            // Auto delete expired story from database
                            child.ref.removeValue()
                        }
                    }
                }

                // Get only the most recent story per user
                val uniqueStories = newStoriesList.groupBy { it.uid }
                    .map { it.value.maxBy { s -> s.timestamp } }
                    .sortedByDescending { it.timestamp }

                // Clear and add with user details
                storiesList.clear()
                uniqueStories.forEach { story ->
                    database.child("users").child(story.uid).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(userSnapshot: DataSnapshot) {
                            val userName = userSnapshot.child("name").getValue(String::class.java) ?: "Unknown"
                            val userProfileImage = userSnapshot.child("imageName").getValue(String::class.java) ?: ""

                            val updatedStory = story.copy(userName = userName, userProfileImage = userProfileImage)
                            val index = storiesList.indexOfFirst { it.uid == story.uid }
                            if (index != -1) {
                                storiesList[index] = updatedStory
                            } else {
                                storiesList.add(updatedStory)
                                storiesList.sortByDescending { it.timestamp }
                            }

                            // Pre-fetch song URL for faster play
                            if (story.songId != 0L && !songUrlCache.containsKey(story.songId)) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val url = URL("https://itunes.apple.com/lookup?id=${story.songId}")
                                        val connection = url.openConnection() as HttpURLConnection
                                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                                        val json = JSONObject(response)
                                        val results = json.getJSONArray("results")
                                        if (results.length() > 0) {
                                            val pUrl = results.getJSONObject(0).optString("previewUrl")
                                            if (pUrl.isNotEmpty()) {
                                                songUrlCache[story.songId] = pUrl
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("PreFetch", "Failed to pre-fetch song", e)
                                    }
                                }
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            scope.launch {
                delay(3000)
                isRefreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
        state = pullRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.padding(top = 80.dp).align(Alignment.TopCenter)
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(androidx.compose.ui.unit.lerp(0.dp, 25.dp, blurProgress))
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        Column(
                            modifier = Modifier
                                .statusBarsPadding()
                                .fillMaxWidth()
                                .background(Color.Transparent)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .align(Alignment.CenterStart)
                                        .clip(CircleShape)
                                        .background(
                                            if (isDark) Color.White else Color(0xFFFFFCDC),
                                            CircleShape
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                context.startActivity(
                                                    Intent(
                                                        context,
                                                        WelcomeActivity::class.java
                                                    )
                                                )
                                            },
                                            onLongClick = onAddAccount
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AnimatedContent(
                                        targetState = isLoadingProfile,
                                        transitionSpec = {
                                            (fadeIn(tween(600)) + scaleIn(initialScale = 0.8f))
                                                .togetherWith(fadeOut(tween(600)))
                                        },
                                        label = "profile_transition"
                                    ) { loading ->
                                        if (loading) {
                                            LoadingIndicator(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                                    .drawWithContent {
                                                        drawContent()
                                                        drawRect(
                                                            brush = Brush.linearGradient(
                                                                listOf(
                                                                    c1,
                                                                    c2,
                                                                    c3
                                                                )
                                                            ),
                                                            blendMode = BlendMode.SrcAtop
                                                        )
                                                    },
                                                color = Color.White
                                            )
                                        } else {
                                            val profileRequest = remember(currentUserImage) {
                                                val data: Any =
                                                    if (currentUserImage.startsWith("data:image")) {
                                                        try {
                                                            val base64Data =
                                                                currentUserImage.substringAfter("base64,")
                                                            Base64.decode(base64Data, Base64.DEFAULT)
                                                        } catch (e: Exception) {
                                                            currentUserImage
                                                        }
                                                    } else if (currentUserImage.startsWith("http")) {
                                                        currentUserImage
                                                    } else if (currentUserImage.isNotEmpty()) {
                                                        "file:///android_asset/$currentUserImage"
                                                    } else {
                                                        R.drawable.logo // Default placeholder
                                                    }

                                                ImageRequest.Builder(context)
                                                    .data(data)
                                                    .crossfade(true)
                                                    .size(120, 120)
                                                    .build()
                                            }
                                            AsyncImage(
                                                model = profileRequest,
                                                contentDescription = "Profile",
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(2.5.dp) // Ring effect
                                                    .clip(CircleShape),
                                                contentScale = ContentScale.Crop,
                                                error = painterResource(R.drawable.logo)
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier.align(Alignment.Center)
                                        .clickable {
                                            context.startActivity(
                                                Intent(
                                                    context,
                                                    Setting::class.java
                                                )
                                            )
                                        }
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.logo),
                                        contentDescription = "Logo",
                                        modifier = Modifier.height(30.dp),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = ColorFilter.tint(if (isDark) Color.White else MaterialTheme.colorScheme.onSurface)
                                    )
                                }

                                Row(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            context.startActivity(
                                                Intent(
                                                    context,
                                                    Setting::class.java
                                                )
                                            )
                                        },
                                        modifier = Modifier.size(35.dp).clip(CircleShape).background(
                                            if (isDark) Color.White else Color(0xFFFFECB3).copy(
                                                alpha = 0.5f
                                            ), CircleShape
                                        )
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.setting_4),
                                            contentDescription = "Settings",
                                            modifier = Modifier.size(20.dp),
                                            tint = Color.Black
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = isSearchVisible,
                                enter = fadeIn(animationSpec = tween(100)) + expandVertically(),
                                exit = fadeOut(animationSpec = tween(100)) + shrinkVertically()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .height(54.dp)
                                        .clip(RoundedCornerShape(60.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                        .border(
                                            1.dp,
                                            if (isDark) Color.White else Color.Black,
                                            RoundedCornerShape(600.dp)
                                        )
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(if (isDark) Color.Black else Color.White.copy(0.35f))
                                            .clickable { isSearchVisible = false },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.search),
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp),
                                            tint = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    }

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
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            modifier = Modifier.focusRequester(focusRequester),
                                            placeholder = {
                                                Text(
                                                    "Search friends...",
                                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                                        0.4f
                                                    ),
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                            },
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent,
                                                disabledContainerColor = Color.Transparent,
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent,
                                                cursorColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 16.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp)
                        ) {
                            // Stories Row at the Top
                            item {
                                AnimatedVisibility(
                                    visible = isStoriesVisible,
                                    enter = fadeIn(animationSpec = tween(300)) + expandVertically(),
                                    exit = fadeOut(animationSpec = tween(300)) + shrinkVertically()
                                ) {
                                    Column {
                                        if (storiesList.isNotEmpty()) {
                                            LazyRow(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 12.dp),
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                items(storiesList, key = { it.id }) { story ->
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.clickable {
                                                            selectedStoryForSheet = story
                                                        }
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(68.dp)
                                                                .clip(CircleShape)
                                                                .border(
                                                                    2.dp,
                                                                    Color(0xFF81C995),
                                                                    CircleShape
                                                                )
                                                                .padding(3.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                    Color.Gray.copy(
                                                                        alpha = 0.1f
                                                                    )
                                                                )
                                                        ) {
                                                            val storyBitmap =
                                                                remember(story.image) {
                                                                    try {
                                                                        val imageBytes =
                                                                            Base64.decode(
                                                                                story.image,
                                                                                Base64.DEFAULT
                                                                            )
                                                                        BitmapFactory.decodeByteArray(
                                                                            imageBytes,
                                                                            0,
                                                                            imageBytes.size
                                                                        )
                                                                    } catch (e: Throwable) {
                                                                        null
                                                                    }
                                                                }
                                                            if (storyBitmap != null) {
                                                                Image(
                                                                    bitmap = storyBitmap.asImageBitmap(),
                                                                    contentDescription = null,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentScale = ContentScale.Crop
                                                                )
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = story.userName.split(" ")
                                                                .firstOrNull() ?: "",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(
                                                                alpha = 0.7f
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                            HorizontalDivider(
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(horizontal = 16.dp),
                                                thickness = 1.dp,
                                                color = if (isDark) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.08f
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            items(items = filteredUsersList.value, key = { it.uid }) { user ->
                                SnapChatItem(
                                    user = user,
                                    isAnimating = animatingUserUid == user.uid,
                                    onClick = {
                                        val intent =
                                            Intent(context, MessageActivity::class.java).apply {
                                                putExtra("receiverUid", user.uid)
                                                putExtra("receiverName", user.name)
                                                putExtra("receiverImage", user.imageName)
                                            }
                                        context.startActivity(intent)
                                    }, onLongClick = {
                                        longPressedUser = user
                                    })
                            }
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth().height(250.dp)
                                .align(Alignment.BottomCenter).background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                                        )
                                    )
                                )
                        )
                    }
                }

                FloatingBottomNavBar(
                    onCameraClick = onCameraClick,
                    onSearchClick = {
                        isSearchVisible = !isSearchVisible
                        if (isSearchVisible) isStoriesVisible = false
                    },
                    onShortsClick = onShortsClick,
                    onStoryClick = {
                        isStoriesVisible = !isStoriesVisible
                        if (isStoriesVisible) isSearchVisible = false
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp)
                )
            }

            if (selectedStoryForSheet != null && isStoryReadyToShow) {
                Box(modifier = Modifier.fillMaxSize().zIndex(10f), contentAlignment = Alignment.TopCenter) {
                    Image(
                        painter = painterResource(id = R.drawable.share_musik),
                        contentDescription = "Like",
                        modifier = Modifier.size(350.dp).padding(top = 90.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                StoryBottomSheet(
                    story = selectedStoryForSheet!!,
                    cache = songUrlCache,
                    onDismiss = { selectedStoryForSheet = null }
                )
            }

            if (longPressedUser != null) {
                UserActionOverlay(
                    user = longPressedUser!!,
                    onPinToggle = { user ->
                        val newPinned = if (pinnedUids.contains(user.uid)) {
                            pinnedUids - user.uid
                        } else {
                            pinnedUids + user.uid
                        }
                        pinnedUids = newPinned
                        pinnedPrefs.edit().putStringSet("uids", newPinned).apply()
                    },
                    onBlock = { user ->
                        scope.launch {
                            animatingUserUid = user.uid
                            delay(1000)
                            animatingUserUid = null
                        }
                    },
                    onDismiss = { longPressedUser = null }
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryBottomSheet(
    story: Story,
    cache: MutableMap<Long, String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var songArtworkUrl by remember { mutableStateOf<String?>(null) }
    var previewUrl by remember { mutableStateOf<String?>(cache[story.songId]) }
    val sheetMediaPlayer = remember { MediaPlayer() }

    val bitmap = remember(story.image) {
        try {
            val imageBytes = Base64.decode(story.image, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    val timeAgo = remember(story.timestamp) {
        if (story.timestamp == 0L) ""
        else DateUtils.getRelativeTimeSpanString(story.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    LaunchedEffect(story.songId) {
        if (story.songId == 0L) return@LaunchedEffect
        if (cache.containsKey(story.songId)) {
            previewUrl = cache[story.songId]
        }

        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://itunes.apple.com/lookup?id=${story.songId}")
                val connection = url.openConnection() as HttpURLConnection
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val results = json.getJSONArray("results")
                if (results.length() > 0) {
                    val item = results.getJSONObject(0)
                    val pUrl = item.optString("previewUrl")
                    previewUrl = pUrl
                    cache[story.songId] = pUrl
                    songArtworkUrl = item.optString("artworkUrl100").replace("100x100bb.jpg", "600x600bb.jpg")
                }
            } catch (e: Exception) {
                Log.e("StorySheet", "Failed to fetch song details", e)
            }
        }
    }

    LaunchedEffect(previewUrl) {
        val url = previewUrl ?: return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                sheetMediaPlayer.apply {
                    reset()
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(url)
                    isLooping = true
                    prepare() // prepare() on IO thread is faster to start than prepareAsync() on main
                }
            }
            sheetMediaPlayer.start()
        } catch (e: Exception) {
            Log.e("StorySheet", "Failed to play music in sheet", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sheetMediaPlayer.release()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Color(0xFFFFF6DE),
        scrimColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(3.dp, Color.White, CircleShape)
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (0.dp), y = (-10.dp))
                        .size(65.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(16.dp))
                        .background(Color(0xFFFFAB91)),
                    contentAlignment = Alignment.Center
                ) {
                    if (songArtworkUrl != null) {
                        AsyncImage(
                            model = songArtworkUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.musicnote),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(25.dp))
            Text(
                text = "${story.userName}, $timeAgo",
                fontSize = 14.sp,
                color = Color.Black.copy(0.60f),
                fontWeight = FontWeight.Medium
            )
            
            if (story.songName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.musicnote),
                        contentDescription = null,
                        tint = Color.Black.copy(0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = story.songName,
                        fontSize = 12.sp,
                        color = Color.Black.copy(0.7f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnapChatItem(user: SnapUser, onClick: () -> Unit, onLongClick: () -> Unit, isAnimating: Boolean = false) {
    val isDark = isSystemInDarkTheme()
    
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            launch {
                scale.animateTo(0.85f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
            launch {
                rotation.animateTo(3f, spring(dampingRatio = Spring.DampingRatioHighBouncy))
                rotation.animateTo(-3f, spring(dampingRatio = Spring.DampingRatioHighBouncy))
                rotation.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
        }
    }

    Row(modifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            rotationZ = rotation.value
        }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .drawBehind {
        val strokeWidth = 1.dp.toPx()
        val y = size.height - strokeWidth / 2
        drawLine(color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.1f), start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = strokeWidth)
    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val statusColor = if (user.status == "Online") Color(0xFFA5D6A7)
                         else if (isDark) Color.White.copy(alpha = 0.2f)
                         else Color(0xFFFFD54F).copy(alpha = 0.5f)

        Box(modifier = Modifier.size(50.dp).border(width = 2.dp, color = statusColor, shape = CircleShape).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
            val context = LocalContext.current
            val imageRequest = remember(user.imageName) {
                val data: Any = if (user.imageName.startsWith("data:image")) {
                    try {
                        val base64Data = user.imageName.substringAfter("base64,")
                        Base64.decode(base64Data, Base64.DEFAULT)
                    } catch (e: Exception) {
                        user.imageName
                    }
                } else if (user.imageName.startsWith("http")) {
                    user.imageName
                } else if (user.imageName.isNotEmpty()) {
                    "file:///android_asset/${user.imageName}"
                } else {
                    R.drawable.logo
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .crossfade(true)
                    .size(150, 150)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.padding(4.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.logo)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = user.name, fontSize = 17.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val displayMsg = remember(user.lastMessage) {
                    if (user.lastMessage.startsWith("img:")) "Sent an image"
                    else EncryptionUtils.decrypt(user.lastMessage)
                }
                val statusText = when {
                    displayMsg.isNotEmpty() -> displayMsg
                    user.status == "Online" -> "Online"
                    else -> "Last seen ${formatLastSeen(user.lastSeen)}"
                }
                Text(text = "➤", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                Text(text = statusText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Icon(
            painter = painterResource(id = R.drawable.heart),
            contentDescription = null,
            modifier = Modifier.size(28.dp).padding(end = 4.dp),
            tint = if (user.isPinned) Color.Red else (if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.4f))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingBottomNavBar(
    onCameraClick: () -> Unit,
    onSearchClick: () -> Unit,
    onShortsClick: () -> Unit,
    onStoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFFF2D9)
    val iconColor = if (isDark) Color.White else Color.Black

    Box(modifier = modifier.zIndex(1f).padding(horizontal = 40.dp).height(50.dp).clip(RoundedCornerShape(30.dp)).background(bgColor).border(width = 2.dp, color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(30.dp))) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCameraClick, modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.camera), null, tint = iconColor, modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onSearchClick, modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.search), null, tint = iconColor, modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onShortsClick, modifier = Modifier.size(36.dp)) { Icon(painterResource(R.drawable.video), null, tint = iconColor, modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onStoryClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.heart), null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun UserActionOverlay(
    user: SnapUser,
    onPinToggle: (SnapUser) -> Unit,
    onBlock: (SnapUser) -> Unit = {},
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    // Glass style for dark mode, normal style for light mode
    val bgColor = if (isDark) Color.Black.copy(alpha = 0.6f) else Color(0xFFFFF8E1).copy(alpha = 0.95f)
    val textColor = if (isDark) Color.White else Color.Black
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.2f) else Color.White

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = true, onClick = onDismiss)
            .zIndex(20f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(25.dp))
                .clickable(enabled = false) { } // Prevent dismiss when clicking the card
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = user.name,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.padding(vertical = 16.dp),
                color = textColor
            )
            
            HorizontalDivider(thickness = 1.dp, color = dividerColor)
            
            if (!user.isPinned) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPinToggle(user); onDismiss() }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Pin", fontSize = 16.sp, color = textColor)
                }
                HorizontalDivider(thickness = 1.dp, color = dividerColor)
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBlock(user); onDismiss() }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Block", color = textColor, fontSize = 16.sp)
            }
            
            HorizontalDivider(thickness = 1.dp, color = dividerColor)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Report", color = textColor, fontSize = 16.sp)
            }

            if (user.isPinned) {
                HorizontalDivider(thickness = 1.dp, color = dividerColor)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPinToggle(user); onDismiss() }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Unpin", color = textColor, fontSize = 16.sp)
                }
            }
        }
    }
}
