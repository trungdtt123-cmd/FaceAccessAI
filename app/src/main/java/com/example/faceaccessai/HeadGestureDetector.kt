package com.example.faceaccessai

import kotlin.math.abs


class HeadGestureDetector(
    private val leftYawThresholdDeg: Float = -25f,
    private val rightYawThresholdDeg: Float = 25f,
    private val upPitchThresholdDeg: Float = -25f,
    private val downPitchThresholdDeg: Float = 25f,
    private val centerYawThresholdDeg: Float = 10f,
    private val centerPitchThresholdDeg: Float = 12f,
    private val centerRollThresholdDeg: Float = 10f,
    private val maximumTurnPitchDeg: Float = 20f,
    private val maximumNodYawDeg: Float = 20f,
    private val maximumGestureRollDeg: Float = 15f,
    private val minimumHoldDurationMs: Long = 300L
) {

    enum class GestureEvent {
        NONE,
        HEAD_LEFT,
        HEAD_RIGHT,
        HEAD_UP,
        HEAD_DOWN
    }


    enum class HeadZone {
        CENTER,
        LEFT,
        RIGHT,
        UP,
        DOWN,
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
        val crossAxisGatePassed: Boolean,
        val lockedUntilCenter: Boolean
    ) {

        // Giữ tương thích với FaceLandmarkerHelper hiện tại
        val pitchGatePassed: Boolean
            get() = crossAxisGatePassed
    }


    private data class ClassificationResult(
        val zone: HeadZone,
        val rollGatePassed: Boolean,
        val crossAxisGatePassed: Boolean
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

        val classification =
            classifyPose(
                yawDeg = pose.yawDeg,
                pitchDeg = pose.pitchDeg,
                rollDeg = pose.rollDeg
            )


        val zone =
            classification.zone


        // Sau khi phát event phải trở về trung tâm
        if (lockedUntilCenter) {

            if (zone == HeadZone.CENTER) {

                lockedUntilCenter =
                    false

                resetCandidate()
            }


            return createResult(
                event = GestureEvent.NONE,
                zone = zone,
                pose = pose,
                candidateDurationMs = 0L,
                rollGatePassed =
                    classification.rollGatePassed,
                crossAxisGatePassed =
                    classification.crossAxisGatePassed,
                lockedUntilCenter =
                    lockedUntilCenter
            )
        }


        if (!isGestureZone(zone)) {

            resetCandidate()


            return createResult(
                event = GestureEvent.NONE,
                zone = zone,
                pose = pose,
                candidateDurationMs = 0L,
                rollGatePassed =
                    classification.rollGatePassed,
                crossAxisGatePassed =
                    classification.crossAxisGatePassed,
                lockedUntilCenter = false
            )
        }


        // Bắt đầu candidate mới
        if (candidateZone != zone) {

            candidateZone =
                zone

            candidateStartTimestampMs =
                timestampMs


            return createResult(
                event = GestureEvent.NONE,
                zone = zone,
                pose = pose,
                candidateDurationMs = 0L,
                rollGatePassed =
                    classification.rollGatePassed,
                crossAxisGatePassed =
                    classification.crossAxisGatePassed,
                lockedUntilCenter = false
            )
        }


        val candidateDurationMs =
            (
                    timestampMs -
                            candidateStartTimestampMs
                    ).coerceAtLeast(0L)


        // Giữ đủ thời gian mới phát event
        if (
            candidateDurationMs >=
            minimumHoldDurationMs
        ) {

            val event =
                eventFromZone(
                    zone
                )


            lockedUntilCenter =
                true


            resetCandidate()


            return createResult(
                event = event,
                zone = zone,
                pose = pose,
                candidateDurationMs =
                    candidateDurationMs,
                rollGatePassed =
                    classification.rollGatePassed,
                crossAxisGatePassed =
                    classification.crossAxisGatePassed,
                lockedUntilCenter = true
            )
        }


        return createResult(
            event = GestureEvent.NONE,
            zone = zone,
            pose = pose,
            candidateDurationMs =
                candidateDurationMs,
            rollGatePassed =
                classification.rollGatePassed,
            crossAxisGatePassed =
                classification.crossAxisGatePassed,
            lockedUntilCenter = false
        )
    }


    // Phân loại tư thế đầu
    private fun classifyPose(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float
    ): ClassificationResult {

        val rollGatePassed =
            abs(rollDeg) <=
                    maximumGestureRollDeg


        val isCenter =
            abs(yawDeg) <=
                    centerYawThresholdDeg &&
                    abs(pitchDeg) <=
                    centerPitchThresholdDeg &&
                    abs(rollDeg) <=
                    centerRollThresholdDeg


        if (isCenter) {

            return ClassificationResult(
                zone = HeadZone.CENTER,
                rollGatePassed =
                    rollGatePassed,
                crossAxisGatePassed = true
            )
        }


        val isLeftCandidate =
            yawDeg <=
                    leftYawThresholdDeg


        val isRightCandidate =
            yawDeg >=
                    rightYawThresholdDeg


        val isUpCandidate =
            pitchDeg <=
                    upPitchThresholdDeg


        val isDownCandidate =
            pitchDeg >=
                    downPitchThresholdDeg


        // Nghiêng đầu quá nhiều
        if (!rollGatePassed) {

            return ClassificationResult(
                zone = HeadZone.REJECTED,
                rollGatePassed = false,
                crossAxisGatePassed =
                    calculateCrossAxisGate(
                        yawDeg = yawDeg,
                        pitchDeg = pitchDeg,
                        isLeftCandidate =
                            isLeftCandidate,
                        isRightCandidate =
                            isRightCandidate,
                        isUpCandidate =
                            isUpCandidate,
                        isDownCandidate =
                            isDownCandidate
                    )
            )
        }


        // Quay trái
        if (isLeftCandidate) {

            val crossAxisGatePassed =
                abs(pitchDeg) <=
                        maximumTurnPitchDeg


            return ClassificationResult(
                zone =
                    if (crossAxisGatePassed) {
                        HeadZone.LEFT
                    } else {
                        HeadZone.REJECTED
                    },
                rollGatePassed = true,
                crossAxisGatePassed =
                    crossAxisGatePassed
            )
        }


        // Quay phải
        if (isRightCandidate) {

            val crossAxisGatePassed =
                abs(pitchDeg) <=
                        maximumTurnPitchDeg


            return ClassificationResult(
                zone =
                    if (crossAxisGatePassed) {
                        HeadZone.RIGHT
                    } else {
                        HeadZone.REJECTED
                    },
                rollGatePassed = true,
                crossAxisGatePassed =
                    crossAxisGatePassed
            )
        }


        // Ngẩng đầu
        if (isUpCandidate) {

            val crossAxisGatePassed =
                abs(yawDeg) <=
                        maximumNodYawDeg


            return ClassificationResult(
                zone =
                    if (crossAxisGatePassed) {
                        HeadZone.UP
                    } else {
                        HeadZone.REJECTED
                    },
                rollGatePassed = true,
                crossAxisGatePassed =
                    crossAxisGatePassed
            )
        }


        // Cúi đầu
        if (isDownCandidate) {

            val crossAxisGatePassed =
                abs(yawDeg) <=
                        maximumNodYawDeg


            return ClassificationResult(
                zone =
                    if (crossAxisGatePassed) {
                        HeadZone.DOWN
                    } else {
                        HeadZone.REJECTED
                    },
                rollGatePassed = true,
                crossAxisGatePassed =
                    crossAxisGatePassed
            )
        }


        return ClassificationResult(
            zone = HeadZone.TRANSITION,
            rollGatePassed = true,
            crossAxisGatePassed = true
        )
    }


    // Gate của trục phụ
    private fun calculateCrossAxisGate(
        yawDeg: Float,
        pitchDeg: Float,
        isLeftCandidate: Boolean,
        isRightCandidate: Boolean,
        isUpCandidate: Boolean,
        isDownCandidate: Boolean
    ): Boolean {

        return when {

            isLeftCandidate ||
                    isRightCandidate -> {

                abs(pitchDeg) <=
                        maximumTurnPitchDeg
            }


            isUpCandidate ||
                    isDownCandidate -> {

                abs(yawDeg) <=
                        maximumNodYawDeg
            }


            else ->
                true
        }
    }


    private fun eventFromZone(
        zone: HeadZone
    ): GestureEvent {

        return when (zone) {

            HeadZone.LEFT ->
                GestureEvent.HEAD_LEFT

            HeadZone.RIGHT ->
                GestureEvent.HEAD_RIGHT

            HeadZone.UP ->
                GestureEvent.HEAD_UP

            HeadZone.DOWN ->
                GestureEvent.HEAD_DOWN

            else ->
                GestureEvent.NONE
        }
    }


    private fun isGestureZone(
        zone: HeadZone
    ): Boolean {

        return zone ==
                HeadZone.LEFT ||
                zone ==
                HeadZone.RIGHT ||
                zone ==
                HeadZone.UP ||
                zone ==
                HeadZone.DOWN
    }


    private fun createResult(
        event: GestureEvent,
        zone: HeadZone,
        pose: HeadPoseSmoother.SmoothedHeadPose,
        candidateDurationMs: Long,
        rollGatePassed: Boolean,
        crossAxisGatePassed: Boolean,
        lockedUntilCenter: Boolean
    ): HeadGestureResult {

        return HeadGestureResult(
            event = event,
            zone = zone,
            yawDeg = pose.yawDeg,
            pitchDeg = pose.pitchDeg,
            rollDeg = pose.rollDeg,
            candidateDurationMs =
                candidateDurationMs,
            rollGatePassed =
                rollGatePassed,
            crossAxisGatePassed =
                crossAxisGatePassed,
            lockedUntilCenter =
                lockedUntilCenter
        )
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