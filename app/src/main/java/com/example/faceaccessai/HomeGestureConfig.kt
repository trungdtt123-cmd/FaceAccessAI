package com.example.faceaccessai

data class HomeGestureConfig(
    val trendWindowMs: Long = 500L,
    val strongRollThreshold: Float = 7.5f,
    val softRollThreshold: Float = 4.75f,
    val travelRollThreshold: Float = 5.5f,
    val travelMinCurrentRoll: Float = 3.75f,
    val softTravelThreshold: Float = 3.75f,
    val rollConsistencySoft: Float = 0.50f,
    val rollConsistencyTrend: Float = 0.65f,
    val rollDominanceSoft: Float = 0.53f,
    val clearYawTravelThreshold: Float = 9.0f,
    val clearYawConsistency: Float = 0.60f,
    val clearYawDominance: Float = 0.60f,
    val firstEvidenceTargetMs: Long = 90L,
    val candidateGraceMs: Long = 200L,
    val centerBandThreshold: Float = 5.0f,
    val centerReturnRatio: Float = 0.60f,
    val minimumCenterReturnTravel: Float = 1.5f,
    val grossYawLimit: Float = 35.0f,
    val grossPitchLimit: Float = 35.0f,
    val sequenceTimeoutMs: Long = 3500L,
    val unlockNeutralHoldMs: Long = 200L,
    val significantDeltaDeg: Float = 0.30f,
    
    // V1.2.3 second-side timing
    val secondStrongEvidenceMs: Long = 0L,
    val secondTravelEvidenceMs: Long = 20L,
    val secondSoftDominantEvidenceMs: Long = 35L,
    val secondTrendEvidenceMs: Long = 50L
)
