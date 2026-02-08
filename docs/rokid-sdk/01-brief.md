# Brief

The CXR-M SDK is a mobile-side development toolkit designed to build companion and control apps for Rokid Glasses. It enables stable connections between phone and glasses, supports data communication, real-time audio/video access, and scene customization. Ideal for apps that require phone-based UI, remote control, or advanced collaboration with the glasses. Currently available for Android.

## CXR SDK and Glasses Architecture

*(Architecture diagram showing the relationship between Rokid Glasses, Mobile Phone, and SDK layers)*

**Rokid Glasses side:**
- YodaOS-Sprite OS
- Rokid Launcher UI, Self View, Self APP
- CXR-A Channel (Music, Telephone, Message, Flying, Photo, Video Record, AI, Voice, Sound Record, Network, Translate, RTC)
- Rokid Assist Service → Rokid Assist Caps Channel
- Self Design Service → Self Caps Channel
- Rokid OTA Service
- CXR-S SDK (Register)
- BLE-Sysinfo, BT-Status, BT-Manager, BTspp-video, BLE-text, L2CAP-pic, L2CAP-video, BTspp-pic
- CXR Service
- AOSP / Hardware

**Mobile Phone side:**
- Self Design UI, User's AI, User's Features
- CXR-M SDK (System Information, Device Connector, AI Flow Features, Photo, Video Record, Flying, Self Caps)

**Connection:** Rokid Glasses Protocol (between glasses and phone)

## Connection and Management of Rokid Glasses Devices

Developers can use the CXR-M SDK to connect with Rokid Glasses, obtain the device status of Rokid Glasses, and manage them.

## Custom Scene Interaction for Rokid Glasses

Developers can quickly integrate into the scene interaction processes defined by the YodaOS-Sprite operating system using the CXR-M SDK. They can rapidly develop custom functions based on the interaction scenarios defined by YodaOS-Sprite.

- Custom AI Assistant

## Rokid Glasses Assist Service

Developers can efficiently utilize the services available in the Rokid Assist Service, including file transfer, audio recording, photo taking, and other capabilities, through the CXR-M SDK.

- Audio recording function
- Photo taking function
- Video recording function
- File transfer (Fly Transfer) function

> **Tips:** Rokid Assist Service is a series of service items provided by Rokid based on YodaOS-Sprite. These service items cannot be used in the CXR-M SDK when they are disabled.
