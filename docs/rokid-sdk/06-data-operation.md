# Data Operation

## 1. Send Data to Glasses

Before sending data to the glasses, ensure that the device is connected via Bluetooth.

Use `fun sendStream(type: CxrStreamType, stream: ByteArray, fileName: String, cb: SendStatusCallback): CxrStatus?`.

```kotlin
val streamCallback = object : SendStatusCallback{
    override fun onSendSucceed() { }

    /**
     * @param errorCode
     * @see ValueUtil.CxrSendErrorCode
     */
    override fun onSendFailed(errorCode: ValueUtil.CxrSendErrorCode?) { }
}

/**
 * @param type
 * @see ValueUtil.CxrStreamType
 * @see ValueUtil.CxrStreamType.WORD_TIPS teleprompter words
 */
fun sendStream(type: ValueUtil.CxrStreamType, stream: ByteArray, fileName: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendStream(type, stream, fileName, streamCallback)
}
```

## 2. Read Unsynced Media Files from Glasses

Use `fun getUnsyncNum(cb: UnsyncNumResultCallback): CxrStatus`.

```kotlin
private val unSyncCallback = object : UnsyncNumResultCallback{
    /**
     * @param status response status
     * @param audioNum number of unsynced audio files
     * @param pictureNum number of unsynced picture files
     * @param videoNum number of unsynced video files
     */
    override fun onUnsyncNumResult(
        status: ValueUtil.CxrStatus?,
        audioNum: Int,
        pictureNum: Int,
        videoNum: Int
    ) { }
}

fun getUnsyncNum(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().getUnsyncNum(unSyncCallback)
}
```

## 3. Listen for Media File Updates from Glasses

```kotlin
private val mediaFileUpdateListener = object : MediaFilesUpdateListener {
    override fun onMediaFilesUpdated() { }
}

fun setMediaFilesUpdateListener(set: Boolean){
    CxrApi.getInstance().setMediaFilesUpdateListener(if (set) mediaFileUpdateListener else null)
}
```

## 4. Sync Media Files

> To sync media files, you need to use the **Wi-Fi communication module**. Please complete the initialization of the Wi-Fi communication module before syncing.

### 4.1 Sync All Files of Specified Types

Use `fun startSync(savePath: String, types: Array<ValueUtil.CxrMediaType>, callback: SyncStatusCallback): Boolean`.

**CxrMediaType values:**
- `AUDIO`: Audio file
- `PICTURE`: Image file
- `VIDEO`: Video file
- `ALL`: All files

**SyncStatusCallback:**
- `onSyncStart()`: Synchronization starts
- `onSingleFileSynced(fileName)`: Single file synced
- `onSyncFailed()`: Synchronization fails
- `onSyncFinished()`: Synchronization completed

```kotlin
private val syncCallback = object : SyncStatusCallback{
    override fun onSyncStart() { }
    override fun onSingleFileSynced(fileName: String?) { }
    override fun onSyncFailed() { }
    override fun onSyncFinished() { }
}

fun startSync(savePath: String, types: Array<ValueUtil.CxrMediaType>): Boolean {
    return CxrApi.getInstance().startSync(savePath, types, syncCallback)
}
```

### 4.2 Sync a Single File

Use `fun syncSingleFiles(savePath, mediaType, filePath, callback): Boolean`.

```kotlin
fun syncSingleFiles(savePath: String, mediaType: ValueUtil.CxrMediaType, fileName: String): Boolean {
    return CxrApi.getInstance().syncSingleFile(savePath, mediaType, fileName, syncCallback)
}
```

### 4.3 Stop Syncing

```kotlin
private fun stopSync() {
    CxrApi.getInstance().stopSync()
}
```
