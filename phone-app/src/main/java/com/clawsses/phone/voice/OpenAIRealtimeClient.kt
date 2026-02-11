package com.clawsses.phone.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI Realtime API client for streaming speech-to-text transcription.
 *
 * Designed to behave like Android's SpeechRecognizer:
 * - Auto-finishes when the user stops speaking (via server-side VAD)
 * - Delivers final result on the main thread
 * - Handles no-speech and transcription timeouts
 * - Safe for repeated use (idempotent cleanup, AtomicBoolean guard)
 *
 * Audio pre-buffering: AudioRecord starts immediately when startListening() is called,
 * before the WebSocket connection is established. Audio is buffered in a ConcurrentLinkedQueue
 * and flushed to the server once the session is ready. This eliminates the ~500-800ms gap
 * where the user's first words would otherwise be lost.
 *
 * Multi-segment speech: The mic stays active after VAD detects a pause. If the user
 * continues speaking, more segments are accumulated. Final delivery happens after
 * DONE_TIMEOUT_MS of silence following the last transcription.
 *
 * Audio: 24kHz, 16-bit PCM, mono (required by OpenAI Realtime API).
 * Transcription: Whisper-1 via input_audio_transcription.
 * Server VAD detects speech end and auto-commits the audio buffer.
 * We disable auto-response generation (create_response: false) since we only need transcription.
 */
class OpenAIRealtimeClient {

