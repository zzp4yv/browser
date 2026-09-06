package com.tvbrowser.app.model

import android.content.Context

/** App-wide browsing preferences that persist across pages and app restarts. */
class BrowserSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    companion object {
        private const val KEY_DESKTOP_MODE = "desktop_mode"

        // A stable, generic desktop Chrome UA. Sites serve their full desktop
        // layout for this instead of a mobile/TV layout.
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
