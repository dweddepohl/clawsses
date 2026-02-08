# Picture/Video/Audio

## Taking Photos

Since taking photos requires the camera to be turned on, it is a high-energy operation. Depending on the method of using the final photo result, three photo-taking methods are provided:

1. Taking photos with a single-function key, with results stored in unsynchronized media files
2. Taking photos in AI scenes, with results transmitted to mobile device via Bluetooth
3. A photo-taking interface, through which the storage address can be obtained or stored in unsynchronized media files

### 1. Setting Parameters for Taking Photos with a Function Key

Use `fun setPhotoParams(width: Int, height: Int): ValueUtil.CxrStatus`.

```kotlin
fun setPhotoParams(width: Int, height: Int): ValueUtil.CxrStatus{
    return CxrApi.getInstance().setPhotoParams(width, height)
}
```

### 2. Taking Photos in AI Scenes

Use `fun openGlassCamera(width, height, quality): CxrStatus` to open camera, then `fun takeGlassPhoto(width, height, quality, callback): CxrStatus` to take photos. Returns WebP format image data via `PhotoResultCallback`.

**Allowed resolutions:** 4032x3024, 4000x3000, 4032x2268, 3264x2448, 3200x2400, 2268x3024, 2876x2156, 2688x2016, 2582x1936, 2400x1800, 1800x2400, 2560x1440, 2400x1350, 2048x1536, 2016x1512, 1920x1080, 1600x1200, 1440x1080, 1280x720, 720x1280, 1024x768, 800x600, 648x648, 854x480, 800x480, 640x480, 480x640, 352x288, 320x240, 320x180, 176x144.

> **Note:** Since image files are transmitted via Bluetooth, please choose the smallest possible image format based on the requirements of the AI scene.

```kotlin
private val result = object : PhotoResultCallback {
    /**
     * @param status photo take status
     * @param photo WebP photo data byte array
     */
    override fun onPhotoResult(status: ValueUtil.CxrStatus?, photo: ByteArray?) { }
}

fun aiOpenCamera(width: Int, height: Int, quality: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().openGlassCamera(width, height, quality)
}

fun takePhoto(width: Int, height: Int, quality: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().takeGlassPhoto(width, height, quality, result)
}
```

### 3. Invoking the Camera to Take Photos (Path-based)

Use `fun takePhoto(width, height, quality, callback: PhotoPathCallback)` to get the path of the photo result.

```kotlin
private val photoPathResult = object : PhotoPathCallback {
    override fun onPhotoPath(status: ValueUtil.CxrStatus?, path: String?) { }
}

fun takePhotoPath(width: Int, height: Int, quality: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().takeGlassPhoto(width, height, quality, photoPathResult)
}
```

## Recording Videos

### 1. Setting Video Recording Parameters

Use `fun setVideoParams(duration, fps, width, height, unit): CxrStatus`.

```kotlin
/**
 * @param duration duration
 * @param fps fps, supports {30}
 * @param width width
 * @param height height
 * @param unit 0: minute, 1: second
 */
fun setVideoParams(duration: Int, fps: Int, width: Int, height: Int, unit: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().setVideoParams(duration, fps, width, height, unit)
}
```

### 2. Starting/Stopping Video Recording

Use `fun controlScene(sceneType, openOrClose, otherParams)` with `CxrSceneType.VIDEO_RECORD`.

```kotlin
fun openOrCloseVideoRecord(openOrClose: Boolean): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.VIDEO_RECORD, openOrClose, null)
}
```

## Recording Audio

Start: `fun openAudioRecord(codecType: Int, streamType: String?): CxrStatus?`
Stop: `fun closeAudioRecord(streamType: String): CxrStatus?`
Listen: `fun setVideoStreamListener(callback: AudioStreamListener)`

```kotlin
private val audioStreamListener = object : AudioStreamListener {
    /**
     * @param codecType The stream codec type: 1:pcm, 2:opus
     * @param streamType The stream type such as "AI_assistant"
     */
    override fun onStartAudioStream(codecType: Int, streamType: String?) { }

    /**
     * @param data The audio stream data
     * @param offset The offset of audio stream data
     * @param length The length of audio stream data
     */
    override fun onAudioStream(data: ByteArray?, offset: Int, length: Int) { }
}

fun setVideoStreamListener(set: Boolean) {
    CxrApi.getInstance().setAudioStreamListener(if (set) audioStreamListener else null)
}

fun openAudioRecord(codecType: Int, streamType: String?): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().openAudioRecord(codecType, streamType)
}

fun closeAudioRecord(streamType: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().closeAudioRecord(streamType)
}
```
