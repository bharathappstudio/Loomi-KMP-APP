package com.echo.loomi.desktop.network

import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName

/**
 * LiveKit client for Desktop.
 *
 * Since the LiveKit Android SDK doesn't run on JVM desktop, the desktop app uses:
 * - LiveKit Server SDK for JWT token generation (same as mobile)
 * - Firebase signaling for call state (ringing/accepted/declined)
 *
 * Audio for desktop calls is handled via the LiveKit WebSocket room protocol.
 * For now, the desktop acts as a "signaling-only" participant — it generates
 * valid tokens and relies on the mobile LiveKit SDK for the actual audio stream.
 *
 * When LiveKit releases their official KMP desktop client SDK, this can be
 * upgraded to full audio support on desktop as well.
 */
object LiveKitClient {
    private const val LIVEKIT_API_KEY = "APIBrf77AzhaNyr"
    private const val LIVEKIT_API_SECRET = "i0rnVXJkC0cHiPjgVIYuSBDiXCGqnNi3UHlwJIXKjvF"
    const val LIVEKIT_WS_URL = "wss://priya135-ue7efos2.livekit.cloud"

    /**
     * Generate a LiveKit JWT access token for the given room and participant.
     */
    fun generateToken(roomName: String, participantIdentity: String, participantName: String = participantIdentity): String {
        val token = AccessToken(LIVEKIT_API_KEY, LIVEKIT_API_SECRET)
        token.identity = participantIdentity
        token.name = participantName

        token.addGrants(
            RoomJoin(true),
            RoomName(roomName),
            CanPublish(true),
            CanSubscribe(true)
        )

        return token.toJwt()
    }

    /**
     * Utility to generate a deterministic room name from two user UIDs.
     * Both users will get the same room name regardless of who calls whom.
     */
    fun generateRoomName(uid1: String, uid2: String): String {
        val sorted = listOf(uid1, uid2).sorted()
        return "loomi-call-${sorted[0].take(8)}-${sorted[1].take(8)}"
    }
}
