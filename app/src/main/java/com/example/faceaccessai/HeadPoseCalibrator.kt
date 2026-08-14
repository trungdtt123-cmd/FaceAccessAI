package com.example.faceaccessai


class HeadPoseCalibrator(
    private val calibrationDurationMs: Long = 3000L,
    private val minimumSampleCount: Int = 30
) {

    enum class CalibrationState {
        IDLE,
        CALIBRATING,
        READY
    }


    data class CalibrationProfile(
        val neutralYawDeg: Float,
        val neutralPitchDeg: Float,
        val neutralRollDeg: Float,
        val sampleCount: Int
    )


    data class CalibratedHeadPose(
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float
    )


    data class CalibrationUpdate(
        val state: CalibrationState,
        val elapsedMs: Long,
        val sampleCount: Int,
        val justCompleted: Boolean,
        val profile: CalibrationProfile?
    )


    private var state =
        CalibrationState.IDLE


    private var calibrationStartTimestampMs =
        0L


    private val yawSamples =
        mutableListOf<Float>()


    private val pitchSamples =
        mutableListOf<Float>()


    private val rollSamples =
        mutableListOf<Float>()


    private var profile:
            CalibrationProfile? = null


    // Bắt đầu calibration
    fun start(
        timestampMs: Long
    ) {

        yawSamples.clear()
        pitchSamples.clear()
        rollSamples.clear()

        profile = null

        calibrationStartTimestampMs =
            timestampMs

        state =
            CalibrationState.CALIBRATING
    }


    // Nhận một mẫu head pose
    fun update(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        timestampMs: Long
    ): CalibrationUpdate {

        if (
            state !=
            CalibrationState.CALIBRATING
        ) {

            return CalibrationUpdate(
                state = state,
                elapsedMs = 0L,
                sampleCount = yawSamples.size,
                justCompleted = false,
                profile = profile
            )
        }


        yawSamples.add(yawDeg)
        pitchSamples.add(pitchDeg)
        rollSamples.add(rollDeg)


        val elapsedMs =
            (
                    timestampMs -
                            calibrationStartTimestampMs
                    ).coerceAtLeast(0L)


        val durationReached =
            elapsedMs >= calibrationDurationMs


        val enoughSamples =
            yawSamples.size >= minimumSampleCount


        if (
            durationReached &&
            enoughSamples
        ) {

            profile =
                CalibrationProfile(
                    neutralYawDeg =
                        median(yawSamples),

                    neutralPitchDeg =
                        median(pitchSamples),

                    neutralRollDeg =
                        median(rollSamples),

                    sampleCount =
                        yawSamples.size
                )


            state =
                CalibrationState.READY


            return CalibrationUpdate(
                state = state,
                elapsedMs = elapsedMs,
                sampleCount = yawSamples.size,
                justCompleted = true,
                profile = profile
            )
        }


        return CalibrationUpdate(
            state = state,
            elapsedMs = elapsedMs,
            sampleCount = yawSamples.size,
            justCompleted = false,
            profile = null
        )
    }


    // Trừ tư thế trung tính
    fun calibrate(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float
    ): CalibratedHeadPose? {

        val currentProfile =
            profile ?: return null


        return CalibratedHeadPose(
            yawDeg =
                yawDeg -
                        currentProfile.neutralYawDeg,

            pitchDeg =
                pitchDeg -
                        currentProfile.neutralPitchDeg,

            rollDeg =
                rollDeg -
                        currentProfile.neutralRollDeg
        )
    }


    fun getProfile():
            CalibrationProfile? {

        return profile
    }


    fun getState():
            CalibrationState {

        return state
    }


    fun reset() {

        state =
            CalibrationState.IDLE

        calibrationStartTimestampMs =
            0L

        yawSamples.clear()
        pitchSamples.clear()
        rollSamples.clear()

        profile = null
    }


    // Median giúp giảm ảnh hưởng của mẫu nhiễu
    private fun median(
        values: List<Float>
    ): Float {

        if (values.isEmpty()) {
            return 0f
        }


        val sorted =
            values.sorted()


        val middle =
            sorted.size / 2


        return if (
            sorted.size % 2 == 0
        ) {

            (
                    sorted[middle - 1] +
                            sorted[middle]
                    ) / 2f

        } else {

            sorted[middle]
        }
    }
}