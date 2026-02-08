# Teleprompter Scene

## 1. Enable/Disable Teleprompter Scene

Toggle using `fun controlScene(CxrSceneType.WORD_TIPS, openOrClose, null): CxrStatus?`.

```kotlin
fun openOrCloseWordTips(toOpen: Boolean): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.WORD_TIPS, toOpen, null)
}
```

## 2. Send Teleprompter Data

Send content via `fun sendStream(CxrStreamType.WORD_TIPS, content, fileName, callback)`.

```kotlin
private val sendCallback = object : SendStatusCallback {
    override fun onSendSucceed() { }
    override fun onSendFailed(p0: ValueUtil.CxrSendErrorCode?) { }
}

fun setWordTipsText(text: String, fileName: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendStream(
        ValueUtil.CxrStreamType.WORD_TIPS,
        text.toByteArray(),
        fileName,
        sendCallback
    )
}
```

## 3. Configure Teleprompter Scene Parameters

Use `fun configWordTipsText(textSize, lineSpace, mode, startPointX, startPointY, width, height): CxrStatus?`.

**Mode values:**
- `"normal"` - Standard mode
- `"ai"` - AI mode, auto-scrolling when ASR result reaches the last few words on screen

```kotlin
fun configWordTipsText(
    textSize: Float,
    lineSpace: Float,
    mode: String,
    startPointX: Int,
    startPointY: Int,
    width: Int,
    height: Int
): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().configWordTipsText(
        textSize, lineSpace, mode, startPointX, startPointY, width, height
    )
}
```

## 4. Teleprompter ASR Results

When in "ai" mode, send ASR results to trigger auto-scrolling when recognition reaches the last few characters.

```kotlin
fun sendWordTipsAsrContent(content: String): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().sendAsrContent(content)
}
```
