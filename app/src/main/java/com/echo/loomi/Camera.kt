package com.echo.loomi

import android.app.Activity
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.google.firebase.database.ServerValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Immutable
data class MusicTrack(
    val trackId: Long,
    val trackName: String,
    val artistName: String,
    val previewUrl: String,
    val artworkUrl: String
)

@Composable
fun CameraScreen(isActive: Boolean, onBack: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as Activity).window
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    BackHandler(onBack = onBack)

    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }
    var currentUserImage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        val currentUser = FirebaseAuth.getInstance().currentUser
        val uid = currentUser?.uid ?: return@LaunchedEffect

        // Fetch current user's asset image name
        database.child("users").child(uid).child("imageName").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                currentUserImage = snapshot.getValue(String::class.java) ?: ""
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(if (isDark) Color.Black else Color(0xFFEFB600))) {
        if (hasCameraPermission) {
            CameraView(
                isActive = isActive,
                onBack = onBack,
                onImageCaptured = { uri ->
                    // Image was handled by uploadToStory, just a general toast if needed or nothing
                },
                currentUserImageAsset = currentUserImage
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Camera permission is required", color = if (isDark) Color.White else Color.Black)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraView(isActive: Boolean, onBack: () -> Unit, onImageCaptured: (Uri) -> Unit, currentUserImageAsset: String) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var selectedPreviewUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedPreviewUri = uri
        }
    }

    var showMusicSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
    var selectedTrackName by remember { mutableStateOf<String?>(null) }
    var selectedTrackArtworkUrl by remember { mutableStateOf<String?>(null) }

    val mediaPlayer = remember { MediaPlayer().apply { isLooping = true } }
    var currentPlayingUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            try {
                if (currentPlayingUrl != null) {
                    mediaPlayer.stop()
                    mediaPlayer.reset()
                    currentPlayingUrl = null
                }
            } catch (e: Exception) {
                Log.e("CameraView", "Error stopping music", e)
            }
        }
    }

    var isUploading by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                // Ignore errors on cleanup
            }
            mediaPlayer.release()
            cameraExecutor.shutdown()
        }
    }

    val resolutionSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
    }

    val preview = remember(resolutionSelector) {
        Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    val imageCapture: ImageCapture = remember(flashMode, resolutionSelector) {
        ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setResolutionSelector(resolutionSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(lensFacing, flashMode, isActive) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageCapture.flashMode = flashMode

            try {
                cameraProvider.unbindAll()
                if (isActive) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                    preview.surfaceProvider = previewView.surfaceProvider
                }
            } catch (exc: Exception) {
                Log.e("CameraView", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(modifier = Modifier.fillMaxSize().background(if (isDark) Color.Black else Color(0xFFFFFBF6))) {

        // --- CENTERED OVERLAY GROUP ---
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                // 1. Centered Camera Circle
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(
                            2.dp,
                            if (isDark) Color.White.copy(0.2f) else Color.Transparent,
                            CircleShape
                        )
                ) {
                    if (selectedPreviewUri != null) {
                        val previewModel = remember(selectedPreviewUri) {
                            if (selectedPreviewUri?.scheme == "data") {
                                try {
                                    val base64Data =
                                        selectedPreviewUri.toString().substringAfter("base64,")
                                    Base64.decode(base64Data, Base64.DEFAULT)
                                } catch (e: Exception) {
                                    selectedPreviewUri!!
                                }
                            } else {
                                selectedPreviewUri!!
                            }
                        }
                        coil.compose.AsyncImage(
                            model = previewModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                    } else {
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 2. Top-Right Pill (Icons)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 20.dp, y = (-10).dp)
                        .clip(RoundedCornerShape(25.dp))
                        .border(
                            2.dp,
                            if (isDark) Color.White.copy(0.3f) else Color.White,
                            RoundedCornerShape(25.dp)
                        )
                        .background(if (isDark) Color(0xFF1A1A1A) else Color(0xFFFFE0B2))
                        .padding(horizontal = 30.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painterResource(R.drawable.call),
                        contentDescription = null,
                        tint = if (isDark) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Icon(
                        painterResource(R.drawable.video),
                        contentDescription = null,
                        tint = if (isDark) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // 3. Bottom-Left Rounded Square (Music)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-15).dp, y = 15.dp)
                        .size(65.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            2.dp,
                            if (isDark) Color.White.copy(0.3f) else Color.White,
                            RoundedCornerShape(16.dp)
                        )
                        .background(if (isDark) Color(0xFF2A2A2A) else Color(0xFFFFAB91))
                        .clickable { showMusicSheet = true }
                        .padding(if (selectedTrackArtworkUrl != null) 0.dp else 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedTrackArtworkUrl != null) {
                        coil.compose.AsyncImage(
                            model = selectedTrackArtworkUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.musicnote),
                            contentDescription = null,
                            tint = Color(0xFFFFFFFF),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
            }

        }
        // --- END CENTERED OVERLAY GROUP ---

        // Top Controls (Close and Flash)
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopStart)
                .background(if (isDark) Color.White.copy(0.15f) else Color.Gray.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(painterResource(R.drawable.arrow_left), contentDescription = "Close", tint = if (isDark) Color.White else Color.Black, modifier = Modifier.size(20.dp))
        }

        IconButton(
            onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            },
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopEnd)
                .background(if (isDark) Color.White.copy(0.15f) else Color.Gray.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(
                painterResource(R.drawable.flass),
                contentDescription = "Flash",
                tint = if (isDark) Color.White else Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }

        // Bottom Controls (Gallery, Capture, Flip)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedTrackName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.musicnote),
                        contentDescription = null,
                        tint = if (isDark) Color.White.copy(0.7f) else Color.Black.copy(0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selectedTrackName!!,
                        fontSize = 12.sp,
                        color = if (isDark) Color.White.copy(0.7f) else Color.Black.copy(0.7f),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(15.dp))
            }

            // 3 Small Circles Above Capture Button
            Row(
                modifier = Modifier.padding(bottom = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. User selected profile image
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color.White.copy(0.15f) else Color.Gray.copy(alpha = 0.1f))
                        .clickable {
                            if (currentUserImageAsset.isNotEmpty()) {
                                val path = if (currentUserImageAsset.startsWith("data:image") || currentUserImageAsset.startsWith("http")) {
                                    currentUserImageAsset
                                } else {
                                    "file:///android_asset/$currentUserImageAsset"
                                }
                                selectedPreviewUri = Uri.parse(path)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val userProfileModel = remember(currentUserImageAsset) {
                        if (currentUserImageAsset.startsWith("data:image")) {
                            try {
                                val base64Data = currentUserImageAsset.substringAfter("base64,")
                                Base64.decode(base64Data, Base64.DEFAULT)
                            } catch (e: Exception) {
                                currentUserImageAsset
                            }
                        } else if (currentUserImageAsset.startsWith("http")) {
                            currentUserImageAsset
                        } else if (currentUserImageAsset.isNotEmpty()) {
                            "file:///android_asset/$currentUserImageAsset"
                        } else {
                            R.drawable.logo
                        }
                    }
                    coil.compose.AsyncImage(
                        model = userProfileModel,
                        contentDescription = "Profile",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        error = painterResource(R.drawable.logo)
                    )
                }

                // 2. Camera icon
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color.White else Color.Black)
                        .clickable { selectedPreviewUri = null },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.camera),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (isDark) Color.Black else Color.White
                    )
                }

                // 3. Google user profile
                val currentUser = FirebaseAuth.getInstance().currentUser
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color.White.copy(0.15f) else Color.Gray.copy(alpha = 0.1f))
                        .clickable {
                            currentUser?.photoUrl?.let { 
                                val highResUrl = it.toString().replace("s96-c", "s4000")
                                selectedPreviewUri = Uri.parse(highResUrl) 
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    coil.compose.AsyncImage(
                        model = currentUser?.photoUrl?.toString()?.replace("s96-c", "s384"),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.size(50.dp).background(if (isDark) Color.White.copy(0.15f) else Color.Gray.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(painterResource(R.drawable.image), contentDescription = "Gallery", tint = if (isDark) Color.White else Color.Black, modifier = Modifier.size(20.dp))
                }

                // Capture/Upload button
                Box(
                    modifier = Modifier
                        .size(85.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .border(2.dp, Color(0xFFFFE082), CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(if (selectedPreviewUri != null) Color(0xFF81C995) else Color(0xFFA5D6A7))
                        .clickable(enabled = !isUploading) {
                            scope.launch {
                                isUploading = true
                                if (selectedPreviewUri != null) {
                                    uploadToStory(context, selectedPreviewUri!!, selectedTrackId, selectedTrackName) {
                                        isUploading = false
                                        selectedPreviewUri = null // Reset after upload
                                    }
                                    onImageCaptured(selectedPreviewUri!!)
                                } else {
                                    takePhoto(
                                        context = context,
                                        imageCapture = imageCapture,
                                        executor = cameraExecutor,
                                        onImageCaptured = { uri ->
                                            scope.launch {
                                                uploadToStory(context, uri, selectedTrackId, selectedTrackName) {
                                                    isUploading = false
                                                }
                                                onImageCaptured(uri)
                                            }
                                        },
                                        onError = {
                                            isUploading = false
                                        }
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                    } else if (selectedPreviewUri != null) {
                        Icon(
                            painter = painterResource(id = R.drawable.send),
                            contentDescription = "Upload",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier.size(50.dp).background(if (isDark) Color.White.copy(0.15f) else Color.Gray.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(painterResource(R.drawable.camera), contentDescription = "Flip", tint = if (isDark) Color.White else Color.Black, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showMusicSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMusicSheet = false },
            sheetState = sheetState,
            containerColor = if (isDark) Color(0xFF121212) else Color(0xFFE8F5E9),
        ) {
            var musicSearchQuery by remember { mutableStateOf("") }
            var musicResults by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
            var isSearching by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (musicSearchQuery.isEmpty()) {
                    isSearching = true
                    musicResults = searchMusic("tamil songs")
                    isSearching = false
                }
            }

            LaunchedEffect(musicSearchQuery) {
                if (musicSearchQuery.length > 1) {
                    kotlinx.coroutines.delay(300) // Debounce search
                    isSearching = true
                    musicResults = searchMusic(musicSearchQuery)
                    isSearching = false
                } else if (musicSearchQuery.isEmpty()) {
                    isSearching = true
                    musicResults = searchMusic("tamil songs")
                    isSearching = false
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .navigationBarsPadding()
            ) {
                TextField(
                    value = musicSearchQuery,
                    onValueChange = { musicSearchQuery = it },
                    placeholder = { Text("Search for Song ...", color = Color.Black.copy(0.4f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, Color.Black, RoundedCornerShape(28.dp))
                        .clip(RoundedCornerShape(28.dp)),
                    leadingIcon = { 
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                        } else {
                            Icon(painterResource(R.drawable.search), null, modifier = Modifier.size(20.dp), tint = Color.Black)
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        disabledContainerColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.Black,
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    ),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                if (musicResults.isEmpty() && !isSearching) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Search for favorite tracks", color = if (isDark) Color.White.copy(0.4f) else Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(musicResults, key = { it.trackId }) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (currentPlayingUrl != track.previewUrl) {
                                            mediaPlayer.stop()
                                            mediaPlayer.reset()
                                            mediaPlayer.setAudioAttributes(
                                                AudioAttributes.Builder()
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                                    .build()
                                            )
                                            mediaPlayer.setDataSource(track.previewUrl)
                                            mediaPlayer.prepareAsync()
                                            mediaPlayer.setOnPreparedListener { 
                                                it.isLooping = true
                                                it.start() 
                                            }
                                            currentPlayingUrl = track.previewUrl
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isDark) Color.White.copy(0.1f) else Color.LightGray)
                                        .clickable {
                                            selectedTrackId = track.trackId
                                            selectedTrackName = track.trackName
                                            selectedTrackArtworkUrl = track.artworkUrl.replace("100x100bb.jpg", "600x600bb.jpg")
                                            
                                            if (currentPlayingUrl != track.previewUrl) {
                                                mediaPlayer.stop()
                                                mediaPlayer.reset()
                                                mediaPlayer.setAudioAttributes(
                                                    AudioAttributes.Builder()
                                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                                        .build()
                                                )
                                                mediaPlayer.setDataSource(track.previewUrl)
                                                mediaPlayer.prepareAsync()
                                                mediaPlayer.setOnPreparedListener { 
                                                    it.isLooping = true
                                                    it.start() 
                                                }
                                                currentPlayingUrl = track.previewUrl
                                            }
                                            showMusicSheet = false
                                        }
                                ) {
                                    coil.compose.AsyncImage(
                                        model = track.artworkUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.trackName, fontWeight = FontWeight.Bold, maxLines = 1, color = if (isDark) Color.White else Color.Black)
                                    Text(track.artistName, style = MaterialTheme.typography.bodySmall, color = if (isDark) Color.White.copy(0.6f) else Color.Gray, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

private suspend fun searchMusic(query: String): List<MusicTrack> = withContext(Dispatchers.IO) {
    val results = mutableListOf<MusicTrack>()
    try {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = URL("https://itunes.apple.com/search?term=$encodedQuery&media=music&limit=20")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val resultArray = json.getJSONArray("results")
        
        for (i in 0 until resultArray.length()) {
            val item = resultArray.getJSONObject(i)
            results.add(
                MusicTrack(
                    trackId = item.getLong("trackId"),
                    trackName = item.optString("trackName", "Unknown"),
                    artistName = item.optString("artistName", "Unknown"),
                    previewUrl = item.optString("previewUrl", ""),
                    artworkUrl = item.optString("artworkUrl100", "")
                )
            )
        }
    } catch (e: Exception) {
        Log.e("CameraView", "Music search failed", e)
    }
    results
}

private suspend fun uploadToStory(context: Context, uri: Uri, songId: Long?, songName: String?, onComplete: () -> Unit) {
    val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
        onComplete()
        return
    }
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    
    withContext(Dispatchers.IO) {
        try {
            val bitmap = if (uri.scheme == "http" || uri.scheme == "https") {
                // Download bitmap from URL
                val connection = URL(uri.toString()).openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()
                val input = connection.inputStream
                BitmapFactory.decodeStream(input)
            } else if (uri.toString().startsWith("file:///android_asset/")) {
                // Load from assets
                val assetPath = uri.toString().substringAfter("android_asset/")
                context.assets.open(assetPath).use {
                    BitmapFactory.decodeStream(it)
                }
            } else if (uri.scheme == "data") {
                // Handle data: URIs (Base64)
                val base64Data = uri.toString().substringAfter("base64,")
                val decodedString = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            } else {
                // Local file or content URI
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }

            if (bitmap == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to process image", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
                return@withContext
            }

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            
            val storyId = currentUid // Use UID as the story ID
            val storyData = mapOf(
                "id" to storyId,
                "uid" to currentUid,
                "image" to base64Image,
                "songId" to (songId ?: 0),
                "songName" to (songName ?: "None"),
                "timestamp" to ServerValue.TIMESTAMP
            )
            
            // Overwrite directly (much faster than fetch-and-delete)
            database.child("stories").child(storyId).setValue(storyData)
                .addOnSuccessListener {
                    Toast.makeText(context, "Story uploaded successfully", Toast.LENGTH_SHORT).show()
                    // Clean up temporary file if it exists in cache
                    if (uri.toString().contains(context.cacheDir.path)) {
                        File(uri.path ?: "").delete()
                    }
                    onComplete()
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Story upload failed", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
        } catch (e: Exception) {
            Log.e("CameraView", "Error uploading story", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    onImageCaptured: (Uri) -> Unit,
    onError: (ImageCaptureException) -> Unit
) {
    val photoFile = File(context.cacheDir, "temp_story_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraView", "Photo capture failed: ${exc.message}", exc)
                onError(exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onImageCaptured(Uri.fromFile(photoFile))
            }
        }
    )
}
