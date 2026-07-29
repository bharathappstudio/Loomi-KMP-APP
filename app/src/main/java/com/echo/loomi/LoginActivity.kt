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
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.google.firebase.FirebaseException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
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
    private var verificationId: String? = null
    private var isPhoneMode = mutableStateOf(false)
    private var isOtpSent = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startGoogleSignIn()
        } else {
            errorMessage.value = "All permissions required"
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
                errorMessage.value = "Please try again."
            }
        }

        setContent {
            LoomiTheme {
                BlackLoginUI(
                    loading = isLoading.value,
                    error = errorMessage.value,
                    isPhoneMode = isPhoneMode.value,
                    isOtpSent = isOtpSent.value,
                    onLoginClick = { handleLoginTap() },
                    onPhoneLoginToggle = { isPhoneMode.value = true },
                    onSendOtp = { phone -> sendVerificationCode(phone) },
                    onVerifyOtp = { otp -> verifyOtp(otp) },
                    onBack = {
                        if (isPhoneMode.value) {
                            if (isOtpSent.value) {
                                isOtpSent.value = false
                            } else {
                                isPhoneMode.value = false
                            }
                        } else {
                            isLoading.value = false
                        }
                    }
                )
            }
        }
    }

    private fun handleLoginTap() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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
                .setPositiveButton("Configure") { _, _ ->
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        onComplete()
                    }
                }
                .setNegativeButton("Skip") { _, _ -> onComplete() }
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

    private fun sendVerificationCode(phoneNumber: String) {
        isLoading.value = true
        val options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signInWithPhone(credential)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    isLoading.value = false
                    errorMessage.value = e.message ?: "Verification failed"
                }

                override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                    isLoading.value = false
                    verificationId = id
                    isOtpSent.value = true
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun verifyOtp(otp: String) {
        val id = verificationId ?: return
        val credential = PhoneAuthProvider.getCredential(id, otp)
        signInWithPhone(credential)
    }

    private fun signInWithPhone(credential: PhoneAuthCredential) {
        isLoading.value = true
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    savePhoneUserToDatabase()
                } else {
                    isLoading.value = false
                    errorMessage.value = task.exception?.message ?: "Login failed"
                }
            }
    }

    private fun savePhoneUserToDatabase() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        
        val userUpdates = mapOf(
            "uid" to user.uid,
            "name" to "New User",
            "phoneNumber" to (user.phoneNumber ?: ""),
            "status" to "Online",
            "loginType" to "phone",
            "lastSeen" to System.currentTimeMillis()
        )

        database.child("users").child(user.uid).updateChildren(userUpdates)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    checkProfileAndNavigate()
                } else {
                    isLoading.value = false
                    errorMessage.value = task.exception?.message ?: "Database update failed"
                }
            }
    }

    private fun checkProfileAndNavigate() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val database = FirebaseDatabase.getInstance("https://echo-loomi-app-default-rtdb.firebaseio.com/").reference
        
        database.child("users").child(user.uid).get().addOnCompleteListener { task ->
            if (task.isSuccessful && task.result.exists() && task.result.hasChild("name") && task.result.hasChild("imageName")) {
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
    isPhoneMode: Boolean,
    isOtpSent: Boolean,
    onLoginClick: () -> Unit,
    onPhoneLoginToggle: () -> Unit,
    onSendOtp: (String) -> Unit,
    onVerifyOtp: (String) -> Unit,
    onBack: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    BackHandler(enabled = loading || isPhoneMode) { onBack() }

    val googleColors = listOf(
        Color(0xFF8AB4F8), Color(0xFFF28B82), Color(0xFFFDD663),
        Color(0xFF81C995), Color(0xFF669DF6)
    )

    var colorIndex1 by remember { mutableIntStateOf(0) }
    var colorIndex2 by remember { mutableIntStateOf(1) }
    var colorIndex3 by remember { mutableIntStateOf(2) }

    LaunchedEffect(loading) {
        if (loading) {
            while (true) {
                delay(600)
                colorIndex1 = (colorIndex1 + 1) % googleColors.size
                colorIndex2 = (colorIndex2 + 1) % googleColors.size
            }
        }
    }

    val c1 by animateColorAsState(googleColors[colorIndex1], animationSpec = tween(600), label = "c1")
    val c2 by animateColorAsState(googleColors[colorIndex2], animationSpec = tween(600), label = "c2")
    val c3 by animateColorAsState(googleColors[colorIndex3], animationSpec = tween(600), label = "c3")

    var rawNumber by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    val fullNumber = "+91$rawNumber"

    val focusRequester = remember { FocusRequester() }

    // Auto-trigger logic
    LaunchedEffect(rawNumber) {
        if (rawNumber.length == 10 && !isOtpSent && !loading) {
            onSendOtp(fullNumber)
        }
    }
    LaunchedEffect(otp) {
        if (otp.length == 6 && isOtpSent && !loading) {
            onVerifyOtp(otp)
        }
    }

    // Auto-show keyboard
    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            delay(300) // Wait for transition
            focusRequester.requestFocus()
        }
    }

    val surfaceColor = if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    val contentColor = if (isDark) Color.White else Color.Black

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
            val columnWeight by animateFloatAsState(if (isPhoneMode) 0.1f else 1f, label = "weight")
            Spacer(modifier = Modifier.weight(columnWeight))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isPhoneMode) Modifier.fillMaxHeight() else Modifier)
                    .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                    .background(surfaceColor)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 26.dp)
            ) {
                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isPhoneMode) Modifier.fillMaxHeight() else Modifier.height(56.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(50.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                                drawContent()
                                drawRect(brush = Brush.linearGradient(listOf(c1, c2, c3)), blendMode = BlendMode.SrcAtop)
                            },
                            color = Color.White
                        )
                    }
                } else if (isPhoneMode) {
                    // Nothing Style Minimal Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))

                    // Material 3 Monochrome Input (Nothing Style)
                    if (!isOtpSent) {
                        OutlinedTextField(
                            value = rawNumber,
                            onValueChange = { if (it.length <= 10) rawNumber = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            label = { Text("MOBILE NUMBER", style = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)) },
                            prefix = { Text("+91 ", color = contentColor, fontWeight = FontWeight.Bold) },
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (rawNumber.length == 10) onSendOtp(fullNumber) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = contentColor,
                                unfocusedBorderColor = contentColor.copy(0.2f),
                                focusedLabelColor = contentColor,
                                unfocusedLabelColor = contentColor.copy(0.4f),
                                focusedTextColor = contentColor,
                                unfocusedTextColor = contentColor,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            value = otp,
                            onValueChange = { if (it.length <= 6) otp = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            label = { Text("VERIFICATION CODE", style = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)) },
                            placeholder = { Text("000000", color = if (isDark) Color.DarkGray else Color.LightGray) },
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (otp.length == 6) onVerifyOtp(otp) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = contentColor,
                                unfocusedBorderColor = contentColor.copy(0.2f),
                                focusedLabelColor = contentColor,
                                unfocusedLabelColor = contentColor.copy(0.4f),
                                focusedTextColor = contentColor,
                                unfocusedTextColor = contentColor,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center, letterSpacing = 8.sp, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = contentColor)
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Nothing Style Solid Button
                    Button(
                        onClick = { if (isOtpSent) onVerifyOtp(otp) else onSendOtp(fullNumber) },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = contentColor, contentColor = surfaceColor),
                        enabled = if (isOtpSent) otp.length == 6 else rawNumber.length == 10
                    ) {
                        Text(
                            if (isOtpSent) "CONFIRM" else "PROCEED",
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    }
                    
                } else {
                    // 1. Agreement Text (Top)
                    Text(
                        text = "You agree to Bharath-App-studio-App",
                        fontSize = 12.sp,
                        color = if (isDark) Color.LightGray.copy(0.6f) else Color(0xFF6E6E6E),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 2. Google Login (Middle)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(30.dp))
                            .background(if (isDark) Color.White else Color(0xFF1C1C1C))
                            .clickable { onLoginClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.google),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Continue With Loomi",
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDark) Color.Black else Color.White
                            )
                        }
                    }

                    if (error.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(error, fontSize = 13.sp, color = Color.Red, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. Phone Link (100% End/Bottom)
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable { onPhoneLoginToggle() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.call),
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = if (isDark) Color.White.copy(0.6f) else Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Developer Login",
                            fontSize = 10.sp,
                            color = if (isDark) Color.White.copy(0.6f) else Color.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    cursorBrush: Brush = Brush.verticalGradient(listOf(Color.Black, Color.Black))
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
        singleLine = true
    )
}
