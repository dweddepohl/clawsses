package com.clawsses.phone.util

import android.os.Build

fun isEmulator(): Boolean {
    return (Build.FINGERPRINT.contains("generic")
            || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
            || Build.MODEL.contains("sdk_gphone")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
            || Build.PRODUCT.contains("emulator"))
}
