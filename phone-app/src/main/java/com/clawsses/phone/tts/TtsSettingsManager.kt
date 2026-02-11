package com.clawsses.phone.tts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages TTS settings persistence and reactive state.
 */
class TtsSettingsManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedVoiceId = MutableStateFlow(prefs.getString(KEY_VOICE_ID, null))
    val selectedVoiceId: StateFlow<String?> = _selectedVoiceId.asStateFlow()

    private val _selectedVoiceName = MutableStateFlow(prefs.getString(KEY_VOICE_NAME, null))
    val selectedVoiceName: StateFlow<String?> = _selectedVoiceName.asStateFlow()

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    fun setApiKey(key: String) {
        _apiKey.value = key
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun setSelectedVoice(id: String, name: String) {
        _selectedVoiceId.value = id
        _selectedVoiceName.value = name
        prefs.edit()
            .putString(KEY_VOICE_ID, id)
            .putString(KEY_VOICE_NAME, name)
            .apply()
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Check if TTS is properly configured (has API key and voice selected).
     */
    fun isConfigured(): Boolean {
        return _apiKey.value.isNotBlank() && _selectedVoiceId.value != null
    }

    companion object {
        private const val PREFS_NAME = "clawsses"
        private const val KEY_API_KEY = "tts_api_key"
        private const val KEY_VOICE_ID = "tts_voice_id"
        private const val KEY_VOICE_NAME = "tts_voice_name"
        private const val KEY_ENABLED = "tts_enabled"
    }
}
