package com.example.faceaccessai

object GestureSensitivityConfigProvider {

    fun homeConfig(sensitivity: GestureSensitivity): HomeGestureConfig {
        return when (sensitivity) {
            GestureSensitivity.SENSITIVE -> HomeGestureConfig(
                strongRollThreshold = 6.0f,
                softRollThreshold = 3.8f,
                travelRollThreshold = 4.4f,
                travelMinCurrentRoll = 3.0f,
                softTravelThreshold = 3.0f,
                firstEvidenceTargetMs = 70L,
                secondStrongEvidenceMs = 0L,
                secondTravelEvidenceMs = 15L,
                secondSoftDominantEvidenceMs = 25L,
                secondTrendEvidenceMs = 40L,
                clearYawTravelThreshold = 9.0f, // Safety: keep conservative
                grossYawLimit = 35.0f,
                grossPitchLimit = 35.0f
            )
            GestureSensitivity.BALANCED -> HomeGestureConfig()
            GestureSensitivity.STABLE -> HomeGestureConfig(
                strongRollThreshold = 9.0f,
                softRollThreshold = 5.5f,
                travelRollThreshold = 6.5f,
                travelMinCurrentRoll = 4.5f,
                softTravelThreshold = 4.5f,
                firstEvidenceTargetMs = 115L,
                secondStrongEvidenceMs = 0L,
                secondTravelEvidenceMs = 30L,
                secondSoftDominantEvidenceMs = 50L,
                secondTrendEvidenceMs = 70L,
                clearYawTravelThreshold = 9.0f,
                grossYawLimit = 35.0f,
                grossPitchLimit = 35.0f
            )
        }
    }

    fun headConfig(sensitivity: GestureSensitivity): HeadGestureConfig {
        return when (sensitivity) {
            GestureSensitivity.SENSITIVE -> HeadGestureConfig(
                leftYawThresholdDeg = -20f,
                rightYawThresholdDeg = 20f,
                upPitchThresholdDeg = -10.5f,
                downPitchThresholdDeg = 12f,
                minimumHoldDurationMs = 100L,
                strongHoldDurationMs = 80L,
                horizontalHoldDurationMs = 130L,
                candidateGracePeriodMs = 200L,
                softUpPitchThresholdDeg = 6.5f,
                softDownPitchThresholdDeg = 7.5f,
                softPitchTravelThresholdDeg = 5.0f,
                softPitchConsistency = 0.50f,
                pitchTravelThresholdDeg = 7.5f,
                pitchTravelMinMagnitudeDeg = 5.0f,
                // Yaw SENSITIVE
                softLeftYawThresholdDeg = 11f,
                softRightYawThresholdDeg = 11f,
                yawSoftTravelThresholdDeg = 6.5f,
                yawSoftConsistency = 0.50f,
                yawTravelThresholdDeg = 10f,
                yawTravelMinMagnitudeDeg = 8f
            )
            GestureSensitivity.BALANCED -> HeadGestureConfig()
            GestureSensitivity.STABLE -> HeadGestureConfig(
                leftYawThresholdDeg = -28f,
                rightYawThresholdDeg = 28f,
                upPitchThresholdDeg = -15f,
                downPitchThresholdDeg = 17.5f,
                minimumHoldDurationMs = 170L,
                strongHoldDurationMs = 130L,
                horizontalHoldDurationMs = 220L,
                candidateGracePeriodMs = 180L,
                softUpPitchThresholdDeg = 9.0f,
                softDownPitchThresholdDeg = 10.0f,
                softPitchTravelThresholdDeg = 6.75f,
                softPitchConsistency = 0.50f,
                pitchTravelThresholdDeg = 10.0f,
                pitchTravelMinMagnitudeDeg = 6.75f,
                // Yaw STABLE
                softLeftYawThresholdDeg = 16f,
                softRightYawThresholdDeg = 16f,
                yawSoftTravelThresholdDeg = 9.5f,
                yawSoftConsistency = 0.55f,
                yawTravelThresholdDeg = 14f,
                yawTravelMinMagnitudeDeg = 12f
            )
        }
    }
}
