package com.example.faceaccessai

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark


object FaceFrameQualityChecker {

    data class FrameQuality(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val leftMargin: Float,
        val rightMargin: Float,
        val topMargin: Float,
        val bottomMargin: Float,
        val tooCloseToEdge: Boolean
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


        for (landmark in landmarks) {

            val x = landmark.x()
            val y = landmark.y()


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


        return FrameQuality(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            leftMargin = leftMargin,
            rightMargin = rightMargin,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            tooCloseToEdge = tooCloseToEdge
        )
    }
}