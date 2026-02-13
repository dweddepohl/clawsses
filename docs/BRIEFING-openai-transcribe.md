# Briefing: Voice Recognition Upgrade - OpenAI Realtime Transcription

## Doel

De standaard Android `SpeechRecognizer` in de Clawsses phone-app vervangen door OpenAI's Realtime Transcription API (`gpt-4o-mini-transcribe`) voor significant betere accuraatheid en snellere first-word latency.

## Waarom

De huidige Android voice recognition heeft twee grote problemen:
1. **Slechte accuraatheid** - vooral in het Nederlands, maar ook in Engels worden woorden regelmatig verkeerd herkend
2. **Trage first-word latency** - het duurt te lang voordat het eerste woord op de bril verschijnt

OpenAI's `gpt-4o-transcribe` scoort ~2.5% WER (word error rate) in benchmarks - veruit de beste beschikbare speech recognition. De `mini` variant is bijna net zo goed, goedkoper, en snel genoeg voor real-time streaming.

## Huidige Architectuur

```
Rokid Glasses (4-mic array)
    │ Bluetooth CXR protocol
    ▼
Phone App (Android) ─── com.clawsses.phone
    │ android.speech.SpeechRecognizer
    │   → onPartialResults() → glasses HUD (live dictation preview)
    │   → onResults() → processSpokenText() → OpenClaw Gateway chat.send
    ▼
OpenClaw Gateway (WebSocket, ws://host:18789)
    → AI agent session
```

### Relevante bestanden

| File | Wat het doet |
|------|-------------|
| `phone-app/.../voice/VoiceCommandHandler.kt` | Core speech recognition - wraps Android SpeechRecognizer |
| `phone-app/.../voice/VoiceLanguageManager.kt` | Taalkeuzem NL/EN toggle, persistence, device language query |
| `phone-app/.../ui/screens/MainScreen.kt` | Wiring - roept voiceHandler aan, stuurt resultaten naar bril + gateway |
| `phone-app/.../service/GlassesConnectionService.kt` | Foreground service |
| `glasses-app/.../voice/GlassesVoiceHandler.kt` | Bril-side voice delegation naar phone |

### Hoe voice nu werkt

1. Gebruiker long-presst de AI scene button op de bril
2. Bril stuurt `start_voice` message naar phone via CXR protocol
3. Phone maakt een `SpeechRecognizer` aan en start listening
4. Audio komt via Bluetooth SCO van de bril's 4-mic array
5. Android SpeechRecognizer verwerkt de audio
6. `onPartialResults()` → phone stuurt `voice_state` naar bril (live preview op HUD)
7. `onResults()` → `processSpokenText()` (word mappings + special commands) → `chat.send` naar OpenClaw Gateway
8. Phone stuurt `voice_result` naar bril (final text)

### Interface van VoiceCommandHandler

```kotlin
class VoiceCommandHandler(context: Context) {
    val isListening: StateFlow<Boolean>
    val lastResult: StateFlow<VoiceResult?>
    var onPartialResult: ((String) -> Unit)?
    
    fun initialize()
    fun startListening(languageTag: String? = null, onResult: (VoiceResult) -> Unit)
    fun stopListening()
    fun cleanup()
}

sealed class VoiceResult {
    data class Text(val text: String) : VoiceResult()
    data class Command(val command: String) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
}
```

MainScreen.kt gebruikt deze interface op ~15 plekken, altijd via dezelfde pattern:
- `voiceHandler.startListening(languageTag = ...) { result -> ... }`
- `voiceHandler.onPartialResult = { partialText -> ... }`
- `voiceHandler.isListening.collectAsState()`
- `voiceHandler.stopListening()` / `voiceHandler.cleanup()`

### VoiceLanguageManager

Er is al een `VoiceLanguageManager` die:
- NL/EN taalvoorkeur beheert (persisted in SharedPreferences)
- Device-supported talen opvraagt
- `getActiveLanguageTag()` returned (bijv. `"nl-NL"` of `"en-US"`)

Deze class kan hergebruikt worden - de taalcode moet alleen vertaald worden naar ISO-639-1 voor de OpenAI API (`"nl"` i.p.v. `"nl-NL"`).

## Nieuwe Architectuur

