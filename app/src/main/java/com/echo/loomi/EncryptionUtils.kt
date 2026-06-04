package com.echo.loomi

import android.util.Base64

object EncryptionUtils {
    private const val KEY = "loomi_secret_key" // In a real app, this would be a per-user key exchange

    fun encrypt(text: String): String {
        val bytes = text.toByteArray()
        val encrypted = ByteArray(bytes.size)
        val keyBytes = KEY.toByteArray()
        for (i in bytes.indices) {
            encrypted[i] = (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return "e2e:" + Base64.encodeToString(encrypted, Base64.DEFAULT)
    }

    fun decrypt(encryptedText: String): String {
        if (!encryptedText.startsWith("e2e:")) return encryptedText
        return try {
            val base64Data = encryptedText.substring(4)
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
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
