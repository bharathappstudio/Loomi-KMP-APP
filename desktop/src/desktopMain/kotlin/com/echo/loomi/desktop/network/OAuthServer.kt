package com.echo.loomi.desktop.network

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI

object OAuthServer {
    private const val CLIENT_ID = "125517755986-gt6udpi3alsf7tig486uoqlpfmj4nibf.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "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"
    private const val FIREBASE_API_KEY = "AIzaSyBjC3-mCdWDP28kA293ZKEsWZPPnjRqr-0"
    private const val PORT = 5000
    private const val REDIRECT_URI = "http://127.0.0.1:$PORT/callback"
    
    private val client = OkHttpClient()

    data class AuthResult(
        val success: Boolean,
        val idToken: String? = null,
        val uid: String? = null,
        val name: String? = null,
        val email: String? = null,
        val photoUrl: String? = null,
        val errorMessage: String? = null
    )

    suspend fun startGoogleSignIn(onResult: (AuthResult) -> Unit) {
        println("OAuthServer: Starting Google Sign-In...")
        withContext(Dispatchers.IO) {
            var server: HttpServer? = null
            try {
                println("OAuthServer: Creating HTTP server on port $PORT...")
                server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
                server.createContext("/callback") { exchange ->
                    println("OAuthServer: Callback received: ${exchange.requestURI}")
                    val uri = exchange.requestURI
                    val query = uri.query
                    val code = query?.split("&")
                        ?.firstOrNull { it.startsWith("code=") }
                        ?.substring(5)

                    if (code != null) {
                        val htmlResponse = """
                            <html>
                            <head>
                                <title>Loomi Login</title>
                                <style>
                                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; text-align: center; padding-top: 100px; background-color: #121212; color: white; }
                                    .container { max-width: 400px; margin: 0 auto; padding: 40px; border-radius: 12px; background-color: #1E1E1E; box-shadow: 0 4px 12px rgba(0,0,0,0.5); }
                                    h2 { color: #66BB6A; }
                                    p { color: #aaaaaa; }
                                </style>
                            </head>
                            <body>
                                <div class="container">
                                    <h2>Loomi Login Successful</h2>
                                    <p>You have successfully authenticated with Google. You can safely close this tab now and return to the Loomi app.</p>
                                </div>
                            </body>
                            </html>
                        """.trimIndent()
                        
                        exchange.sendResponseHeaders(200, htmlResponse.toByteArray().size.toLong())
                        exchange.responseBody.write(htmlResponse.toByteArray())
                        exchange.responseBody.close()

                        // Code received, stop the server and complete auth asynchronously
                        server?.stop(1)
                        
                        // Exchange authorization code for tokens
                        exchangeCodeForTokens(code, onResult)
                    } else {
                        val htmlResponse = "<h3>Authorization Failed: Code not found.</h3>"
                        exchange.sendResponseHeaders(400, htmlResponse.toByteArray().size.toLong())
                        exchange.responseBody.write(htmlResponse.toByteArray())
                        exchange.responseBody.close()
                        server?.stop(1)
                        onResult(AuthResult(false, errorMessage = "Authorization code not found in callback."))
                    }
                }

                server.start()
                println("OAuthServer: Server started.")

                // Open user browser
                val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                        "client_id=$CLIENT_ID" +
                        "&redirect_uri=$REDIRECT_URI" +
                        "&response_type=code" +
                        "&scope=email%20profile%20openid"
                
                println("OAuthServer: Attempting to open browser...")
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    println("OAuthServer: Using Desktop API to open browser.")
                    Desktop.getDesktop().browse(URI(authUrl))
                } else {
                    println("OAuthServer: Desktop API not supported, trying xdg-open.")
                    // Alternative command execution for browser if Desktop API is not supported on some Linux environments
                    val runtime = Runtime.getRuntime()
                    try {
                        runtime.exec(arrayOf("xdg-open", authUrl))
                    } catch (e: Exception) {
                        println("OAuthServer: xdg-open failed: ${e.message}")
                        server.stop(0)
                        onResult(AuthResult(false, errorMessage = "Could not open system browser. URL: $authUrl"))
                    }
                }
            } catch (e: Exception) {
                println("OAuthServer: Error during sign-in startup: ${e.message}")
                e.printStackTrace()
                server?.stop(0)
                onResult(AuthResult(false, errorMessage = e.localizedMessage))
            }
        }
    }

    private fun exchangeCodeForTokens(code: String, onResult: (AuthResult) -> Unit) {
        println("OAuthServer: Exchanging code for tokens...")
        try {
            // Exchange code for Google ID token
            val tokenRequest = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("client_secret", CLIENT_SECRET)
                        .add("code", code)
                        .add("redirect_uri", REDIRECT_URI)
                        .add("grant_type", "authorization_code")
                        .build()
                )
                .build()

            client.newCall(tokenRequest).execute().use { response ->
                println("OAuthServer: Token exchange response code: ${response.code}")
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    println("OAuthServer: Token exchange failed body: $body")
                    onResult(AuthResult(false, errorMessage = "Failed to exchange code for Google token: ${response.code}"))
                    return
                }

                val responseBody = response.body?.string() ?: ""
                val googleObj = JsonParser.parseString(responseBody).asJsonObject
                val idToken = googleObj.get("id_token")?.asString

                if (idToken != null) {
                    println("OAuthServer: Google ID Token obtained, signing in with Firebase...")
                    signInWithFirebase(idToken, onResult)
                } else {
                    println("OAuthServer: Google ID Token not found in response.")
                    onResult(AuthResult(false, errorMessage = "Google ID Token not found in response."))
                }
            }
        } catch (e: Exception) {
            println("OAuthServer: Error during token exchange: ${e.message}")
            e.printStackTrace()
            onResult(AuthResult(false, errorMessage = "OAuth token exchange error: ${e.localizedMessage}"))
        }
    }

    private fun signInWithFirebase(googleIdToken: String, onResult: (AuthResult) -> Unit) {
        println("OAuthServer: Signing in with Firebase...")
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$FIREBASE_API_KEY"
            val jsonBody = """
                {
                    "postBody": "id_token=$googleIdToken&providerId=google.com",
                    "requestUri": "http://127.0.0.1",
                    "returnSecureToken": true
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                println("OAuthServer: Firebase response code: ${response.code}")
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    println("OAuthServer: Firebase Auth failed body: $body")
                    onResult(AuthResult(false, errorMessage = "Firebase Auth failed: ${response.code}"))
                    return
                }

                val responseBody = response.body?.string() ?: ""
                val firebaseObj = JsonParser.parseString(responseBody).asJsonObject
                
                val firebaseIdToken = firebaseObj.get("idToken")?.asString
                val localId = firebaseObj.get("localId")?.asString
                val displayName = firebaseObj.get("displayName")?.asString
                val email = firebaseObj.get("email")?.asString
                val photoUrl = firebaseObj.get("photoUrl")?.asString

                if (firebaseIdToken != null && localId != null) {
                    println("OAuthServer: Firebase login successful for UID: $localId")
                    onResult(AuthResult(
                        success = true,
                        idToken = firebaseIdToken,
                        uid = localId,
                        name = displayName ?: "Loomi User",
                        email = email ?: "",
                        photoUrl = photoUrl ?: ""
                    ))
                } else {
                    println("OAuthServer: Firebase parameters not found in response.")
                    onResult(AuthResult(false, errorMessage = "Firebase parameters not found in response."))
                }
            }
        } catch (e: Exception) {
            println("OAuthServer: Firebase authentication error: ${e.message}")
            e.printStackTrace()
            onResult(AuthResult(false, errorMessage = "Firebase authentication error: ${e.localizedMessage}"))
        }
    }
}
