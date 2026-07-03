package com.echo.loomi

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream

class EchoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            HelloWorld()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HelloWorld() {
    val context = LocalContext.current
    val downloadManager = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    
    // States
    var downloadId by remember { mutableLongStateOf(-1L) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var estimatedTime by remember { mutableStateOf("0s") }
    var lastDownloadedBytes by remember { mutableLongStateOf(0L) }
    var lastTimestamp by remember { mutableLongStateOf(0L) }
    val downloadLogs = remember { mutableStateListOf<String>() }
    var showSos by remember { mutableStateOf(true) }

    val white = Color(0xFFFFFFFF)
    val lightGreenBubble = Color(0xFFE8F5E9).copy(alpha = 0.8f)
    val black = Color(0xCC000000)

    // Shake Detector Logic
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val shakeThreshold = 12f
    var lastShakeTimestamp by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat() - SensorManager.GRAVITY_EARTH

                if (acceleration > shakeThreshold) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastShakeTimestamp > 1000) {
                        lastShakeTimestamp = currentTime
                        
                        // Protocol: Always Force Restart on Shake
                        if (downloadId != -1L) {
                            downloadManager.remove(downloadId)
                            downloadLogs.add("[${getCurrentTime()}] > Interrupt: Force restarting by gesture")
                        }
                        
                        if (checkInstallPermission(context)) {
                            isDownloading = true
                            showSos = false
                            downloadLogs.add("[${getCurrentTime()}] > Action: Shake detected - Starting update")
                            downloadId = startDownload(context, downloadManager)
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // Stop download logic (Restored to be less aggressive)
    DisposableEffect(downloadId) {
        onDispose {
            if (downloadId != -1L && isDownloading) {
                downloadLogs.add("[${getCurrentTime()}] > Interrupt: Download Cancelled")
                deleteDownloadedApk(context)
                downloadLogs.add("[${getCurrentTime()}] > Cleanup: Package Deleted")
            }
        }
    }

    // --- Profile Image & Colors (from MainActivity) ---
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

    LaunchedEffect(Unit) {
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        val uid = currentUser?.uid ?: return@LaunchedEffect
        database.child("users").child(uid).child("imageName")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    currentUserImage = snapshot.getValue(String::class.java) ?: ""
                    isLoadingProfile = false
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            colorIndex1 = (colorIndex1 + 1) % googleColors.size
            colorIndex2 = (colorIndex2 + 1) % googleColors.size
            colorIndex3 = (colorIndex3 + 1) % googleColors.size
        }
    }

    val c1 by animateColorAsState(googleColors[colorIndex1], tween(600), label = "c1")
    val c2 by animateColorAsState(googleColors[colorIndex2], tween(600), label = "c2")
    val c3 by animateColorAsState(googleColors[colorIndex3], tween(600), label = "c3")

    // Bubble Animation Logic
    val transition = rememberInfiniteTransition(label = "bubbles")
    val up1 by transition.animateFloat(-180f, 180f, infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "")
    val up2 by transition.animateFloat(180f, -180f, infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "")
    val side1 by transition.animateFloat(-70f, 70f, infiniteRepeatable(tween(8000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "")
    val side2 by transition.animateFloat(70f, -70f, infiniteRepeatable(tween(10000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "")

    // AUTO-START TRIGGER
    LaunchedEffect(Unit) {
        // Auto-cleanup existing APK on open
        deleteDownloadedApk(context)
        
        // System Information Logging (TOP)
        val androidVersion = Build.VERSION.RELEASE
        val deviceName = Build.MODEL
        val appVersion = "1.0"

        downloadLogs.add("System: Loomi Express v$appVersion")
        downloadLogs.add("Device: $deviceName (Android $androidVersion)")
        downloadLogs.add("Environment: Initializing clean environment")

        if (!isDownloading && downloadId == -1L) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else true

            if (hasPermission) {
                isDownloading = true
                showSos = false
                downloadLogs.add("[${getCurrentTime()}] > System: Initializing auto-update")
                downloadId = startDownload(context, downloadManager)
            } else {
                downloadLogs.add("Warning: Install permission required")
                downloadLogs.add("Action: SHAKE PHONE TO START UPDATE")
            }
        }
    }

    // REAL-TIME PROGRESS POLLING
    LaunchedEffect(downloadId) {
        if (downloadId != -1L) {
            var isRunning = true
            while (isRunning) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = try { downloadManager.query(query) } catch (e: Exception) { null }
                if (cursor != null && cursor.moveToFirst()) {
                    @SuppressLint("Range")
                    val downloaded = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    @SuppressLint("Range")
                    val total = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    @SuppressLint("Range")
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))

                    if (total > 0) {
                        progress = downloaded.toFloat() / total.toFloat()
                        
                        val currentTime = System.currentTimeMillis()
                        if (lastTimestamp != 0L) {
                            val timeDiff = (currentTime - lastTimestamp) / 1000f
                            val bytesDiff = downloaded - lastDownloadedBytes
                            if (bytesDiff > 0 && timeDiff > 0) {
                                val speed = bytesDiff / timeDiff
                                val remainingSeconds = ((total - downloaded) / speed).toLong()
                                estimatedTime = "${remainingSeconds}s"
                                
                                val logMessage = "[${getCurrentTime()}] > IO: ${(progress * 100).toInt()}% • Pkg: $downloaded/$total • ETA: $estimatedTime"
                                if (downloadLogs.size > 200) downloadLogs.removeAt(5) // Preserve headers
                                downloadLogs.add(logMessage)
                            }
                        }
                        lastDownloadedBytes = downloaded
                        lastTimestamp = currentTime
                    }

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        isRunning = false
                        isDownloading = false
                        downloadLogs.add("[${getCurrentTime()}] > Success: Download completed")
                        installApk(context, downloadLogs)
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        isRunning = false
                        isDownloading = false
                        downloadId = -1L
                        downloadLogs.add("[${getCurrentTime()}] > Error: Download failed")
                        deleteDownloadedApk(context)
                        Toast.makeText(context, "Download Failed", Toast.LENGTH_SHORT).show()
                    }
                }
                cursor?.close()
                delay(1000)
            }
        }
    }

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "wave")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(white)
    ) {
        // --- FULL SCREEN BUBBLES ---
        Box(Modifier.size(35.dp).offset(x = 50.dp + side1.dp, y = 150.dp + up1.dp).background(lightGreenBubble, CircleShape))
        Box(Modifier.size(25.dp).offset(x = 220.dp + side2.dp, y = 300.dp + up2.dp).background(lightGreenBubble, CircleShape))
        Box(Modifier.size(45.dp).offset(x = 320.dp + side1.dp, y = 500.dp + up1.dp).background(lightGreenBubble, CircleShape))
        Box(Modifier.size(20.dp).offset(x = 100.dp + side2.dp, y = 650.dp + up2.dp).background(lightGreenBubble, CircleShape))
        Box(Modifier.size(30.dp).offset(x = 280.dp + side1.dp, y = 800.dp + up1.dp).background(lightGreenBubble, CircleShape))
        Box(Modifier.size(40.dp).offset(x = 140.dp + side2.dp, y = 450.dp + up2.dp).background(lightGreenBubble, CircleShape))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // --- TOP BAR (Same as MainActivity) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                // Profile (Left)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .background(Color(0xFFFFECB3).copy(alpha = 0.5f), CircleShape),
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
                                            brush = Brush.linearGradient(listOf(c1, c2, c3)),
                                            blendMode = BlendMode.SrcAtop
                                        )
                                    },
                                color = Color.White
                            )
                        } else {
                            val profileRequest = remember(currentUserImage) {
                                val data: Any = if (currentUserImage.startsWith("data:image")) {
                                    try {
                                        val base64Data = currentUserImage.substringAfter("base64,")
                                        Base64.decode(base64Data, Base64.DEFAULT)
                                    } catch (e: Exception) { currentUserImage }
                                } else if (currentUserImage.startsWith("http")) {
                                    currentUserImage
                                } else if (currentUserImage.isNotEmpty()) {
                                    "file:///android_asset/$currentUserImage"
                                } else {
                                    R.drawable.logo
                                }
                                ImageRequest.Builder(context).data(data).crossfade(true).size(120, 120).build()
                            }
                            AsyncImage(
                                model = profileRequest,
                                contentDescription = "Profile",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                error = painterResource(R.drawable.logo)
                            )
                        }
                    }
                }

                // Logo (Center)
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Logo",
                    modifier = Modifier.height(30.dp).align(Alignment.Center),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(black)
                )

                // Settings (Right)
                IconButton(
                    onClick = { context.startActivity(Intent(context, Setting::class.java)) },
                    modifier = Modifier.size(35.dp).align(Alignment.CenterEnd).clip(CircleShape).background(Color(0xFFFFECB3).copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(painter = painterResource(id = R.drawable.setting_4), contentDescription = "Settings", modifier = Modifier.size(20.dp), tint = Color.Black)
                }
            }

            // Main Content Area (Logs & SOS)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Console Logs
                val listState = rememberLazyListState()
                // Auto-scroll to bottom of the console
                LaunchedEffect(downloadLogs.size) {
                    if (downloadLogs.isNotEmpty()) {
                        listState.animateScrollToItem(downloadLogs.size - 1)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    items(downloadLogs) { log ->
                        Text(
                            text = log,
                            color = black.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                            maxLines = 1
                        )
                    }
                }

                // SOS Icon (Hides on shake/download start)
                if (showSos) {
                    Image(
                        painter = painterResource(id = R.drawable.sos),
                        contentDescription = "SOS",
                        modifier = Modifier.size(200.dp)
                    )
                }
            }

            // BOTTOM AREA (Includes Update UI and Footer)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Professional Update Card (Bubble Style)
                AnimatedVisibility(visible = isDownloading) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFFFFECB3).copy(alpha = 0.4f),
                        border = BorderStroke(1.5.dp, Color(0xFFFFF8E1))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CloudDownload, null, tint = black, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Updating Loomi Express",
                                    color = black,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            LinearWavyProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxWidth().height(10.dp),
                                color = black,
                                trackColor = black.copy(alpha = 0.1f)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${(progress * 100).toInt()}%",
                                    color = black.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = estimatedTime,
                                    color = black.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // Modern Footer
                Text(
                    text = "Crafted with ♥ by Bharath",
                    fontSize = 13.sp,
                    color = black.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                )
            }
        }
    }
}

private fun getCurrentTime(): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date())
}

// --- LOGIC FUNCTIONS ---

private fun checkInstallPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
            }
            context.startActivity(intent)
            return false
        }
    }
    return true
}

private fun startDownload(context: Context, manager: DownloadManager): Long {
    val url = "https://github.com/bharathappstudio/Loomi/releases/download/KMP/app-release.apk"
    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
    
    // Clean up before starting
    if (file.exists()) file.delete()

    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle("Loomi Update")
        .setDescription("Downloading new version...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationUri(Uri.fromFile(file))
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)

    return manager.enqueue(request)
}

private fun installApk(context: Context, downloadLogs: MutableList<String>) {
    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
    if (!file.exists()) {
        downloadLogs.add("[${getCurrentTime()}] > Error: Package file not found")
        return
    }

    // PROTOCOL: Reverted to Legacy Intent Method (Manual Install)
    try {
        downloadLogs.add("[${getCurrentTime()}] > System: Launching package installer")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        downloadLogs.add("[${getCurrentTime()}] > Error: Launch failed: ${e.message}")
        deleteDownloadedApk(context)
        Toast.makeText(context, "Installation failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun deleteDownloadedApk(context: Context) {
    try {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (file.exists()) {
            file.delete()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
