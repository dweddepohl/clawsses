# Message Sending

The CXR-S SDK provides two message-sending methods, allowing glasses-side applications to send data to connected mobile devices:

- **Basic Message Sending**: Sending structured data (in Caps format)
- **Binary Message Sending**: Sending structured data + binary content

## 1. Basic Message Sending

### 1.1 Interface Definition

```kotlin
int sendMessage(String name, Caps args)
```

**Parameters:**
- `name`: Message name (must be agreed upon with the mobile end)
- `args`: Structured message parameters (Caps object)

**Return Values:**
- `0`: Sending successful
- `-1`: Parameter error
- `-3`: Internal error

### 1.2 Example Code

```kotlin
val cxrServiceBridge = CXRServiceBridge()

fun sendExampleMessage() {
    // Create a Caps object and populate it with data
    val args = Caps()
    args.write("send_message")
    args.writeUInt32(5)

    // Send the message
    val result = cxrServiceBridge.sendMessage(
        "message_channel",  // Message channel name
        args
    )

    if (result == 0) {
        Log.d("send_message", "Send message successful")
    } else {
        Log.d("send_message", "Send message error: $result")
    }
}
```

## 2. Binary Message Sending

### 2.1 Interface Definition

```kotlin
int sendMessage(String name, Caps args, byte[] data, int offset, int size)
```

**Parameters:**
- `name`: Message name (must be agreed upon with the mobile end)
- `args`: Structured message parameters (Caps object)
- `data`: Binary data array
- `offset`: Starting offset of the data
- `size`: Length of the data to be sent

**Return Values:**
- `0`: Sending successful
- `-1`: Parameter error
- `-3`: Internal error

### 2.2 Example Code

```kotlin
val cxrServiceBridge = CXRServiceBridge()

fun sendExampleMessage() {
    val args = Caps()
    args.write("send_message")
    args.writeUInt32(5)

    // Prepare binary data
    val data = byteArrayOf()  // Replace with actual data
    val offset = 0
    val size = data.size

    val result = cxrServiceBridge.sendMessage(
        "message_channel",
        args,
        data,
        offset,
        size
    )

    if (result == 0) {
        Log.d("send_message", "Send message successful")
    } else {
        Log.d("send_message", "Send message error: $result")
    }
}
```
