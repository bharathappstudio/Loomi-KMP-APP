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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.loomi.desktop.network.FirebaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Base64

@Composable
fun WelcomeScreen(
    googlePhotoUrl: String,
    userName: String,
    userEmail: String,
    onProfileComplete: () -> Unit
) {
    var customImageBase64 by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val accent = if (isDark) Color.White else Color.Black
    val surface = if (isDark) Color.Black else Color.White

    // URL / Base64 image cache
    var customBitmap by remember(customImageBase64) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(customImageBase64) {
        if (customImageBase64 != null) {
            withContext(Dispatchers.IO) {
                try {
                    val cleanStr = customImageBase64!!.substringAfter("base64,")
                    val bytes = Base64.getDecoder().decode(cleanStr.replace("\\s".toRegex(), ""))
                    customBitmap = loadImageBitmap(bytes.inputStream())
                } catch (e: Exception) {}
            }
        }
    }

    var googleBitmap by remember(googlePhotoUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(googlePhotoUrl) {
        if (googlePhotoUrl.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val highResUrl = if (googlePhotoUrl.contains("googleusercontent.com")) {
                        if (googlePhotoUrl.contains("=")) {
                            googlePhotoUrl.substringBeforeLast("=") + "=s512-c"
                        } else if (googlePhotoUrl.contains("/s96-c/")) {
                            googlePhotoUrl.replace("/s96-c/", "/s512-c/")
                        } else {
                            "$googlePhotoUrl=s512-c"
                        }
                    } else googlePhotoUrl
                    
                    val connection = URL(highResUrl).openConnection()
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    googleBitmap = loadImageBitmap(connection.getInputStream())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(surface), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(460.dp).wrapContentHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                "PROFILE SETUP",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                color = accent
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Preview
            Box(
                modifier = Modifier.size(220.dp).clip(CircleShape).border(1.dp, accent.copy(0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when {
                    customBitmap != null -> Image(
                        customBitmap!!, 
                        null, 
                        modifier = Modifier.fillMaxSize(), 
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High
                    )
                    googleBitmap != null -> Image(
                        googleBitmap!!, 
                        null, 
                        modifier = Modifier.fillMaxSize(), 
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High
                    )
                    else -> Icon(
                        painter = painterResource("drawable/image.xml"), 
                        contentDescription = null, 
                        modifier = Modifier.size(60.dp),
                        tint = accent.copy(0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Options
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Upload
                    Box(modifier = Modifier.size(54.dp).border(1.dp, accent.copy(0.2f), CircleShape).clickable {
                        scope.launch(Dispatchers.IO) {
                            val fd = java.awt.FileDialog(null as java.awt.Frame?, "UPLOAD", java.awt.FileDialog.LOAD)
                            fd.isVisible = true
                            if (fd.file != null) {
                                val bytes = File(fd.directory, fd.file).readBytes()
                                withContext(Dispatchers.Main) { customImageBase64 = "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(bytes)}" }
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource("drawable/image.xml"),
                            contentDescription = "Upload",
                            modifier = Modifier.size(20.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(accent)
                        )
                    }
                    
                    if (googleBitmap != null) {
                        Box(modifier = Modifier.size(54.dp).clip(CircleShape).clickable { customImageBase64 = null }) {
                            Image(googleBitmap!!, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                Text(
                    "Select a profile picture to continue",
                    fontSize = 14.sp,
                    color = accent.copy(0.5f)
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = {
                        isLoading = true
                        val uid = FirebaseClient.currentUid
                        if (uid != null) {
                            scope.launch(Dispatchers.IO) {
                                val imagePath = when {
                                    customImageBase64 != null -> customImageBase64!!
                                    googlePhotoUrl.isNotEmpty() -> googlePhotoUrl
                                    else -> ""
                                }
                                
                                val userUpdates = mutableMapOf<String, Any>(
                                    "uid" to uid,
                                    "name" to userName,
                                    "email" to userEmail,
                                    "imageName" to imagePath,
                                    "status" to "Online",
                                    "lastSeen" to System.currentTimeMillis()
                                )
                                
                                FirebaseClient.update("users/$uid", userUpdates) { if (it) onProfileComplete() }
                            }
                        } else onProfileComplete()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = customImageBase64 != null || googlePhotoUrl.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = surface)
                ) {
                    if (isLoading) CircularProgressIndicator(color = surface, modifier = Modifier.size(20.dp))
                    else Text("COMPLETE SETUP", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}
