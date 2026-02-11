package com.clawsses.phone.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Manages TTS audio playback using MediaPlayer.
 * New messages interrupt current playback.
 */
class TtsPlaybackManager(
    private val context: Context,
    private val client: ElevenLabsClient,
    private val settings: TtsSettingsManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaPlayer: MediaPlayer? = null
    private var currentTempFile: File? = null

    /**
     * Speak the given text using ElevenLabs TTS.
     * Stops any current playback first.
     */
    fun speak(text: String) {
        if (!settings.isEnabled.value) {
            Log.d(TAG, "TTS disabled, skipping")
            return
        }

        val apiKey = settings.apiKey.value
        val voiceId = settings.selectedVoiceId.value

        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key configured")
            return
        }

        if (voiceId == null) {
            Log.w(TAG, "No voice selected")
            return
        }

        // Stop any current playback
        stop()

        scope.launch {
            try {
                Log.d(TAG, "Synthesizing TTS for text: ${text.take(50)}...")

                val result = client.synthesize(apiKey, voiceId, text)

                result.onSuccess { inputStream ->
                    // Write to temp file for MediaPlayer
                    val tempFile = File.createTempFile("tts_", ".mp3", context.cacheDir)
                    currentTempFile = tempFile

                    FileOutputStream(tempFile).use { output ->
                        inputStream.copyTo(output)
                    }
                    inputStream.close()

                    Log.d(TAG, "Audio saved to temp file: ${tempFile.absolutePath}")

                    // Play on main thread
                    launch(Dispatchers.Main) {
                        playAudioFile(tempFile)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "TTS synthesis failed", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during TTS", e)
            }
        }
    }

    private fun playAudioFile(file: File) {
        try {
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    Log.d(TAG, "Playback completed")
                    cleanup()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    cleanup()
                    true
                }
                prepare()
                start()
                Log.d(TAG, "Playback started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file", e)
            cleanup()
        }
    }

    /**
     * Stop current playback and cleanup.
     */
    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaPlayer", e)
        }
        mediaPlayer = null
        deleteTempFile()
    }

    private fun cleanup() {
        mediaPlayer?.release()
        mediaPlayer = null
        deleteTempFile()
    }

    private fun deleteTempFile() {
        currentTempFile?.let { file ->
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting temp file", e)
            }
        }
        currentTempFile = null
    }

    /**
     * Called when a chat message stream ends.
     * Speaks the message if TTS is enabled.
     */
    fun onMessageComplete(text: String) {
        if (settings.isEnabled.value && text.isNotBlank()) {
            speak(text)
        }
    }

    companion object {
        private const val TAG = "TtsPlaybackManager"
    }
}
