package com.clawsses.phone.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.LocaleList
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

        /** Common languages to include in fallback so most users find their language. */
        private val COMMON_LOCALES = listOf(
            Locale("de", "DE"),  // German
            Locale("fr", "FR"),  // French
            Locale("es", "ES"),  // Spanish
            Locale("it", "IT"),  // Italian
            Locale("pt", "BR"),  // Portuguese (Brazil)
            Locale("pt", "PT"),  // Portuguese (Portugal)
            Locale("pl", "PL"),  // Polish
            Locale("tr", "TR"),  // Turkish
            Locale("ru", "RU"),  // Russian
            Locale("ja", "JP"),  // Japanese
            Locale("ko", "KR"),  // Korean
            Locale("zh", "CN"),  // Chinese (Simplified)
            Locale("ar", "SA"),  // Arabic
            Locale("hi", "IN"),  // Hindi
            Locale("sv", "SE"),  // Swedish
            Locale("da", "DK"),  // Danish
            Locale("nb", "NO"),  // Norwegian
            Locale("fi", "FI"),  // Finnish
            Locale("uk", "UA"),  // Ukrainian
            Locale("cs", "CZ"),  // Czech
            Locale("ro", "RO"),  // Romanian
            Locale("el", "GR"),  // Greek
            Locale("id", "ID"),  // Indonesian
            Locale("th", "TH"),  // Thai
            Locale("vi", "VN"),  // Vietnamese
        )

        private val FALLBACK_LOCALE = Locale("en", "US")

        /** Get the user's configured device languages (Android settings). */
        fun getDeviceLocales(): List<Locale> {
            val localeList = LocaleList.getDefault()
            return (0 until localeList.size()).map { localeList[it] }
        }
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
            _availableLanguages.value = buildFallbackList()
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
            _availableLanguages.value = buildFallbackList()
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
            // Fallback: device languages + preferred + common languages
            return buildFallbackList()
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

        // Partition: device languages + preferred first, then the rest sorted alphabetically
        val deviceLocales = getDeviceLocales()
        val topTags = (deviceLocales.map { it.toLanguageTag() } +
            PREFERRED_LOCALES.map { it.toLanguageTag() }).toCollection(LinkedHashSet())

        val top = topTags.mapNotNull { tag ->
            allOptions.firstOrNull { it.tag == tag }
                ?: Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() }?.toLanguageOption()
        }
        val rest = allOptions.filter { it.tag !in topTags }.sortedBy { it.displayName }

        return top + rest
    }

    /**
     * Build a language list when the device speech recognizer query fails.
     * Uses device-configured languages + preferred + common languages.
     */
    private fun buildFallbackList(): List<LanguageOption> {
        val deviceLocales = getDeviceLocales()

        // Device languages first, then preferred, then common — deduplicated
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<LanguageOption>()

        for (locale in deviceLocales + PREFERRED_LOCALES + COMMON_LOCALES) {
            val tag = locale.toLanguageTag()
            if (seen.add(tag)) {
                result.add(locale.toLanguageOption())
            }
        }

        return result
    }

    private fun Locale.toLanguageOption() = LanguageOption(
        locale = this,
        tag = toLanguageTag(),
        displayName = getDisplayName(this).replaceFirstChar { it.uppercase() }
    )
}
