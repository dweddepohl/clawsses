package com.clawsses.phone.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages voice recognition language selection, persistence, and runtime locale querying.
 */
class VoiceLanguageManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceLanguage"
        private const val PREFS_NAME = "clawsses"
        private const val KEY_VOICE_LANGUAGE = "voice_language"

        /** Languages that should always appear at the top if supported by the device. */
        val PREFERRED_LOCALES = listOf(
            Locale("nl", "NL"),  // Dutch
            Locale("en", "US"),  // English (US)
        )

        private val FALLBACK_LOCALE = Locale("en", "US")
    }

    data class LanguageOption(
        val locale: Locale,
        val tag: String,        // e.g. "nl-NL", "en-US"
        val displayName: String // e.g. "Nederlands (Nederland)", "English (United States)"
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selectedLanguage = MutableStateFlow(loadPersistedLanguage())
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _availableLanguages = MutableStateFlow<List<LanguageOption>>(emptyList())
    val availableLanguages: StateFlow<List<LanguageOption>> = _availableLanguages.asStateFlow()

    private val _isLoadingLanguages = MutableStateFlow(false)
    val isLoadingLanguages: StateFlow<Boolean> = _isLoadingLanguages.asStateFlow()

    /**
     * Query the device for supported speech recognition languages.
     * Must be called from a context that can receive broadcast results.
     */
    fun queryAvailableLanguages() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available — using fallback list")
            _availableLanguages.value = PREFERRED_LOCALES.map { it.toLanguageOption() }
            return
        }

        _isLoadingLanguages.value = true

        val detailsIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)

        try {
            SpeechRecognizer.createSpeechRecognizer(context)?.let { recognizer ->
                // Use the details broadcast to get supported languages
                context.sendOrderedBroadcast(
                    detailsIntent,
                    null,
                    object : android.content.BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, broadcastIntent: Intent?) {
                            val extras = getResultExtras(true)
                            val supportedLanguages = extras.getStringArrayList(
                                RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES
                            )
                            val preferredLanguage = extras.getString(
                                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE
                            )

                            Log.i(TAG, "Device preferred language: $preferredLanguage")
                            Log.i(TAG, "Supported languages count: ${supportedLanguages?.size ?: 0}")

                            val options = buildLanguageList(supportedLanguages)
                            _availableLanguages.value = options
                            _isLoadingLanguages.value = false

                            // If no language was persisted, pick device default or en-US
                            if (_selectedLanguage.value.isEmpty()) {
                                val defaultTag = preferredLanguage
                                    ?: Locale.getDefault().toLanguageTag()
                                val matchOrFallback = options.firstOrNull { it.tag == defaultTag }?.tag
                                    ?: options.firstOrNull { it.tag.startsWith("en") }?.tag
                                    ?: FALLBACK_LOCALE.toLanguageTag()
                                selectLanguage(matchOrFallback)
                            }

                            recognizer.destroy()
                        }
                    },
                    null,
                    android.app.Activity.RESULT_OK,
                    null,
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying languages", e)
            _availableLanguages.value = PREFERRED_LOCALES.map { it.toLanguageOption() }
            _isLoadingLanguages.value = false
        }
    }

    fun selectLanguage(tag: String) {
        Log.i(TAG, "Selected voice language: $tag")
        _selectedLanguage.value = tag
        prefs.edit().putString(KEY_VOICE_LANGUAGE, tag).apply()
    }

    /**
     * Returns the locale tag to pass to RecognizerIntent.EXTRA_LANGUAGE.
     * Falls back to en-US if the persisted language is empty or unavailable.
     */
    fun getActiveLanguageTag(): String {
        val tag = _selectedLanguage.value
        if (tag.isNotEmpty()) return tag

        val deviceTag = Locale.getDefault().toLanguageTag()
        return deviceTag.ifEmpty { FALLBACK_LOCALE.toLanguageTag() }
    }

    private fun loadPersistedLanguage(): String {
        return prefs.getString(KEY_VOICE_LANGUAGE, "") ?: ""
    }

    private fun buildLanguageList(supported: List<String>?): List<LanguageOption> {
        if (supported.isNullOrEmpty()) {
            // Fallback: offer preferred locales only
            return PREFERRED_LOCALES.map { it.toLanguageOption() }
        }

        val allOptions = supported.mapNotNull { tag ->
            try {
                val locale = Locale.forLanguageTag(tag)
                if (locale.language.isEmpty()) null
                else LanguageOption(
                    locale = locale,
                    tag = tag,
                    displayName = locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
                )
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.tag }

        // Partition: preferred locales first, then the rest sorted alphabetically
        val preferredTags = PREFERRED_LOCALES.map { it.toLanguageTag() }.toSet()
        val preferred = PREFERRED_LOCALES.mapNotNull { pref ->
            allOptions.firstOrNull { it.tag == pref.toLanguageTag() }
                ?: if (preferredTags.contains(pref.toLanguageTag())) {
                    // Include even if not in device list (spec says "must always appear")
                    pref.toLanguageOption()
                } else null
        }
        val rest = allOptions.filter { it.tag !in preferredTags }.sortedBy { it.displayName }

        return preferred + rest
    }

    private fun Locale.toLanguageOption() = LanguageOption(
        locale = this,
        tag = toLanguageTag(),
        displayName = getDisplayName(this).replaceFirstChar { it.uppercase() }
    )
}
