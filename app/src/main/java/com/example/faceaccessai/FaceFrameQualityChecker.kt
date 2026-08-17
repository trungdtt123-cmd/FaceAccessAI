package com.example.faceaccessai

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt


object FaceFrameQualityChecker {

    enum class CalibrationFrameQuality {
        SAFE,
        CAUTION,
        UNUSABLE
    }

    data class FrameQuality(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val leftMargin: Float,
        val rightMargin: Float,
        val topMargin: Float,
        val bottomMargin: Float,
        val tooCloseToEdge: Boolean,
        val robustMinX: Float,
        val robustMaxX: Float,
        val robustMinY: Float,
        val robustMaxY: Float,
        val faceWidthRatio: Float,
        val faceHeightRatio: Float,
        val faceScale: Float,
        val calibrationFrameQuality: CalibrationFrameQuality
    )


    // Kiểm tra khoảng cách khuôn mặt tới các mép frame
    fun check(
        landmarks: List<NormalizedLandmark>,
        minimumMargin: Float = 0.05f
    ): FrameQuality? {

        if (landmarks.size < 468) {
            return null
        }


        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE

        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        val xCoords = mutableListOf<Float>()
        val yCoords = mutableListOf<Float>()


        for (landmark in landmarks) {

            val x = landmark.x()
            val y = landmark.y()

            // V3: Finite safety validation
            if (!x.isFinite() || !y.isFinite()) {
                return null
            }

            xCoords.add(x)
            yCoords.add(y)


            if (x < minX) {
                minX = x
            }

            if (x > maxX) {
                maxX = x
            }

            if (y < minY) {
                minY = y
            }

            if (y > maxY) {
                maxY = y
            }
        }


        val leftMargin =
            minX


        val rightMargin =
            1f - maxX


        val topMargin =
            minY


        val bottomMargin =
            1f - maxY


        val tooCloseToEdge =
            leftMargin < minimumMargin ||
                    rightMargin < minimumMargin ||
                    topMargin < minimumMargin ||
                    bottomMargin < minimumMargin

        // Robust face bounds based on percentiles
        xCoords.sort()
        yCoords.sort()
        val size = xCoords.size
        
        fun getP(list: List<Float>, p: Float): Float {
            val index = ((size - 1) * p).toInt().coerceIn(0, size - 1)
            return list[index]
        }

        val robustMinX = getP(xCoords, 0.05f)
        val robustMaxX = getP(xCoords, 0.95f)
        val robustMinY = getP(yCoords, 0.05f)
        val robustMaxY = getP(yCoords, 0.95f)

        val faceWidthRatio = (robustMaxX - robustMinX).coerceAtLeast(0f)
        val faceHeightRatio = (robustMaxY - robustMinY).coerceAtLeast(0f)
        val rawFaceScale = sqrt(faceWidthRatio * faceHeightRatio)

        // Validate derived calibration geometry
        if (!robustMinX.isFinite() || !robustMaxX.isFinite() || 
            !robustMinY.isFinite() || !robustMaxY.isFinite() ||
            !faceWidthRatio.isFinite() || !faceHeightRatio.isFinite() ||
            !rawFaceScale.isFinite()) {
            return null
        }

        val faceScale = rawFaceScale

        // Calibration-specific quality (2% robust margin)
        val calMargin = 0.02f
        val isRobustInside = robustMinX >= calMargin && robustMaxX <= (1f - calMargin) &&
                            robustMinY >= calMargin && robustMaxY <= (1f - calMargin)
        
        val calQuality = when {
            !tooCloseToEdge -> CalibrationFrameQuality.SAFE
            isRobustInside -> CalibrationFrameQuality.CAUTION
            else -> CalibrationFrameQuality.UNUSABLE
        }

        return FrameQuality(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            leftMargin = leftMargin,
            rightMargin = rightMargin,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            tooCloseToEdge = tooCloseToEdge,
            robustMinX = robustMinX,
            robustMaxX = robustMaxX,
            robustMinY = robustMinY,
            robustMaxY = robustMaxY,
            faceWidthRatio = faceWidthRatio,
            faceHeightRatio = faceHeightRatio,
            faceScale = faceScale,
            calibrationFrameQuality = calQuality
        )
    }
}