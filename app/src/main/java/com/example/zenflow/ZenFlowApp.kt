package com.example.zenflow

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.example.zenflow.audio.SoundStore

class ZenFlowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.app = this
        SoundStore.warmUpAsync(this)   // tones + bundled metadata index, off-thread

        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build()
            )
        }
    }

    override fun onTerminate() {
        AppContextHolder.app = null
        super.onTerminate()
    }

    companion object {
        fun get(context: Context): ZenFlowApp =
            context.applicationContext as ZenFlowApp
    }
}
