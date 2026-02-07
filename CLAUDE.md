# Claude Code Context

Project context and guidelines for Claude Code development on this repository.

## Project Overview

**Clawsses** - A wearable OpenClaw client for Rokid AR glasses. View and interact with an OpenClaw AI assistant through AR glasses using gestures and voice commands.

### Architecture

```
OpenClaw Gateway  ←WebSocket→  Phone App (Android)  ←Bluetooth→  Glasses App (Android)
     │                              │                                   │
     │                              │                                   │
  AI agent                     CXR-M SDK                           CXR-S SDK
  Block streaming              Voice input                         Chat HUD
  Sessions                     Bridge logic                        Gesture input
```

### Key Components

| Component | Path | Purpose |
|-----------|------|---------|
| **Phone App** | `phone-app/` | Android companion app with CXR-M SDK, OpenClaw client |
| **Glasses App** | `glasses-app/` | Android HUD app with CXR-S SDK |
| **Shared** | `shared/` | Protocol definitions (OpenClaw + phone-glasses) |
| **Docs** | `docs/` | Documentation and images |

## OpenClaw Protocol

The phone app connects to an OpenClaw Gateway via WebSocket (default port 18789).

### Message Types

- **Request** (`type: "req"`): Client → server with method + params, tracked by `id`
- **Response** (`type: "res"`): Server → client, correlated by `id`, `ok: true/false`
- **Event** (`type: "event"`): Server push notifications (streaming, presence, etc.)

### Connection Flow

1. Connect WebSocket to `ws://host:port`
2. Server sends `connect.challenge` event (nonce)
3. Client sends `connect` request with auth token
4. Server responds with `hello-ok` + device token

### Streaming (Block-based)

1. Client sends `agent.run` request
2. Server acks with `runId` + `status: "accepted"`
3. Server pushes `event:agent` frames with buffered text chunks
4. Final `res` with `runId` signals completion

## Phone ↔ Glasses Protocol

### Phone → Glasses

| Message | Purpose |
|---------|---------|
| `chat_message` | Complete message (user echo or finished assistant msg) |
| `agent_thinking` | Agent acknowledged, no content yet |
| `chat_stream` | Streaming text chunk to append |
| `chat_stream_end` | Stream complete |
| `connection_update` | OpenClaw connection state |
| `session_list` | Available sessions |
| `voice_state` / `voice_result` | Voice recognition feedback |

### Glasses → Phone

| Message | Purpose |
|---------|---------|
| `user_input` | User text + optional photo |
| `list_sessions` / `switch_session` | Session management |
| `slash_command` | e.g. "/model" |
| `start_voice` / `cancel_voice` | Voice delegation |

## Development Guidelines

### Display Constraints

The Rokid glasses use **JBD 0.13" micro LED displays** (per eye):
- Resolution: **640×480** (landscape) / **480×640** (portrait mode)
- Pixel density: **~6,150 DPI** (extremely high - emulators cannot accurately simulate)
- Monochrome green, 1500 nits brightness
- Pure black background (transparent on AR display)
- JetBrains Mono font

**Emulator note**: A 5" emulator at 480×640 is ~160 DPI vs ~6,150 DPI on real glasses.

### Glasses HUD Layout

```
┌─[TopBar]──────────────────────────────┐
│ ● connected              12/42 lines  │
├───────────────────────────────────────┤
│ Assistant message (left, green)       │
│       User message (right, light bg)  │
│ Streaming...█                         │
├───[Input]─────────────────────────────┤
│ > current input text...               │
├───[Menu Bar]──────────────────────────┤
│ ↵Enter ⌫Clear ◎Sess ⬚Size AaFont …More │
└───────────────────────────────────────┘
```

HUD position cycles: Full → Bottom Half → Top Half

### Gesture Model

Three focus areas: CONTENT, INPUT, MENU

| Area | Swipe Fwd | Swipe Bwd | Tap | Double-tap | Long-press |
|------|-----------|-----------|-----|------------|------------|
| CONTENT | Scroll up | Scroll down | Jump to end | → INPUT | Voice |
| INPUT | → CONTENT | → MENU | Submit | — | Voice |
| MENU | Prev item | Next item | Execute | → INPUT | Voice |

### SDK Integration

**Rokid CXR SDK** handles phone↔glasses Bluetooth communication:
- Phone: `com.rokid.cxr:client-m:1.0.8`
- Glasses: `com.rokid.cxr:cxr-service-bridge:1.0`

SDK credentials required in `local.properties`:
```properties
rokid.clientSecret=xxx
rokid.accessKey=xxx
```

### Debug Mode

For emulator testing without physical glasses:
- Phone app starts WebSocket server on port 8081
- Glasses app connects to `10.0.2.2:8081` (Android emulator host alias)
- Enabled automatically in debug builds via `BuildConfig.DEBUG`

Create glasses emulator: 480×640 resolution (portrait), 5.0" screen

## File Reference

### Glasses App

```
glasses-app/src/main/java/com/clawsses/glasses/
├── HudActivity.kt              # Main activity, gesture routing, message handling
├── GlassesApp.kt               # Application class
├── ui/
│   ├── HudScreen.kt            # Compose chat HUD display
│   └── theme/                   # Theme definitions
├── input/
│   └── GestureHandler.kt       # Touchpad gesture detection
├── voice/
│   └── GlassesVoiceHandler.kt  # Voice delegation to phone
├── service/
│   └── PhoneConnectionService.kt  # CXR-S bridge
└── debug/
    └── DebugPhoneClient.kt     # WebSocket client for emulator
```

### Phone App

```
phone-app/src/main/java/com/clawsses/phone/
├── ui/screens/
│   └── MainScreen.kt           # Main UI, message forwarding
├── openclaw/
│   └── OpenClawClient.kt       # WebSocket client to OpenClaw Gateway
├── glasses/
│   ├── GlassesConnectionManager.kt  # CXR-M or debug server
│   ├── RokidSdkManager.kt      # SDK initialization
│   └── ApkInstaller.kt         # Glasses APK installation
├── voice/
│   └── VoiceCommandHandler.kt  # Speech recognition
├── service/
│   └── GlassesConnectionService.kt  # Foreground service
└── debug/
    └── DebugGlassesServer.kt   # WebSocket server for emulator
```

## Debugging Tips

```bash
# Glasses app
adb -s emulator-5556 logcat | grep -E "(GlassesApp|HudScreen|PhoneConnection)"

# Phone app
adb -s emulator-5554 logcat | grep -E "(MainScreen|OpenClawClient|GlassesConnection)"
```

## External Documentation

- [Rokid SDK Reference](docs/ROKID.md) - Hardware specs, SDK APIs, gesture handling
- [OpenClaw Gateway Protocol](https://docs.openclaw.ai/gateway/protocol) - WebSocket protocol
- [OpenClaw Streaming](https://docs.openclaw.ai/concepts/streaming) - Block streaming docs
