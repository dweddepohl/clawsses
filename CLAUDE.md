# Claude Code Context

Project context and guidelines for Claude Code development on this repository.

## Project Overview

**Claude Glasses Terminal** - A wearable terminal interface for Claude Code on Rokid AR glasses. View and interact with Claude Code through AR glasses using gestures and voice commands.

### Architecture

```
Server (Node.js)  ←WebSocket→  Phone App (Android)  ←Bluetooth→  Glasses App (Android)
     │                              │                                   │
     │                              │                                   │
  tmux + Claude Code           CXR-M SDK                           CXR-S SDK
  Terminal capture             Voice input                         HUD display
  Key injection                Bridge logic                        Gesture input
```

### Key Components

| Component | Path | Purpose |
|-----------|------|---------|
| **Server** | `server/` | Node.js WebSocket server running Claude Code in tmux |
| **Phone App** | `phone-app/` | Android companion app with CXR-M SDK |
| **Glasses App** | `glasses-app/` | Android HUD app with CXR-S SDK |
| **Shared** | `shared/` | Protocol definitions |
| **Docs** | `docs/` | Documentation and images |

## Development Guidelines

### Display Constraints

The Rokid glasses use **JBD 0.13" micro LED displays** (per eye):
- Resolution: **640×480** (landscape) / **480×640** (portrait mode)
- Pixel density: **~6,150 DPI** (extremely high - emulators cannot accurately simulate)
- Monochrome green, 1500 nits brightness
- Terminal sized to **64 columns × 31 rows**
- Pure black background (transparent on AR display)
- JetBrains Mono font for box-drawing characters

**Emulator note**: A 5" emulator at 480×640 is ~160 DPI vs ~6,150 DPI on real glasses.
Text will appear much larger on emulator than on actual device.

### Gesture Modes

Three interaction modes, cycled via double-tap. Gestures are **unified** across modes:

| Mode | Forward/Backward Swipe | Tap | Purpose |
|------|------------------------|-----|---------|
| **SCROLL** | Scroll up/down | Jump to end | Reading output |
| **NAVIGATE** | Arrow up/down | Enter | Menu navigation |
| **COMMAND** | Tab/Escape | Shift-Tab | Claude Code UI |

**Touchpad directions:**
- Forward (towards eyes) = scroll up / arrow up / tab
- Backward (towards ear) = scroll down / arrow down / escape

### SDK Integration

**Rokid CXR SDK** handles phone↔glasses Bluetooth communication:
- Phone: `com.rokid.cxr:client-m:1.0.4`
- Glasses: `com.rokid.cxr:cxr-service-bridge:1.0`

SDK credentials required in `local.properties`:
```properties
rokid.clientId=xxx
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

### Glasses App (Key Files)

```
glasses-app/src/main/java/com/claudeglasses/glasses/
├── HudActivity.kt              # Main activity, gesture routing
├── GlassesApp.kt               # Application class
├── ui/
│   ├── HudScreen.kt            # Compose HUD display
│   └── theme/                  # Theme definitions
├── input/
│   └── GestureHandler.kt       # Touchpad gesture detection
├── service/
│   └── PhoneConnectionService.kt  # CXR-S bridge
└── debug/
    └── DebugPhoneClient.kt     # WebSocket client for emulator
```

### Phone App (Key Files)

```
phone-app/src/main/java/com/claudeglasses/phone/
├── ui/screens/
│   └── MainScreen.kt           # Main UI, command forwarding
├── terminal/
│   └── TerminalClient.kt       # WebSocket client to server
├── glasses/
│   ├── GlassesConnectionManager.kt  # CXR-M or debug server
│   └── RokidSdkManager.kt      # SDK initialization
├── voice/
│   └── VoiceCommandHandler.kt  # Speech recognition
└── debug/
    └── DebugGlassesServer.kt   # WebSocket server for emulator
```

### Server (Key Files)

```
server/src/
└── index.js                    # WebSocket server, tmux management
```

## Message Protocol

### Terminal Updates (Server → Phone → Glasses)

```json
{
  "type": "terminal_update",
  "lines": ["line1", "line2", ...],
  "cursorPosition": 0,
  "mode": "SCROLL"
}
```

### Commands (Glasses → Phone → Server)

```json
{
  "type": "command",
  "command": "enter|escape|tab|shift_tab|up|down|left|right"
}
```

### Key Events (Phone → Server)

```json
{
  "type": "key",
  "key": "enter|escape|tab|shift_tab|up|down|..."
}
```

## Common Tasks

### Adding a New Gesture

1. Add to `GestureHandler.Gesture` enum
2. Handle in `HudActivity.handleGesture()`
3. Update hints in `HudScreen.GestureHints()`

### Adding a New Command

1. Add to server's `keyMap` in `server/src/index.js`
2. Add to `TerminalClient.SpecialKey` enum (optional)
3. Send via `terminalClient.sendKey("command_name")`

### Modifying HUD Layout

Edit `glasses-app/.../ui/HudScreen.kt`:
- `HudScreen()` - Main composable
- `StatusBar()` - Top overlay
- `TerminalLine()` - Individual lines
- `GestureHints()` - Bottom overlay
- `HudColors` - Color palette

## Debugging Tips

### Check Logs

```bash
# Glasses app
adb -s emulator-5556 logcat | grep -E "(GlassesApp|HudScreen|PhoneConnection)"

# Phone app
adb -s emulator-5554 logcat | grep -E "(MainScreen|TerminalClient|GlassesConnection)"

# Server
# Console output shows all WebSocket messages
```

### Common Issues

| Issue | Check |
|-------|-------|
| Tap not detected | Logcat for "Compose onTap" or "Gesture detected" |
| Commands not reaching server | Check phone app "Received command from glasses" log |
| Display not updating | Verify WebSocket connection state |
| Font rendering issues | Ensure JetBrains Mono font is in `res/font/` |

## External Documentation

- [Rokid SDK Reference](docs/ROKID.md) - Hardware specs, SDK APIs, gesture handling
- [Rokid Developer Portal](https://ar.rokid.com) - SDK downloads and credentials
- [Rokid GitHub](https://github.com/RokidGlass) - Official repositories
