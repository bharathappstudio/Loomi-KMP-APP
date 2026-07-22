package com.echo.loomi

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Global Permission Manager to respect User Privacy settings across the entire app.
 * Use LoomiPermissions.isAllowed(context, permission) instead of ContextCompat.checkSelfPermission
 */
object LoomiPermissions {
    private const val PREFS_NAME = "echo_prefs"
    
    fun isAllowed(context: Context, permission: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userPrefEnabled = prefs.getBoolean("pref_$permission", true)
        val systemGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return systemGranted && userPrefEnabled
    }

    fun setUserPreference(context: Context, permission: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pref_$permission", enabled).apply()
    }
}

class PermissionsActivity : ComponentActivity() {

    // ---- STATE ----
    private var locationGranted by mutableStateOf(false)
    private var notificationGranted by mutableStateOf(false)
    private var cameraGranted by mutableStateOf(false)
    private var microphoneGranted by mutableStateOf(false)
    private var contactsGranted by mutableStateOf(false)
    private var storageGranted by mutableStateOf(false)

    // ---- PERMISSION LAUNCHERS ----
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // EDGE TO EDGE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        updateState()

        setContent {
            PermissionsUI(
                onBack = { finish() },
                states = PermissionStates(
                    location = locationGranted,
                    notification = notificationGranted,
                    camera = cameraGranted,
                    microphone = microphoneGranted,
                    contacts = contactsGranted,
                    storage = storageGranted
                ),
                onToggle = { permission, enabled ->
                    LoomiPermissions.setUserPreference(this, permission, enabled)
                    if (enabled) {
                        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(permission)
                        } else {
                            updateState()
                        }
                    } else {
                        updateState()
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    private fun updateState() {
        locationGranted = LoomiPermissions.isAllowed(this, Manifest.permission.ACCESS_FINE_LOCATION)
        notificationGranted = if (Build.VERSION.SDK_INT >= 33) {
            LoomiPermissions.isAllowed(this, Manifest.permission.POST_NOTIFICATIONS)
        } else true
        cameraGranted = LoomiPermissions.isAllowed(this, Manifest.permission.CAMERA)
        microphoneGranted = LoomiPermissions.isAllowed(this, Manifest.permission.RECORD_AUDIO)
        contactsGranted = LoomiPermissions.isAllowed(this, Manifest.permission.READ_CONTACTS)
        storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LoomiPermissions.isAllowed(this, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            LoomiPermissions.isAllowed(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

data class PermissionStates(
    val location: Boolean,
    val notification: Boolean,
    val camera: Boolean,
    val microphone: Boolean,
    val contacts: Boolean,
    val storage: Boolean
)

@Composable
fun PermissionsUI(
    onBack: () -> Unit,
    states: PermissionStates,
    onToggle: (String, Boolean) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFFFFF))
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(scrollState)
            .padding(20.dp)
    ) {

        // ---- HEADER ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Text(
                text = "Permissions",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(32.dp))

        // ---- NOTIFICATIONS ----
        PermissionItem(
            title = "Notifications",
            description = "Allow Loomi to send notifications and messages",
            checked = states.notification,
            onCheckedChange = {
                if (Build.VERSION.SDK_INT >= 33) {
                    onToggle(Manifest.permission.POST_NOTIFICATIONS, it)
                }
            }
        )

        Spacer(Modifier.height(28.dp))

        // ---- LOCATION ----
        PermissionItem(
            title = "Location access",
            description = "Allow Loomi to give better local answers based on your location",
            checked = states.location,
            onCheckedChange = { onToggle(Manifest.permission.ACCESS_FINE_LOCATION, it) }
        )

        Spacer(Modifier.height(28.dp))

        // ---- CAMERA ----
        PermissionItem(
            title = "Camera",
            description = "Needed for video calls and capturing photos within the app",
            checked = states.camera,
            onCheckedChange = { onToggle(Manifest.permission.CAMERA, it) }
        )

        Spacer(Modifier.height(28.dp))

        // ---- MICROPHONE ----
        PermissionItem(
            title = "Microphone",
            description = "Needed for voice calls and recording voice messages",
            checked = states.microphone,
            onCheckedChange = { onToggle(Manifest.permission.RECORD_AUDIO, it) }
        )

        Spacer(Modifier.height(28.dp))

        // ---- CONTACTS ----
        PermissionItem(
            title = "Contacts",
            description = "Allow Loomi to help you find and connect with your friends",
            checked = states.contacts,
            onCheckedChange = { onToggle(Manifest.permission.READ_CONTACTS, it) }
        )

        Spacer(Modifier.height(28.dp))

        // ---- STORAGE/MEDIA ----
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        PermissionItem(
            title = "Storage & Media",
            description = "Needed to send and save photos, videos and files",
            checked = states.storage,
            onCheckedChange = { onToggle(storagePermission, it) }
        )
        
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color(0xCC000000)
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFFFFE0B2),
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFC8E6C9)
            )
        )
    }
}
