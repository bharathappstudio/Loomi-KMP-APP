package com.echo.loomi

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.echo.loomi.ui.theme.LoomiTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WelcomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            LoomiTheme {
                WelcomeScreen(onFinish = {
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeScreen(onFinish: () -> Unit) {
    val imageNames = (1..14).map { if (it < 10) "0$it.png" else "$it.png" }
    var selectedGender by remember { mutableStateOf("Male") }
    var selectedImage by remember { mutableStateOf(imageNames[0]) }
    var customImageUri by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isProfileLoading by remember { mutableStateOf(false) }

    val auth = FirebaseAuth.getInstance()
    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            customImageUri = uri
            selectedImage = "" // Clear memoji selection
        }
    }

    // Loading indicator animation logic
    val googleColors = listOf(
        Color(0xFF8AB4F8), Color(0xFFF28B82), Color(0xFFFDD663),
        Color(0xFF81C995), Color(0xFF669DF6)
    )
    var colorIndex1 by remember { mutableIntStateOf(0) }
    var colorIndex2 by remember { mutableIntStateOf(1) }
    var colorIndex3 by remember { mutableIntStateOf(2) }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            while (true) {
                delay(700)
                colorIndex1 = (colorIndex1 + 1) % googleColors.size
                colorIndex2 = (colorIndex2 + 1) % googleColors.size
                colorIndex3 = (colorIndex3 + 1) % googleColors.size
            }
        }
    }

    val c1 by animateColorAsState(googleColors[colorIndex1], tween(600), label = "c1")
    val c2 by animateColorAsState(googleColors[colorIndex2], tween(600), label = "c2")
    val c3 by animateColorAsState(googleColors[colorIndex3], tween(600), label = "c3")

    val isDark = isSystemInDarkTheme()
    // --- Dynamic System Bars Support ---
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as androidx.activity.ComponentActivity).window
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isSystemInDarkTheme()) Color.Black else Color(0xFFC8E6C9))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Section: Selection Preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(32.dp))

                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape)
                            .background(if (isSystemInDarkTheme()) Color.White.copy(0.1f) else Color.White.copy(alpha = 0.4f))
                            .border(4.dp, if (isSystemInDarkTheme()) Color.White.copy(0.2f) else Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isProfileLoading) {
                            LoadingIndicator(
                                modifier = Modifier
                                    .size(60.dp)
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
                            val model = if (customImageUri != null) {
                                customImageUri
                            } else {
                                "file:///android_asset/Memoji/$selectedGender/Circle/$selectedImage"
                            }
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(model)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Selected Profile",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // Bottom Section: Selection List
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // Top 2 Round Buttons: Gallery and Google
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Gallery Button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (isSystemInDarkTheme()) Color.White.copy(0.1f) else Color(0xFFF5F5F5))
                            .border(1.dp, Color.LightGray.copy(0.3f), CircleShape)
                            .clickable { galleryLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.image), null, modifier = Modifier.size(24.dp), tint = if (isSystemInDarkTheme()) Color.White else Color.Black)
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    // 2. Google Profile Button
                    val currentUser = auth.currentUser
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (isSystemInDarkTheme()) Color.White.copy(0.1f) else Color(0xFFF5F5F5))
                            .border(
                                width = if (customImageUri?.toString()?.contains("google") == true) 2.dp else 1.dp,
                                color = if (customImageUri?.toString()?.contains("google") == true) (if (isSystemInDarkTheme()) Color.White else Color.Black) else Color.LightGray.copy(0.3f),
                                shape = CircleShape
                            )
                            .clickable {
                                currentUser?.photoUrl?.let {
                                    val highResUrl = it.toString().replace("s96-c", "s4000") // HD Quality fix
                                    customImageUri = Uri.parse(highResUrl)
                                    selectedImage = ""
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = currentUser?.photoUrl?.toString()?.replace("s96-c", "s384"),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                // Gender Filter Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf("Male", "Female").forEach { gender ->
                        val isSelected = selectedGender == gender
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(if (isSelected) (if (isSystemInDarkTheme()) Color.White else Color.Black) else (if (isSystemInDarkTheme()) Color.White.copy(0.1f) else Color(0xFFF5F5F5)))
                                .clickable {
                                    if (selectedGender != gender) {
                                        scope.launch {
                                            isProfileLoading = true
                                            selectedGender = gender
                                            customImageUri = null // Reset custom if changing gender/memoji
                                            if (selectedImage == "") selectedImage = imageNames[0]
                                            delay(200)
                                            isProfileLoading = false
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = gender,
                                color = if (isSelected) (if (isSystemInDarkTheme()) Color.Black else Color.White) else (if (isSystemInDarkTheme()) Color.White else Color.Black),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(imageNames) { imageName ->
                        val isSelected = selectedImage == imageName
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) (if (isSystemInDarkTheme()) Color.White else Color.Black) else Color.LightGray.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    if (selectedImage != imageName) {
                                        scope.launch {
                                            isProfileLoading = true
                                            selectedImage = imageName
                                            customImageUri = null // Reset custom if picking memoji
                                            delay(1000)
                                            isProfileLoading = false
                                        }
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data("file:///android_asset/Memoji/$selectedGender/Circle/$imageName")
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(
                            modifier = Modifier
                                .size(50.dp)
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
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(if (isSystemInDarkTheme()) Color.White else Color(0xFF1C1C1C))
                            .clickable {
                                if (isLoading) return@clickable
                                isLoading = true
                                val user = auth.currentUser
                                if (user != null) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val imagePath = if (customImageUri != null) {
                                                // Upload custom image as base64
                                                val bitmap = if (customImageUri!!.scheme?.startsWith("http") == true) {
                                                    val url = java.net.URL(customImageUri.toString())
                                                    val connection = url.openConnection() as java.net.HttpURLConnection
                                                    connection.doInput = true
                                                    connection.connect()
                                                    BitmapFactory.decodeStream(connection.inputStream)
                                                } else {
                                                    context.contentResolver.openInputStream(customImageUri!!)?.use {
                                                        BitmapFactory.decodeStream(it)
                                                    }
                                                }

                                                if (bitmap != null) {
                                                    // Scale down to reasonable size for Base64 storage while keeping it "HD"
                                                    val maxSize = 720
                                                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                                    val finalWidth = if (bitmap.width > bitmap.height) maxSize else (maxSize * ratio).toInt()
                                                    val finalHeight = if (bitmap.width > bitmap.height) (maxSize / ratio).toInt() else maxSize
                                                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)

                                                    val outputStream = java.io.ByteArrayOutputStream()
                                                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream) // Good balance
                                                    val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                                                    "data:image/jpeg;base64,$base64"
                                                } else {
                                                    "Memoji/$selectedGender/Circle/$selectedImage"
                                                }
                                            } else {
                                                "Memoji/$selectedGender/Circle/$selectedImage"
                                            }

                                            database.child("users").child(user.uid).child("imageName").setValue(imagePath)
                                                .addOnCompleteListener {
                                                    val prefs = context.getSharedPreferences("echo_prefs", android.content.Context.MODE_PRIVATE)
                                                    prefs.edit().putBoolean("profile_done", true).apply()
                                                    onFinish()
                                                }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Failed to upload image", Toast.LENGTH_SHORT).show()
                                                isLoading = false
                                            }
                                        }
                                    }
                                } else {
                                    onFinish()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Let's Go!",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSystemInDarkTheme()) Color.Black else Color.White
                        )
                    }
                }
            }
        }
    }
}