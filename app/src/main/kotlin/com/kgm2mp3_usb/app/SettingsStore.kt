package com.kgm2mp3_usb.app

import android.content.Context

/** Small preferences are persisted immediately so folder grants survive process restarts. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var downloadPath: String
        get() = prefs.getString("download_path", DEFAULT_DOWNLOAD_PATH)!!
        set(value) { prefs.edit().putString("download_path", value).apply() }
    var sourceTreeUri: String?
        get() = prefs.getString("source_tree", null)
        set(value) { prefs.edit().putString("source_tree", value).apply() }
    var usbTreeUri: String?
        get() = prefs.getString("usb_tree", null)
        set(value) { prefs.edit().putString("usb_tree", value).apply() }
    var usbPath: String?
        get() = prefs.getString("usb_path", null)
        set(value) { prefs.edit().putString("usb_path", value).apply() }
    var outputFormat: OutputFormat
        get() = runCatching { OutputFormat.valueOf(prefs.getString("output_format", "MP3")!!) }
            .getOrDefault(OutputFormat.MP3)
        set(value) { prefs.edit().putString("output_format", value.name).apply() }

    companion object { const val DEFAULT_DOWNLOAD_PATH = "/storage/emulated/0/kgmusic/download/kgmusic" }
}
