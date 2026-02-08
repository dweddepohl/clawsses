# Rokid CXR-S SDK Documentation (Glasses-Side)

> Version 1.0 | Source: https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/us/3fe1c87b945245bf8b6c50393f4da7b6.html

The CXR-S SDK is the on-device development toolkit running on YodaOS-Sprite, enabling developers to create standalone applications directly on Rokid Glasses. It provides access to the data channel and establishes two-way communication with the CXR-M SDK on mobile, supporting custom protocols and command transmission.

## Maven Coordinates

```
com.rokid.cxr:cxr-service-bridge:1.0-20250519.061355-45
```

Maven repository: `https://maven.rokid.com/repository/maven-public/`

## Requirements

- Android minSdk >= 28
- ADB enabled on Rokid Glasses (via Rokid AI APP)
- Dedicated development cable (magnetic charging port = data port)

## Documentation Index

1. [Brief](01-brief.md) - Overview and architecture
2. [Development Environment](02-development-environment.md) - ADB setup, dev cable
3. [SDK Import](03-sdk-import.md) - Maven setup, dependencies
4. [Manage Device Connection](04-manage-device-connection.md) - Monitor mobile connection status
5. [Message Subscription](05-message-subscription.md) - Receive messages from mobile (regular + reply-enabled)
6. [Message Sending](06-message-sending.md) - Send messages to mobile (basic + binary)
7. [Data Structure](07-data-structure.md) - Caps serialization format

## Key Classes

- `CXRServiceBridge` - Main bridge class for glasses-side communication
- `CXRServiceBridge.StatusListener` - Connection status monitoring
- `CXRServiceBridge.MsgCallback` - Regular message subscription
- `CXRServiceBridge.MsgReplyCallback` - Reply-enabled message subscription
- `Caps` - Structured data serialization/deserialization
- `Reply` - Reply object for responding to mobile messages
