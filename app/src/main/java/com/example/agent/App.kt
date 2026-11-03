package com.example.agent

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ChatStore.init(this)
        CrashLog.install(this)
    }

    companion object {
        @Volatile var instance: App? = null
    }
}
