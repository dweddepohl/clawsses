package com.claudeglasses.phone.glasses

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.claudeglasses.phone.BuildConfig
import com.rokid.cxr.Caps
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.listeners.CustomCmdListener
import com.rokid.cxr.client.utils.ValueUtil
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages Rokid CXR-M SDK initialization and lifecycle.
 *
 * Connection flow:
 * 1. initialize(context) - Get CxrApi singleton, set up listeners
 * 2. initBluetooth(device) - Start Bluetooth init with discovered device
 *    -> callback.onConnectionInfo(socketUuid, macAddress, rokidAccount, glassesType)
 * 3. connectBluetooth(socketUuid, macAddress) - Complete connection
 *    -> callback.onConnected()
 * 4. (Optional) initWifiP2P() - For APK uploads
 *
 * SN verification: The SDK performs an AES-encrypted serial number check after
 * Bluetooth connects. On first attempt, SN_CHECK_FAILED is expected — we read
 * the glasses SN from the SDK via reflection, generate the correct encrypted
 * content, and reconnect automatically.
 */
object RokidSdkManager {

    private const val TAG = "RokidSdkManager"

    private var isInitialized = false
    private var cxrApi: CxrApi? = null
    private var appContext: Context? = null

    // Connection state
    private var isBluetoothConnectedState = false
    private var isWifiP2PConnectedState = false

    // Saved connection info for reconnection
    private var savedSocketUuid: String? = null
    private var savedMacAddress: String? = null
    private var savedRokidAccount: String? = null
    private var savedDeviceName: String? = null

    // Track if we're in init phase (need to call connectBluetooth after getting info)
    private var pendingConnect = false

    // SN auto-generation: first attempt fails, we read the SN and retry
    private var snAutoRetryInProgress = false
    // Generated snEncryptContent for retry (stored after first SN_CHECK_FAILED)
    private var generatedSnEncryptContent: ByteArray? = null

    // Callbacks for glasses events
    var onGlassesConnected: (() -> Unit)? = null
    var onGlassesDisconnected: (() -> Unit)? = null
    var onMessageFromGlasses: ((String, Caps?) -> Unit)? = null
    var onConnectionInfo: ((name: String, mac: String, account: String, type: Int) -> Unit)? = null
    var onBluetoothFailed: ((String) -> Unit)? = null

    // WiFi P2P callbacks
    var onWifiP2PConnected: (() -> Unit)? = null
    var onWifiP2PDisconnected: (() -> Unit)? = null
    var onWifiP2PFailed: (() -> Unit)? = null

    // APK installation callbacks
    var onApkUploadSucceed: (() -> Unit)? = null
    var onApkUploadFailed: (() -> Unit)? = null
    var onApkInstallSucceed: (() -> Unit)? = null
    var onApkInstallFailed: (() -> Unit)? = null

    // AI scene callbacks (voice input via glasses long-press)
    var onAiKeyDown: (() -> Unit)? = null
    var onAiKeyUp: (() -> Unit)? = null
    var onAiExit: (() -> Unit)? = null

