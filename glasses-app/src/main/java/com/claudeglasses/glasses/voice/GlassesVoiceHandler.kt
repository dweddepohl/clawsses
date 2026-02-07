package com.claudeglasses.glasses.voice

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages voice input state on the glasses.
 *
 * The glasses don't run speech recognition locally (YodaOS-Sprite lacks Google
 * Play Services). Instead, voice recognition is delegated to the phone app:
 *
 * 1. Glasses sends "start_voice" command to phone
 * 2. Phone calls setCommunicationDevice() to route glasses mic via Bluetooth
 * 3. Phone runs SpeechRecognizer and sends partial/final results back
 * 4. Glasses updates UI and forwards final text to server
 *
 * This class manages the UI state (Listening/Recognizing/Error/Idle) and
 * provides the bridge between gesture triggers and phone communication.
 */
class GlassesVoiceHandler {

    companion object {
        private const val TAG = "GlassesVoice"
    }

    /**
     * Voice recognition states for UI display
     */
    sealed class VoiceState {
        object Idle : VoiceState()
        object Listening : VoiceState()
        data class Recognizing(val partialText: String) : VoiceState()
        data class Error(val message: String) : VoiceState()
    }

    /**
     * Final voice recognition result (received from phone)
     */
    sealed class VoiceResult {
        data class Text(val text: String) : VoiceResult()
        data class Command(val command: String) : VoiceResult()
        data class Error(val message: String) : VoiceResult()
    }

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private var onResult: ((VoiceResult) -> Unit)? = null

    // Callback to send messages to phone
    var sendToPhone: ((String) -> Unit)? = null

    /**
     * Initialize the voice handler. Always returns true since recognition
     * is handled by the phone — we just manage UI state here.
     */
    fun initialize(): Boolean {
        Log.d(TAG, "Voice handler initialized (recognition delegated to phone)")
        return true
    }

    /**
     * Start voice input by notifying the phone to begin speech recognition.
     * The phone will route the glasses mic via setCommunicationDevice() and
     * run SpeechRecognizer, sending results back via voice_state/voice_result messages.
     */
    fun startListening(onResult: (VoiceResult) -> Unit) {
        this.onResult = onResult
        _voiceState.value = VoiceState.Listening

        Log.d(TAG, "Requesting phone to start voice recognition")
        sendToPhone?.invoke("""{"type":"start_voice"}""")
    }

    /**
     * Cancel voice recognition.
     */
    fun cancel() {
        Log.d(TAG, "Cancelling voice recognition")
        sendToPhone?.invoke("""{"type":"cancel_voice"}""")
        _voiceState.value = VoiceState.Idle
        onResult = null
    }

    /**
     * Handle voice state update from phone (partial results, state changes).
     */
    fun handleVoiceState(state: String, partialText: String = "") {
        when (state) {
            "listening" -> {
                _voiceState.value = VoiceState.Listening
                Log.d(TAG, "Phone: listening")
            }
            "recognizing" -> {
                _voiceState.value = VoiceState.Recognizing(partialText)
                Log.d(TAG, "Phone: recognizing '$partialText'")
            }
            "error" -> {
                _voiceState.value = VoiceState.Error(partialText)
                onResult?.invoke(VoiceResult.Error(partialText))
                onResult = null
                Log.e(TAG, "Phone: voice error: $partialText")
            }
            "idle" -> {
                _voiceState.value = VoiceState.Idle
            }
        }
    }

    /**
     * Handle final voice result from phone.
     */
    fun handleVoiceResult(type: String, text: String) {
        Log.d(TAG, "Phone voice result: type=$type, text='${text.take(100)}'")
        _voiceState.value = VoiceState.Idle

        val result = when (type) {
            "command" -> VoiceResult.Command(text)
            "text" -> VoiceResult.Text(text)
            "error" -> VoiceResult.Error(text)
            else -> VoiceResult.Text(text)
        }

        onResult?.invoke(result)
        onResult = null
    }

    /**
     * Check if currently listening or showing voice UI
     */
    fun isListening(): Boolean {
        return _voiceState.value is VoiceState.Listening ||
               _voiceState.value is VoiceState.Recognizing
    }

    /**
     * Check if voice state is showing (not idle)
     */
    fun isActive(): Boolean {
        return _voiceState.value !is VoiceState.Idle
    }

    /**
     * Simulate voice input for debug/emulator testing (keyboard input).
     */
    fun simulateVoiceInput(text: String, onResult: (VoiceResult) -> Unit) {
        Log.d(TAG, "Simulating voice input: $text")
        _voiceState.value = VoiceState.Recognizing(text)
        _voiceState.value = VoiceState.Idle
        onResult(VoiceResult.Text(text))
    }

    /**
     * Update the displayed partial text during simulated input.
     */
    fun updateSimulatedText(text: String) {
        _voiceState.value = if (text.isEmpty()) {
            VoiceState.Listening
        } else {
            VoiceState.Recognizing(text)
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        _voiceState.value = VoiceState.Idle
        onResult = null
        sendToPhone = null
        Log.d(TAG, "Voice handler cleaned up")
    }
}
