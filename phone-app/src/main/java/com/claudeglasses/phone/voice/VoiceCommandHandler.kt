package com.claudeglasses.phone.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles voice commands and converts speech to terminal input
 */
class VoiceCommandHandler(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommand"

        // Word mappings for common terminal symbols
        private val WORD_MAPPINGS = mapOf(
            "slash" to "/",
            "forward slash" to "/",
            "backslash" to "\\",
            "back slash" to "\\",
            "dot" to ".",
            "period" to ".",
            "comma" to ",",
            "colon" to ":",
            "semicolon" to ";",
            "dash" to "-",
            "hyphen" to "-",
            "underscore" to "_",
            "at" to "@",
            "at sign" to "@",
            "hash" to "#",
            "hashtag" to "#",
            "pound" to "#",
            "dollar" to "$",
            "dollar sign" to "$",
            "percent" to "%",
            "caret" to "^",
            "ampersand" to "&",
            "and sign" to "&",
            "asterisk" to "*",
            "star" to "*",
            "open paren" to "(",
            "close paren" to ")",
            "open bracket" to "[",
            "close bracket" to "]",
            "open brace" to "{",
            "close brace" to "}",
            "pipe" to "|",
            "tilde" to "~",
            "backtick" to "`",
            "quote" to "\"",
            "single quote" to "'",
            "apostrophe" to "'",
            "equals" to "=",
            "plus" to "+",
            "minus" to "-",
            "less than" to "<",
            "greater than" to ">",
            "space" to " ",
            "newline" to "\n",
            "enter" to "\n",
            "tab" to "\t"
        )

        // Special commands that trigger actions instead of text input
        private val SPECIAL_COMMANDS = setOf(
            "escape",
            "scroll up",
            "scroll down",
            "take screenshot",
            "take photo",
            "switch mode",
            "navigate mode",
            "scroll mode",
            "command mode"
        )
    }

    sealed class VoiceResult {
        data class Text(val text: String) : VoiceResult()
        data class Command(val command: String) : VoiceResult()
        data class Error(val message: String) : VoiceResult()
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastResult = MutableStateFlow<VoiceResult?>(null)
    val lastResult: StateFlow<VoiceResult?> = _lastResult.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var onResult: ((VoiceResult) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
    }

    fun startListening(onResult: (VoiceResult) -> Unit) {
        this.onResult = onResult
        _isListening.value = true

        // Always recreate the SpeechRecognizer for each session to avoid stale state
        // (e.g. ERROR_RECOGNIZER_BUSY after a previous error)
        resetRecognizer()

        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer still null after init — recognition unavailable")
            _isListening.value = false
            onResult(VoiceResult.Error("Speech recognition unavailable"))
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            Log.i(TAG, "Starting SpeechRecognizer.startListening()")
            speechRecognizer?.startListening(intent)
            Log.i(TAG, "SpeechRecognizer.startListening() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            _isListening.value = false
            onResult(VoiceResult.Error("Failed to start: ${e.message}"))
        }
    }

    /**
     * Destroy and recreate the SpeechRecognizer.
     * Android's SpeechRecognizer can get stuck after errors, so we always start fresh.
     */
    private fun resetRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying old recognizer", e)
        }
        speechRecognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
        Log.i(TAG, "SpeechRecognizer recreated fresh")
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, ">>> Ready for speech (mic is active)")
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, ">>> Speech started (audio detected)")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            _isListening.value = false
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                else -> "Unknown error: $error"
            }
            _isListening.value = false

            // NO_MATCH and SPEECH_TIMEOUT are normal — user just didn't speak (in time)
            // Treat them as empty text result instead of error
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Log.i(TAG, ">>> No speech detected (error $error) — treating as empty result")
                val result = VoiceResult.Text("")
                _lastResult.value = result
                onResult?.invoke(result)
            } else {
                Log.e(TAG, ">>> Recognition error ($error): $errorMessage")
                val result = VoiceResult.Error(errorMessage)
                _lastResult.value = result
                onResult?.invoke(result)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spokenText = matches?.firstOrNull() ?: ""

            Log.i(TAG, ">>> Final recognition result: '$spokenText'")

            val processedResult = processSpokenText(spokenText)
            _lastResult.value = processedResult
            onResult?.invoke(processedResult)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partialText = matches?.firstOrNull() ?: ""
            if (partialText.isNotEmpty()) {
                Log.d(TAG, "Partial: $partialText")
                onPartialResult?.invoke(partialText)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Process spoken text, converting word mappings and detecting special commands
     */
    private fun processSpokenText(spokenText: String): VoiceResult {
        val lowerText = spokenText.lowercase().trim()

        // Check for special commands first
        for (command in SPECIAL_COMMANDS) {
            if (lowerText == command || lowerText.startsWith("$command ")) {
                return VoiceResult.Command(command)
            }
        }

        // Apply word mappings
        var processedText = spokenText
        for ((word, symbol) in WORD_MAPPINGS) {
            // Replace whole words only (case insensitive)
            processedText = processedText.replace(
                Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE),
                symbol
            )
        }

        return VoiceResult.Text(processedText)
    }

    fun cleanup() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
