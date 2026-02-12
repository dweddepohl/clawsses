# Clawsses

An AI glasses companion for [OpenClaw](https://github.com/openclaw/openclaw). Talk to your AI assistant through AR glasses - see responses stream in your field of view, speak commands hands-free, and snap photos for visual context. Built for [Rokid AR Lite](https://www.rokid.com/en/product/ar-lite/) glasses.

## What It Does

Clawsses connects your AR glasses to an OpenClaw Gateway, giving you a wearable AI interface:

- **Voice-first interaction** - Long-press to speak, see the response appear in your HUD
- **Live streaming** - AI responses stream token-by-token onto the glasses display
- **Camera input** - Snap photos through the glasses camera and attach them to your message ("what am I looking at?")
- **Session management** - Switch between multiple OpenClaw sessions from the glasses
- **Text-to-speech** - Hear responses read aloud via ElevenLabs, controllable from the glasses
- **Wake-on-message** - Glasses display wakes automatically when new messages arrive
- **Slash commands** - Quick access to OpenClaw commands (`/model`, `/clear`, `/status`, etc.)

### Screenshots

<p align="center">
  <img src="docs/images/Screenshot_20260111_154026.png" width="280" alt="Session picker">
  <img src="docs/images/Screenshot_20260111_154323.png" width="280" alt="Voice input">
  <img src="docs/images/Screenshot_20260111_154114.png" width="280" alt="Streaming response">
</p>

## Architecture

The system is three components: a **phone app** that bridges everything, a **glasses app** that runs the HUD, and an **OpenClaw Gateway** that provides the AI backend.

```
OpenClaw Gateway ←─ WebSocket ──→ Phone App (Android) ←─ Bluetooth CXR ──→ Glasses App (Rokid)
      │                                │                                        │
  AI sessions                    Bridge + voice                          HUD + gestures
  Chat streaming                 TTS playback                           Camera capture
  Tool execution                 Wake management                        Session picker
```

### Modules

| Module | Description |
|--------|-------------|
| **phone-app/** | Android companion app. Connects to OpenClaw Gateway via WebSocket and to glasses via Rokid CXR-M SDK (Bluetooth). Handles voice recognition, TTS playback, wake signal coordination, and glasses APK sideloading. |
| **glasses-app/** | HUD app running on Rokid glasses. Renders chat UI with Jetpack Compose on the 480x640 monochrome green micro-LED display. Handles touchpad gestures and camera capture. |
| **shared/** | Protocol definitions (Gson-serialized data classes) used by both apps. |

## Setup

### Prerequisites

- Android Studio
- Rokid AR Lite glasses (or emulator - see below)
- Rokid developer account (for CXR SDK credentials)
- A running [OpenClaw](https://github.com/openclaw/openclaw) Gateway

### 1. SDK Credentials

Create `local.properties` in the project root with your Rokid CXR SDK credentials:

```properties
rokid.clientSecret=your-client-secret
rokid.accessKey=your-access-key
```

These are injected as `BuildConfig` fields at compile time and are required for Bluetooth pairing with the glasses.

### 2. Build & Install

```bash
# Build both apps (glasses APK is bundled into phone app assets automatically)
./gradlew assembleDebug

# Install phone app
adb install phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

The phone app bundles the glasses APK and can push it to the glasses over WiFi P2P - no developer cable needed.

### 3. Connect

1. Open the phone app and configure your OpenClaw Gateway host, port, and token in Settings
2. Scan for and connect to your Rokid glasses via Bluetooth
3. The glasses HUD will show the connection status and your current session

## Usage

### Voice Input

Long-press on the glasses touchpad (or side button) to start voice recognition.

Two speech recognition backends are supported:
- **OpenAI Realtime API** (primary) - streaming transcription via Whisper with server-side VAD, multi-segment speech support, and audio pre-buffering for zero-latency start
- **Android SpeechRecognizer** (fallback) - used automatically when no OpenAI API key is configured

Configure your OpenAI API key in Settings > Voice to enable the primary backend.

### Touchpad Gestures

The glasses touchpad has two focus areas that change what gestures do:

| Gesture | Content Area | Menu Bar |
|---------|-------------|----------|
| **Swipe forward** (→ eyes) | Scroll up | Previous menu item |
| **Swipe backward** (→ ear) | Scroll down | Next menu item |
| **Tap** | Jump to bottom | Execute menu action |
| **Double-tap** | Switch to Menu | Switch to Content |
| **Long-press** | Voice input | Voice input |

### Menu Bar

| Item | Action |
|------|--------|
| 📷 Photo | Capture a photo to attach to your next message (up to 4) |
| ◎ Session | Open session picker - browse, switch, or create sessions |
| █ Size | Cycle HUD position: Full → Bottom Half → Top Half |
| … More | Font size, slash commands, toggle voice responses |

### Camera

Tap the Photo menu item to capture an image through the glasses camera. Photos are attached to your next voice message as base64-encoded images, enabling multimodal AI interactions ("what does this error message say?", "describe what I'm looking at").

### Text-to-Speech

Toggle voice responses from the More menu on the glasses. When enabled, AI responses are spoken aloud via ElevenLabs TTS. Configure your ElevenLabs API key and preferred voice in the phone app Settings.

### Wake-on-Message

When new content arrives (streaming responses, proactive messages, cron notifications), the phone automatically wakes the glasses display via the CXR SDK and delivers buffered messages once the glasses acknowledge readiness. A keep-alive mechanism prevents the display from sleeping during long streaming responses.

## Display

The Rokid AR Lite uses JBD 0.13" micro-LED displays:
- **Resolution:** 480x640 (portrait)
- **Color:** Monochrome green on transparent AR waveguide
- **Brightness:** 1500 nits
- **Font:** JetBrains Mono
- **Font sizes:** Compact / Normal / Comfortable / Large (configurable from glasses)

## Emulator Testing

You can develop without physical glasses using the built-in debug mode. In debug builds, Bluetooth is replaced with a local WebSocket connection:

1. Create a glasses AVD: **480x640**, 5" screen
2. Run the phone emulator - it starts a WebSocket server on port 8081
3. Run the glasses emulator - it auto-connects to `10.0.2.2:8081`

```bash
# Phone app (includes glasses APK in assets)
./gradlew :phone-app:installDebug

# Glasses app
./gradlew :glasses-app:installDebug
```

Keyboard shortcuts in the glasses emulator: Volume keys = swipe, Enter = tap, Back = double-tap.

## OpenClaw Protocol

The phone app implements the [OpenClaw Gateway protocol](https://docs.openclaw.ai):

- **Authentication:** Token auth + Ed25519 device identity (keypair stored in Android Keystore)
- **Chat:** Sends `chat.send`, receives streaming `chat` events with accumulated text (client diffs to extract new content)
- **Sessions:** Full session management - list, switch, create, reset
- **Auto-reconnect:** 3-second backoff on disconnect

## Phone-Glasses Protocol

Communication between phone and glasses uses JSON messages over the CXR SDK bridge (or WebSocket in debug mode):

**Phone → Glasses:** `chat_message`, `agent_thinking`, `chat_stream`, `chat_stream_end`, `connection_update`, `session_list`, `voice_state`, `voice_result`, `wake_signal`, `tts_state`

**Glasses → Phone:** `user_input` (text + optional photo), `list_sessions`, `switch_session`, `slash_command`, `start_voice`, `cancel_voice`, `request_more_history`, `wake_ack`, `tts_toggle`

## License

MIT
