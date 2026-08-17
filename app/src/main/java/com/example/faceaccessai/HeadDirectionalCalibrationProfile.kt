package com.example.faceaccessai

data class HeadDirectionalCalibrationProfile(
    val leftIntentYawDeg: Float,
    val rightIntentYawDeg: Float,
    val upIntentPitchDeg: Float,
    val downIntentPitchDeg: Float,
    val neutralYawNoiseDeg: Float,
    val neutralPitchNoiseDeg: Float,
    val neutralRollNoiseDeg: Float,
    val leftConfidence: Float,
    val rightConfidence: Float,
    val upConfidence: Float,
    val downConfidence: Float,
    val referenceFaceScale: Float,
    val version: Int = 3
)
