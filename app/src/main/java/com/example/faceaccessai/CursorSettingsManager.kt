package com.example.faceaccessai

import android.content.Context

class CursorSettingsManager private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSensitivity(): Float {
        return prefs.getFloat(KEY_SENSITIVITY, 25f)
    }

    fun setSensitivity(value: Float) {
        prefs.edit().putFloat(KEY_SENSITIVITY, value).apply()
    }

    fun isInvertHorizontal(): Boolean {
        return prefs.getBoolean(KEY_INVERT_HORIZONTAL, false)
    }

    fun setInvertHorizontal(value: Boolean) {
        prefs.edit().putBoolean(KEY_INVERT_HORIZONTAL, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "cursor_settings_prefs"
        private const val KEY_SENSITIVITY = "cursor_sensitivity"
        private const val KEY_INVERT_HORIZONTAL = "cursor_invert_horizontal"

        @Volatile
        private var instance: CursorSettingsManager? = null

        fun getInstance(context: Context): CursorSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: CursorSettingsManager(context).also { instance = it }
            }
        }
    }
}
