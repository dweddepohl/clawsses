# Rokid Glasses Reference

Comprehensive documentation for Rokid AR glasses hardware and SDK.

## Hardware Specifications

### Rokid AR Lite / Rokid Glasses (2024-2025)

| Spec | Details |
|------|---------|
| **Weight** | 49 grams |
| **Display** | Dual-eye monochrome, 0.15cc Micro-LED engine |
| **Brightness** | 1500 nits, 10-level dimming |
| **Resolution** | 640×480 (for AR Lite) |
| **Camera** | 12MP POV with Low-Light HDR (LLHDR) |
| **Video** | Up to 1680P @ 30FPS |
| **Audio** | Dual AAC near-ear directional speakers |
| **Microphones** | 4-mic array with noise reduction |
| **Battery** | 210 mAh (glasses) + 3000 mAh (case) |
| **Frame** | TR90 titanium plastic, titanium alloy hinges |
| **Connectivity** | Bluetooth, Wi-Fi |

### Input Methods

**Temple Touchpad:**
- Located on right temple
- Linear touchpad with two swipe directions only:
  - Forward (towards eyes)
  - Backward (towards ear)
- Tap (single, double)
- Long press

**Physical Buttons:**
- Home button
- Back button
- Volume up/down

**Voice:**
- Wake word: "Hi Rokid"
- Voice commands for photos, AI assistant, translation

## SDK Architecture

Rokid uses a split SDK architecture for glasses-phone communication:

```
┌─────────────────────┐          ┌─────────────────────┐
│   GLASSES (CXR-S)   │◄────────►│    PHONE (CXR-M)    │
│  cxr-service-bridge │ Bluetooth│     client-m        │
└─────────────────────┘          └─────────────────────┘
```

### Phone SDK: CXR-M (Client Mobile)

**Maven Artifact:** `com.rokid.cxr:client-m:1.0.4`

**Key Classes:**
- `CxrApi` - Main SDK singleton
- `BluetoothStatusCallback` - Connection state events
- `CustomCmdListener` - Receive messages from glasses

**Usage:**
```kotlin
// Initialize
val cxrApi = CxrApi.getInstance()
cxrApi.updateRokidAccount(accessKey)

// Connect via Bluetooth
cxrApi.connectBluetooth(context, address, name, callback, encryptKey, account)

// Listen for messages from glasses
cxrApi.setCustomCmdListener { cmd, caps ->
    // Handle command from glasses
}
```

### Glasses SDK: CXR-S (Service)

**Maven Artifact:** `com.rokid.cxr:cxr-service-bridge:1.0`

**Key Classes:**
- `CXRServiceBridge` - Main bridge for phone communication
- `StatusListener` - Connection and status events
- `MsgCallback` - Message reception

**Usage:**
```kotlin
val bridge = CXRServiceBridge()

// Listen for connection status
bridge.setStatusListener(object : StatusListener {
    override fun onConnected(name: String?, mac: String?, deviceType: Int) { }
    override fun onDisconnected() { }
    override fun onConnecting(name: String?, mac: String?, deviceType: Int) { }
    override fun onARTCStatus(latency: Float, connected: Boolean) { }
    override fun onRokidAccountChanged(account: String?) { }
})

// Subscribe to messages
bridge.subscribe("terminal", object : MsgCallback {
    override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
        // Handle message from phone
    }
})

// Send message to phone
val caps = Caps()
caps.write(message)
bridge.sendMessage("command", caps)
```

### SDK Credentials

Required in `local.properties`:
```properties
rokid.clientId=your_client_id
rokid.clientSecret=your_client_secret
rokid.accessKey=your_access_key
```

Get credentials from [Rokid Developer Portal](https://ar.rokid.com).

## Gesture Handling

### MotionEvent Processing

The glasses touchpad sends standard Android `MotionEvent`:

```kotlin
override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.action) {
        MotionEvent.ACTION_DOWN -> { /* Touch started */ }
        MotionEvent.ACTION_MOVE -> { /* Finger moving */ }
        MotionEvent.ACTION_UP -> { /* Touch ended */ }
    }
}
```

### Generic Motion Events

For external touchpads (like Rokid temple pad), use:

```kotlin
override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    // Handle touchpad events
    gestureHandler.onTouchEvent(event)
}
```

### KeyEvent for Hardware Buttons

```kotlin
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { /* Volume up pressed */ }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { /* Volume down pressed */ }
        KeyEvent.KEYCODE_BACK -> { /* Back button pressed */ }
    }
}
```

### Recommended Gesture Thresholds

| Gesture | Threshold |
|---------|-----------|
| Swipe distance | 100px minimum |
| Swipe velocity | 100px/s minimum |
| Tap movement | <50px |
| Double-tap timeout | 300ms |
| Long press timeout | 500ms |

## Display Guidelines

### Monochrome Optimization

The Rokid display is monochrome (green), so:
- Use **pure black** (#000000) background - blends with real world
- Use **bright green** (#00FF00) for primary text
- Use **cyan** (#00FFFF) for highlights
- Avoid gradients and subtle color differences

### Terminal Sizing

For a 640×480 display with JetBrains Mono:
- **65 columns** fits comfortably without wrapping
- **~15 rows** visible (with status bar and hints)
- Dynamic font scaling recommended based on available width

### UI Best Practices

1. **Minimize chrome** - Use overlays that fade in/out
2. **High contrast** - Everything should be clearly visible
3. **Large touch targets** - Account for imprecise touchpad input
4. **Status indicators** - Use simple icons (●/○) for connection state

## Official Resources

### Documentation
- [Rokid Glass GitBook](https://rokid.github.io/glass-docs/) - Legacy docs
- [Rokid Glass 2 Docs](https://rokidglass.github.io/glass2-docs/en/) - Current docs
- [Rokid Developer Documentation](https://rokid.yuque.com/books/share/ec68dac1-6728-4e71-896e-3664b6f5f039) - Chinese

### SDKs
- [Rokid CXR-M SDK](https://ar.rokid.com/sdk?lang=en) - Phone SDK portal
- [Rokid Maven Repository](https://maven.rokid.com/repository/maven-public/) - SDK artifacts
- [UXR SDK Docs](https://github.com/RokidGlass/UXR-docs) - Unity SDK (archived)

### GitHub
- [RokidGlass Organization](https://github.com/RokidGlass) - Official repos
- [glass2-docs](https://github.com/RokidGlass/glass2-docs) - Documentation source
- [glass-docs](https://github.com/Rokid/glass-docs) - Legacy documentation

### Community
- [Rokid Developer Forum](https://forum.rokid.com/) - Developer community
- [Rokid Global](https://global.rokid.com) - Product pages

### Related Platforms
- [Google Glass GDK Touch](https://developers.google.com/glass/develop/gdk/touch) - Similar gesture patterns
- [Google Glass Enterprise](https://developers.google.com/glass-enterprise/guides/inputs-sensors) - Input reference

## Notes

- CXR SDK requires **minSdk 28** (Android 9.0+)
- SDK documentation is limited publicly; detailed API docs may require developer program access
- The SDK handles Bluetooth communication; apps handle UI and gestures
- Debug mode can bypass Bluetooth using WebSocket for emulator testing
