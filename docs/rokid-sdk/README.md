# Rokid CXR-M SDK Documentation (Mobile-Side)

> Version 1.0.1 | Source: https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/us/index.html

The CXR-M SDK is a mobile-side development toolkit designed to build companion and control apps for Rokid Glasses. It enables stable connections between phone and glasses, supports data communication, real-time audio/video access, and scene customization. **Currently available for Android.**

## Maven Coordinates

```
com.rokid.cxr:client-m:1.0.1-20250812.080117-2
```

Maven repository: `https://maven.rokid.com/repository/maven-public/`

## Requirements

- Android minSdk >= 28

## Documentation Index

1. [Brief](01-brief.md) - Overview and architecture
2. [SDK Import](02-sdk-import.md) - Maven setup, dependencies, permissions
3. [Device Connection](03-device-connection.md) - Bluetooth & Wi-Fi connection management
4. [Equipment Status and Controls](04-equipment-status-controls.md) - Brightness, volume, battery, reboot, shutdown
5. [Picture/Video/Audio](05-picture-video-audio.md) - Camera, video recording, audio recording
6. [Data Operation](06-data-operation.md) - Send data, sync media files
7. [AI Scene](07-ai-scene.md) - AI events, ASR, camera, TTS, error handling
8. [Teleprompter Scene](08-teleprompter-scene.md) - Teleprompter display and control
9. [Translation Scene](09-translation-scene.md) - Real-time translation display
10. [Custom View Scene](10-custom-view-scene.md) - Custom UI on glasses (JSON layout)
11. [Version History](11-version-history.md) - Changelog

## Key Classes

- `CxrApi` - Main API class (singleton via `CxrApi.getInstance()`)
- `BluetoothStatusCallback` - Bluetooth connection events
- `WifiP2PStatusCallback` - Wi-Fi P2P connection events
- `PhotoResultCallback` - Photo result (WebP byte array)
- `AudioStreamListener` - Audio recording stream
- `AiEventListener` - AI scene events (key press, exit)
- `CustomViewListener` - Custom view lifecycle events
- `ValueUtil.CxrStatus` - Request/response status enum
- `ValueUtil.CxrSceneType` - Scene types (VIDEO_RECORD, WORD_TIPS, Translation)
