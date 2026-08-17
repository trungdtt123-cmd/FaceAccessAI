package com.example.faceaccessai

import android.content.Context
import android.content.SharedPreferences

class HeadDirectionalCalibrationManager(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun save(profile: HeadDirectionalCalibrationProfile) {
        prefs.edit()
            .putFloat(KEY_LEFT_INTENT_YAW, profile.leftIntentYawDeg)
            .putFloat(KEY_RIGHT_INTENT_YAW, profile.rightIntentYawDeg)
            .putFloat(KEY_UP_INTENT_PITCH, profile.upIntentPitchDeg)
            .putFloat(KEY_DOWN_INTENT_PITCH, profile.downIntentPitchDeg)
            .putFloat(KEY_NEUTRAL_YAW_NOISE, profile.neutralYawNoiseDeg)
            .putFloat(KEY_NEUTRAL_PITCH_NOISE, profile.neutralPitchNoiseDeg)
            .putFloat(KEY_NEUTRAL_ROLL_NOISE, profile.neutralRollNoiseDeg)
            .putFloat(KEY_LEFT_CONFIDENCE, profile.leftConfidence)
            .putFloat(KEY_RIGHT_CONFIDENCE, profile.rightConfidence)
            .putFloat(KEY_UP_CONFIDENCE, profile.upConfidence)
            .putFloat(KEY_DOWN_CONFIDENCE, profile.downConfidence)
            .putFloat(KEY_FACE_SCALE, profile.referenceFaceScale)
            .putInt(KEY_VERSION, profile.version)
            .apply()
    }

    fun load(): HeadDirectionalCalibrationProfile? {
        val version = prefs.getInt(KEY_VERSION, 0)
        if (version != 3) return null

        val profile = runCatching {
            HeadDirectionalCalibrationProfile(
                leftIntentYawDeg = prefs.getFloat(KEY_LEFT_INTENT_YAW, 0f),
                rightIntentYawDeg = prefs.getFloat(KEY_RIGHT_INTENT_YAW, 0f),
                upIntentPitchDeg = prefs.getFloat(KEY_UP_INTENT_PITCH, 0f),
                downIntentPitchDeg = prefs.getFloat(KEY_DOWN_INTENT_PITCH, 0f),
                neutralYawNoiseDeg = prefs.getFloat(KEY_NEUTRAL_YAW_NOISE, 0f),
                neutralPitchNoiseDeg = prefs.getFloat(KEY_NEUTRAL_PITCH_NOISE, 0f),
                neutralRollNoiseDeg = prefs.getFloat(KEY_NEUTRAL_ROLL_NOISE, 0f),
                leftConfidence = prefs.getFloat(KEY_LEFT_CONFIDENCE, 0f),
                rightConfidence = prefs.getFloat(KEY_RIGHT_CONFIDENCE, 0f),
                upConfidence = prefs.getFloat(KEY_UP_CONFIDENCE, 0f),
                downConfidence = prefs.getFloat(KEY_DOWN_CONFIDENCE, 0f),
                referenceFaceScale = prefs.getFloat(KEY_FACE_SCALE, 0f),
                version = version
            )
        }.getOrNull() ?: return null

        return if (isValid(profile)) profile else null
    }

    fun hasCalibration(): Boolean {
        return load() != null
    }

    private fun isValid(profile: HeadDirectionalCalibrationProfile): Boolean {
        if (profile.version != 3) return false
        
        val intentValues = listOf(
            profile.leftIntentYawDeg,
            profile.rightIntentYawDeg,
            profile.upIntentPitchDeg,
            profile.downIntentPitchDeg
        )

        for (v in intentValues) {
            if (!v.isFinite() || v <= 0f) return false
        }

        if (profile.leftIntentYawDeg < 5f || profile.leftIntentYawDeg > 40f) return false
        if (profile.rightIntentYawDeg < 5f || profile.rightIntentYawDeg > 40f) return false
        if (profile.upIntentPitchDeg < 3.5f || profile.upIntentPitchDeg > 35f) return false
        if (profile.downIntentPitchDeg < 3.5f || profile.downIntentPitchDeg > 35f) return false

        val noiseValues = listOf(
            profile.neutralYawNoiseDeg,
            profile.neutralPitchNoiseDeg,
            profile.neutralRollNoiseDeg
        )
        for (v in noiseValues) {
            if (!v.isFinite() || v < 0f || v > 12f) return false
        }

        val confidences = listOf(
            profile.leftConfidence,
            profile.rightConfidence,
            profile.upConfidence,
            profile.downConfidence
        )
        for (v in confidences) {
            if (!v.isFinite() || v < 0f || v > 1f) return false
        }

        if (!profile.referenceFaceScale.isFinite() || profile.referenceFaceScale <= 0f || profile.referenceFaceScale > 2f) return false

        return true
    }

    fun clearCalibration() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "head_calibration_prefs_v3"
        private const val KEY_LEFT_INTENT_YAW = "left_intent_yaw"
        private const val KEY_RIGHT_INTENT_YAW = "right_intent_yaw"
        private const val KEY_UP_INTENT_PITCH = "up_intent_pitch"
        private const val KEY_DOWN_INTENT_PITCH = "down_intent_pitch"
        private const val KEY_NEUTRAL_YAW_NOISE = "neutral_yaw_noise"
        private const val KEY_NEUTRAL_PITCH_NOISE = "neutral_pitch_noise"
        private const val KEY_NEUTRAL_ROLL_NOISE = "neutral_roll_noise"
        private const val KEY_LEFT_CONFIDENCE = "left_confidence"
        private const val KEY_RIGHT_CONFIDENCE = "right_confidence"
        private const val KEY_UP_CONFIDENCE = "up_confidence"
        private const val KEY_DOWN_CONFIDENCE = "down_confidence"
        private const val KEY_FACE_SCALE = "face_scale"
        private const val KEY_VERSION = "version"

        @Volatile
        private var instance: HeadDirectionalCalibrationManager? = null

        fun getInstance(context: Context): HeadDirectionalCalibrationManager {
            return instance ?: synchronized(this) {
                instance ?: HeadDirectionalCalibrationManager(context).also { instance = it }
            }
        }
    }
}
