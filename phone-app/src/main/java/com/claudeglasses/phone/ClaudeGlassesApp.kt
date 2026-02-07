package com.claudeglasses.phone

import android.app.Application
import android.util.Log
import com.claudeglasses.phone.glasses.RokidSdkManager

class ClaudeGlassesApp : Application() {

    companion object {
        const val TAG = "ClaudeGlasses"
        lateinit var instance: ClaudeGlassesApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Claude Glasses Terminal app initialized")

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
