package com.imagine.livelingo.tts

import android.content.Context
import dev.ffmpegkit.kokoro.KokoroTTS
import kotlinx.coroutines.runBlocking
import java.io.File

/** Thin blocking bridge used only from LiveLingo's dedicated TTS worker thread. */
object KokoroBridge {
    @Volatile private var loadedPath: String? = null

    @JvmStatic
    @Synchronized
    fun ensureLoaded(context: Context, modelPath: String): Boolean {
        if (loadedPath == modelPath) return true
        val f = File(modelPath)
        if (!f.isFile || f.length() < 250_000_000L) return false
        release()
        runBlocking { KokoroTTS.initialize(context.applicationContext, modelPath) }
        loadedPath = modelPath
        return true
    }

    @JvmStatic
    @Synchronized
    fun synthesize(context: Context, modelPath: String, text: String): ByteArray? {
        if (text.isBlank()) return null
        if (!ensureLoaded(context, modelPath)) return null
        return runBlocking { KokoroTTS.speak(text).audioData }
    }

    @JvmStatic
    @Synchronized
    fun release() {
        if (loadedPath == null) return
        try { runBlocking { KokoroTTS.release() } } catch (_: Throwable) {}
        loadedPath = null
    }

    @JvmStatic fun isLoaded(): Boolean = loadedPath != null
}
