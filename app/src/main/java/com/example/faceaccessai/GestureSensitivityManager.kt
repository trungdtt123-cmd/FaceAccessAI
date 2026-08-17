package com.example.faceaccessai

import android.content.Context
import android.util.Log

class GestureSensitivityManager(context: Context) {

    private val sharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getSensitivity(): GestureSensitivity {
        val name = sharedPreferences.getString(KEY_SENSITIVITY, GestureSensitivity.BALANCED.name)
        return try {
            GestureSensitivity.valueOf(name ?: GestureSensitivity.BALANCED.name)
        } catch (e: IllegalArgumentException) {
            GestureSensitivity.BALANCED
        }
    }

    fun setSensitivity(sensitivity: GestureSensitivity) {
        val old = getSensitivity()
        if (old != sensitivity) {
            sharedPreferences.edit()
                .putString(KEY_SENSITIVITY, sensitivity.name)
                .apply()
            
            Log.d(TAG, "GestureSensitivity | Changed=$old->$sensitivity")
        }
    }

    companion object {
        private const val TAG = "SensitivityManager"
        private const val PREFS_NAME = "face_access_prefs"
        private const val KEY_SENSITIVITY = "gesture_sensitivity"
        
        @Volatile
        private var instance: GestureSensitivityManager? = null

        fun getInstance(context: Context): GestureSensitivityManager {
            return instance ?: synchronized(this) {
                instance ?: GestureSensitivityManager(context).also { instance = it }
            }
        }
    }
}
