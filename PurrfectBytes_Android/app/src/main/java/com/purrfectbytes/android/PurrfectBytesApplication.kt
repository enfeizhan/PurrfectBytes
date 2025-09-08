package com.purrfectbytes.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PurrfectBytesApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        // Initialize any app-wide configurations here
    }
}