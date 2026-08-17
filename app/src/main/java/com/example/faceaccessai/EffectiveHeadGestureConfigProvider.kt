package com.example.faceaccessai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object EffectiveHeadGestureConfigProvider {

    fun getEffectiveConfig(
        sensitivity: GestureSensitivity,
        profile: HeadDirectionalCalibrationProfile?
    ): HeadGestureConfig {
        val baseConfig = GestureSensitivityConfigProvider.headConfig(sensitivity)
        if (profile == null || profile.version != 3) return baseConfig

        val presetStrength = when (sensitivity) {
            GestureSensitivity.SENSITIVE -> 1.00f
            GestureSensitivity.BALANCED -> 0.85f
            GestureSensitivity.STABLE -> 0.65f
        }

        // Personalize each direction independently
        
        // LEFT
        val effectiveLeft = personalizeDirection(
            baseMag = abs(baseConfig.leftYawThresholdDeg),
            intentMag = profile.leftIntentYawDeg,
            noise = profile.neutralYawNoiseDeg,
            confidence = profile.leftConfidence,
            presetStrength = presetStrength,
            isYaw = true
        )

        // RIGHT
        val effectiveRight = personalizeDirection(
            baseMag = abs(baseConfig.rightYawThresholdDeg),
            intentMag = profile.rightIntentYawDeg,
            noise = profile.neutralYawNoiseDeg,
            confidence = profile.rightConfidence,
            presetStrength = presetStrength,
            isYaw = true
        )

        // UP
        val effectiveUp = personalizeDirection(
            baseMag = abs(baseConfig.upPitchThresholdDeg),
            intentMag = profile.upIntentPitchDeg,
            noise = profile.neutralPitchNoiseDeg,
            confidence = profile.upConfidence,
            presetStrength = presetStrength,
            isYaw = false
        )

        // DOWN
        val effectiveDown = personalizeDirection(
            baseMag = abs(baseConfig.downPitchThresholdDeg),
            intentMag = profile.downIntentPitchDeg,
            noise = profile.neutralPitchNoiseDeg,
            confidence = profile.downConfidence,
            presetStrength = presetStrength,
            isYaw = false
        )

        // Soft thresholds
        val effectiveSoftLeft = personalizeSoft(effectiveLeft, baseConfig.softLeftYawThresholdDeg, true)
        val effectiveSoftRight = personalizeSoft(effectiveRight, baseConfig.softRightYawThresholdDeg, true)
        val effectiveSoftUp = personalizeSoft(effectiveUp, baseConfig.softUpPitchThresholdDeg, false)
        val effectiveSoftDown = personalizeSoft(effectiveDown, baseConfig.softDownPitchThresholdDeg, false)

        return baseConfig.copy(
            leftYawThresholdDeg = -effectiveLeft,
            rightYawThresholdDeg = effectiveRight,
            upPitchThresholdDeg = -effectiveUp,
            downPitchThresholdDeg = effectiveDown,
            
            softLeftYawThresholdDeg = effectiveSoftLeft,
            softRightYawThresholdDeg = effectiveSoftRight,
            softUpPitchThresholdDeg = effectiveSoftUp,
            softDownPitchThresholdDeg = effectiveSoftDown
        )
    }

    private fun personalizeDirection(
        baseMag: Float,
        intentMag: Float,
        noise: Float,
        confidence: Float,
        presetStrength: Float,
        isYaw: Boolean
    ): Float {
        val safetyFloor = if (isYaw) min(7.5f, baseMag) else min(4.5f, baseMag)
        val noiseGuard = if (isYaw) {
            max(safetyFloor, noise * 3.5f + 2.5f)
        } else {
            max(safetyFloor, noise * 3.5f + 1.5f)
        }

        val personalCandidate = max(intentMag * 0.85f, noiseGuard).coerceAtMost(baseMag)
        
        val qualityWeight = ((confidence - 0.45f) / 0.45f).coerceIn(0f, 1f)
        val weight = qualityWeight * presetStrength

        val effective = baseMag + (personalCandidate - baseMag) * weight
        
        return effective.coerceIn(safetyFloor, baseMag)
    }

    private fun personalizeSoft(strong: Float, baseSoft: Float, isYaw: Boolean): Float {
        val floor = if (isYaw) 5.5f else 3.5f
        val limit = min(floor, baseSoft)
        
        val softCandidate = if (isYaw) {
            max(limit, strong * 0.65f)
        } else {
            max(limit, strong * 0.70f)
        }
        
        return softCandidate.coerceAtMost(baseSoft)
    }
}
