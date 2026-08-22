package com.echo.loomi.desktop.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object FirebaseClient {
    private const val DATABASE_URL = "https://echo-loomi-app-default-rtdb.firebaseio.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Infinite read timeout for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private const val FIREBASE_API_KEY = "AIzaSyBjC3-mCdWDP28kA293ZKEsWZPPnjRqr-0"

    var authToken: String? = null
    var refreshToken: String? = null
    var currentUid: String? = null
    
    var onTokenRefreshed: ((idToken: String, refreshToken: String) -> Unit)? = null

    private val activeListeners = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initAuth(token: String, uid: String, refresh: String? = null) {
        authToken = token
        currentUid = uid
        refreshToken = refresh
    }

    suspend fun refreshAuthToken(): Boolean {
        val refresh = refreshToken ?: return false
        return withContext(Dispatchers.IO) {
            val url = "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY"
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .build()
            
            val request = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = response.body?.string() ?: ""
                        val obj = JsonParser.parseString(json).asJsonObject
                        val newIdToken = obj.get("id_token").asString
                        val newRefreshToken = obj.get("refresh_token").asString
                        
                        authToken = newIdToken
                        refreshToken = newRefreshToken
                        
                        withContext(Dispatchers.Main) {
                            onTokenRefreshed?.invoke(newIdToken, newRefreshToken)
                        }
                        println("FirebaseClient: Token refreshed successfully.")
                        true
                    } else {
                        println("FirebaseClient: Token refresh failed: ${response.code}")
                        false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    fun isAuthorized(): Boolean {
        return authToken != null && currentUid != null
    }

    // Write (PUT) -> Overwrites path (Suspending version for reliability)
    suspend fun writeSync(path: String, value: Any): Boolean {
        return withContext(Dispatchers.IO) {
            val url = "$DATABASE_URL/$path.json?auth=$authToken"
            val body = gson.toJson(value).toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).put(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    fun write(path: String, value: Any, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            val success = writeSync(path, value)
            onComplete(success)
        }
    }

    // Push (POST) -> Generates a new child node with a unique key
    fun push(path: String, value: Any, onComplete: (String?) -> Unit) {
        scope.launch {
            val url = "$DATABASE_URL/$path.json?auth=$authToken"
            val body = gson.toJson(value).toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        val obj = JsonParser.parseString(responseBody).asJsonObject
                        val key = obj.get("name")?.asString
                        onComplete(key)
                    } else {
                        onComplete(null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(null)
            }
        }
    }

    // Update (PATCH) -> Updates specific fields without overwriting
    fun update(path: String, value: Map<String, Any>, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            val url = "$DATABASE_URL/$path.json?auth=$authToken"
            val body = gson.toJson(value).toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).patch(body).build()
            try {
                client.newCall(request).execute().use { response ->
                    onComplete(response.isSuccessful)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false)
            }
        }
    }

    // Delete (DELETE) -> Removes node
    fun delete(path: String, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            val url = "$DATABASE_URL/$path.json?auth=$authToken"
            val request = Request.Builder().url(url).delete().build()
            try {
                client.newCall(request).execute().use { response ->
                    onComplete(response.isSuccessful)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false)
            }
        }
    }

    // Read (GET) -> Fetch value once
    fun read(path: String, onResult: (String?) -> Unit) {
        scope.launch {
            val url = "$DATABASE_URL/$path.json?auth=$authToken"
            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401 && refreshToken != null) {
                        println("FirebaseClient: 401 on read. Refreshing...")
                        if (refreshAuthToken()) {
                            read(path, onResult) // Retry once
                            return@launch
                        }
                    }
                    
                    if (response.isSuccessful) {
                        onResult(response.body?.string())
                    } else {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(null)
            }
        }
    }

    // Listen (SSE Stream) -> Listens to database path in real-time
    fun startListener(
        path: String, 
        onAuthError: () -> Unit = {},
        onUpdate: (event: String, childPath: String, json: String) -> Unit
    ) {
        // Stop any existing listener on this path
        stopListener(path)

        val job = scope.launch {
            var retryDelay = 2000L
            while (isActive) {
                val url = "$DATABASE_URL/$path.json?auth=$authToken"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "text/event-stream")
                    .build()
                
                var call: Call? = null
                try {
                    call = client.newCall(request)
                    val response = call.execute()
                    
                    if (response.code == 401) {
                        response.close()
                        println("FirebaseClient: 401 Unauthorized on $path. Attempting token refresh...")
                        
                        val refreshed = refreshAuthToken()
                        if (refreshed) {
                            println("FirebaseClient: Refresh success, restarting listener for $path.")
                            continue // Retry with new token immediately
                        } else {
                            println("FirebaseClient: Refresh failed. Notifying Auth Error for $path.")
                            withContext(Dispatchers.Main) { onAuthError() }
                            delay(retryDelay)
                            retryDelay = (retryDelay * 2).coerceAtMost(30000L)
                            continue
                        }
                    }

                    if (!response.isSuccessful) {
                        response.close()
                        throw IOException("HTTP error: ${response.code}")
                    }
                    
                    // Connection succeeded, reset retry delay
                    retryDelay = 2000L
                    val reader = response.body?.charStream()?.buffered()
                    if (reader != null) {
                        var currentEvent = ""
                        while (isActive) {
                            val line = reader.readLine() ?: break // EOF
                            if (line.isBlank()) continue
                            
                            if (line.startsWith("event:")) {
                                currentEvent = line.substring(6).trim()
                            } else if (line.startsWith("data:")) {
                                val rawData = line.substring(5).trim()
                                
                                // Only process data events (put/patch)
                                if (currentEvent != "put" && currentEvent != "patch") continue
                                
                                if (rawData == "null") {
                                    onUpdate(currentEvent, "", "null")
                                    continue
                                }
                                try {
                                    val obj = JsonParser.parseString(rawData).asJsonObject
                                    val childPath = obj.get("path")?.asString ?: ""
                                    val dataElement = obj.get("data")
                                    val dataJson = if (dataElement == null || dataElement.isJsonNull) {
                                        "null"
                                    } else {
                                        dataElement.toString()
                                    }
                                    onUpdate(currentEvent, childPath, dataJson)
                                } catch (e: Exception) {
                                    // Sometimes data contains direct values or non-objects
                                    onUpdate(currentEvent, "", rawData)
                                }
                            }
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    if (!isActive) break
                    e.printStackTrace()
                }
                
                // Exponential backoff retry on failure
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(60000L)
            }
        }
        
        activeListeners[path] = job
    }

    fun stopListener(path: String) {
        activeListeners[path]?.cancel()
        activeListeners.remove(path)
    }

    fun stopAll() {
        activeListeners.forEach { (_, job) -> job.cancel() }
        activeListeners.clear()
    }
}
