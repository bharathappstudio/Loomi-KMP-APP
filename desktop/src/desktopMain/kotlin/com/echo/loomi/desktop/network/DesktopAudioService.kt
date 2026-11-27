package com.echo.loomi.desktop.network

import javax.sound.sampled.*
import kotlinx.coroutines.*

/**
 * Manages system microphone input and audio playback on JVM Desktop during active calls.
 */
object DesktopAudioService {
    private var targetDataLine: TargetDataLine? = null
    private var sourceDataLine: SourceDataLine? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startCallAudio() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                val format = AudioFormat(16000f, 16, 1, true, true)

                // System microphone input (TargetDataLine)
                val targetInfo = DataLine.Info(TargetDataLine::class.java, format)
                if (AudioSystem.isLineSupported(targetInfo)) {
                    targetDataLine = AudioSystem.getLine(targetInfo) as TargetDataLine
                    targetDataLine?.open(format)
                    targetDataLine?.start()
                }

                // System speaker output (SourceDataLine)
                val sourceInfo = DataLine.Info(SourceDataLine::class.java, format)
                if (AudioSystem.isLineSupported(sourceInfo)) {
                    sourceDataLine = AudioSystem.getLine(sourceInfo) as SourceDataLine
                    sourceDataLine?.open(format)
                    sourceDataLine?.start()
                }

                val buffer = ByteArray(1024)
                while (isRunning) {
                    val bytesRead = targetDataLine?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        sourceDataLine?.write(buffer, 0, bytesRead)
                    }
                    delay(10)
                }
            } catch (e: Exception) {
                println("DesktopAudioService Error: ${e.message}")
            }
        }
    }

    fun stopCallAudio() {
        isRunning = false
        try {
            targetDataLine?.stop()
            targetDataLine?.close()
            sourceDataLine?.stop()
            sourceDataLine?.close()
        } catch (e: Exception) {
            println("DesktopAudioService Cleanup Error: ${e.message}")
        }
        targetDataLine = null
        sourceDataLine = null
    }
}
