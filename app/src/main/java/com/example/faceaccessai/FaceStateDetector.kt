package com.example.faceaccessai

class FaceStateDetector(

    // EAR nhỏ hơn hoặc bằng mức này được xem là mắt nhắm
    private val eyeClosedThreshold: Float = 0.10f,

    // EAR lớn hơn hoặc bằng mức này được xem là mắt mở
    private val eyeOpenThreshold: Float = 0.15f,

    // Hạ nhẹ ngưỡng để BACK không cần há miệng quá lớn
    private val mouthOpenThreshold: Float = 0.25f,

    // Ngưỡng đóng thấp hơn để tạo hysteresis cho trạng thái miệng
    private val mouthClosedThreshold: Float = 0.15f

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
        val eyeState: EyeState,
        val mouthState: MouthState,
        val averageEAR: Float,
        val mar: Float
    )

    // Lưu trạng thái mắt của frame trước
    private var previousEyeState =
        EyeState.UNKNOWN

    // Lưu trạng thái miệng của frame trước
    private var previousMouthState =
        MouthState.UNKNOWN

    // Chuyển các đặc trưng liên tục thành trạng thái ổn định
    fun detect(
        features: FaceFeatureExtractor.FaceFeatures
    ): FaceState {

        val eyeState =
            detectEyeState(
                averageEAR = features.averageEAR
            )

        val mouthState =
            detectMouthState(
                mar = features.mar
            )

        previousEyeState =
            eyeState

        previousMouthState =
            mouthState

        return FaceState(
            eyeState = eyeState,
            mouthState = mouthState,
            averageEAR = features.averageEAR,
            mar = features.mar
        )
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

        previousEyeState =
            EyeState.UNKNOWN

        previousMouthState =
            MouthState.UNKNOWN
    }
}