```
Rokid Glasses (4-mic array)
    │ Bluetooth CXR protocol
    ▼
Phone App (Android) ─── com.clawsses.phone
    │ AudioRecord (raw PCM, 24kHz mono)
    │   → WebSocket naar OpenAI Realtime Transcription API
    │   ← delta events → glasses HUD (live dictation preview)
    │   ← completed events → processSpokenText() → OpenClaw Gateway chat.send
    ▼
OpenClaw Gateway (WebSocket)
    → AI agent session
```

## Implementatie Plan

### Stap 1: Nieuwe class `OpenAITranscribeEngine.kt`

Locatie: `phone-app/src/main/java/com/clawsses/phone/voice/OpenAITranscribeEngine.kt`

Biedt **exact dezelfde interface** als `VoiceCommandHandler`:

```kotlin
class OpenAITranscribeEngine(private val context: Context) {
    
    val isListening: StateFlow<Boolean>
    val lastResult: StateFlow<VoiceResult?>
    var onPartialResult: ((String) -> Unit)?
    
    fun initialize() { /* setup OkHttp WebSocket client */ }
    fun startListening(languageTag: String? = null, onResult: (VoiceResult) -> Unit) { ... }
    fun stopListening() { ... }
    fun cleanup() { ... }
}
```

Hergebruik `VoiceCommandHandler.VoiceResult` sealed class (of verplaats naar apart bestand).

### Stap 2: OpenAI Realtime Transcription WebSocket

**Endpoint:** `wss://api.openai.com/v1/realtime?model=gpt-4o-mini-transcribe`

**Headers:**
```
Authorization: Bearer <OPENAI_API_KEY>
```

**Session configuratie** (stuur als eerste bericht na connect):
```json
{
    "type": "session.update",
    "session": {
        "type": "transcription",
        "audio": {
            "input": {
                "format": {
                    "type": "audio/pcm",
                    "rate": 24000
                },
                "noise_reduction": {
                    "type": "near_field"
                },
                "transcription": {
                    "model": "gpt-4o-mini-transcribe",
                    "language": "nl"
                },
                "turn_detection": {
                    "type": "server_vad",
                    "threshold": 0.5,
                    "prefix_padding_ms": 300,
                    "silence_duration_ms": 500
                }
            }
        }
    }
}
```

**Taal instellen:** Vertaal de `languageTag` parameter (bijv. `"nl-NL"`) naar ISO-639-1 (`"nl"`):
```kotlin
val isoLang = languageTag?.split("-")?.firstOrNull() ?: "en"
```

**Audio streamen** (continu zolang mic actief):
```json
{
    "type": "input_audio_buffer.append",
    "audio": "<base64 encoded PCM audio chunk>"
}
```

**Events om te luisteren:**

| Server Event | Mapping naar huidige interface |
|-------------|-------------------------------|
| `conversation.item.input_audio_transcription.delta` | `onPartialResult?.invoke(delta)` - live preview op bril HUD |
| `conversation.item.input_audio_transcription.completed` | `onResult(VoiceResult.Text(transcript))` via `processSpokenText()` |
| `input_audio_buffer.speech_started` | `_isListening.value = true` |
| `input_audio_buffer.speech_stopped` | UI update |
| `error` | `onResult(VoiceResult.Error(message))` |

**Let op:** Voor `gpt-4o-mini-transcribe` bevat het `delta` event incrementele tekst (niet de volle tekst). Je moet deltas accumuleren voor de partial result string die naar de bril gaat.

### Stap 3: Audio Capture

Vervang de impliciete Android SpeechRecognizer audio capture door directe `AudioRecord`:

```kotlin
private val sampleRate = 24000  // Vereist door OpenAI
private val bufferSize = AudioRecord.getMinBufferSize(
    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
)

val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Pakt BT SCO audio
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
)
```

**Bluetooth SCO:** De bril's 4-mic array wordt via Bluetooth SCO gerouteerd. De huidige flow:
1. CXR SDK setup maakt de Bluetooth verbinding
2. `AudioManager.startBluetoothSco()` routeert audio naar BT
3. `VOICE_COMMUNICATION` source pakt automatisch het SCO kanaal

Dit moet behouden blijven. Check of BT SCO al gestart wordt door de bestaande code (in `GlassesConnectionService` of `RokidSdkManager`). Zo niet, voeg toe:

