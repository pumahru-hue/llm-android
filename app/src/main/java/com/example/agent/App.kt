package com.example.agent

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ChatStore.init(this)
        CrashLog.install(this)
    }
}
