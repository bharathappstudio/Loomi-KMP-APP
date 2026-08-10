package com.echo.loomi

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.echo.loomi.ui.theme.LoomiTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import java.net.URLEncoder

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

fun getAvatarImageUrl(gender: String): String {
    // Updated prompts to match the specific aesthetic of the images provided with 4K quality keywords
    val prompt = if (gender == "Male") {
        """
        Ultra HD  boy magical realism digital painting of a mysterious young boy standing in a moonlit field at night. He has messy windblown hair, a long flowing hooded cloak fluttering dramatically in the wind, and a confident heroic pose. His body is a solid black silhouette with a soft glowing white rim light outlining his figure. Above him, a brilliant full moon shines at the top center, casting a massive vertical beam of white stardust, glowing particles, and celestial light onto him. Swirling rings of magical white energy and sparkling runes circle around his hands, waist, and feet, creating an aura of mystical power. The foreground features dark grass with glowing white feathery wheat catching the moonlight. Deep midnight-blue sky with wispy clouds, countless stars, cinematic volumetric lighting, dreamy ethereal atmosphere, fantasy magic, high contrast between pure black and radiant white, ultra-detailed, masterpiece, 8K boy onley no girlm , epic fantasy artwork, sharp focus, dramatic composition.
        """.trimIndent()
    } else {
        """
       HD Magical realism digital painting of a dark silhouette of a girl with long windblown hair in a twirling flared dress dancing in a field at night. Layer 1: Deep midnight blue sky with wispy dark blue clouds. Layer 2: A single bright white full moon at the top center emitting a massive vertical ray of dense white stardust and glittering particles cascading downward. Layer 3: The girl is a solid black profile silhouette with soft blue rim lighting from the moon. Layer 4: A swirling ring of glowing white magic sparks circling her waist and feet. Layer 5: Dark foreground grass with prominent white feathery wheat stalks catching the moonlight. Style: Dreamy ethereal atmosphere, sharp contrast between pure black and glowing white, epic cinematic lighting, starlit night scene.
        """.trimIndent()
    }

    val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
    val seed = Random.nextInt(1_000_000)

    // Using 4K resolution and the flagship Flux model for superior quality
    return "https://image.pollinations.ai/prompt/$encodedPrompt?width=4096&height=4096&nologo=true&seed=$seed&model=flux"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeScreen(onFinish: () -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    var selectedGender by remember { mutableStateOf<String?>(null) }
    var imageUrl by remember { mutableStateOf<String?>(null) }
    var customImageUri by remember {
        mutableStateOf<Uri?>(currentUser?.photoUrl?.let {
            Uri.parse(it.toString().replace("s96-c", "s512-c"))
        })
    }
    var isLoading by remember { mutableStateOf(false) }
    var isProfileLoading by remember { mutableStateOf(false) }

    val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Custom ImageLoader with longer timeouts to fix AI generation timeouts
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            }
            .build()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            customImageUri = uri
            imageUrl = null
            selectedGender = null
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

    LaunchedEffect(isLoading || isProfileLoading) {
        if (isLoading || isProfileLoading) {
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
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as androidx.activity.ComponentActivity).window
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = true // Bottom sheet is always light (Orange/White)
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
                        val model: Any = customImageUri ?: imageUrl ?: R.drawable.image

                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(model)
                                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                                .crossfade(true)
                                .build(),
                            imageLoader = imageLoader,
                            contentDescription = "Selected Profile",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            onLoading = { isProfileLoading = true },
                            onSuccess = { isProfileLoading = false },
                            onError = { result ->
                                isProfileLoading = false
                                val errorMsg = result.result.throwable.message ?: "Unknown error"
                                android.util.Log.e("WelcomeActivity", "Image load failed for model: $model, Error: $errorMsg")
                            }
                        )

                        if (isProfileLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isSystemInDarkTheme()) Color.Black.copy(0.3f) else Color.White.copy(0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
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
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                    .background(if (isSystemInDarkTheme()) Color(0xFFC8E6C9) else Color.White)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Google Profile First
                    val isGoogleSelected = customImageUri?.toString()?.contains("google") == true
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (isGoogleSelected) Color.Black
                                else if (isSystemInDarkTheme()) Color.White else Color(0xFFF5F5F5)
                            )
                            .border(
                                width = if (isGoogleSelected) 2.dp else 1.dp,
                                color = if (isGoogleSelected) (if (isSystemInDarkTheme()) Color.White else Color.Black) else Color.LightGray.copy(0.3f),
                                shape = CircleShape
                            )
                            .clickable {
                                currentUser?.photoUrl?.let {
                                    val highResUrl = it.toString().replace("s96-c", "s512-c")
                                    customImageUri = Uri.parse(highResUrl)
                                    imageUrl = null
                                    selectedGender = null
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

                    Spacer(modifier = Modifier.width(24.dp))

                    // Gallery Second
                    val isGallerySelected = customImageUri != null && customImageUri?.toString()?.contains("google") == false
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (isGallerySelected) Color.Black
                                else if (isSystemInDarkTheme()) Color.White else Color(0xFFF5F5F5)
                            )
                            .border(
                                width = if (isGallerySelected) 2.dp else 1.dp,
                                color = if (isGallerySelected) (if (isSystemInDarkTheme()) Color.White else Color.Black) else Color.LightGray.copy(0.3f),
                                shape = CircleShape
                            )
                            .clickable { galleryLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.image),
                            null,
                            modifier = Modifier.size(24.dp),
                            tint = if (isGallerySelected) Color.White else Color.Black
                        )
                    }
                }

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
                                .background(
                                    if (isSelected) Color.Black
                                    else if (isSystemInDarkTheme()) Color.White else Color(0xFFF5F5F5)
                                )
                                .clickable {
                                    isProfileLoading = true
                                    selectedGender = gender
                                    customImageUri = null
                                    imageUrl = getAvatarImageUrl(gender)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = gender,
                                color = if (isSelected) Color.White else Color.Black,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Tap a gender to generate a unique AI profile photo",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

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
                                            val currentImageSource = customImageUri?.toString() ?: imageUrl
                                            val imagePath = if (currentImageSource != null) {
                                                val bitmap = if (currentImageSource.startsWith("http")) {
                                                    // Use Coil's ImageLoader to fetch the image efficiently (likely from cache)
                                                    val request = ImageRequest.Builder(context)
                                                        .data(currentImageSource)
                                                        .size(512)
                                                        .allowHardware(false) // Required for conversion to bitmap
                                                        .build()
                                                    val result = imageLoader.execute(request)
                                                    if (result is coil.request.SuccessResult) {
                                                        (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                                    } else {
                                                        null
                                                    }
                                                } else {
                                                    context.contentResolver.openInputStream(Uri.parse(currentImageSource))?.use {
                                                        BitmapFactory.decodeStream(it)
                                                    }
                                                }

                                                if (bitmap != null) {
                                                    val maxSize = 4096 // Full 4K resolution
                                                    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                                    val finalWidth = if (bitmap.width > bitmap.height) maxSize else (maxSize * ratio).toInt()
                                                    val finalHeight = if (bitmap.width > bitmap.height) (maxSize / ratio).toInt() else maxSize
                                                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
                                                    val outputStream = java.io.ByteArrayOutputStream()
                                                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 98, outputStream) // Near-lossless quality
                                                    val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                                                    "data:image/jpeg;base64,$base64"
                                                } else ""
                                            } else ""

                                            val userUpdates = mutableMapOf<String, Any>(
                                                "uid" to user.uid,
                                                "name" to (user.displayName ?: "Anonymous"),
                                                "email" to (user.email ?: ""),
                                                "imageName" to imagePath,
                                                "status" to "Online",
                                                "lastSeen" to System.currentTimeMillis()
                                            )

                                            database.child("users").child(user.uid).updateChildren(userUpdates)
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
                            text = "NEXT",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.W400,
                            color = if (isSystemInDarkTheme()) Color.Black else Color.White
                        )
                    }
                }
            }
        }
    }
}