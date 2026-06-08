package com.echo.loomi

import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.loomi.ui.theme.LoomiTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.delay

class LoginActivity : AppCompatActivity() {

    private lateinit var googleAuthClient: GoogleAuthClient
    private var isLoading = mutableStateOf(false)
    private var errorMessage = mutableStateOf("")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startGoogleSignIn()
        } else {
            errorMessage.value = "All permissions (Location, Camera, Notifications) are required to login."
            isLoading.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        googleAuthClient = GoogleAuthClient(this) { success ->
            if (success) {
                checkProfileAndNavigate()
            } else {
                isLoading.value = false
                errorMessage.value = "Sign in failed. Please try again."
            }
        }

        setContent {
            LoomiTheme {
                BlackLoginUI(
                    loading = isLoading.value,
                    error = errorMessage.value,
                    onLoginClick = {
                        handleLoginTap()
                    },
                    onBackWhileLoading = {
                        isLoading.value = false
                    }
                )
            }
        }
    }

    private fun handleLoginTap() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            isLoading.value = true
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Check for background reliability permissions before signing in
            requestBackgroundPermissions {
                startGoogleSignIn()
            }
        }
    }

    private fun requestBackgroundPermissions(onComplete: () -> Unit) {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Background Reliability")
                .setMessage("To receive messages and calls instantly, please allow Loomi to run in the background. Select 'Allow' in the next screen.")
                .setPositiveButton("Configure") { dialog, which ->
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        onComplete()
                    }
                }
                .setNegativeButton("Skip") { dialog, which -> onComplete() }
                .setCancelable(false)
                .show()
        } else {
            onComplete()
        }
    }

    private fun startGoogleSignIn() {
        isLoading.value = true
        errorMessage.value = ""
        googleAuthClient.signIn()
    }

    private fun checkProfileAndNavigate() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        
        database.child("users").child(user.uid).child("imageName").get().addOnCompleteListener { task ->
            if (task.isSuccessful && task.result.exists()) {
                val prefs = getSharedPreferences("echo_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("profile_done", true).apply()
                navigateToMain()
            } else {
                navigateToWelcome()
            }
        }
    }

    private fun navigateToWelcome() {
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BlackLoginUI(
    loading: Boolean,
    error: String,
    onLoginClick: () -> Unit,
    onBackWhileLoading: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    BackHandler(enabled = loading) {
        onBackWhileLoading()
    }

    val googleColors = listOf(
        Color(0xFF7E57C2), Color(0xFFEF5350), Color(0xFFFFEE58),
        Color(0xFF5C6BC0), Color(0xFF66BB6A)
    )

    var colorIndex1 by remember { mutableIntStateOf(0) }
    var colorIndex2 by remember { mutableIntStateOf(1) }

    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                delay(600)
                colorIndex1 = (colorIndex1 + 1) % googleColors.size
                colorIndex2 = (colorIndex2 + 1) % googleColors.size
            }
        }
    }

    val animatedColor1 by animateColorAsState(
        targetValue = googleColors[colorIndex1],
        animationSpec = tween(durationMillis = 600),
        label = "C1"
    )
    val animatedColor2 by animateColorAsState(
        targetValue = googleColors[colorIndex2],
        animationSpec = tween(durationMillis = 600),
        label = "C2"
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    // --- Dynamic System Bars Support ---
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as AppCompatActivity).window
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.b1),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 26.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(100))
                        .background(if (isDark) Color.White.copy(0.2f) else Color(0xFF2A2A2A))
                )

                Spacer(modifier = Modifier.height(22.dp))

                if (loading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(
                            modifier = Modifier
                                .size(50.dp)
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.linearGradient(
                                            colors = listOf(animatedColor1, animatedColor2)
                                        ),
                                        blendMode = BlendMode.SrcAtop
                                    )
                                },
                            color = animatedColor1
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(if (isDark) Color.White else Color(0xFF1C1C1C))
                            .clickable { onLoginClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.google),
                                contentDescription = "Google",
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Continue with Google",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color.Black else Color.White
                            )
                        }
                    }
                }

                if (error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = error,
                        fontSize = 13.sp,
                        color = Color.Red,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = "You agree to Bharath-App-studio-App",
                    fontSize = 12.sp,
                    color = Color(0xFF6E6E6E),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
