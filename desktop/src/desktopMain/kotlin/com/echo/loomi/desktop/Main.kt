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
import com.google.gson.JsonParser
import java.io.File

private const val SCREEN_LOGIN = 0
private const val SCREEN_WELCOME = 1
private const val SCREEN_MAIN = 2

private val SESSION_FILE = File(System.getProperty("user.home"), ".gemini/antigravity/loomi_session.json")

data class SessionData(
    val idToken: String,
    val uid: String,
    val name: String,
    val email: String,
    val photoUrl: String,
    val imageName: String = ""
)

fun main() = application {
    var currentScreen by remember { mutableStateOf(SCREEN_LOGIN) }
    var session by remember { mutableStateOf<SessionData?>(null) }
    
    // Auto-load session if exists
    LaunchedEffect(Unit) {
        if (SESSION_FILE.exists()) {
            try {
                val json = SESSION_FILE.readText()
                val data = Gson().fromJson(json, SessionData::class.java)
                if (data != null) {
                    FirebaseClient.initAuth(data.idToken, data.uid)
                    
                    // Fetch latest imageName from database to make sure it matches
                    FirebaseClient.read("users/${data.uid}/imageName") { dbImageName ->
                        val cleanDbImage = if (dbImageName == "null" || dbImageName == null) "" else dbImageName.replace("\"", "")
                        val finalImage = if (cleanDbImage.isEmpty()) data.imageName else cleanDbImage
                        session = data.copy(imageName = finalImage)
                        currentScreen = if (finalImage.isEmpty()) SCREEN_WELCOME else SCREEN_MAIN
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Window(
        onCloseRequest = {
            // Set offline status on exit
            val uid = FirebaseClient.currentUid
            if (uid != null) {
                FirebaseClient.write("users/$uid/status", "Offline")
                FirebaseClient.write("users/$uid/lastSeen", System.currentTimeMillis())
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
                            
                            // Check if profile is done (imageName exists in database)
                            FirebaseClient.read("users/$uid") { userJson ->
                                var dbImageName = ""
                                if (userJson != null && userJson != "null") {
                                    try {
                                        val obj = JsonParser.parseString(userJson).asJsonObject
                                        dbImageName = obj.get("imageName")?.asString ?: ""
                                    } catch (e: Exception) {}
                                }
                                
                                // Save initial user data
                                val userUpdates = mapOf(
                                    "uid" to uid,
                                    "name" to name,
                                    "email" to email,
                                    "lastSeen" to System.currentTimeMillis()
                                )
                                FirebaseClient.update("users/$uid", userUpdates)
                                
                                val newSession = SessionData(token, uid, name, email, photoUrl, dbImageName)
                                session = newSession
                                saveSession(newSession)

                                // Fix: Go directly to Main Screen for 1-step login experience
                                currentScreen = SCREEN_MAIN
                            }
                        }
                    )
                }
                SCREEN_WELCOME -> {
                    WelcomeScreen(
                        googlePhotoUrl = session?.photoUrl ?: "",
                        onProfileComplete = {
                            // Re-read to get new imageName
                            val uid = session?.uid ?: return@WelcomeScreen
                            FirebaseClient.read("users/$uid/imageName") { dbImageName ->
                                val cleanImageName = dbImageName?.replace("\"", "") ?: ""
                                session?.let {
                                    val updatedSession = it.copy(imageName = cleanImageName)
                                    session = updatedSession
                                    saveSession(updatedSession)
                                }
                                currentScreen = SCREEN_MAIN
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
                                SESSION_FILE.delete()
                                session = null
                                FirebaseClient.authToken = null
                                FirebaseClient.currentUid = null
                                currentScreen = SCREEN_LOGIN
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