    private val bluetoothCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, deviceType: Int) {
            Log.i(TAG, "=== onConnectionInfo ===")
            Log.i(TAG, "  socketUuid=$socketUuid")
            Log.i(TAG, "  macAddress=$macAddress")
            Log.i(TAG, "  rokidAccount=$rokidAccount")
            Log.i(TAG, "  deviceType=$deviceType")

            // Save for reconnection
            savedSocketUuid = socketUuid
            savedMacAddress = macAddress
            savedRokidAccount = rokidAccount
            // Try to save device name from Bluetooth device
            try {
                val name = cxrApi?.let { api ->
                    val glassInfoField = api.javaClass.getDeclaredField("I")
                    glassInfoField.isAccessible = true
                    val glassInfo = glassInfoField.get(api)
                    glassInfo?.javaClass?.getMethod("getDeviceName")?.invoke(glassInfo) as? String
                }
                if (!name.isNullOrEmpty()) {
                    savedDeviceName = name
                    Log.i(TAG, "  deviceName=$name")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not read device name from GlassInfo: ${e.message}")
            }
            onConnectionInfo?.invoke(socketUuid ?: "", macAddress ?: "", rokidAccount ?: "", deviceType)

            // After initBluetooth, call connectBluetooth to complete the connection
            if (pendingConnect && !socketUuid.isNullOrEmpty() && !macAddress.isNullOrEmpty()) {
                Log.i(TAG, "Got connection info, now calling connectBluetooth...")
                pendingConnect = false
                connectBluetoothInternal(socketUuid, macAddress, rokidAccount ?: "")
            }
        }

        override fun onConnected() {
            Log.i(TAG, "=== onConnected === Bluetooth connected to glasses!")
            isBluetoothConnectedState = true
            pendingConnect = false
            snAutoRetryInProgress = false
            onGlassesConnected?.invoke()
        }

        override fun onDisconnected() {
            Log.i(TAG, "=== onDisconnected === Bluetooth disconnected from glasses")
            isBluetoothConnectedState = false
            onGlassesDisconnected?.invoke()
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            Log.e(TAG, "=== onFailed === Bluetooth connection failed: $errorCode")
            isBluetoothConnectedState = false
            pendingConnect = false

            // SN_CHECK_FAILED means BT connected but SN verification failed.
            // Read the glasses SN from the SDK, generate encrypted content, and retry.
            if (errorCode == ValueUtil.CxrBluetoothErrorCode.SN_CHECK_FAILED && !snAutoRetryInProgress) {
                Log.i(TAG, "SN_CHECK_FAILED - attempting auto-recovery...")
                val glassesSn = readGlassesSnFromSdk()
                if (glassesSn != null && glassesSn.isNotEmpty()) {
                    val clientSecret = BuildConfig.ROKID_CLIENT_SECRET.replace("-", "")
                    val encrypted = generateSnEncryptContent(glassesSn, clientSecret)
                    if (encrypted != null) {
                        Log.i(TAG, "Generated snEncryptContent for SN=$glassesSn (${encrypted.size} bytes)")
                        generatedSnEncryptContent = encrypted
                        snAutoRetryInProgress = true
                        // Retry connection with correct snEncryptContent
                        val uuid = savedSocketUuid
                        val mac = savedMacAddress
                        if (!uuid.isNullOrEmpty() && !mac.isNullOrEmpty()) {
                            Log.i(TAG, "Retrying connectBluetooth with generated snEncryptContent...")
                            connectBluetoothInternal(uuid, mac, savedRokidAccount ?: "")
                            return
                        }
                    }
                }
                Log.e(TAG, "SN auto-recovery failed - could not read glasses SN or generate encrypted content")
            }

            snAutoRetryInProgress = false
            onBluetoothFailed?.invoke(errorCode?.name ?: "Unknown error")
        }
    }

    private val wifiP2PCallback = object : WifiP2PStatusCallback {
        override fun onConnected() {
            Log.i(TAG, "=== WiFi P2P onConnected === WiFi P2P link established!")
            isWifiP2PConnectedState = true
            onWifiP2PConnected?.invoke()
        }

        override fun onDisconnected() {
            Log.i(TAG, "=== WiFi P2P onDisconnected ===")
            isWifiP2PConnectedState = false
            onWifiP2PDisconnected?.invoke()
        }

        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            Log.e(TAG, "=== WiFi P2P onFailed === errorCode=$errorCode")
            isWifiP2PConnectedState = false
            onWifiP2PFailed?.invoke()
        }

        override fun onP2pDeviceAvailable(deviceName: String?, ip: String?, port: String?) {
            Log.i(TAG, "=== WiFi P2P onP2pDeviceAvailable === device=$deviceName, ip=$ip, port=$port")
        }
    }

    private val apkCallback = object : ApkStatusCallback {
        override fun onUploadApkSucceed() {
            Log.d(TAG, "APK upload succeeded")
            onApkUploadSucceed?.invoke()
        }

        override fun onUploadApkFailed() {
            Log.e(TAG, "APK upload failed")
            onApkUploadFailed?.invoke()
        }

        override fun onInstallApkSucceed() {
            Log.d(TAG, "APK installation succeeded")
            onApkInstallSucceed?.invoke()
        }

        override fun onInstallApkFailed() {
            Log.e(TAG, "APK installation failed")
            onApkInstallFailed?.invoke()
        }

        override fun onUninstallApkSucceed() {
            Log.d(TAG, "APK uninstall succeeded")
        }

        override fun onUninstallApkFailed() {
            Log.e(TAG, "APK uninstall failed")
        }

        override fun onOpenAppSucceed() {
            Log.d(TAG, "App opened successfully")
        }

        override fun onOpenAppFailed() {
            Log.e(TAG, "Failed to open app")
        }
    }

    /**
     * Initialize the CxrApi singleton and set up listeners.
     * Registers the access key for SN verification during Bluetooth connection.
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            Log.d(TAG, "SDK already initialized")
            return true
        }

        appContext = context.applicationContext

        try {
            cxrApi = CxrApi.getInstance()

            // Register access key for SN verification (required for connectBluetooth)
            val accessKey = BuildConfig.ROKID_ACCESS_KEY
            if (accessKey.isNotEmpty()) {
                cxrApi?.updateRokidAccount(accessKey)
                Log.d(TAG, "Rokid account registered (accessKey length=${accessKey.length})")
            } else {
                Log.w(TAG, "No ROKID_ACCESS_KEY configured - SN verification may fail")
            }

            // Set up custom command listener to receive messages from glasses
            // The glasses sends via bridge.sendMessage(msgType, caps) where caps contains the actual data.
            // Here, cmd = the message type (e.g. "command"), and caps holds the content string at index 0.
            cxrApi?.setCustomCmdListener(object : CustomCmdListener {
                override fun onCustomCmd(cmd: String?, caps: Caps?) {
                    Log.d(TAG, "Received custom command from glasses: type=$cmd, caps=${caps != null}")
                    if (caps != null && caps.size() > 0) {
                        try {
                            val message = caps.at(0).getString()
                            Log.d(TAG, "Glasses message content (${message.length} chars): ${message.take(100)}")
                            onMessageFromGlasses?.invoke(message, caps)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read message from Caps", e)
                            cmd?.let { onMessageFromGlasses?.invoke(it, caps) }
                        }
                    } else {
                        cmd?.let { onMessageFromGlasses?.invoke(it, caps) }
                    }
                }
            })

            // Set up AI event listener for glasses long-press voice activation
            cxrApi?.setAiEventListener(object : com.rokid.cxr.client.extend.listeners.AiEventListener {
                override fun onAiKeyDown() {
                    Log.i(TAG, "AI key pressed on glasses (long press)")
                    onAiKeyDown?.invoke()
                }
                override fun onAiKeyUp() {
                    Log.d(TAG, "AI key released on glasses")
                    onAiKeyUp?.invoke()
                }
                override fun onAiExit() {
                    Log.d(TAG, "AI scene exited on glasses")
                    onAiExit?.invoke()
                }
            })

            Log.d(TAG, "Rokid SDK initialized successfully")
            isInitialized = true
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Rokid SDK", e)
            return false
        }
    }

    /**
     * Initialize Bluetooth connection with a discovered device.
     * This triggers onConnectionInfo callback, then we automatically call
     * connectBluetooth to complete the connection.
     */
    fun initBluetooth(device: BluetoothDevice) {
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            Log.i(TAG, "=== initBluetooth === Starting with device: ${device.address}")
            pendingConnect = true
            cxrApi?.initBluetooth(context, device, bluetoothCallback)
            Log.i(TAG, "initBluetooth called, waiting for onConnectionInfo callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth", e)
            pendingConnect = false
        }
    }

    /**
     * Connect using socketUuid and macAddress from onConnectionInfo.
     *
     * SDK signature: connectBluetooth(context, socketUuid, macAddress, callback, snEncryptContent, clientSecret)
     *
     * The SDK performs an SN verification after BT connects:
     * 1. Gets glasses SN via getGlassInfo
     * 2. Decrypts snEncryptContent with clientSecret (AES/CBC/PKCS5Padding)
     * 3. Checks if decrypted text contains the glasses SN
     *
     * On first connect we pass empty snEncryptContent, which triggers SN_CHECK_FAILED.
     * The onFailed handler then reads the SN via reflection and auto-retries with
     * correctly generated encrypted content.
     */
    private fun connectBluetoothInternal(socketUuid: String, macAddress: String, rokidAccount: String = "") {
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            val clientSecret = BuildConfig.ROKID_CLIENT_SECRET

            Log.i(TAG, "=== connectBluetoothInternal ===")
            Log.i(TAG, "  socketUuid=$socketUuid")
            Log.i(TAG, "  macAddress=$macAddress")
            Log.i(TAG, "  autoRetry=$snAutoRetryInProgress, cachedSn=${generatedSnEncryptContent != null}")

            // Use cached snEncryptContent if available (from previous SN auto-recovery).
            // Only use dummy content on the very first connection attempt when we don't
            // know the glasses SN yet. This avoids a redundant two-pass flow on reconnects.
            val encryptContent = if (generatedSnEncryptContent != null) {
                Log.i(TAG, "Using cached snEncryptContent (${generatedSnEncryptContent!!.size} bytes)")
                generatedSnEncryptContent!!
            } else {
                Log.i(TAG, "First attempt - using dummy snEncryptContent (SN_CHECK_FAILED expected)")
                ByteArray(16)
            }

            cxrApi?.connectBluetooth(
                context,
                socketUuid,
                macAddress,
                bluetoothCallback,
                encryptContent,
                clientSecret
            )
            Log.i(TAG, "connectBluetooth called, waiting for callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting via Bluetooth", e)
        }
    }

    /**
     * Connect to glasses via Bluetooth using saved connection info.
     */
    fun connectBluetooth(socketUuid: String, macAddress: String) {
        connectBluetoothInternal(socketUuid, macAddress)
    }

    // ============== SN Auto-Generation Helpers ==============

    /**
     * Read the glasses serial number from CxrApi's internal GlassInfo field (field I).
     * The SDK populates this in the getGlassInfo response handler, which runs
     * before the SN check — so even on SN_CHECK_FAILED, the SN is available.
     */
    private fun readGlassesSnFromSdk(): String? {
        return try {
            val api = cxrApi ?: return null
            // CxrApi stores GlassInfo in field 'I'
            val glassInfoField = api.javaClass.getDeclaredField("I")
            glassInfoField.isAccessible = true
            val glassInfo = glassInfoField.get(api) ?: run {
                Log.w(TAG, "GlassInfo field I is null")
                return null
            }
            // GlassInfo.getDeviceId() returns the serial number
            val getDeviceId = glassInfo.javaClass.getMethod("getDeviceId")
            val sn = getDeviceId.invoke(glassInfo) as? String
            Log.i(TAG, "Read glasses SN from SDK: $sn")
            sn
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read glasses SN from SDK via reflection", e)
            null
        }
    }

    /**
     * Generate snEncryptContent by encrypting the glasses SN using the same
     * algorithm the SDK uses for verification: AES/CBC/PKCS5Padding.
     *
     * Key = clientSecret bytes (32 chars = 32 bytes for AES-256)
     * IV = first 16 bytes of clientSecret
     */
    private fun generateSnEncryptContent(glassesSn: String, clientSecret: String): ByteArray? {
        return try {
            val keyBytes = clientSecret.toByteArray(Charsets.UTF_8)
            val key = SecretKeySpec(keyBytes, "AES")
            val iv = IvParameterSpec(keyBytes, 0, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(glassesSn.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate snEncryptContent", e)
            null
        }
    }


    /**
     * Send a custom command/message to the glasses via Bluetooth
     */
    fun sendToGlasses(command: String, caps: Caps = Caps()): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Not connected to glasses via Bluetooth")
            return false
        }

        return try {
            caps.write(command)
            cxrApi?.sendCustomCmd("terminal", caps)
            Log.d(TAG, "Sent to glasses: ${command.take(50)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to glasses", e)
            false
        }
    }

    // ============== WiFi P2P Methods ==============

    /**
     * Initialize WiFi P2P connection for data transfer (APK uploads, etc.)
     * Call this after Bluetooth is connected.
     */
    fun initWifiP2P(): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Bluetooth not connected - connect via Bluetooth first")
            return false
        }

        return try {
            // initWifiP2P2(connectP2p=true) tells the SDK to auto-connect WiFi
            // when the glasses report P2P device availability.
            // Plain initWifiP2P() sets connectP2p=false and skips the connection.
            val status = cxrApi?.initWifiP2P2(true, wifiP2PCallback)
            Log.d(TAG, "WiFi P2P initialization status: $status")
            status == ValueUtil.CxrStatus.REQUEST_SUCCEED
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WiFi P2P", e)
            false
        }
    }

    /**
     * Deinitialize WiFi P2P connection
     */
    fun deinitWifiP2P() {
        try {
            cxrApi?.deinitWifiP2P()
            isWifiP2PConnectedState = false
            Log.d(TAG, "WiFi P2P deinitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error deinitializing WiFi P2P", e)
        }
    }

    /**
     * Check if WiFi P2P is connected
     */
    fun isWifiP2PConnected(): Boolean {
        return try {
            cxrApi?.isWifiP2PConnected ?: false
        } catch (e: Exception) {
            isWifiP2PConnectedState
        }
    }

    // ============== APK Installation Methods ==============

    /**
     * Upload and install an APK on the glasses via WiFi P2P.
     * Requires: Bluetooth connected AND WiFi P2P connected
     */
    fun startUploadApk(apkPath: String): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Bluetooth not connected")
            return false
        }

        return try {
            val result = cxrApi?.startUploadApk(apkPath, apkCallback) ?: false
            Log.d(TAG, "startUploadApk result: $result for path: $apkPath")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error starting APK upload", e)
            false
        }
    }

    /**
     * Cancel an ongoing APK upload
     */
    fun stopUploadApk() {
        try {
            cxrApi?.stopUploadApk()
            Log.d(TAG, "APK upload stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping APK upload", e)
        }
    }

    // ============== Status Methods ==============

    fun isReady(): Boolean = isInitialized

    fun isConnected(): Boolean {
        return try {
            cxrApi?.isBluetoothConnected ?: false
        } catch (e: Exception) {
            isBluetoothConnectedState
        }
    }

    fun getSavedMacAddress(): String? = savedMacAddress
    fun getSavedDeviceName(): String? = savedDeviceName
    fun getSavedSocketUuid(): String? = savedSocketUuid

    /**
     * Attempt to reconnect to previously connected glasses
     */
    fun reconnect(): Boolean {
        val socketUuid = savedSocketUuid
        val mac = savedMacAddress
        if (socketUuid.isNullOrEmpty() || mac.isNullOrEmpty()) {
            Log.w(TAG, "No saved connection info for reconnection (socketUuid=$socketUuid, mac=$mac)")
            return false
        }
        Log.i(TAG, "Reconnecting with saved socketUuid and macAddress...")
        connectBluetooth(socketUuid, mac)
        return true
    }

    /**
     * Disconnect from glasses
     */
    fun disconnect() {
        try {
            deinitWifiP2P()
            cxrApi?.deinitBluetooth()
            isBluetoothConnectedState = false
            Log.d(TAG, "Disconnected from glasses")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * Set audio as communication device (for voice input via glasses mic)
     */
    fun setCommunicationDevice() {
        cxrApi?.setCommunicationDevice()
    }

    /**
     * Clear communication device setting
     */
    fun clearCommunicationDevice() {
        cxrApi?.clearCommunicationDevice()
    }

    // --- AI Scene methods (for voice input via glasses long-press) ---

    /**
     * Send ASR (speech recognition) content to the glasses AI scene.
     * The glasses display this text in the AI scene UI.
     */
    fun sendAsrContent(content: String): ValueUtil.CxrStatus? {
        Log.d(TAG, "Sending ASR content to glasses: ${content.take(50)}")
        return cxrApi?.sendAsrContent(content)
    }

    /**
     * Notify glasses that ASR recognition returned no result.
     */
    fun notifyAsrNone(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: ASR none")
        return cxrApi?.notifyAsrNone()
    }

    /**
     * Notify glasses that ASR recognition had an error.
     */
    fun notifyAsrError(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: ASR error")
        return cxrApi?.notifyAsrError()
    }

    /**
     * Notify glasses that ASR recognition has ended.
     */
    fun notifyAsrEnd(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: ASR end")
        return cxrApi?.notifyAsrEnd()
    }

    /**
     * Send exit event to dismiss the AI scene on glasses.
     */
    fun sendExitEvent(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Sending exit event to glasses AI scene")
        return cxrApi?.sendExitEvent()
    }

    /**
     * Send TTS content to the glasses AI scene (for displaying AI response text).
     */
    fun sendTtsContent(content: String): ValueUtil.CxrStatus? {
        Log.d(TAG, "Sending TTS content to glasses: ${content.take(50)}")
        return cxrApi?.sendTtsContent(content)
    }

    /**
     * Notify glasses that TTS audio has finished.
     */
    fun notifyTtsAudioFinished(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: TTS finished")
        return cxrApi?.notifyTtsAudioFinished()
    }

    /**
     * Cleanup SDK resources
     */
    fun cleanup() {
        if (!isInitialized) return

        try {
            deinitWifiP2P()
            cxrApi?.clearCommunicationDevice()
            cxrApi = null
            appContext = null
            isInitialized = false
            isBluetoothConnectedState = false
            isWifiP2PConnectedState = false
            Log.d(TAG, "Rokid SDK cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Rokid SDK", e)
        }
    }
}
