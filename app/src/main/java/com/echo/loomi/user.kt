package com.echo.loomi

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import java.io.File

class EchoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

    val backgroundGreen = Color(0xFF66BB6A)
    val white = Color(0xFFFFFFFF)

    // AUTO-START TRIGGER
    LaunchedEffect(Unit) {
        if (!isDownloading && downloadId == -1L) {
            // Check permission first
            if (checkInstallPermission(context)) {
                isDownloading = true
                downloadId = startDownload(context, downloadManager)
            }
        }
    }

    // REAL-TIME PROGRESS POLLING
    LaunchedEffect(downloadId) {
        if (downloadId != -1L) {
            var isRunning = true
            while (isRunning) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    @SuppressLint("Range")
                    val downloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    @SuppressLint("Range")
                    val total = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    @SuppressLint("Range")
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))

                    if (total > 0) progress = downloaded.toFloat() / total.toFloat()

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        isRunning = false
                        isDownloading = false
                        installApk(context)
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        isRunning = false
                        isDownloading = false
                        downloadId = -1L
                        Toast.makeText(context, "Download Failed", Toast.LENGTH_SHORT).show()
                    }
                }
                cursor?.close()
                delay(300)
            }
        }
    }

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "wave")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGreen)
    ) {
        // Main Content Area (Centers your original LoadingIndicator)
        Box(
            modifier = Modifier
                .weight(30f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(color = white)
        }

        // BOTTOM UPDATE UI (Automatically appears during download)
        AnimatedVisibility(visible = isDownloading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Updating Echo: ${(progress * 100).toInt()}%",
                    color = white,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    color = white,
                    trackColor = white.copy(alpha = 0.3f)
                )
            }
        }

        // Your Original Footer
        Text(
            text = "UI Was Developing By Bharath",
            fontSize = 12.sp,
            color = white,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 32.dp)
        )
    }
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
    val url = "https://raw.githubusercontent.com/bharathappstudio/Loomi/refs/heads/release-apk/app/release/app-release.apk"
    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
    if (file.exists()) file.delete()

    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle("Echo Update")
        .setDescription("Downloading new version...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationUri(Uri.fromFile(file))

    return manager.enqueue(request)
}

private fun installApk(context: Context) {
    try {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Auto-Install failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
