package com.clawsses.phone.glasses

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.clawsses.phone.debug.DebugGlassesServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

/**
 * Manages connection to Rokid Glasses using CXR-M SDK
 * Supports debug mode for emulator testing via WebSocket
 *
 * Connection flow:
 * 1. startScanning() - Discover nearby Rokid devices
 * 2. User selects device from discoveredDevices list
 * 3. connectToDevice(device) - Establish Bluetooth connection via SDK
 * 4. Once connected, initWifiP2P() for data transfer (optional)
 */
class GlassesConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "GlassesConnection"
        private const val SCAN_TIMEOUT_MS = 15000L
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5

        // Rokid BLE Service UUID (glasses advertise with this UUID)
        val ROKID_SERVICE_UUID: UUID = UUID.fromString("00009100-0000-1000-8000-00805f9b34fb")
    }

    /**
     * Represents a discovered Bluetooth device
     */
    data class DiscoveredDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val device: BluetoothDevice
    )

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        object InitializingWifiP2P : ConnectionState()
        data class Connected(val deviceName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // List of discovered devices during scanning
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    // WiFi P2P state
    private val _wifiP2PConnected = MutableStateFlow(false)
    val wifiP2PConnected: StateFlow<Boolean> = _wifiP2PConnected.asStateFlow()

    private val _lastMessages = MutableStateFlow<List<String>>(emptyList())
    val lastMessages: StateFlow<List<String>> = _lastMessages.asStateFlow()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null

    // Debug mode WebSocket server for emulator testing
    private var debugServer: DebugGlassesServer? = null
    private var _debugModeEnabled = MutableStateFlow(false)
    val debugModeEnabled: StateFlow<Boolean> = _debugModeEnabled.asStateFlow()

    // Auto-reconnect: when glasses disconnect unexpectedly (e.g. glasses app restart),
    // automatically attempt to reconnect using saved BT credentials.
    private val reconnectScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var userInitiatedDisconnect = false
    private var reconnectAttempts = 0

    // Callback for messages from glasses (both BLE and debug modes)
    var onMessageFromGlasses: ((String) -> Unit)? = null

    // AI scene callbacks (glasses long-press voice activation)
    var onAiKeyDown: (() -> Unit)? = null
    var onAiExit: (() -> Unit)? = null

    init {
        // Set up SDK callbacks
        setupSdkCallbacks()
    }

    private fun setupSdkCallbacks() {
        RokidSdkManager.onGlassesConnected = {
            val name = RokidSdkManager.getSavedDeviceName() ?: "Rokid Glasses"
            _connectionState.value = ConnectionState.Connected(name)
            reconnectAttempts = 0
            reconnectJob?.cancel()
            Log.d(TAG, "SDK: Glasses connected")
        }

        RokidSdkManager.onGlassesDisconnected = {
            _connectionState.value = ConnectionState.Disconnected
            _wifiP2PConnected.value = false
            Log.d(TAG, "SDK: Glasses disconnected")

            // Auto-reconnect: when the glasses app restarts, the system-level BT
            // connection drops and the phone sees a disconnect. Automatically try to
            // reconnect so the glasses app can re-establish communication without
            // requiring the user to manually pair again.
            if (!userInitiatedDisconnect && !_debugModeEnabled.value) {
                scheduleReconnect()
            }
        }

        RokidSdkManager.onBluetoothFailed = { error ->
            _connectionState.value = ConnectionState.Error("Bluetooth failed: $error")
            Log.e(TAG, "SDK: Bluetooth failed: $error")

            // If this failure happened during auto-reconnect, try again
            if (!userInitiatedDisconnect && !_debugModeEnabled.value && reconnectAttempts > 0) {
                scheduleReconnect()
            }
        }

        RokidSdkManager.onWifiP2PConnected = {
            _wifiP2PConnected.value = true
            // Update state if we were initializing WiFi P2P
            val currentState = _connectionState.value
            if (currentState is ConnectionState.InitializingWifiP2P) {
                val name = RokidSdkManager.getSavedDeviceName() ?: "Rokid Glasses"
                _connectionState.value = ConnectionState.Connected(name)
            }
            Log.d(TAG, "SDK: WiFi P2P connected")
        }

        RokidSdkManager.onWifiP2PDisconnected = {
            _wifiP2PConnected.value = false
            Log.d(TAG, "SDK: WiFi P2P disconnected")
        }

        RokidSdkManager.onWifiP2PFailed = {
            _wifiP2PConnected.value = false
            Log.e(TAG, "SDK: WiFi P2P failed")
        }

        RokidSdkManager.onMessageFromGlasses = { cmd, caps ->
            // Forward messages from SDK to our callback
            onMessageFromGlasses?.invoke(cmd)
        }

        // AI scene events (glasses long-press triggers voice input)
        RokidSdkManager.onAiKeyDown = {
            Log.d(TAG, "SDK: AI key down (voice activation)")
            onAiKeyDown?.invoke()
        }
        RokidSdkManager.onAiExit = {
            Log.d(TAG, "SDK: AI scene exited")
            onAiExit?.invoke()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                "Unknown Device"
            }
            val address = device.address
            val rssi = result.rssi

            // Add to discovered devices if not already present
            val currentDevices = _discoveredDevices.value.toMutableList()
            val existingIndex = currentDevices.indexOfFirst { it.address == address }

            val discoveredDevice = DiscoveredDevice(deviceName, address, rssi, device)

            if (existingIndex >= 0) {
                // Update existing device (RSSI might have changed)
                currentDevices[existingIndex] = discoveredDevice
            } else {
                // Add new device
                currentDevices.add(discoveredDevice)
                Log.d(TAG, "Discovered device: $deviceName ($address) RSSI: $rssi")
            }

            _discoveredDevices.value = currentDevices
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
        }
    }

    /**
     * Start scanning for Rokid devices.
     * Discovered devices are added to the discoveredDevices flow.
     */
    fun startScanning() {
        if (_debugModeEnabled.value) {
            Log.d(TAG, "Debug mode enabled - skipping BLE scan")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = ConnectionState.Error("Bluetooth is not enabled")
            return
        }

        // Initialize SDK if not already
        if (!RokidSdkManager.isReady()) {
            if (!RokidSdkManager.initialize(context)) {
                _connectionState.value = ConnectionState.Error("Failed to initialize SDK")
                return
            }
        }

        // Clear previous scan results
        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        // Scan with Rokid service UUID filter
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(ROKID_SERVICE_UUID))
            .build()

        // Also scan without filter to catch devices that might not advertise the UUID
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            // Start scan (with filter for Rokid UUID)
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(TAG, "Started BLE scanning for Rokid devices")
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.Error("Missing Bluetooth permissions")
        }
    }

    /**
     * Stop scanning for devices
     */
    fun stopScanning() {
        try {
            bleScanner?.stopScan(scanCallback)
            if (_connectionState.value is ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Disconnected
            }
            Log.d(TAG, "Stopped BLE scanning")
        } catch (e: SecurityException) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    /**
     * Connect to a specific device from the discovered list
     */
    fun connectToDevice(device: DiscoveredDevice) {
        stopScanning()
        userInitiatedDisconnect = false
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Connecting to device: ${device.name} (${device.address})")

        // Use SDK to establish connection
        RokidSdkManager.initBluetooth(device.device)
    }

    /**
     * Connect to a device using saved connection info (for reconnection).
     * Requires both socketUuid and macAddress from previous connection.
     */
    fun connectWithSavedInfo(socketUuid: String, macAddress: String) {
        userInitiatedDisconnect = false
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Reconnecting with socketUuid=$socketUuid, mac=$macAddress")

        RokidSdkManager.connectBluetooth(socketUuid, macAddress)
    }

    /**
     * Attempt to reconnect using saved connection info
     */
    fun reconnect(): Boolean {
        userInitiatedDisconnect = false
        _connectionState.value = ConnectionState.Connecting
        return RokidSdkManager.reconnect()
    }

    /**
     * Initialize WiFi P2P connection (required for APK uploads)
     * Call this after Bluetooth is connected.
     */
    fun initWifiP2P(): Boolean {
        if (!RokidSdkManager.isConnected()) {
            Log.e(TAG, "Cannot init WiFi P2P - Bluetooth not connected")
            return false
        }

        _connectionState.value = ConnectionState.InitializingWifiP2P
        return RokidSdkManager.initWifiP2P()
    }

    /**
     * Disconnect from glasses (user-initiated)
     */
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectAttempts = 0
        if (_debugModeEnabled.value) {
            stopDebugServer()
        } else {
            RokidSdkManager.disconnect()
        }
        _connectionState.value = ConnectionState.Disconnected
        _wifiP2PConnected.value = false
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            return
        }
        reconnectJob = reconnectScope.launch {
            val attempt = reconnectAttempts + 1
            Log.i(TAG, "Auto-reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS in ${RECONNECT_DELAY_MS}ms")
            delay(RECONNECT_DELAY_MS)
            reconnectAttempts = attempt
            if (RokidSdkManager.reconnect()) {
                Log.i(TAG, "Auto-reconnect initiated (attempt $attempt)")
            } else {
                Log.w(TAG, "Auto-reconnect failed (no saved connection info)")
            }
        }
    }

    // ============== Debug Mode Methods ==============

    /**
     * Enable debug mode for emulator testing.
     * Starts a WebSocket server that glasses app can connect to.
     */
    fun enableDebugMode() {
        if (_debugModeEnabled.value) {
            Log.d(TAG, "Debug mode already enabled")
            return
        }

        Log.i(TAG, "Enabling debug mode - starting WebSocket server on port ${DebugGlassesServer.DEFAULT_PORT}")
        _debugModeEnabled.value = true

        debugServer = DebugGlassesServer().apply {
            onGlassesConnected = {
                _connectionState.value = ConnectionState.Connected("Debug Glasses (WebSocket)")
            }
            onGlassesDisconnected = {
                _connectionState.value = ConnectionState.Disconnected
            }
            onMessageFromGlasses = { message ->
                this@GlassesConnectionManager.onMessageFromGlasses?.invoke(message)
            }
            start()
        }

        // Update state to show we're waiting for connection
        _connectionState.value = ConnectionState.Scanning
    }

    /**
     * Disable debug mode and stop the WebSocket server.
     */
    fun disableDebugMode() {
        stopDebugServer()
        _debugModeEnabled.value = false
        _connectionState.value = ConnectionState.Disconnected
        Log.i(TAG, "Debug mode disabled")
    }

    private fun stopDebugServer() {
        debugServer?.stop()
        debugServer = null
    }

    /**
     * Send a JSON message to glasses (chat messages, streaming chunks, status updates, etc.)
     */
    fun sendRawMessage(jsonMessage: String) {
        val msgType = try {
            org.json.JSONObject(jsonMessage).optString("type", "?")
        } catch (_: Exception) { "?" }
        val isDebug = _debugModeEnabled.value
        Log.d(TAG, "sendRawMessage: type=$msgType, size=${jsonMessage.length}, debug=$isDebug")

        if (isDebug) {
            val sent = debugServer?.sendToGlasses(jsonMessage) ?: false
            if (!sent) {
                Log.w(TAG, "sendRawMessage: debugServer.sendToGlasses returned false (no client?)")
            }
        } else {
            RokidSdkManager.sendToGlasses(jsonMessage)
        }
    }

    /**
     * Check if we can upload APKs (WiFi P2P connected or debug mode)
     */
    fun canUploadApk(): Boolean {
        return _debugModeEnabled.value || _wifiP2PConnected.value
    }

}
