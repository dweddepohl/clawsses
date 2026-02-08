# Translation Scene

## 1. Turn On/Off Translation Scene

Use `fun controlScene(CxrSceneType.Translation, openOrClose, null): CxrStatus?`.

```kotlin
fun openOrCloseTranslation(toOpen: Boolean): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().controlScene(ValueUtil.CxrSceneType.WORD_TIPS, toOpen, null)
}
```

## 2. Send Translation Content to Glasses

In the translation scene, the glasses enter **far-field sound pickup mode** (the wearer's voice will not be picked up). The translation results of the conversing party are displayed.

Use `fun sendTranslationContent(id, subId, temporary, finished, content): CxrStatus?`.

- `id`: Serial number of a specific VAD (Voice Activity Detection)
- `subId`: Sub-ID for partial results
- `temporary`: `true` for interim results, `false` for final
- `finished`: `true` when content is complete

```kotlin
fun sendTranslationContent(
    vadId: Int,
    subId: Int,
    temporary: Boolean,
    finished: Boolean,
    content: String
): ValueUtil.CxrStatus {
    return CxrApi.getInstance().sendTranslationContent(vadId, subId, temporary, finished, content)
}
```

## 3. Configure Display Parameters for Translation Content

Use `fun configTranslationText(textSize, startPointX, startPointY, width, height): CxrStatus?`.

```kotlin
fun configTranslationText(
    textSize: Int,
    startPointX: Int,
    startPointY: Int,
    width: Int,
    height: Int
): ValueUtil.CxrStatus {
    return CxrApi.getInstance().configTranslationText(
        textSize, startPointX, startPointY, width, height
    )
}
```
