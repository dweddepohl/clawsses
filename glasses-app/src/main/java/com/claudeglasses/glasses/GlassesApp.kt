package com.claudeglasses.glasses

import android.app.Application
import android.util.Log

class GlassesApp : Application() {

    companion object {
        const val TAG = "GlassesHUD"
        lateinit var instance: GlassesApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Claude Glasses HUD initialized")
    }
}