```kotlin
val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
audioManager.startBluetoothSco()
audioManager.isBluetoothScoOn = true
```

**Streaming loop** (in een coroutine/thread):
```kotlin
val buffer = ByteArray(2400 * 2)  // 100ms @ 24kHz, 16-bit
while (isRecording) {
    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
    if (bytesRead > 0) {
        val base64Audio = Base64.encodeToString(buffer.copyOf(bytesRead), Base64.NO_WRAP)
        webSocket.send("""{"type":"input_audio_buffer.append","audio":"$base64Audio"}""")
    }
}
```

### Stap 4: API Key Management

Voeg toe aan `phone-app/build.gradle.kts`:

```kotlin
buildConfigField("String", "OPENAI_API_KEY", 
    "\"${localProperties.getProperty("openai.apiKey", "")}\"")
```

En in `local.properties`:
```properties
openai.apiKey=sk-...
```

### Stap 5: Swap in MainScreen.kt

Minimale wijziging:

```kotlin
// Was:
val voiceHandler = remember { VoiceCommandHandler(context) }

// Wordt:
val voiceHandler = remember { OpenAITranscribeEngine(context) }
```

**Niets anders in MainScreen.kt hoeft te veranderen** als de interface identiek is.

### Stap 6: Fallback bij geen internet

```kotlin
val voiceHandler = remember {
    val apiKey = BuildConfig.OPENAI_API_KEY
    if (apiKey.isNotEmpty() && isNetworkAvailable(context)) {
        OpenAITranscribeEngine(context)
    } else {
        VoiceCommandHandler(context)  // Offline fallback
    }
}
```

## Dependencies

Geen nieuwe dependencies nodig. OkHttp 4.12.0 zit er al in voor de OpenClaw Gateway WebSocket. `android.util.Base64` is standaard Android.

## Kosten

| Model | Prijs | Accuraatheid |
|-------|-------|-------------|
| `gpt-4o-mini-transcribe` | ~$0.01-0.03/min | Zeer goed |
| `gpt-4o-transcribe` | ~$0.06/min | Beste beschikbaar (~2.5% WER) |

Bij normaal gebruik (paar minuten voice per dag): **< $1/maand**.

Start met `gpt-4o-mini-transcribe`. Kan later opgeschaald worden naar `gpt-4o-transcribe` door alleen de model string te wijzigen.

## Samenvatting Wijzigingen

| File | Wijziging |
|------|-----------|
| `voice/OpenAITranscribeEngine.kt` | **NIEUW** (~200-250 regels) |
| `voice/VoiceCommandHandler.kt` | Ongewijzigd (behouden als offline fallback) |
| `voice/VoiceLanguageManager.kt` | Ongewijzigd (hergebruiken voor taalvoorkeur) |
| `ui/screens/MainScreen.kt` | 1 regel: `VoiceCommandHandler` → `OpenAITranscribeEngine` |
| `build.gradle.kts` | 1 regel: BuildConfig field voor API key |
| `local.properties` | 1 regel: `openai.apiKey=sk-...` |

## Word Mappings & Special Commands

De bestaande `processSpokenText()` functie in `VoiceCommandHandler` bevat word-to-symbol mappings (bijv. "slash" → "/") en special commands (bijv. "escape", "scroll up"). Deze logica moet:
- Ofwel verplaatst worden naar een shared utility functie
- Ofwel gekopieerd worden naar `OpenAITranscribeEngine`
- Ofwel: `OpenAITranscribeEngine` returned raw text en de caller doet de processing (dan moet MainScreen.kt aangepast)

Aanbeveling: verplaats `processSpokenText()` naar een companion object of top-level functie die door beide engines gebruikt wordt.

## Referenties

- [OpenAI Realtime Transcription Guide](https://platform.openai.com/docs/guides/realtime-transcription)
- [Realtime API Reference](https://platform.openai.com/docs/api-reference/realtime)
- [Realtime WebSocket Connection](https://platform.openai.com/docs/guides/realtime-websocket)
- [Supported transcription models](https://platform.openai.com/docs/models/gpt-4o-transcribe): `whisper-1`, `gpt-4o-transcribe`, `gpt-4o-mini-transcribe`
- [Audio format](https://platform.openai.com/docs/guides/realtime-transcription#session-fields): PCM 24kHz mono, G.711 μ-law, G.711 A-law
