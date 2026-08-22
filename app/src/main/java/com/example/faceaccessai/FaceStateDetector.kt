package com.example.faceaccessai

class FaceStateDetector(

    // EAR nhỏ hơn hoặc bằng mức này được xem là mắt nhắm (Tăng lên 0.18 để cực nhạy cho người mắt nhỏ)
    private val eyeClosedThreshold: Float = 0.18f,

    // EAR lớn hơn hoặc bằng mức này được xem là mắt mở
    private val eyeOpenThreshold: Float = 0.24f,

    // Hạ ngưỡng há miệng để BACK và LOCK dễ kích hoạt hơn, không cần há quá to
    private val mouthOpenThreshold: Float = 0.16f,

    // Ngưỡng đóng thấp hơn để tạo hysteresis cho trạng thái miệng
    private val mouthClosedThreshold: Float = 0.10f

) {

    enum class EyeState {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    enum class MouthState {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    data class FaceState(
        val leftEyeState: EyeState,
        val rightEyeState: EyeState,
        val eyeState: EyeState,
        val mouthState: MouthState,
        val leftEAR: Float,
        val rightEAR: Float,
        val averageEAR: Float,
        val mar: Float
    )

    // Lưu trạng thái mắt của frame trước
    private var previousLeftEyeState = EyeState.UNKNOWN
    private var previousRightEyeState = EyeState.UNKNOWN
    private var previousEyeState = EyeState.UNKNOWN

    // Lưu trạng thái miệng của frame trước
    private var previousMouthState = MouthState.UNKNOWN

    // Chuyển các đặc trưng liên tục thành trạng thái ổn định
    fun detect(
        features: FaceFeatureExtractor.FaceFeatures
    ): FaceState {

        val leftEyeState = detectIndividualEyeState(
            ear = features.leftEAR,
            previous = previousLeftEyeState
        )

        val rightEyeState = detectIndividualEyeState(
            ear = features.rightEAR,
            previous = previousRightEyeState
        )

        val eyeState = detectEyeState(
            averageEAR = features.averageEAR
        )

        val mouthState = detectMouthState(
            mar = features.mar
        )

        previousLeftEyeState = leftEyeState
        previousRightEyeState = rightEyeState
        previousEyeState = eyeState
        previousMouthState = mouthState

        return FaceState(
            leftEyeState = leftEyeState,
            rightEyeState = rightEyeState,
            eyeState = eyeState,
            mouthState = mouthState,
            leftEAR = features.leftEAR,
            rightEAR = features.rightEAR,
            averageEAR = features.averageEAR,
            mar = features.mar
        )
    }

    private fun detectIndividualEyeState(
        ear: Float,
        previous: EyeState
    ): EyeState {
        if (ear <= eyeClosedThreshold) return EyeState.CLOSED
        if (ear >= eyeOpenThreshold) return EyeState.OPEN
        return previous
    }

    // Phân loại trạng thái mắt bằng hysteresis
    private fun detectEyeState(
        averageEAR: Float
    ): EyeState {

        if (
            averageEAR <=
            eyeClosedThreshold
        ) {
            return EyeState.CLOSED
        }

        if (
            averageEAR >=
            eyeOpenThreshold
        ) {
            return EyeState.OPEN
        }

        return previousEyeState
    }

    // Phân loại trạng thái miệng bằng hysteresis
    private fun detectMouthState(
        mar: Float
    ): MouthState {

        if (
            mar >=
            mouthOpenThreshold
        ) {
            return MouthState.OPEN
        }

        if (
            mar <=
            mouthClosedThreshold
        ) {
            return MouthState.CLOSED
        }

        return previousMouthState
    }

    // Reset khi mất khuôn mặt hoặc khởi động lại pipeline
    fun reset() {
        previousLeftEyeState = EyeState.UNKNOWN
        previousRightEyeState = EyeState.UNKNOWN
        previousEyeState = EyeState.UNKNOWN
        previousMouthState = MouthState.UNKNOWN
    }
}