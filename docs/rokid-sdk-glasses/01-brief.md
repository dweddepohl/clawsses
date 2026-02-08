# Brief - CXR-S SDK (Glasses-Side)

The CXR-S SDK is the on-device development toolkit running on YodaOS-Sprite, enabling developers to create standalone applications directly on Rokid Glasses. It provides access to the data channel and establishes two-way communication with the CXR-M SDK on mobile, supporting custom protocols and command transmission. With CXR-S, developers can deeply leverage hardware resources, unlock device potential, and deliver low-latency smart experiences.

## SDK and Glasses Architecture

*(Same architecture diagram as CXR-M SDK - showing Rokid Glasses, YodaOS-Sprite, CXR-S SDK layer, and Mobile Phone with CXR-M SDK)*

## Device Connection Management

Developers can use the Rokid CXR-S SDK to monitor the connection/disconnection status of mobile devices (Android/iOS) in real time and obtain the health status of ARTC data transmission.

## Message Communication

Developers can use the Rokid CXR-S SDK to subscribe to command messages sent from the mobile end and send structured data (Caps) or binary streams (byte[]) to the mobile end, enabling bidirectional communication.
