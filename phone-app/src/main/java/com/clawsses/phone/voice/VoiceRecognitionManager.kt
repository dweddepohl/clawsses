package com.clawsses.phone.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages voice recognition with OpenAI Realtime as primary and Android SpeechRecognizer as fallback.
 *
 * Provides a unified interface for voice recognition with automatic fallback when OpenAI is
 * unavailable (no API key, network error, etc.).
 */
class VoiceRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecognitionMgr"
        private const val PREFS_NAME = "clawsses"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_VOICE_ENABLED = "openai_voice_enabled"
    }

    /**
     * Which voice recognition mode is currently active.
     */
    enum class RecognitionMode {
        NONE,           // Not currently listening
        OPENAI,         // Using OpenAI Realtime API
        FALLBACK        // Using Android's SpeechRecognizer
    }

    /**
     * Reason why we're using fallback instead of OpenAI.
     */
    enum class FallbackReason {
        NONE,                   // Not using fallback (using OpenAI)
        NO_API_KEY,             // No OpenAI API key configured
        DISABLED,               // OpenAI voice explicitly disabled in settings
        CONNECTION_FAILED,      // Failed to connect to OpenAI
        API_ERROR,              // OpenAI API returned an error
        PREFERENCE              // User prefers fallback
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val openAIClient = OpenAIRealtimeClient()
    private val fallbackHandler = VoiceCommandHandler(context)

    private val _activeMode = MutableStateFlow(RecognitionMode.NONE)
    val activeMode: StateFlow<RecognitionMode> = _activeMode.asStateFlow()

    private val _fallbackReason = MutableStateFlow(FallbackReason.NONE)
    val fallbackReason: StateFlow<FallbackReason> = _fallbackReason.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var onPartialResult: ((String) -> Unit)? = null
    var onSpeechStopped: (() -> Unit)? = null

    init {
        fallbackHandler.initialize()
    }

    /**
     * Check if OpenAI voice recognition is available and configured.
     */
    fun isOpenAIAvailable(): Boolean {
        val apiKey = getOpenAIApiKey()
        val enabled = isOpenAIVoiceEnabled()
        return apiKey.isNotEmpty() && enabled
    }

    /**
     * Get the stored OpenAI API key.
     */
    fun getOpenAIApiKey(): String {
        return prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
    }

    /**
     * Store the OpenAI API key securely.
     */
    fun setOpenAIApiKey(apiKey: String) {
        prefs.edit().putString(KEY_OPENAI_API_KEY, apiKey).apply()
        Log.i(TAG, "OpenAI API key ${if (apiKey.isNotEmpty()) "saved" else "cleared"}")
    }

    /**
     * Check if OpenAI voice recognition is enabled in settings.
     */
    fun isOpenAIVoiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_OPENAI_VOICE_ENABLED, true)
    }

    /**
     * Enable or disable OpenAI voice recognition.
     */
    fun setOpenAIVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OPENAI_VOICE_ENABLED, enabled).apply()
        Log.i(TAG, "OpenAI voice recognition ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Start voice recognition. Will use OpenAI if available, otherwise falls back to Android.
     *
     * @param languageTag BCP-47 language tag (e.g., "en-US", "nl-NL")
     * @param onResult Callback for the final result
     */
    fun startListening(
        languageTag: String? = null,
        onResult: (VoiceCommandHandler.VoiceResult) -> Unit
    ) {
        if (_isListening.value) {
            Log.w(TAG, "Already listening, stopping first")
            stopListening()
        }

        _isListening.value = true
        _lastError.value = null

        val apiKey = getOpenAIApiKey()
        val openAIEnabled = isOpenAIVoiceEnabled()

        if (apiKey.isEmpty()) {
            Log.i(TAG, "No OpenAI API key, using fallback")
            _fallbackReason.value = FallbackReason.NO_API_KEY
            startFallbackRecognition(languageTag, onResult)
            return
        }

        if (!openAIEnabled) {
            Log.i(TAG, "OpenAI voice disabled, using fallback")
            _fallbackReason.value = FallbackReason.DISABLED
            startFallbackRecognition(languageTag, onResult)
            return
        }

        // Try OpenAI first
        Log.i(TAG, "Starting OpenAI voice recognition")
        _activeMode.value = RecognitionMode.OPENAI
        _fallbackReason.value = FallbackReason.NONE

        openAIClient.startListening(
            apiKey = apiKey,
            languageTag = languageTag,
            onPartial = { partialText ->
                onPartialResult?.invoke(partialText)
            },
            onSpeechStopped = {
                onSpeechStopped?.invoke()
            },
            onFinal = { finalText ->
                Log.i(TAG, "OpenAI final result: ${finalText.take(100)}")
                _isListening.value = false
                _activeMode.value = RecognitionMode.NONE

                val result = if (finalText.isEmpty()) {
                    VoiceCommandHandler.VoiceResult.Text("")
                } else {
                    // Apply the same word mappings as fallback
                    processText(finalText)
                }
                onResult(result)
            },
            onError = { errorMessage ->
                Log.w(TAG, "OpenAI error: $errorMessage, falling back to Android")
                _lastError.value = errorMessage
                _fallbackReason.value = FallbackReason.API_ERROR

                // Fall back to Android speech recognition
                startFallbackRecognition(languageTag, onResult)
            }
        )
    }

    private fun startFallbackRecognition(
        languageTag: String?,
        onResult: (VoiceCommandHandler.VoiceResult) -> Unit
    ) {
        Log.i(TAG, "Starting fallback (Android) voice recognition")
        _activeMode.value = RecognitionMode.FALLBACK

        fallbackHandler.onPartialResult = { partialText ->
            onPartialResult?.invoke(partialText)
        }

        fallbackHandler.startListening(languageTag = languageTag) { result ->
            _isListening.value = false
            _activeMode.value = RecognitionMode.NONE
            onResult(result)
        }
    }

    /**
     * Process transcribed text with word mappings (same as VoiceCommandHandler).
     */
    private fun processText(text: String): VoiceCommandHandler.VoiceResult {
        val lowerText = text.lowercase().trim()

        // Check for special commands
        val commands = setOf(
            "escape", "scroll up", "scroll down", "take screenshot",
            "take photo", "switch mode", "navigate mode", "scroll mode", "command mode"
        )
        for (command in commands) {
            if (lowerText == command || lowerText.startsWith("$command ")) {
                return VoiceCommandHandler.VoiceResult.Command(command)
            }
        }

        // Apply word mappings
        val mappings = mapOf(
            "slash" to "/", "forward slash" to "/", "backslash" to "\\",
            "dot" to ".", "period" to ".", "comma" to ",", "colon" to ":",
            "semicolon" to ";", "dash" to "-", "hyphen" to "-", "underscore" to "_",
            "at" to "@", "at sign" to "@", "hash" to "#", "hashtag" to "#",
            "pound" to "#", "dollar" to "$", "dollar sign" to "$", "percent" to "%",
            "caret" to "^", "ampersand" to "&", "and sign" to "&", "asterisk" to "*",
            "star" to "*", "open paren" to "(", "close paren" to ")",
            "open bracket" to "[", "close bracket" to "]", "open brace" to "{",
            "close brace" to "}", "pipe" to "|", "tilde" to "~", "backtick" to "`",
            "quote" to "\"", "single quote" to "'", "apostrophe" to "'",
            "equals" to "=", "plus" to "+", "minus" to "-", "less than" to "<",
            "greater than" to ">", "space" to " ", "newline" to "\n",
            "enter" to "\n", "tab" to "\t"
        )

        var processedText = text
        for ((word, symbol) in mappings) {
            processedText = processedText.replace(
                Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE),
                symbol
            )
        }

        return VoiceCommandHandler.VoiceResult.Text(processedText)
    }

    /**
     * Stop any active voice recognition.
     */
    fun stopListening() {
        when (_activeMode.value) {
            RecognitionMode.OPENAI -> {
                openAIClient.stopListening()
            }
            RecognitionMode.FALLBACK -> {
                fallbackHandler.stopListening()
            }
            RecognitionMode.NONE -> {
                // Nothing to stop
            }
        }
        _isListening.value = false
        _activeMode.value = RecognitionMode.NONE
    }

    /**
     * Get a human-readable description of the current recognition mode.
     */
    fun getModeDescription(): String {
        return when (_activeMode.value) {
            RecognitionMode.OPENAI -> "OpenAI"
            RecognitionMode.FALLBACK -> {
                when (_fallbackReason.value) {
                    FallbackReason.NO_API_KEY -> "Device (no API key)"
                    FallbackReason.DISABLED -> "Device (OpenAI disabled)"
                    FallbackReason.CONNECTION_FAILED -> "Device (connection failed)"
                    FallbackReason.API_ERROR -> "Device (API error)"
                    else -> "Device"
                }
            }
            RecognitionMode.NONE -> "Idle"
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        stopListening()
        openAIClient.destroy()
        fallbackHandler.cleanup()
    }
}
