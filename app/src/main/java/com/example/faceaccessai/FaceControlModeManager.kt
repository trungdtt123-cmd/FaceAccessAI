package com.example.faceaccessai

import android.content.Context
import android.content.SharedPreferences

enum class FaceControlMode {
    NAVIGATION,
    MEDIA
}

object FaceControlModeManager {

    private const val PREFS_NAME = "face_control_mode_prefs"
    private const val KEY_MODE = "face_control_mode"

    fun getMode(context: Context): FaceControlMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_MODE, FaceControlMode.NAVIGATION.name)
        return try {
            FaceControlMode.valueOf(modeName ?: FaceControlMode.NAVIGATION.name)
        } catch (e: Exception) {
            FaceControlMode.NAVIGATION
        }
    }

    fun setMode(context: Context, mode: FaceControlMode) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }
}
