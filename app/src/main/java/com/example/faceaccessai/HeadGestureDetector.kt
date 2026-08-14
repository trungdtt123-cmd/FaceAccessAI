package com.example.faceaccessai

import kotlin.math.abs


class HeadGestureDetector(
    private val leftYawThresholdDeg: Float = -25f,
    private val rightYawThresholdDeg: Float = 25f,
    private val centerYawThresholdDeg: Float = 10f,
    private val maximumTurnRollDeg: Float = 15f,
    private val maximumTurnPitchDeg: Float = 20f,
    private val centerRollThresholdDeg: Float = 10f,
    private val centerPitchThresholdDeg: Float = 12f,
    private val minimumHoldDurationMs: Long = 300L
) {

    enum class GestureEvent {
        NONE,
        HEAD_LEFT,
        HEAD_RIGHT
    }


    enum class HeadZone {
        CENTER,
        LEFT,
        RIGHT,
        TRANSITION,
        REJECTED
    }


    data class HeadGestureResult(
        val event: GestureEvent,
        val zone: HeadZone,
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        val candidateDurationMs: Long,
        val rollGatePassed: Boolean,
        val pitchGatePassed: Boolean,
        val lockedUntilCenter: Boolean
    )


    private var candidateZone:
            HeadZone? = null


    private var candidateStartTimestampMs =
        0L


    private var lockedUntilCenter =
        false


    // Cập nhật trạng thái head gesture
    fun update(
        pose: HeadPoseSmoother.SmoothedHeadPose,
        timestampMs: Long
    ): HeadGestureResult {

        val rollGatePassed =
            abs(pose.rollDeg) <=
                    maximumTurnRollDeg


        val pitchGatePassed =
            abs(pose.pitchDeg) <=
                    maximumTurnPitchDeg


        val zone =
            classifyZone(
                yawDeg = pose.yawDeg,
                pitchDeg = pose.pitchDeg,
                rollDeg = pose.rollDeg,
                rollGatePassed = rollGatePassed,
                pitchGatePassed = pitchGatePassed
            )


        // Sau khi phát event phải quay về trung tâm
        if (lockedUntilCenter) {

            if (zone == HeadZone.CENTER) {

                lockedUntilCenter =
                    false

                resetCandidate()
            }


            return HeadGestureResult(
                event = GestureEvent.NONE,
                zone = zone,
                yawDeg = pose.yawDeg,
                pitchDeg = pose.pitchDeg,
                rollDeg = pose.rollDeg,
                candidateDurationMs = 0L,
                rollGatePassed = rollGatePassed,
                pitchGatePassed = pitchGatePassed,
                lockedUntilCenter = lockedUntilCenter
            )
        }


        if (
            zone != HeadZone.LEFT &&
            zone != HeadZone.RIGHT
        ) {

            resetCandidate()


            return HeadGestureResult(
                event = GestureEvent.NONE,
                zone = zone,
                yawDeg = pose.yawDeg,
                pitchDeg = pose.pitchDeg,
                rollDeg = pose.rollDeg,
                candidateDurationMs = 0L,
                rollGatePassed = rollGatePassed,
                pitchGatePassed = pitchGatePassed,
                lockedUntilCenter = false
            )
        }


        if (candidateZone != zone) {

            candidateZone =
                zone

            candidateStartTimestampMs =
                timestampMs


            return HeadGestureResult(
                event = GestureEvent.NONE,
                zone = zone,
                yawDeg = pose.yawDeg,
                pitchDeg = pose.pitchDeg,
                rollDeg = pose.rollDeg,
                candidateDurationMs = 0L,
                rollGatePassed = rollGatePassed,
                pitchGatePassed = pitchGatePassed,
                lockedUntilCenter = false
            )
        }


        val candidateDurationMs =
            (
                    timestampMs -
                            candidateStartTimestampMs
                    ).coerceAtLeast(0L)


        if (
            candidateDurationMs >=
            minimumHoldDurationMs
        ) {

            val event =
                when (zone) {

                    HeadZone.LEFT ->
                        GestureEvent.HEAD_LEFT

                    HeadZone.RIGHT ->
                        GestureEvent.HEAD_RIGHT

                    else ->
                        GestureEvent.NONE
                }


            lockedUntilCenter =
                true


            resetCandidate()


            return HeadGestureResult(
                event = event,
                zone = zone,
                yawDeg = pose.yawDeg,
                pitchDeg = pose.pitchDeg,
                rollDeg = pose.rollDeg,
                candidateDurationMs = candidateDurationMs,
                rollGatePassed = rollGatePassed,
                pitchGatePassed = pitchGatePassed,
                lockedUntilCenter = true
            )
        }


        return HeadGestureResult(
            event = GestureEvent.NONE,
            zone = zone,
            yawDeg = pose.yawDeg,
            pitchDeg = pose.pitchDeg,
            rollDeg = pose.rollDeg,
            candidateDurationMs = candidateDurationMs,
            rollGatePassed = rollGatePassed,
            pitchGatePassed = pitchGatePassed,
            lockedUntilCenter = false
        )
    }


    // Phân loại tư thế đầu hiện tại
    private fun classifyZone(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        rollGatePassed: Boolean,
        pitchGatePassed: Boolean
    ): HeadZone {

        val isCenter =
            abs(yawDeg) <=
                    centerYawThresholdDeg &&
                    abs(pitchDeg) <=
                    centerPitchThresholdDeg &&
                    abs(rollDeg) <=
                    centerRollThresholdDeg


        if (isCenter) {
            return HeadZone.CENTER
        }


        if (
            !rollGatePassed ||
            !pitchGatePassed
        ) {

            return HeadZone.REJECTED
        }


        if (
            yawDeg <=
            leftYawThresholdDeg
        ) {

            return HeadZone.LEFT
        }


        if (
            yawDeg >=
            rightYawThresholdDeg
        ) {

            return HeadZone.RIGHT
        }


        return HeadZone.TRANSITION
    }


    // Hủy candidate nhưng giữ khóa chống lặp
    fun interrupt() {

        resetCandidate()
    }


    private fun resetCandidate() {

        candidateZone =
            null

        candidateStartTimestampMs =
            0L
    }


    // Reset toàn bộ detector
    fun reset() {

        resetCandidate()

        lockedUntilCenter =
            false
    }
}