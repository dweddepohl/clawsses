package com.clawsses.phone.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI Realtime API client for streaming speech-to-text transcription.
 * Uses WebSocket connection with audio streaming for low-latency voice recognition.
 *
 * Audio format: 24kHz, 16-bit PCM, mono (required by OpenAI Realtime API)
 */
class OpenAIRealtimeClient {

    companion object {
        private const val TAG = "OpenAIRealtime"
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime"
        private const val MODEL = "gpt-4o-realtime-preview"

        // Audio settings for OpenAI Realtime API
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    sealed class TranscriptionResult {
        data class Partial(val text: String) : TranscriptionResult()
        data class Final(val text: String) : TranscriptionResult()
        data class Error(val message: String) : TranscriptionResult()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var scope: CoroutineScope? = null

    private var onPartialResult: ((String) -> Unit)? = null
    private var onFinalResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // No timeout for WebSocket
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var accumulatedTranscript = StringBuilder()

    /**
     * Start a voice recognition session.
     *
     * @param apiKey OpenAI API key
     * @param languageTag BCP-47 language tag (e.g., "en-US", "nl-NL")
     * @param onPartial Callback for partial transcription results
     * @param onFinal Callback for final transcription result
     * @param onError Callback for errors
     */
    fun startListening(
        apiKey: String,
        languageTag: String? = null,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isListening.value) {
            Log.w(TAG, "Already listening, stopping first")
            stopListening()
        }

        this.onPartialResult = onPartial
        this.onFinalResult = onFinal
        this.onError = onError
        this.accumulatedTranscript.clear()

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        _connectionState.value = ConnectionState.Connecting
        _isListening.value = true

        val url = "$REALTIME_URL?model=$MODEL"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.Connected

                // Configure the session for transcription-only mode
                configureSession(webSocket, languageTag)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = t.message ?: "Connection failed"
                Log.e(TAG, "WebSocket failure: $errorMsg", t)
                handleConnectionError(errorMsg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                cleanup()
            }
        })
    }

    private fun configureSession(webSocket: WebSocket, languageTag: String?) {
        // Create session configuration for speech-to-text only
        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                // We only want input audio transcription, not voice responses
                put("modalities", JSONArray().apply {
                    put("text")  // We want text output from transcription
                })
                put("input_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                    // Language hint if provided
                    if (languageTag != null) {
                        // Convert BCP-47 to ISO 639-1 (e.g., "en-US" -> "en")
                        val isoLang = languageTag.split("-").firstOrNull()?.lowercase() ?: "en"
                        put("language", isoLang)
                    }
                })
                // Disable voice response (we only want transcription)
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                })
            })
        }

        Log.d(TAG, "Sending session config: $sessionConfig")
        webSocket.send(sessionConfig.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "session.created" -> {
                    Log.i(TAG, "Session created, starting audio capture")
                    startAudioCapture()
                }

                "session.updated" -> {
                    Log.d(TAG, "Session updated")
                }

                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech started detected")
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped detected")
                    // Request transcription commit
                    commitAudioBuffer()
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    // Final transcription for an audio segment
                    val transcript = json.optString("transcript", "")
                    Log.i(TAG, "Transcription completed: $transcript")
                    if (transcript.isNotEmpty()) {
                        accumulatedTranscript.append(transcript).append(" ")
                        onPartialResult?.invoke(accumulatedTranscript.toString().trim())
                    }
                }

                "response.audio_transcript.delta" -> {
                    // Partial transcription update
                    val delta = json.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        accumulatedTranscript.append(delta)
                        onPartialResult?.invoke(accumulatedTranscript.toString().trim())
                    }
                }

                "response.audio_transcript.done" -> {
                    // Final transcript for this response
                    val transcript = json.optString("transcript", "")
                    Log.i(TAG, "Response transcript done: $transcript")
                }

                "input_audio_buffer.committed" -> {
                    Log.d(TAG, "Audio buffer committed")
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message") ?: "Unknown error"
                    val code = error?.optString("code") ?: ""
                    Log.e(TAG, "API error: $code - $message")
                    handleConnectionError(message)
                }

                else -> {
                    Log.v(TAG, "Received event: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}", e)
        }
    }

    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_MULTIPLIER

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            handleConnectionError("Failed to calculate audio buffer size")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                handleConnectionError("Failed to initialize AudioRecord")
                return
            }

            audioRecord?.startRecording()
            Log.i(TAG, "Audio recording started (24kHz, 16-bit PCM)")

            recordingJob = scope?.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && _isListening.value) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        sendAudioData(buffer.copyOf(bytesRead))
                    }
                }
            }
        } catch (e: SecurityException) {
            handleConnectionError("Microphone permission denied")
        } catch (e: Exception) {
            handleConnectionError("Audio capture error: ${e.message}")
        }
    }

    private fun sendAudioData(audioData: ByteArray) {
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        webSocket?.send(message.toString())
    }

    private fun commitAudioBuffer() {
        val message = JSONObject().apply {
            put("type", "input_audio_buffer.commit")
        }
        webSocket?.send(message.toString())
    }

    fun stopListening() {
        Log.i(TAG, "Stopping voice recognition")

        // Stop recording first
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
            }
        }
        audioRecord = null

        // Commit any remaining audio and get final result
        webSocket?.let { ws ->
            try {
                // Commit any buffered audio
                commitAudioBuffer()

                // Request response to get final transcription
                val responseRequest = JSONObject().apply {
                    put("type", "response.create")
                    put("response", JSONObject().apply {
                        put("modalities", JSONArray().put("text"))
                    })
                }
                ws.send(responseRequest.toString())

                // Give a moment for final transcription, then close
                scope?.launch {
                    delay(500)
                    finishSession()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during cleanup: ${e.message}")
                finishSession()
            }
        } ?: finishSession()
    }

    private fun finishSession() {
        val finalText = accumulatedTranscript.toString().trim()
        Log.i(TAG, "Final transcription: $finalText")

        // Invoke final result callback
        if (finalText.isNotEmpty()) {
            onFinalResult?.invoke(finalText)
        } else {
            onFinalResult?.invoke("")
        }

        cleanup()
    }

    private fun handleConnectionError(message: String) {
        Log.e(TAG, "Connection error: $message")
        _connectionState.value = ConnectionState.Error(message)
        onError?.invoke(message)
        cleanup()
    }

    private fun cleanup() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
            }
        }
        audioRecord = null

        webSocket?.close(1000, "Session ended")
        webSocket = null

        scope?.cancel()
        scope = null

        _isListening.value = false
        _connectionState.value = ConnectionState.Disconnected

        onPartialResult = null
        onFinalResult = null
        onError = null
    }

    /**
     * Check if the client is currently in a usable state for voice recognition.
     */
    fun isAvailable(): Boolean {
        return _connectionState.value != ConnectionState.Error("") &&
               !_isListening.value
    }

    /**
     * Force cleanup of all resources.
     */
    fun destroy() {
        cleanup()
        client.dispatcher.executorService.shutdown()
    }
}
