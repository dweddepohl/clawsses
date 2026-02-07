package com.claudeglasses.glasses.debug

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * Debug WebSocket client that connects to the phone app's debug server.
 * This replaces Bluetooth communication when testing on emulators.
 *
 * For emulator-to-emulator testing:
 * - Phone app emulator runs debug server on port 8081
 * - Use `adb forward tcp:8081 tcp:8081` to expose it
 * - Glasses app connects to 10.0.2.2:8081 (host machine from emulator)
 */
class DebugPhoneClient {

    companion object {
        private const val TAG = "DebugPhoneClient"
        const val DEFAULT_HOST = "10.0.2.2"  // Host machine from Android emulator
        const val DEFAULT_PORT = 8081
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var scope: CoroutineScope? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Callback for messages from phone
    var onMessageFromPhone: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    fun connect(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT) {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope?.launch {
            _connectionState.value = ConnectionState.Connecting
            Log.i(TAG, "Connecting to phone debug server at $host:$port")

            try {
                socket = Socket(host, port)
                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()

                // Perform WebSocket handshake
                if (!performHandshake()) {
                    throw Exception("WebSocket handshake failed")
                }

                _connectionState.value = ConnectionState.Connected
                Log.i(TAG, "Connected to phone debug server")

                withContext(Dispatchers.Main) {
                    onConnected?.invoke()
                }

                // Start reading messages
                readMessages()

            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
                disconnect()
            }
        }
    }

    private fun performHandshake(): Boolean {
        val output = outputStream ?: return false
        val input = inputStream ?: return false

        try {
            // Generate random key
            val keyBytes = ByteArray(16)
            Random.nextBytes(keyBytes)
            val key = Base64.getEncoder().encodeToString(keyBytes)

            // Send HTTP upgrade request
            val request = buildString {
                append("GET / HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray())
            output.flush()

            // Read response byte-by-byte to avoid buffering WebSocket data
            // HTTP headers end with \r\n\r\n
            val responseBuilder = StringBuilder()
            var prevByte = 0
            var crlfCount = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                responseBuilder.append(b.toChar())

                // Check for end of headers (\r\n\r\n)
                // \r = 13, \n = 10
                if (b == 10 && prevByte == 13) {
                    crlfCount++
                    if (crlfCount >= 2) break  // Found \r\n\r\n
                } else if (b != 13) {
                    crlfCount = 0
                }
                prevByte = b
            }

            val response = responseBuilder.toString()
            Log.d(TAG, "Handshake response: ${response.take(200)}")

            // Verify response contains 101 Switching Protocols
            if (!response.contains("101")) {
                Log.e(TAG, "Invalid handshake response")
                return false
            }

            Log.d(TAG, "WebSocket handshake completed")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Handshake error", e)
            return false
        }
    }

    private suspend fun readMessages() {
        val input = inputStream ?: return

        try {
            while (_connectionState.value is ConnectionState.Connected) {
                val message = readWebSocketFrame(input)
                if (message != null) {
                    Log.d(TAG, "Received from phone: ${message.take(100)}")
                    withContext(Dispatchers.Main) {
                        onMessageFromPhone?.invoke(message)
                    }
                } else {
                    // Connection closed or error
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading messages", e)
        } finally {
            withContext(Dispatchers.Main) {
                onDisconnected?.invoke()
            }
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun readWebSocketFrame(input: InputStream): String? {
        return try {
            val firstByte = input.read()
            if (firstByte == -1) return null

            val secondByte = input.read()
            if (secondByte == -1) return null

            val isMasked = (secondByte and 0x80) != 0
            var payloadLength = (secondByte and 0x7F).toLong()

            if (payloadLength == 126L) {
                // 2-byte extended length
                val b1 = input.read()
                val b2 = input.read()
                if (b1 == -1 || b2 == -1) return null
                payloadLength = ((b1 shl 8) or b2).toLong()
            } else if (payloadLength == 127L) {
                // 8-byte extended length
                payloadLength = 0
                for (i in 0..7) {
                    val b = input.read()
                    if (b == -1) return null
                    payloadLength = (payloadLength shl 8) or b.toLong()
                }
            }

            // Sanity check payload length (max 10MB)
            if (payloadLength < 0 || payloadLength > 10 * 1024 * 1024) {
                Log.e(TAG, "Invalid payload length: $payloadLength (firstByte=0x${firstByte.toString(16)}, secondByte=0x${secondByte.toString(16)})")
                return null
            }

            val mask = if (isMasked) {
                ByteArray(4).also { input.read(it) }
            } else null

            val payload = ByteArray(payloadLength.toInt())
            var bytesRead = 0
            while (bytesRead < payloadLength) {
                val read = input.read(payload, bytesRead, (payloadLength - bytesRead).toInt())
                if (read == -1) return null
                bytesRead += read
            }

            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            // Check for close frame
            val opcode = firstByte and 0x0F
            if (opcode == 0x08) return null

            String(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WebSocket frame", e)
            null
        }
    }

    fun sendToPhone(message: String): Boolean {
        val output = outputStream ?: return false

        // Run on IO thread to avoid NetworkOnMainThreadException
        scope?.launch {
            try {
                val payload = message.toByteArray(Charsets.UTF_8)
                val frame = createWebSocketFrame(payload)
                output.write(frame)
                output.flush()
                Log.d(TAG, "Sent to phone: ${message.take(100)}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to phone", e)
            }
        }
        return true
    }

    private fun createWebSocketFrame(payload: ByteArray): ByteArray {
        // Client frames must be masked
        val mask = ByteArray(4)
        Random.nextBytes(mask)

        val frameLength = when {
            payload.size < 126 -> 6 + payload.size  // 2 header + 4 mask + payload
            payload.size < 65536 -> 8 + payload.size
            else -> 14 + payload.size
        }

        val frame = ByteArray(frameLength)
        frame[0] = 0x81.toByte()  // FIN + Text frame

        var offset: Int
        when {
            payload.size < 126 -> {
                frame[1] = (0x80 or payload.size).toByte()  // Masked + length
                offset = 2
            }
            payload.size < 65536 -> {
                frame[1] = (0x80 or 126).toByte()
                frame[2] = (payload.size shr 8).toByte()
                frame[3] = payload.size.toByte()
                offset = 4
            }
            else -> {
                frame[1] = (0x80 or 127).toByte()
                // IMPORTANT: Convert to Long before shifting, otherwise Int shift wraps at 32 bits
                // causing the 32-bit value to be duplicated in both halves of the 64-bit field
                val len = payload.size.toLong()
                for (i in 0..7) {
                    frame[2 + i] = (len shr (56 - i * 8)).toByte()
                }
                offset = 10
            }
        }

        // Add mask
        System.arraycopy(mask, 0, frame, offset, 4)
        offset += 4

        // Add masked payload
        for (i in payload.indices) {
            frame[offset + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        return frame
    }

    fun disconnect() {
        scope?.cancel()
        scope = null

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }

        socket = null
        outputStream = null
        inputStream = null
        _connectionState.value = ConnectionState.Disconnected

        Log.i(TAG, "Disconnected from phone debug server")
    }

    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected
}
