package com.example.faceaccessai


class HeadPoseSmoother(
    private val alpha: Float = 0.30f
) {

    data class SmoothedHeadPose(
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float
    )


    private var initialized =
        false


    private var smoothedYaw =
        0f


    private var smoothedPitch =
        0f


    private var smoothedRoll =
        0f


    init {

        require(
            alpha > 0f &&
                    alpha <= 1f
        ) {
            "alpha must be in the range (0, 1]"
        }
    }


    // Cập nhật head pose đã làm mượt
    fun update(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float
    ): SmoothedHeadPose {

        if (!initialized) {

            smoothedYaw =
                yawDeg

            smoothedPitch =
                pitchDeg

            smoothedRoll =
                rollDeg

            initialized =
                true

        } else {

            smoothedYaw =
                exponentialAverage(
                    previous = smoothedYaw,
                    current = yawDeg
                )


            smoothedPitch =
                exponentialAverage(
                    previous = smoothedPitch,
                    current = pitchDeg
                )


            smoothedRoll =
                exponentialAverage(
                    previous = smoothedRoll,
                    current = rollDeg
                )
        }


        return SmoothedHeadPose(
            yawDeg = smoothedYaw,
            pitchDeg = smoothedPitch,
            rollDeg = smoothedRoll
        )
    }


    // EMA giúp giảm rung nhưng vẫn giữ phản hồi nhanh
    private fun exponentialAverage(
        previous: Float,
        current: Float
    ): Float {

        return alpha * current +
                (1f - alpha) * previous
    }


    fun reset() {

        initialized =
            false

        smoothedYaw =
            0f

        smoothedPitch =
            0f

        smoothedRoll =
            0f
    }
}