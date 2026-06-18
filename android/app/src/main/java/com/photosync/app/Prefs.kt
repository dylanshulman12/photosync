package com.photosync.app

import android.content.Context

/** Simple typed wrapper over SharedPreferences for the app's settings. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("photosync", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "")!!.trimEnd('/')
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = sp.getString("key", "")!!
        set(v) = sp.edit().putString("key", v.trim()).apply()

    var backupEnabled: Boolean
        get() = sp.getBoolean("backup_on", false)
        set(v) = sp.edit().putBoolean("backup_on", v).apply()

    /** one of: daily, weekly, monthly */
    var backupInterval: String
        get() = sp.getString("interval", "daily")!!
        set(v) = sp.edit().putString("interval", v).apply()

    /** also pull (download) during the periodic backup, not just push */
    var backupPulls: Boolean
        get() = sp.getBoolean("backup_pull", false)
        set(v) = sp.edit().putBoolean("backup_pull", v).apply()

    val isConfigured: Boolean get() = serverUrl.startsWith("http")
}
