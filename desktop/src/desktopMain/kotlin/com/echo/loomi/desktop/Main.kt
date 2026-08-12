package com.echo.loomi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.echo.loomi.desktop.network.FirebaseClient
import com.echo.loomi.desktop.ui.LoginScreen
import com.echo.loomi.desktop.ui.MainScreen
import com.echo.loomi.desktop.ui.WelcomeScreen
import com.echo.loomi.desktop.ui.theme.LoomiTheme
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private const val SCREEN_LOGIN = 0
private const val SCREEN_MAIN = 1
private const val SCREEN_WELCOME = 2

private val SESSION_FILE = File(System.getProperty("user.home"), ".gemini/antigravity/loomi_session.json")

data class SessionData(
    val idToken: String,
    val uid: String,
    val name: String,
    val email: String,
    val photoUrl: String,
    val imageName: String = ""
)

fun main(args: Array<String>) {
    startApp()
}

fun startApp() = application {
    val initialSession = remember {
        if (SESSION_FILE.exists()) {
            try {
                val json = SESSION_FILE.readText()
                val data = Gson().fromJson(json, SessionData::class.java)
                if (data != null) {
                    FirebaseClient.initAuth(data.idToken, data.uid)
                }
                data
            } catch (e: Exception) {
                null
            }
        } else null
    }

    var currentScreen by remember { 
        mutableStateOf(if (initialSession != null) SCREEN_MAIN else SCREEN_LOGIN) 
    }
    var session by remember { mutableStateOf(initialSession) }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        session?.let { data ->
            println("Main: Session found. Initializing Auth for UID: ${data.uid}")
            FirebaseClient.initAuth(data.idToken, data.uid)
            
            // Background update of session data without forcing screen changes
            FirebaseClient.read("users/${data.uid}/imageName") { dbImageName ->
                val cleanDbImage = if (dbImageName == "null" || dbImageName == null) "" else dbImageName.replace("\"", "")
                if (cleanDbImage.isNotEmpty() && cleanDbImage != data.imageName) {
                    session = data.copy(imageName = cleanDbImage)
                }
            }
        }
    }

    Window(
        onCloseRequest = {
            val uid = FirebaseClient.currentUid
            if (uid != null) {
                // Use a dedicated scope to ensure the write finishes before exit
                runBlocking {
                    FirebaseClient.writeSync("users/$uid/status", "Offline")
                    FirebaseClient.writeSync("users/$uid/lastSeen", System.currentTimeMillis())
                }
            }
            exitApplication()
        },
        title = "LOOMI",
        state = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            width = 1100.dp, 
            height = 750.dp
        )
    ) {
        LoomiTheme {
            when (currentScreen) {
                SCREEN_LOGIN -> {
                    LoginScreen(
                        onLoginSuccess = { token, uid, name, email, photoUrl ->
                            FirebaseClient.initAuth(token, uid)
                            FirebaseClient.read("users/$uid/imageName") { dbImageNameJson ->
                                val dbImageName = if (dbImageNameJson == null || dbImageNameJson == "null") "" else dbImageNameJson.replace("\"", "")
                                
                                val newSession = SessionData(token, uid, name, email, photoUrl, dbImageName)
                                session = newSession
                                saveSession(newSession)
                                
                                if (dbImageName.isEmpty()) {
                                    currentScreen = SCREEN_WELCOME
                                } else {
                                    // Update basic info for existing users
                                    val userUpdates = mapOf(
                                        "uid" to uid,
                                        "name" to name,
                                        "email" to email,
                                        "lastSeen" to System.currentTimeMillis()
                                    )
                                    FirebaseClient.update("users/$uid", userUpdates)
                                    currentScreen = SCREEN_MAIN
                                }
                            }
                        }
                    )
                }
                SCREEN_MAIN -> {
                    session?.let {
                        MainScreen(
                            currentUserName = it.name,
                            currentUserImage = it.imageName,
                            onLogout = {
                                scope.launch {
                                    val uid = FirebaseClient.currentUid
                                    if (uid != null) {
                                        FirebaseClient.writeSync("users/$uid/status", "Offline")
                                        FirebaseClient.writeSync("users/$uid/lastSeen", System.currentTimeMillis())
                                    }
                                    SESSION_FILE.delete()
                                    session = null
                                    FirebaseClient.authToken = null
                                    FirebaseClient.currentUid = null
                                    currentScreen = SCREEN_LOGIN
                                }
                            },
                            onProfileClick = {
                                currentScreen = SCREEN_WELCOME
                            }
                        )
                    }
                }
                SCREEN_WELCOME -> {
                    session?.let {
                        WelcomeScreen(
                            googlePhotoUrl = it.photoUrl,
                            userName = it.name,
                            userEmail = it.email,
                            onProfileComplete = {
                                FirebaseClient.read("users/${it.uid}/imageName") { dbImageName ->
                                    val cleanDbImage = if (dbImageName == "null" || dbImageName == null) "" else dbImageName.replace("\"", "")
                                    session = it.copy(imageName = cleanDbImage)
                                    saveSession(session!!)
                                    currentScreen = SCREEN_MAIN
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun saveSession(data: SessionData) {
    try {
        if (!SESSION_FILE.parentFile.exists()) {
            SESSION_FILE.parentFile.mkdirs()
        }
        SESSION_FILE.writeText(Gson().toJson(data))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
