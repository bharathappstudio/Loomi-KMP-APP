package com.echo.loomi.desktop.utils

import java.util.Base64

object DesktopEncryptionUtils {
    private const val KEY = "loomi_secret_key"

    fun encrypt(text: String): String {
        val bytes = text.toByteArray()
        val encrypted = ByteArray(bytes.size)
        val keyBytes = KEY.toByteArray()
        for (i in bytes.indices) {
            encrypted[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        val base64Encoded = Base64.getEncoder().encodeToString(encrypted)
        return "e2e:$base64Encoded"
    }

    fun decrypt(encryptedText: String): String {
        if (!encryptedText.startsWith("e2e:")) return encryptedText
        return try {
            val base64Data = encryptedText.substring(4)
            val cleanBase64 = base64Data.replace("\\s".toRegex(), "")
            val bytes = Base64.getDecoder().decode(cleanBase64)
            val decrypted = ByteArray(bytes.size)
            val keyBytes = KEY.toByteArray()
            for (i in bytes.indices) {
                decrypted[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            String(decrypted)
        } catch (e: Exception) {
            encryptedText
        }
    }
}
