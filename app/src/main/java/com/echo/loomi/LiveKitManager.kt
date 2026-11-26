package com.echo.loomi

import android.content.Context
import android.util.Base64
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.*
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LiveKit audio call manager. Replaces the old WebRTC peer-to-peer approach with
 * LiveKit's SFU-based architecture for more reliable calls.
 *
 * Uses native JWT generation locally to generate tokens and
 * the LiveKit Android SDK to connect to the LiveKit Cloud room.
 */
class LiveKitManager(
    private val context: Context,
    private val listener: LiveKitCallListener
) {
    companion object {
        private const val TAG = "LiveKitManager"
        private const val LIVEKIT_API_KEY = "APIBrf77AzhaNyr"
        private const val LIVEKIT_API_SECRET = "i0rnVXJkC0cHiPjgVIYuSBDiXCGqnNi3UHlwJIXKjvF"
        private const val LIVEKIT_WS_URL = "wss://priya135-ue7efos2.livekit.cloud"
    }

    private var room: Room? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Generate a LiveKit JWT access token for the given room and participant.
     */
    private fun generateToken(roomName: String, participantIdentity: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 86400

        val header = JSONObject().apply {
            put("alg", "HS256")
            put("typ", "JWT")
        }

        val videoGrants = JSONObject().apply {
            put("room", roomName)
            put("roomJoin", true)
            put("canPublish", true)
            put("canSubscribe", true)
        }

        val payload = JSONObject().apply {
            put("iss", LIVEKIT_API_KEY)
            put("sub", participantIdentity)
            put("name", participantIdentity)
            put("nbf", now - 5)
            put("exp", exp)
            put("video", videoGrants)
        }

        fun base64UrlEncode(bytes: ByteArray): String {
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        val headerB64 = base64UrlEncode(header.toString().toByteArray(Charsets.UTF_8))
        val payloadB64 = base64UrlEncode(payload.toString().toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$payloadB64"

        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(LIVEKIT_API_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val signature = mac.doFinal(signingInput.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64UrlEncode(signature)

        return "$signingInput.$signatureB64"
    }

    /**
     * Connect to a LiveKit room for an audio call.
     * @param roomName Unique room name (derived from both user UIDs)
     * @param participantIdentity Current user's UID
     * @param participantName Current user's display name
     */
    fun connect(roomName: String, participantIdentity: String, participantName: String = participantIdentity) {
        scope.launch {
            try {
                val jwt = generateToken(roomName, participantIdentity)

                val newRoom = LiveKit.create(context)
                room = newRoom

                // Collect room events
                launch {
                    newRoom.events.collect { event ->
                        when (event) {
                            is RoomEvent.Connected -> {
                                Log.d(TAG, "Connected to LiveKit room: $roomName")
                                // Enable microphone (audio-only call)
                                newRoom.localParticipant.setMicrophoneEnabled(true)
                                withContext(Dispatchers.Main) {
                                    listener.onConnected()
                                }
                            }
                            is RoomEvent.Disconnected -> {
                                Log.d(TAG, "Disconnected from LiveKit room")
                                withContext(Dispatchers.Main) {
                                    listener.onDisconnected()
                                }
                            }
                            is RoomEvent.TrackSubscribed -> {
                                Log.d(TAG, "Remote audio track subscribed — audio should be audible now.")
                                withContext(Dispatchers.Main) {
                                    listener.onRemoteAudioReceived()
                                }
                            }
                            is RoomEvent.ParticipantDisconnected -> {
                                Log.d(TAG, "Remote participant disconnected.")
                                withContext(Dispatchers.Main) {
                                    listener.onParticipantLeft()
                                }
                            }
                            is RoomEvent.FailedToConnect -> {
                                Log.e(TAG, "Failed to connect to LiveKit: ${event.error}")
                                withContext(Dispatchers.Main) {
                                    listener.onError("Failed to connect: ${event.error.message}")
                                }
                            }
                            else -> { /* Ignore other events */ }
                        }
                    }
                }

                // Connect to the room
                newRoom.connect(LIVEKIT_WS_URL, jwt)
                Log.d(TAG, "Connecting to room $roomName as $participantIdentity...")

            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to LiveKit: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    listener.onError("Connection error: ${e.message}")
                }
            }
        }
    }

    /**
     * Set microphone mute state.
     */
    fun setMicEnabled(enabled: Boolean) {
        scope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling mic: ${e.message}")
            }
        }
    }

    /**
     * Disconnect from the LiveKit room and clean up resources.
     */
    fun disconnect() {
        scope.launch {
            try {
                room?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
            } finally {
                room?.release()
                room = null
            }
        }
    }

    /**
     * Full cleanup — call when completely done.
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }

    /**
     * Listener interface for call events.
     */
    interface LiveKitCallListener {
        fun onConnected()
        fun onDisconnected()
        fun onRemoteAudioReceived()
        fun onParticipantLeft()
        fun onError(message: String)
    }
}

/**
 * Utility to generate a deterministic room name from two user UIDs.
 * Both users will get the same room name regardless of who calls whom.
 */
fun generateRoomName(uid1: String, uid2: String): String {
    val sorted = listOf(uid1, uid2).sorted()
    return "loomi-call-${sorted[0].take(8)}-${sorted[1].take(8)}"
}
