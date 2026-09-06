package com.tvbrowser.app

import android.app.Application
import com.tvbrowser.app.adblock.AdBlockManager

class TvBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdBlockManager.initialize(this)
    }
}