    companion object {
        private const val TAG = "OpenAIRealtime"
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime"
        private const val MODEL = "gpt-4o-realtime-preview"

        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2

        // Send audio in small frames for responsive VAD (~20ms = 960 bytes at 24kHz 16-bit mono)
        private const val SEND_FRAME_BYTES = 960

        private const val NO_SPEECH_TIMEOUT_MS = 10_000L
        private const val TRANSCRIPTION_TIMEOUT_MS = 5_000L
        private const val DONE_TIMEOUT_MS = 1_000L
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Guard: only the first call to deliverFinalResult/deliverError takes effect
    private val resultDelivered = AtomicBoolean(false)

    private var speechDetected = false
    @Volatile private var currentlySpeaking = false

    // Audio pre-buffering: record starts before WebSocket is ready
    private val preBuffer = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var sessionReady = false

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var scope: CoroutineScope? = null

    @Volatile private var onPartialResult: ((String) -> Unit)? = null
    @Volatile private var onFinalResult: ((String) -> Unit)? = null
    @Volatile private var onError: ((String) -> Unit)? = null
    @Volatile private var onSpeechStopped: (() -> Unit)? = null

    private val transcriptLock = Any()
    private val accumulatedTranscript = StringBuilder()

    private var noSpeechTimeoutJob: Job? = null
    private var transcriptionTimeoutJob: Job? = null
    private var doneTimeoutJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Start a voice recognition session.
     *
     * Audio capture starts immediately (before WebSocket connects) and buffers PCM data.
     * Once the session is configured, buffered audio is flushed and streaming continues directly.
     *
     * @param apiKey OpenAI API key
     * @param languageTag BCP-47 language tag (e.g., "en-US", "nl-NL")
     * @param onPartial Callback for partial transcription (main thread)
     * @param onFinal Callback for final transcription (main thread)
     * @param onError Callback for errors (main thread)
     * @param onSpeechStopped Callback when VAD detects speech end (main thread), for "processing" UI
     */
    fun startListening(
        apiKey: String,
        languageTag: String? = null,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onSpeechStopped: (() -> Unit)? = null
    ) {
        if (_isListening.value) {
            Log.w(TAG, "Already listening, cleaning up first")
            cleanupSilently()
        }

        // Reset all state
        resultDelivered.set(false)
        speechDetected = false
        currentlySpeaking = false
        sessionReady = false
        preBuffer.clear()
        synchronized(transcriptLock) { accumulatedTranscript.clear() }

        this.onPartialResult = onPartial
        this.onFinalResult = onFinal
        this.onError = onError
        this.onSpeechStopped = onSpeechStopped

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        _connectionState.value = ConnectionState.Connecting
        _isListening.value = true

        // Start audio capture immediately — buffer until WebSocket session is ready
        // No-speech timeout starts later when session is configured (server can't detect speech until then)
        startAudioCapture()

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
                configureSession(webSocket, languageTag)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = t.message ?: "Connection failed"
                Log.e(TAG, "WebSocket failure: $errorMsg", t)
                deliverError(errorMsg)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                // If result wasn't delivered yet (unexpected close), deliver empty
                deliverFinalResult()
            }
        })
    }

    private fun configureSession(webSocket: WebSocket, languageTag: String?) {
        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply { put("text") })
                put("input_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                    if (languageTag != null) {
                        val isoLang = languageTag.split("-").firstOrNull()?.lowercase() ?: "en"
                        put("language", isoLang)
                    }
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 750)
                    put("create_response", false) // We only need transcription, not AI response
                })
            })
        }

        Log.d(TAG, "Sending session config")
        webSocket.send(sessionConfig.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "session.created" -> {
                    Log.i(TAG, "Session created, waiting for config confirmation")
                    // Don't start audio yet — wait for session.updated
                }

                "session.updated" -> {
                    Log.i(TAG, "Session configured, flushing pre-buffered audio")
                    sessionReady = true
                    // Recording coroutine will drain preBuffer on next iteration
                    startNoSpeechTimeout()
                }

                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech started")
                    speechDetected = true
                    currentlySpeaking = true
                    cancelNoSpeechTimeout()
                    cancelTranscriptionTimeout()  // Previous segment's timeout is irrelevant
                    cancelDoneTimeout()  // User is speaking again after a pause
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped — waiting for transcription (mic stays active)")
                    currentlySpeaking = false
                    // Server VAD auto-commits the buffer
                    // DON'T stop recording — user might continue speaking after a pause
                    // Notify caller so they can show "processing" state on glasses
                    val speechStoppedCallback = onSpeechStopped
                    mainHandler.post { speechStoppedCallback?.invoke() }
                    startTranscriptionTimeout()
                }

                "input_audio_buffer.committed" -> {
                    Log.d(TAG, "Audio buffer committed")
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    Log.i(TAG, "Transcription completed: ${transcript.take(100)}")
                    cancelTranscriptionTimeout()

                    if (transcript.isNotEmpty()) {
                        synchronized(transcriptLock) {
                            if (accumulatedTranscript.isNotEmpty()) {
                                accumulatedTranscript.append(" ")
                            }
                            accumulatedTranscript.append(transcript.trim())
                        }
                        // Send partial so glasses see the text before final delivery
                        val currentText = synchronized(transcriptLock) {
                            accumulatedTranscript.toString().trim()
                        }
                        val partialCallback = onPartialResult
                        mainHandler.post { partialCallback?.invoke(currentText) }
                    }

                    // Only start done timeout if user is NOT currently speaking.
                    // This transcription may be for a previous segment while the user
                    // has already started a new one.
                    if (!currentlySpeaking) {
                        startDoneTimeout()
                    } else {
                        Log.d(TAG, "User still speaking — not starting done timeout")
                    }
                }

                "conversation.item.input_audio_transcription.failed" -> {
                    val errorObj = json.optJSONObject("error")
                    val message = errorObj?.optString("message") ?: "Transcription failed"
                    Log.e(TAG, "Transcription failed: $message")
                    cancelTranscriptionTimeout()
                    // Wait for more speech or done timeout
                    startDoneTimeout()
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message") ?: "Unknown error"
                    val code = error?.optString("code") ?: ""
                    Log.e(TAG, "API error: $code - $message")
                    deliverError(message)
                }

                // Ignore response/conversation events — we only care about transcription
                else -> {
                    Log.v(TAG, "Event: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}", e)
        }
    }

    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        ) * BUFFER_SIZE_MULTIPLIER

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            deliverError("Failed to calculate audio buffer size")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                deliverError("Failed to initialize AudioRecord")
                return
            }

            audioRecord?.startRecording()
            Log.i(TAG, "Audio recording started (24kHz, 16-bit PCM) — pre-buffering until session ready")

            recordingJob = scope?.launch {
                // Read in small frames (~20ms) for responsive VAD detection.
                // AudioRecord's internal buffer is larger to prevent overflow,
                // but we send small chunks so the server gets a smooth stream.
                val readBuffer = ByteArray(SEND_FRAME_BYTES)
                while (isActive) {
                    val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        val chunk = readBuffer.copyOf(bytesRead)
                        if (!sessionReady) {
                            // Buffer audio until WebSocket session is configured
                            preBuffer.add(chunk)
                        } else {
                            // Drain any pre-buffered audio first
                            var buffered = preBuffer.poll()
                            while (buffered != null) {
                                sendAudioData(buffered)
                                buffered = preBuffer.poll()
                            }
                            // Then send current chunk
                            sendAudioData(chunk)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            deliverError("Microphone permission denied")
        } catch (e: Exception) {
            deliverError("Audio capture error: ${e.message}")
        }
    }

    private fun sendAudioData(audioData: ByteArray) {
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        try {
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send audio: ${e.message}")
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let { record ->
            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
            }
        }
        audioRecord = null
    }

    // --- Timeouts ---

    private fun startNoSpeechTimeout() {
        noSpeechTimeoutJob = scope?.launch {
            delay(NO_SPEECH_TIMEOUT_MS)
            Log.i(TAG, "No speech detected after ${NO_SPEECH_TIMEOUT_MS}ms — delivering empty result")
            deliverFinalResult()
        }
    }

    private fun cancelNoSpeechTimeout() {
        noSpeechTimeoutJob?.cancel()
        noSpeechTimeoutJob = null
    }

    private fun startTranscriptionTimeout() {
        transcriptionTimeoutJob?.cancel()
        transcriptionTimeoutJob = scope?.launch {
            delay(TRANSCRIPTION_TIMEOUT_MS)
            Log.w(TAG, "Transcription timeout after ${TRANSCRIPTION_TIMEOUT_MS}ms — delivering accumulated text")
            deliverFinalResult()
        }
    }

    private fun cancelTranscriptionTimeout() {
        transcriptionTimeoutJob?.cancel()
        transcriptionTimeoutJob = null
    }

    private fun startDoneTimeout() {
        doneTimeoutJob?.cancel()
        doneTimeoutJob = scope?.launch {
            delay(DONE_TIMEOUT_MS)
            Log.i(TAG, "No more speech after ${DONE_TIMEOUT_MS}ms — delivering final result")
            deliverFinalResult()
        }
    }

    private fun cancelDoneTimeout() {
        doneTimeoutJob?.cancel()
        doneTimeoutJob = null
    }

    // --- Result delivery ---

    /**
     * Deliver the final transcription result on the main thread.
     * Only the first call takes effect (guarded by AtomicBoolean).
     */
    private fun deliverFinalResult() {
        if (!resultDelivered.compareAndSet(false, true)) return

        val finalText = synchronized(transcriptLock) {
            accumulatedTranscript.toString().trim()
        }
        Log.i(TAG, "Delivering final result: '${finalText.take(100)}' (${finalText.length} chars)")

        val callback = onFinalResult
        mainHandler.post { callback?.invoke(finalText) }

        cleanupConnection()
    }

    /**
     * Deliver an error on the main thread.
     * Only the first call takes effect (guarded by AtomicBoolean).
     */
    private fun deliverError(message: String) {
        if (!resultDelivered.compareAndSet(false, true)) return

        Log.e(TAG, "Delivering error: $message")
        _connectionState.value = ConnectionState.Error(message)

        val callback = onError
        mainHandler.post { callback?.invoke(message) }

        cleanupConnection()
    }

    // --- Lifecycle ---

    /**
     * Stop voice recognition. Delivers any accumulated text as the final result.
     */
    fun stopListening() {
        Log.i(TAG, "stopListening() called")
        stopRecording()
        deliverFinalResult()
    }

    /**
     * Clean up connection resources. Idempotent — safe to call multiple times.
     */
    private fun cleanupConnection() {
        cancelNoSpeechTimeout()
        cancelTranscriptionTimeout()
        cancelDoneTimeout()
        stopRecording()

        sessionReady = false
        preBuffer.clear()

        webSocket?.let {
            try { it.close(1000, "done") } catch (_: Exception) {}
        }
        webSocket = null

        scope?.cancel()
        scope = null

        _isListening.value = false
        _connectionState.value = ConnectionState.Disconnected

        onPartialResult = null
        onFinalResult = null
        onError = null
        onSpeechStopped = null
    }

    /**
     * Silent cleanup without delivering results. Used when re-starting a new session.
     */
    private fun cleanupSilently() {
        cancelNoSpeechTimeout()
        cancelTranscriptionTimeout()
        cancelDoneTimeout()
        stopRecording()

        sessionReady = false
        preBuffer.clear()

        webSocket?.let {
            try { it.close(1000, "restart") } catch (_: Exception) {}
        }
        webSocket = null

        scope?.cancel()
        scope = null

        _isListening.value = false
        _connectionState.value = ConnectionState.Disconnected

        onPartialResult = null
        onFinalResult = null
        onError = null
        onSpeechStopped = null
    }

    /**
     * Force cleanup of all resources.
     */
    fun destroy() {
        cleanupSilently()
        client.dispatcher.executorService.shutdown()
    }
}
