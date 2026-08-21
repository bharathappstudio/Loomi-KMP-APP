package com.echo.loomi.desktop.utils

import com.echo.loomi.desktop.ui.ChatMessage
import com.echo.loomi.desktop.ui.SnapUser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest

object LocalCacheManager {
    private val cacheDir = File(System.getProperty("user.home"), ".gemini/antigravity/loomi_cache")
    private val imagesDir = File(cacheDir, "images")
    private val gson = Gson()

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
    }

    // Hash helper for image caching
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Save users list to cache
    fun saveUsers(users: List<SnapUser>) {
        try {
            val usersFile = File(cacheDir, "users_cache.json")
            val json = gson.toJson(users)
            usersFile.writeText(json)
        } catch (e: Exception) {
            println("LocalCacheManager: Error saving users: ${e.message}")
        }
    }

    // Load users list from cache
    fun loadUsers(): List<SnapUser> {
        return try {
            val usersFile = File(cacheDir, "users_cache.json")
            if (usersFile.exists()) {
                val json = usersFile.readText()
                val type = object : TypeToken<List<SnapUser>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("LocalCacheManager: Error loading users: ${e.message}")
            emptyList()
        }
    }

    // Save chat messages to cache
    fun saveMessages(chatId: String, messages: List<ChatMessage>) {
        try {
            val chatFile = File(cacheDir, "chat_$chatId.json")
            val json = gson.toJson(messages)
            chatFile.writeText(json)
        } catch (e: Exception) {
            println("LocalCacheManager: Error saving messages for chat $chatId: ${e.message}")
        }
    }

    // Load chat messages from cache
    fun loadMessages(chatId: String): List<ChatMessage> {
        return try {
            val chatFile = File(cacheDir, "chat_$chatId.json")
            if (chatFile.exists()) {
                val json = chatFile.readText()
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("LocalCacheManager: Error loading messages for chat $chatId: ${e.message}")
            emptyList()
        }
    }

    // Remove chat messages from cache
    fun removeChat(chatId: String) {
        try {
            val chatFile = File(cacheDir, "chat_$chatId.json")
            if (chatFile.exists()) {
                chatFile.delete()
            }
        } catch (e: Exception) {
            println("LocalCacheManager: Error removing chat $chatId: ${e.message}")
        }
    }

    // Image Caching methods
    fun getCachedImage(path: String): File? {
        if (path.isEmpty()) return null
        val key = md5(path)
        val file = File(imagesDir, key)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun saveImageBytes(path: String, bytes: ByteArray) {
        if (path.isEmpty() || bytes.isEmpty()) return
        try {
            val key = md5(path)
            val file = File(imagesDir, key)
            file.writeBytes(bytes)
        } catch (e: Exception) {
            println("LocalCacheManager: Error saving image cache: ${e.message}")
        }
    }

    // Clear all cache files (on logout or server verification / cloud removal)
    fun clearCache() {
        try {
            cacheDir.deleteRecursively()
            // Re-create folders
            cacheDir.mkdirs()
            imagesDir.mkdirs()
            println("LocalCacheManager: Cache cleared successfully.")
        } catch (e: Exception) {
            println("LocalCacheManager: Error clearing cache: ${e.message}")
        }
    }
}
