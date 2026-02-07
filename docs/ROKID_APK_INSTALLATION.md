# Rokid SDK APK Installation Research

This document summarizes the research findings on Rokid CXR-M SDK capabilities for installing/uploading APKs to Rokid glasses from a connected Android phone.

## SDK Version

- **CXR-M SDK**: `com.rokid.cxr:client-m:1.0.4`
- **Maven Repository**: `https://maven.rokid.com/repository/maven-public/`

## APK Installation API

The Rokid CXR-M SDK provides built-in support for uploading and installing APKs on connected glasses.

### Key Classes

#### CxrApi (Main SDK Singleton)

Location: `com.rokid.cxr.client.extend.CxrApi`

Key APK-related methods:

```kotlin
// Upload and install an APK to the glasses
// Returns true if upload started successfully, false otherwise
fun startUploadApk(apkPath: String, callback: ApkStatusCallback): Boolean

// Cancel an ongoing APK upload
fun stopUploadApk()

// Uninstall an app from the glasses
fun uninstallApk(packageName: String, callback: ApkStatusCallback): CxrStatus

// Open/launch an app on the glasses
fun openApp(appInfo: RKAppInfo, callback: ApkStatusCallback): CxrStatus
```

#### ApkStatusCallback (Progress Interface)

Location: `com.rokid.cxr.client.extend.callbacks.ApkStatusCallback`

```kotlin
interface ApkStatusCallback {
    // Called when APK file upload to glasses completes successfully
    fun onUploadApkSucceed()

    // Called when APK file upload fails
    fun onUploadApkFailed()

    // Called when APK installation on glasses completes successfully
    fun onInstallApkSucceed()

    // Called when APK installation on glasses fails
    fun onInstallApkFailed()

    // Called when app uninstall completes successfully
    fun onUninstallApkSucceed()

    // Called when app uninstall fails
    fun onUninstallApkFailed()

    // Called when app launch completes successfully
    fun onOpenAppSucceed()

    // Called when app launch fails
    fun onOpenAppFailed()
}
```

#### RKAppInfo (App Identifier)

Location: `com.rokid.cxr.client.extend.infos.RKAppInfo`

```kotlin
class RKAppInfo(packageName: String, activityName: String) {
    fun getPackageName(): String
    fun setPackageName(packageName: String)
    fun getActivityName(): String
    fun setActivityName(activityName: String)
}
```

### FileController (Alternative/Internal API)

Location: `com.rokid.cxr.client.extend.controllers.FileController`

The SDK also has a `FileController` singleton that handles file transfers:

```kotlin
// Alternative method for APK upload (lower-level)
fun startUploadApk(apkFile: File, glassesIp: String, callback: ApkStatusCallback)
fun stopUploadApk()
```

This suggests the APK upload happens over WiFi P2P (not Bluetooth), which explains why the SDK has WiFi P2P initialization methods.

## Communication Channels

Based on SDK analysis, APK uploads use **WiFi P2P** (not Bluetooth):

1. **Bluetooth**: Used for control commands, status, and small data transfers
2. **WiFi P2P**: Used for large file transfers (media sync, APK uploads)

### WiFi P2P API

```kotlin
// Initialize WiFi P2P connection
fun initWifiP2P(callback: WifiP2PStatusCallback): CxrStatus

// Check if WiFi P2P is connected
fun isWifiP2PConnected(): Boolean

// Deinitialize WiFi P2P
fun deinitWifiP2P(): CxrStatus
```

## Implementation Flow

### Prerequisites

1. **Bluetooth Connected**: `cxrApi.isBluetoothConnected()` must return `true`
2. **WiFi P2P Connected**: For APK upload, WiFi P2P connection may be required

### Upload Sequence

```kotlin
// 1. Ensure Bluetooth connection is established
if (!cxrApi.isBluetoothConnected()) {
    // Connect first
    cxrApi.connectBluetooth(...)
}

// 2. Initialize WiFi P2P (if required for APK upload)
cxrApi.initWifiP2P(object : WifiP2PStatusCallback {
    override fun onWifiP2PConnected() {
        // Ready for APK upload
    }
    override fun onWifiP2PDisconnected() { }
    override fun onWifiP2PFailed() { }
})

// 3. Start APK upload
val apkPath = "/path/to/glasses-app.apk"
val started = cxrApi.startUploadApk(apkPath, object : ApkStatusCallback {
    override fun onUploadApkSucceed() {
        // File transferred, waiting for installation
    }

    override fun onUploadApkFailed() {
        // Upload failed - handle error
    }

    override fun onInstallApkSucceed() {
        // APK installed successfully!
    }

    override fun onInstallApkFailed() {
        // Installation failed - handle error
    }

    // Other methods for uninstall/open...
    override fun onUninstallApkSucceed() {}
    override fun onUninstallApkFailed() {}
    override fun onOpenAppSucceed() {}
    override fun onOpenAppFailed() {}
})

// 4. Optionally cancel
cxrApi.stopUploadApk()
```

## Limitations

1. **No Progress Percentage**: The `ApkStatusCallback` only provides success/failure callbacks, not transfer progress percentage
2. **WiFi P2P Required**: Large file transfers likely require WiFi P2P, not just Bluetooth
3. **Limited Error Details**: Callbacks don't provide detailed error codes for APK operations

## Error Handling

The SDK uses `CxrStatus` enum for return values. Common scenarios:

- **Not connected**: Operation fails if Bluetooth not connected
- **WiFi P2P not ready**: APK upload may fail if WiFi P2P not established
- **File not found**: Invalid APK path
- **Installation failure**: APK incompatible, insufficient storage, etc.

## Alternative: ADB over WiFi

For development/debugging, an alternative is to use ADB over WiFi:

1. Connect glasses to same WiFi network
2. Enable ADB over WiFi on glasses
3. Use `adb connect <glasses-ip>:5555`
4. Use `adb install glasses-app.apk`

However, this requires manual setup and is not suitable for end-user deployment.

## Recommendations

1. **Use CxrApi.startUploadApk()**: Primary SDK method for APK deployment
2. **Handle WiFi P2P**: May need to establish WiFi P2P before upload
3. **Provide UI feedback**: Show uploading/installing states based on callbacks
4. **Graceful fallback**: Provide manual installation instructions if SDK upload fails
5. **Bundle APK**: Consider bundling glasses-app APK in phone app assets
