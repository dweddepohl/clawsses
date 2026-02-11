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

        // Reconnection with exponential backoff
        private const val RECONNECT_BASE_DELAY_MS = 1000L   // Start with 1 second
        private const val RECONNECT_MAX_DELAY_MS = 60000L   // Cap at 60 seconds
        private const val RECONNECT_BACKOFF_MULTIPLIER = 1.5
        private const val RECONNECT_TIMEOUT_MS = 10000L     // 10s timeout for silent failures

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
        /** Paired but temporarily disconnected; auto-reconnecting in background */
        data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState()
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
    // automatically attempt to reconnect using saved BT credentials with exponential backoff.
    private val reconnectScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var userInitiatedDisconnect = false
    private var reconnectAttempts = 0
    private var currentReconnectDelayMs = RECONNECT_BASE_DELAY_MS

    // Callback for messages from glasses (both BLE and debug modes)
    var onMessageFromGlasses: ((String) -> Unit)? = null

    // AI scene callbacks (glasses long-press voice activation)
    var onAiKeyDown: (() -> Unit)? = null
    var onAiExit: (() -> Unit)? = null

    init {
        // Set up SDK callbacks first so any state transitions are captured
        setupSdkCallbacks()

        // Check if the SDK singleton is already connected (e.g. Activity was recreated
        // while the process and Bluetooth connection are still alive). This prevents the
        // new manager instance from starting at Disconnected and killing the foreground
        // service + BT connection.
        if (RokidSdkManager.isReady() && RokidSdkManager.isConnected()) {
            val name = RokidSdkManager.getSavedDeviceName() ?: "Rokid Glasses"
            _connectionState.value = ConnectionState.Connected(name)
            Log.i(TAG, "SDK already connected on init — restored Connected state ($name)")
        }
    }

    private fun setupSdkCallbacks() {
        RokidSdkManager.onGlassesConnected = {
            val name = RokidSdkManager.getSavedDeviceName() ?: "Rokid Glasses"
            _connectionState.value = ConnectionState.Connected(name)
            // Reset reconnect state on successful connection
            reconnectAttempts = 0
            currentReconnectDelayMs = RECONNECT_BASE_DELAY_MS
            reconnectJob?.cancel()
            RokidSdkManager.setScreenOffTimeout(30)
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
            Log.e(TAG, "SDK: Bluetooth failed: $error")

            // Schedule reconnect if this wasn't user-initiated.
            // This covers both: failures during active reconnect attempts AND
            // failures from the initial tryAutoReconnectOnStartup() call.
            if (!userInitiatedDisconnect && !_debugModeEnabled.value &&
                RokidSdkManager.hasSavedConnectionInfo()) {
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.Error("Bluetooth failed: $error")
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
     * Try to auto-reconnect to previously paired glasses on app startup.
     * Only attempts if there's saved connection info and not in debug mode.
     *
     * If the SDK singleton reports it's already connected (e.g. Activity recreation
     * while process is alive), this is a no-op since the init block already set
     * Connected state.
     *
     * Returns true if reconnection was initiated, false if no action needed.
     */
    fun tryAutoReconnectOnStartup(): Boolean {
        if (_debugModeEnabled.value) {
            Log.d(TAG, "Auto-reconnect skipped: debug mode enabled")
            return false
        }

        // Already connected (set by init block)? No action needed.
        if (_connectionState.value is ConnectionState.Connected) {
            Log.d(TAG, "Auto-reconnect skipped: already connected")
            return false
        }

        // Initialize SDK if not already
        if (!RokidSdkManager.isReady()) {
            if (!RokidSdkManager.initialize(context)) {
                Log.w(TAG, "Auto-reconnect skipped: SDK initialization failed")
                return false
            }
        }

        if (!RokidSdkManager.hasSavedConnectionInfo()) {
            Log.d(TAG, "Auto-reconnect skipped: no saved connection info")
            return false
        }

        Log.i(TAG, "Attempting auto-reconnect to previously paired glasses")
        userInitiatedDisconnect = false

        // Use scheduleReconnect which has proper exponential backoff and retry.
        // This is more robust than calling reconnect() directly because if the
        // first attempt fails (silently or via onBluetoothFailed), scheduleReconnect
        // will keep trying.
        scheduleReconnect()
        return true
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
     * Disconnect from glasses (user-initiated).
     * This sets userInitiatedDisconnect to prevent auto-reconnect and explicitly
     * stops the foreground service. Pairing info is preserved so the user can
     * reconnect later without re-pairing.
     */
    fun disconnect() {
        userInitiatedDisconnect = true
        resetReconnectState()
        if (_debugModeEnabled.value) {
            stopDebugServer()
        } else {
            RokidSdkManager.disconnect()
        }
        _connectionState.value = ConnectionState.Disconnected
        _wifiP2PConnected.value = false
        // Explicitly stop the foreground service on user-initiated disconnect.
        // The LaunchedEffect won't stop it because hasSavedConnectionInfo() is still true.
        com.clawsses.phone.service.GlassesConnectionService.stop(context)
    }

    /**
     * Schedule auto-reconnect with exponential backoff.
     * Uses increasing delays (1s, 1.5s, 2.25s, ...) capped at 60 seconds.
     * Continues indefinitely until connection succeeds or user manually disconnects.
     *
     * Includes a timeout: if the SDK doesn't fire onConnected or onFailed within
     * RECONNECT_TIMEOUT_MS, we assume the attempt failed silently and retry.
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()

        // Check if we have saved connection info
        if (!RokidSdkManager.hasSavedConnectionInfo()) {
            Log.w(TAG, "Cannot auto-reconnect: no saved connection info")
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        val attempt = reconnectAttempts + 1
        val delayMs = currentReconnectDelayMs

        Log.i(TAG, "Auto-reconnect attempt $attempt in ${delayMs}ms (exponential backoff)")
        _connectionState.value = ConnectionState.Reconnecting(attempt, delayMs)

        reconnectJob = reconnectScope.launch {
            delay(delayMs)

            // Update state before attempting
            reconnectAttempts = attempt

            // Calculate next delay with exponential backoff, capped at max
            currentReconnectDelayMs = (currentReconnectDelayMs * RECONNECT_BACKOFF_MULTIPLIER)
                .toLong()
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)

            if (RokidSdkManager.reconnect(attempt)) {
                Log.i(TAG, "Auto-reconnect initiated (attempt $attempt)")

                // Timeout: if no callback fires within RECONNECT_TIMEOUT_MS, the SDK
                // silently failed. Schedule another attempt.
                delay(RECONNECT_TIMEOUT_MS)
                val state = _connectionState.value
                if (state is ConnectionState.Reconnecting || state is ConnectionState.Connecting) {
                    Log.w(TAG, "Auto-reconnect attempt $attempt timed out (no SDK callback)")
                    scheduleReconnect()
                }
            } else {
                Log.w(TAG, "Auto-reconnect failed (no saved connection info)")
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    /**
     * Stop any ongoing auto-reconnect attempts.
     * Call this when user manually disconnects or clears pairing.
     */
    private fun resetReconnectState() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        currentReconnectDelayMs = RECONNECT_BASE_DELAY_MS
    }

    /**
     * Cancel auto-reconnect and go back to Disconnected state.
     * Called when the user wants to stop waiting for auto-reconnect.
     * From Disconnected, the existing Scan button lets them re-pair manually.
     */
    fun cancelReconnect() {
        resetReconnectState()
        userInitiatedDisconnect = true // prevent auto-reconnect from re-triggering
        RokidSdkManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
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
