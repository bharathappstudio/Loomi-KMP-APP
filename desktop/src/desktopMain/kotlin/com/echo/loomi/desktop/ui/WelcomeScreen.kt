package com.echo.loomi.desktop.ui

import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.loomi.desktop.network.FirebaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Base64

@Composable
fun WelcomeScreen(
    googlePhotoUrl: String,
    onProfileComplete: () -> Unit
) {
    val imageNames = (1..14).map { if (it < 10) "0$it.png" else "$it.png" }
    var selectedGender by remember { mutableStateOf("Male") }
    var selectedImage by remember { mutableStateOf(imageNames[0]) }
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
                    googleBitmap = loadImageBitmap(URL(googlePhotoUrl).openStream())
                } catch (e: Exception) {}
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
                    customBitmap != null -> Image(customBitmap!!, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    selectedImage.isEmpty() && googleBitmap != null -> Image(googleBitmap!!, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else -> Image(painterResource("Memoji/$selectedGender/Circle/$selectedImage"), null, modifier = Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Crop)
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
                                withContext(Dispatchers.Main) { customImageBase64 = "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(bytes)}"; selectedImage = "" }
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                        Text("↑", fontSize = 24.sp, color = accent)
                    }
                    
                    if (googleBitmap != null) {
                        Box(modifier = Modifier.size(54.dp).clip(CircleShape).clickable { customImageBase64 = null; selectedImage = "" }) {
                            Image(googleBitmap!!, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("MALE", "FEMALE").forEach { g ->
                        val sel = selectedGender.uppercase() == g
                        Button(
                            onClick = { selectedGender = if (g == "MALE") "Male" else "Female"; customImageBase64 = null; if (selectedImage.isEmpty()) selectedImage = imageNames[0] },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (sel) accent else accent.copy(0.05f), contentColor = if (sel) surface else accent),
                            border = if (!sel) BorderStroke(1.dp, accent.copy(0.1f)) else null
                        ) { Text(g, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(imageNames) { img ->
                        val sel = selectedImage == img
                        Box(modifier = Modifier.size(64.dp).border(if (sel) 2.dp else 1.dp, if (sel) accent else accent.copy(0.1f), CircleShape).padding(4.dp).clip(CircleShape).clickable { selectedImage = img; customImageBase64 = null }) {
                            Image(painterResource("Memoji/$selectedGender/Circle/$img"), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = {
                        isLoading = true
                        val uid = FirebaseClient.currentUid
                        if (uid != null) {
                            scope.launch(Dispatchers.IO) {
                                val path = when {
                                    customImageBase64 != null -> customImageBase64!!
                                    selectedImage.isEmpty() && googlePhotoUrl.isNotEmpty() -> googlePhotoUrl
                                    else -> "Memoji/$selectedGender/Circle/$selectedImage"
                                }
                                FirebaseClient.write("users/$uid/imageName", path) { if (it) onProfileComplete() }
                            }
                        } else onProfileComplete()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
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
