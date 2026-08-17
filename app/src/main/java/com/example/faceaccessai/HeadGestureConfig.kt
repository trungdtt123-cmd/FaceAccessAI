package com.example.faceaccessai

data class HeadGestureConfig(
    val leftYawThresholdDeg: Float = -25f,
    val rightYawThresholdDeg: Float = 25f,
    val upPitchThresholdDeg: Float = -13f,
    val downPitchThresholdDeg: Float = 15f,
    val centerYawThresholdDeg: Float = 10f,
    val centerPitchThresholdDeg: Float = 12f,
    val centerRollThresholdDeg: Float = 10f,
    val maximumTurnPitchDeg: Float = 24f,
    val maximumNodYawDeg: Float = 24f,
    val maximumGestureRollDeg: Float = 18f,
    val minimumHoldDurationMs: Long = 130L,
    
    val trendWindowMs: Long = 450L,
    val minimumSignificantDeltaDeg: Float = 0.35f,
    val rollWeight: Float = 0.75f,
    val minimumDirectionalConfidence: Float = 0.60f,
    val candidateGracePeriodMs: Long = 200L,
    val minimumAxisDominanceMargin: Float = 0.15f,
    val holdRetentionRatio: Float = 0.80f,
    
    val strongHoldDurationMs: Long = 100L,
    val horizontalHoldDurationMs: Long = 170L,
    
    val softUpPitchThresholdDeg: Float = 8f,
    val softDownPitchThresholdDeg: Float = 9f,
    val softPitchTravelThresholdDeg: Float = 6f,
    val softPitchConsistency: Float = 0.50f,
    val pitchTravelThresholdDeg: Float = 9f,
    val pitchTravelMinMagnitudeDeg: Float = 6f,

    // Yaw Approach V2
    val softLeftYawThresholdDeg: Float = 14f,
    val softRightYawThresholdDeg: Float = 14f,
    val yawSoftTravelThresholdDeg: Float = 8f,
    val yawSoftConsistency: Float = 0.50f,
    val yawTravelThresholdDeg: Float = 12f,
    val yawTravelMinMagnitudeDeg: Float = 10f,

    // Retention V2
    val retentionRatio: Float = 0.70f // 70% of the activation threshold
)
