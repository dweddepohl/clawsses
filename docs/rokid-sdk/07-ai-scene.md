# AI Scene

## 1. Listen for AI Events on the Glasses

Set up an `AiEventListener` to listen for AI scene events from the Glasses.

```kotlin
private val aiEventListener = object : AiEventListener {
    /** When the key is long pressed */
    override fun onAiKeyDown() { }
    /** When the key is released (currently has no effect) */
    override fun onAiKeyUp() { }
    /** When the AI Scene exits */
    override fun onAiExit() { }
}

fun setAiEventListener(set: Boolean) {
    CxrApi.getInstance().setAiEventListener(if (set) aiEventListener else null)
}
```

## 2. Send Exit Event to the Glasses

Exit the AI scene on the Glasses.

```kotlin
fun sendExitEvent(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendExitEvent()
}
```

## 3. Send ASR Content to the Glasses

After obtaining the ASR result on the mobile device, push the content to the Glasses. Handle empty results with `notifyAsrNone()`, errors with `notifyAsrError()`, and end of recognition with `notifyAsrEnd()`.

```kotlin
fun sendAsrContent(content: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendAsrContent(content)
}

fun notifyAsrNone(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAsrNone()
}

fun notifyAsrError(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAsrError()
}

fun notifyAsrEnd(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAsrEnd()
}
```

## 4. Camera Operations in the AI Process

Open the camera with `openGlassCamera(width, height, quality)`, then take a photo with `takeGlassPhoto(width, height, quality, callback)`. Quality value ranges from **[0-100]**.

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

## 5. AI Return Results

Send AI results to the Glasses display using TTS content, and notify when TTS playback ends.

```kotlin
fun sendTTSContent(content: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendTtsContent(content)
}

fun notifyTtsAudioFinished(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyTtsAudioFinished()
}
```

## 6. Error Handling in the AI Process

```kotlin
// No network
fun notifyNoNetwork(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyNoNetwork()
}

// Image upload error
fun notifyPicUploadError(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyPicUploadError()
}

// AI request failed
fun notifyAiError(): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().notifyAiError()
}
```
