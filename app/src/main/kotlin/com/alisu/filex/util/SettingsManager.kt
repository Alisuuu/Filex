package com.alisu.filex.util

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("filex_settings", Context.MODE_PRIVATE)

    companion object {
        const val MODE_DEFAULT = "default"
        const val MODE_ROOT = "root"
        const val MODE_SHIZUKU = "shizuku"
    }

    var accessMode: String
        get() = prefs.getString("access_mode", MODE_DEFAULT) ?: MODE_DEFAULT
        set(value) = prefs.edit().putString("access_mode", value).apply()

    var isTrashEnabled: Boolean
        get() = prefs.getBoolean("trash_enabled", true)
        set(value) = prefs.edit().putBoolean("trash_enabled", value).apply()

    var trashRetentionDays: Int
        get() = prefs.getInt("trash_retention_days", 1)
        set(value) = prefs.edit().putInt("trash_retention_days", value).apply()

    fun getActiveModeName(): String {
        return when (accessMode) {
            MODE_ROOT -> "Root"
            MODE_SHIZUKU -> "Shizuku"
            else -> "Padrão"
        }
    }
}
