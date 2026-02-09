package com.clawsses.glasses.service

import android.content.Context
import android.util.Log
import com.clawsses.glasses.debug.DebugPhoneClient
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service to handle communication with the phone app via CXR-S SDK
 * Supports debug mode for emulator testing via WebSocket
 *
 * Receives terminal updates from phone and sends gesture/voice commands back
 */
class PhoneConnectionService(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val debugMode: Boolean = false,
    private val debugHost: String = DebugPhoneClient.DEFAULT_HOST,
    private val debugPort: Int = DebugPhoneClient.DEFAULT_PORT
) {
    companion object {
        private const val TAG = "PhoneConnection"
        // Message types for subscribing
        private const val MSG_TYPE_TERMINAL = "terminal"
        private const val MSG_TYPE_COMMAND = "command"
    }

    private var cxrBridge: CXRServiceBridge? = null
    private var debugClient: DebugPhoneClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var isConnected = false
    private var connectedDeviceName: String? = null
    private var connectedDeviceMac: String? = null
    private var probeJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val info: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    /**
     * Start listening for phone connections via CXR-S SDK or WebSocket (debug mode)
     */
    fun startListening() {
        if (isRunning) return
        isRunning = true

        if (debugMode) {
            Log.d(TAG, "Starting in DEBUG MODE - connecting via WebSocket to $debugHost:$debugPort")
            startDebugConnection()
        } else {
            Log.d(TAG, "Starting CXR Service Bridge for phone connection")
            try {
                initializeBridge()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing CXR bridge", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun startDebugConnection() {
        _connectionState.value = ConnectionState.Connecting

        debugClient = DebugPhoneClient().apply {
            onMessageFromPhone = { message ->
                Log.d(TAG, "Debug: received from phone: ${message.take(100)}")
                onMessageReceived(message)
            }
            onConnected = {
                isConnected = true
                connectedDeviceName = "Debug Phone (WebSocket)"
                _connectionState.value = ConnectionState.Connected("Debug Mode: $debugHost:$debugPort")
                Log.i(TAG, "Debug: connected to phone app")
            }
            onDisconnected = {
                isConnected = false
                connectedDeviceName = null
                _connectionState.value = ConnectionState.Disconnected
                Log.i(TAG, "Debug: disconnected from phone app")
            }
            connect(debugHost, debugPort)
        }
    }

    /**
     * Consolidate connection detection from any source (onConnected, ARTC, message receipt).
     * Cancels the active probe once a connection is confirmed.
     */
    private fun markConnected(info: String) {
        if (!isConnected) {
            Log.i(TAG, "Connection detected: $info")
            isConnected = true
            _connectionState.value = ConnectionState.Connected(info)
        }
        probeJob?.cancel()
    }

    private fun initializeBridge() {
        cxrBridge = CXRServiceBridge()

        _connectionState.value = ConnectionState.Connecting

        // Set up status listener for connection events
        cxrBridge?.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String?, mac: String?, deviceType: Int) {
                Log.i(TAG, "Phone connected via CXR bridge: $name ($mac), type=$deviceType")
                connectedDeviceName = name
                connectedDeviceMac = mac
                markConnected("$name ($mac)")
            }

            override fun onDisconnected() {
                Log.i(TAG, "Phone disconnected from CXR bridge")
                connectedDeviceName = null
                connectedDeviceMac = null
                isConnected = false
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onConnecting(name: String?, mac: String?, deviceType: Int) {
                Log.d(TAG, "Phone connecting: $name ($mac)")
                _connectionState.value = ConnectionState.Connecting
            }

            override fun onARTCStatus(latency: Float, connected: Boolean) {
                Log.d(TAG, "ARTC status: latency=$latency, connected=$connected")
                if (connected) {
                    markConnected("Phone (detected via ARTC)")
                }
            }

            override fun onRokidAccountChanged(account: String?) {
                Log.d(TAG, "Rokid account changed: $account")
            }
        })

        // Subscribe to terminal messages from phone
        val result = cxrBridge?.subscribe(MSG_TYPE_TERMINAL, object : CXRServiceBridge.MsgCallback {
            override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
                Log.d(TAG, "Received message type: $msgType, caps=${caps != null}, data=${data?.size}")
                val message = when {
                    data != null && data.isNotEmpty() -> {
                        String(data, Charsets.UTF_8)
                    }
                    caps != null && caps.size() > 0 -> {
                        try {
                            caps.at(0).getString()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read string from Caps", e)
                            ""
                        }
                    }
                    else -> ""
                }
                if (message.isNotEmpty()) {
                    markConnected("Phone (detected via message)")
                    Log.d(TAG, "Message content (${message.length} chars): ${message.take(100)}...")
                    onMessageReceived(message)
                } else {
                    Log.w(TAG, "Received empty message from phone")
                }
            }
        })

        Log.d(TAG, "Subscribed to $MSG_TYPE_TERMINAL messages, result: $result")

        // Start active connection probe: the CXR system service may already have a
        // BT connection alive from before the app restart, but a new CXRServiceBridge
        // does NOT replay onConnected for pre-existing connections. Actively send
        // request_state probes to trigger a response from the phone, which will arrive
        // via the subscribe callback and confirm the connection is alive.
        startConnectionProbe()
    }

    /**
     * Actively probe for a live phone connection by sending request_state messages
     * directly through the CXR bridge (bypassing the isConnected check).
     * When the phone responds, the subscribe callback fires and markConnected() is called.
     */
    private fun startConnectionProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            // Give the bridge a moment to fire onConnected if BT is freshly established
            delay(2000L)
            repeat(10) { attempt ->
                if (isConnected) return@launch
                Log.i(TAG, "Connection probe ${attempt + 1}/10: sending request_state")
                withContext(Dispatchers.Main) {
                    try {
                        val caps = Caps()
                        caps.write("""{"type":"request_state"}""")
                        cxrBridge?.sendMessage(MSG_TYPE_COMMAND, caps)
                    } catch (e: Exception) {
                        Log.e(TAG, "Connection probe send failed", e)
                    }
                }
                delay(3000L)
            }
            if (!isConnected) {
                Log.w(TAG, "Connection probe: no response after 10 attempts")
            }
        }
    }

    /**
     * Send a command/message back to the phone
     */
    fun sendToPhone(message: String) {
        if (!isConnected) {
            Log.w(TAG, "Not connected to phone, cannot send message")
            return
        }

        if (debugMode) {
            // Send via debug WebSocket client
            debugClient?.sendToPhone(message)
            Log.d(TAG, "Debug: sent to phone: ${message.take(50)}...")
        } else {
            // CXR bridge must be called from the same thread it was initialized on (main thread).
            // Using an IO coroutine here causes sendMessage to silently fail.
            try {
                val caps = Caps()
                caps.write(message)
                val result = cxrBridge?.sendMessage(MSG_TYPE_COMMAND, caps)
                Log.d(TAG, "Sent to phone: ${message.take(50)}..., result: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to phone", e)
            }
        }
    }

    /**
     * Send a command with binary data
     */
    fun sendToPhone(messageType: String, caps: Caps, data: ByteArray? = null) {
        if (!isConnected) {
            Log.w(TAG, "Not connected to phone, cannot send message")
            return
        }

        scope.launch {
            try {
                val result = if (data != null) {
                    cxrBridge?.sendMessage(messageType, caps, data)
                } else {
                    cxrBridge?.sendMessage(messageType, caps)
                }
                Log.d(TAG, "Sent message type $messageType, result: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to phone", e)
            }
        }
    }

    /**
     * Send a captured image to phone (for Claude screenshot feature)
     */
    fun sendImage(base64Image: String) {
        val caps = Caps()
        caps.write("image")
        caps.write(base64Image)
        sendToPhone("image", caps)
    }

    /**
     * Check if connected to phone
     */
    fun isPhoneConnected(): Boolean = isConnected

    /**
     * Get connected device info
     */
    fun getConnectedDevice(): Pair<String?, String?> = Pair(connectedDeviceName, connectedDeviceMac)

    /**
     * Soft stop: release our bridge/client references and reset local state, but do NOT
     * call disconnectCXRDevice(). The CXR system service manages the Bluetooth connection
     * independently — calling disconnectCXRDevice() tears it down and only the phone can
     * re-initiate it, so the glasses would be stuck "disconnected" on the next open.
     *
     * For debug (WebSocket) mode we do close the socket because startDebugConnection()
     * can always open a new one to the still-running phone server.
     */
    fun stop() {
        isRunning = false
        isConnected = false
        probeJob?.cancel()
        scope.cancel()

        if (debugMode) {
            debugClient?.disconnect()
            debugClient = null
        } else {
            // Drop our reference without disconnecting BT — the system service keeps it alive.
            cxrBridge = null
        }

        _connectionState.value = ConnectionState.Disconnected
        Log.d(TAG, "Phone connection service stopped (BT preserved)")
    }

}
