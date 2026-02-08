# Equipment Status and Controls

> Note: For content related to hardware information on the glasses end, ensure that the Bluetooth channel between the mobile phone end and the glasses end is connected.

## 1. Obtaining Glasses Information

Use `fun getGlassInfo(callback: GlassInfoResultCallback): ValueUtil.CxrStatus`.

```kotlin
fun getGlassesInfo() {
    CxrApi.getInstance().getGlassInfo(object : GlassInfoResultCallback {
        /**
         * @param status Information retrieval status
         * @see ValueUtil.CxrStatus.RESPONSE_SUCCEED
         * @see ValueUtil.CxrStatus.RESPONSE_INVALID
         * @see ValueUtil.CxrStatus.RESPONSE_TIMEOUT
         * @param glassesInfo Glasses information
         * @see GlassInfo
         */
        override fun onGlassInfoResult(
            status: ValueUtil.CxrStatus?,
            glassesInfo: GlassInfo?
        ) { }
    })
}
```

## 2. Synchronizing Glasses Time and Time Zone

Use `fun setGlassTime(): CxrStatus`.

```kotlin
fun setTime(): CxrStatus{
    return CxrApi.getInstance().setGlassTime()
}
```

Returns: `REQUEST_SUCCEED`, `REQUEST_WAITING`, or `REQUEST_FAILED`.

## 3. Setting and Listening to Glasses Brightness

Use `fun setGlassBrightness(int value): CxrStatus` where brightness ranges from **[0, 15]**.

```kotlin
private val glassesBrightnessUpdateListener = object : BrightnessUpdateListener {
    override fun onBrightnessUpdated(brightness: Int) { }
}

fun getGlassesBrightness(set: Boolean) {
    CxrApi.getInstance().setBrightnessUpdateListener(
        if (set) glassesBrightnessUpdateListener else null
    )
}

fun setBrightness(brightness: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().setGlassBrightness(brightness)
}
```

## 4. Setting and Listening to Glasses Volume

Use `fun setGlassVolume(int value): CxrStatus` where volume ranges from **[0, 15]**.

```kotlin
private val volumeUpdate = object : VolumeUpdateListener{
    override fun onVolumeUpdated(volume: Int) { }
}

fun getGlassesVolume(set: Boolean){
    CxrApi.getInstance().setVolumeUpdateListener(if (set) volumeUpdate else null)
}

fun setVolume(volume: Int): ValueUtil.CxrStatus? {
    return CxrApi.getInstance().setGlassVolume(volume)
}
```

## 5. Setting Glasses Sound Effect Mode

Use `fun setSoundEffect(mode: String)`.

Modes:
- `"AdiMode0"` - Loud
- `"AdiMode1"` - Rhythm
- `"AdiMode2"` - Podcast

```kotlin
fun setSoundEffect(value: String): ValueUtil.CxrStatus?{
    return CxrApi.getInstance().setSoundEffect(value)
}
```

## 6. Listening to Glasses Battery Level Changes

```kotlin
CxrApi.getInstance().setBatteryLevelUpdateListener(object : BatteryLevelUpdateListener{
    /**
     * @param level Battery level [0-100]
     * @param charging true: Charging, false: Not charging
     */
    override fun onBatteryLevelUpdated(level: Int, charging: Boolean) { }
})
```

## 7. Setting Automatic Screen Off Timeout

Use `fun setScreenOffTimeout(second: Long): ValueUtil.CxrStatus?` (unit: seconds).

```kotlin
fun setScreenOffTimeout(seconds: Long): ValueUtil.CxrStatus?{
    return CxrApi.getInstance().setScreenOffTimeout(seconds)
}
```

## 8. Setting Automatic Power Off Timeout

Use `fun setPowerOffTimeout(minutes: Int): ValueUtil.CxrStatus?` (unit: minutes).

```kotlin
fun setPowerOffTimeout(minutes: Int): ValueUtil.CxrStatus?{
    return CxrApi.getInstance().setPowerOffTimeout(minutes)
}
```

## 9. Notifying Glasses to Reboot

```kotlin
fun rebootGlasses(): ValueUtil.CxrStatus{
    return CxrApi.getInstance().notifyGlassReboot()
}
```

## 10. Notifying Glasses to Shutdown

```kotlin
fun shutdownGlasses(): ValueUtil.CxrStatus{
    return CxrApi.getInstance().notifyGlassShutdown()
}
```
