package com.clawsses.phone

import android.app.Application
import android.util.Log
import com.clawsses.phone.glasses.RokidSdkManager

class ClawssesApp : Application() {

    companion object {
        const val TAG = "Clawsses"
        lateinit var instance: ClawssesApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Clawsses app initialized")

        // Initialize Rokid SDK
        if (RokidSdkManager.initialize(this)) {
            Log.d(TAG, "Rokid SDK initialized successfully")
        } else {
            Log.w(TAG, "Rokid SDK initialization failed - check rokid.accessKey in local.properties")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        RokidSdkManager.cleanup()
    }
}
