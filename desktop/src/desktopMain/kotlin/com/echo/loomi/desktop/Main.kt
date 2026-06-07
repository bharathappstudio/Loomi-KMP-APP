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
import javafx.application.Platform
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
    // Initialize JavaFX once at startup
    try {
        Platform.startup {}
    } catch (e: Exception) {}

    startApp()
}

fun startApp() = application {
    var currentScreen by remember { mutableStateOf(SCREEN_LOGIN) }
    var session by remember { mutableStateOf<SessionData?>(null) }
    
    LaunchedEffect(Unit) {
        println("Main: Checking for existing session at ${SESSION_FILE.absolutePath}")
        if (SESSION_FILE.exists()) {
            try {
                val json = SESSION_FILE.readText()
                println("Main: Session file found. Attempting to parse...")
                val data = Gson().fromJson(json, SessionData::class.java)
                if (data != null) {
                    println("Main: Session parsed for UID: ${data.uid}. Initializing Auth...")
                    FirebaseClient.initAuth(data.idToken, data.uid)
                    FirebaseClient.read("users/${data.uid}/imageName") { dbImageName ->
                        println("Main: Read imageName from Firebase: $dbImageName")
                        val cleanDbImage = if (dbImageName == "null" || dbImageName == null) "" else dbImageName.replace("\"", "")
                        val finalImage = if (cleanDbImage.isEmpty()) data.imageName else cleanDbImage
                        session = data.copy(imageName = finalImage)
                        currentScreen = SCREEN_MAIN
                        println("Main: Navigating to Main Screen.")
                    }
                }
            } catch (e: Exception) {
                println("Main: Error during session restoration: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("Main: No session file found.")
        }
    }

    Window(
        onCloseRequest = {
            val uid = FirebaseClient.currentUid
            if (uid != null) {
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
                            FirebaseClient.read("users/$uid") { userJson ->
                                var dbImageName = ""
                                if (userJson != null && userJson != "null") {
                                    try {
                                        val obj = JsonParser.parseString(userJson).asJsonObject
                                        dbImageName = obj.get("imageName")?.asString ?: ""
                                    } catch (e: Exception) {}
                                }
                                
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
