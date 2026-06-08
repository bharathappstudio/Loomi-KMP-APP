package com.echo.loomi.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.loomi.desktop.network.OAuthServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.concurrent.Worker
import java.net.URI
import java.awt.Desktop
import java.io.File
import javax.swing.JPanel
import java.awt.BorderLayout

@Composable
fun LoginScreen(
    onLoginSuccess: (idToken: String, uid: String, name: String, email: String, photoUrl: String) -> Unit
) {
    var emailInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    
    val themeText = if (isDark) Color.White else Color.Black
    val themeBg = if (isDark) Color.Black else Color(0xFFF5F5F5)
    val themeSurface = if (isDark) Color(0xFF121212) else Color.White
    val themeBorder = themeText.copy(alpha = 0.1f)

    Box(modifier = Modifier.fillMaxSize().background(themeBg)) {
        // Grid background effect
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gap = 40.dp.toPx()
            for (x in 0..size.width.toInt() step gap.toInt()) {
                for (y in 0..size.height.toInt() step gap.toInt()) {
                    drawCircle(themeText.copy(0.05f), radius = 1.dp.toPx(), center = Offset(x.toFloat(), y.toFloat()))
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // LEFT SIDE: LOGIN FORM
            Box(
                modifier = Modifier
                    .width(400.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(19.dp))
                    .background(themeSurface)
                    .border(2.dp, themeBorder, RoundedCornerShape(16.dp))
                    .padding(40.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource("drawable/logo.xml"),
                        contentDescription = "Loomi Logo",
                        modifier = Modifier.height(60.dp)
                    )
                    Text(
                        text = "BHARATH-APP-STUDIO",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = themeText.copy(0.4f),
                        letterSpacing = 2.sp
                    )

                    Spacer(Modifier.height(60.dp))

                    if (isLoading) {
                        CircularProgressIndicator(color = themeText, strokeWidth = 2.dp)
                    } else {
                        NothingButton("CONTINUE WITH GOOGLE", false, themeText, themeBg) {
                            isLoading = true
                            scope.launch {
                                OAuthServer.startGoogleSignIn { result ->
                                    isLoading = false
                                    if (result.success) {
                                        onLoginSuccess(result.idToken!!, result.uid!!, result.name!!, result.email!!, result.photoUrl ?: "")
                                    } else {
                                        errorMessage = result.errorMessage ?: "AUTH_FAILED"
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            placeholder = { 
                                Text("INTERNAL_EMAIL_ADDRESS", 
                                    color = themeText.copy(0.3f), 
                                    fontSize = 11.sp, 
                                    fontFamily = FontFamily.Monospace
                                ) 
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = themeText,
                                unfocusedTextColor = themeText,
                                focusedBorderColor = themeText,
                                unfocusedBorderColor = themeBorder
                            )
                        )

                        Spacer(Modifier.height(16.dp))

                        NothingButton("ACCESS_CORE", true, themeText, themeBg) {
                            if (emailInput.isNotBlank()) {
                                println("LoginScreen: Using ACCESS_CORE (Mock Login) for $emailInput")
                                isLoading = true
                                scope.launch {
                                    delay(500)
                                    isLoading = false
                                    val n = emailInput.substringBefore("@")
                                    onLoginSuccess("mock", "test_$n", n, emailInput, "")
                                }
                            }
                        }
                    }

                    if (errorMessage.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "ERR: $errorMessage",
                            color = Color.Red,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            // RIGHT SIDE: FULL WEB VIEW
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color.Black)
                    .border(2.dp, themeBorder, RoundedCornerShape(30.dp))
            ) {
                val webPanel = remember { JFXPanel() }
                var webViewInstance by remember { mutableStateOf<WebView?>(null) }
                
                LaunchedEffect(Unit) {
                    Platform.runLater {
                        val webView = WebView()
                        webView.engine.isJavaScriptEnabled = true
                        
                        // Optimization: Set a persistent cache directory for faster subsequent loads
                        val cacheDir = File(System.getProperty("user.home"), ".loomi/web_cache")
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        webView.engine.userDataDirectory = cacheDir
                        
                        webView.engine.load("https://authentic-slide-917706.framer.app/")
                        
                        // Remove scrolling once the page loads
                        webView.engine.loadWorker.stateProperty().addListener { _, _, newState ->
                            if (newState == Worker.State.SUCCEEDED) {
                                webView.engine.executeScript("""
                                    document.body.style.overflow = 'hidden';
                                    document.documentElement.style.overflow = 'hidden';
                                    document.body.style.touchAction = 'none';
                                """)
                                // Initial theme sync
                                val mode = if (isDark) "dark" else "light"
                                webView.engine.executeScript("document.documentElement.style.colorScheme = '$mode';")
                            }
                        }

                        val scene = Scene(webView)
                        webPanel.scene = scene
                        webViewInstance = webView
                    }
                }

                // Sync theme changes in real-time
                LaunchedEffect(isDark) {
                    val webView = webViewInstance ?: return@LaunchedEffect
                    Platform.runLater {
                        val mode = if (isDark) "dark" else "light"
                        webView.engine.executeScript("""
                            document.documentElement.style.colorScheme = '$mode';
                            if (window.framerTheme) window.framerTheme.setTheme('$mode');
                        """)
                    }
                }

                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    factory = { webPanel }
                )
            }
        }
    }
}

@Composable
fun NothingButton(text: String, isPrimary: Boolean, accent: Color, bg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPrimary) accent else Color.Transparent)
            .border(1.dp, accent, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isPrimary) bg else accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    }
}
