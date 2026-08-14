package com.example.faceaccessai


class FaceStateDetector(

    /*
     * =========================================================
     * NGƯỠNG MẮT
     * =========================================================
     *
     * Dữ liệu thử nghiệm hiện tại:
     *
     * Mắt mở:
     * AvgEAR khoảng 0.28 - 0.31
     *
     * Mắt nhắm:
     * AvgEAR khoảng 0.007 - 0.02
     *
     * Vì vậy tạm thời:
     *
     * EAR <= 0.10  → CLOSED
     * EAR >= 0.15  → OPEN
     *
     * Khoảng 0.10 - 0.15:
     * giữ nguyên trạng thái trước đó.
     */
    private val eyeClosedThreshold: Float = 0.10f,
    private val eyeOpenThreshold: Float = 0.15f,


    /*
     * =========================================================
     * NGƯỠNG MIỆNG
     * =========================================================
     *
     * Dữ liệu thử nghiệm hiện tại:
     *
     * Miệng đóng:
     * MAR khoảng 0.01
     *
     * Há miệng:
     * MAR khoảng 0.70 - 0.82
     *
     * Tạm thời:
     *
     * MAR >= 0.30 → OPEN
     * MAR <= 0.20 → CLOSED
     *
     * Khoảng 0.20 - 0.30:
     * giữ trạng thái trước.
     */
    private val mouthOpenThreshold: Float = 0.30f,
    private val mouthClosedThreshold: Float = 0.20f

) {


    /*
     * =========================================================
     * TRẠNG THÁI MẮT
     * =========================================================
     */
    enum class EyeState {

        OPEN,

        CLOSED,

        UNKNOWN

    }


    /*
     * =========================================================
     * TRẠNG THÁI MIỆNG
     * =========================================================
     */
    enum class MouthState {

        OPEN,

        CLOSED,

        UNKNOWN

    }


    /*
     * =========================================================
     * KẾT QUẢ TRẠNG THÁI KHUÔN MẶT
     * =========================================================
     */
    data class FaceState(

        val eyeState: EyeState,

        val mouthState: MouthState,

        val averageEAR: Float,

        val mar: Float

    )


    /*
     * Lưu trạng thái frame trước.
     *
     * Điều này cần thiết cho hysteresis.
     */
    private var previousEyeState =
        EyeState.UNKNOWN


    private var previousMouthState =
        MouthState.UNKNOWN


    /*
     * =========================================================
     * HÀM CHÍNH
     * =========================================================
     *
     * Nhận kết quả từ FaceFeatureExtractor
     * và chuyển thành trạng thái.
     */
    fun detect(
        features: FaceFeatureExtractor.FaceFeatures
    ): FaceState {


        /*
         * =====================================================
         * 1. PHÁT HIỆN TRẠNG THÁI MẮT
         * =====================================================
         */
        val eyeState =
            detectEyeState(
                averageEAR = features.averageEAR
            )


        /*
         * =====================================================
         * 2. PHÁT HIỆN TRẠNG THÁI MIỆNG
         * =====================================================
         */
        val mouthState =
            detectMouthState(
                mar = features.mar
            )


        /*
         * Lưu lại để frame tiếp theo sử dụng.
         */
        previousEyeState =
            eyeState


        previousMouthState =
            mouthState


        /*
         * Trả kết quả.
         */
        return FaceState(

            eyeState = eyeState,

            mouthState = mouthState,

            averageEAR = features.averageEAR,

            mar = features.mar

        )
    }


    /*
     * =========================================================
     * EYE STATE
     * =========================================================
     */
    private fun detectEyeState(
        averageEAR: Float
    ): EyeState {


        /*
         * EAR rất thấp
         * → mắt nhắm.
         */
        if (
            averageEAR <=
            eyeClosedThreshold
        ) {

            return EyeState.CLOSED

        }


        /*
         * EAR đủ cao
         * → mắt mở.
         */
        if (
            averageEAR >=
            eyeOpenThreshold
        ) {

            return EyeState.OPEN

        }


        /*
         * Giá trị đang nằm giữa:
         *
         * 0.10 < EAR < 0.15
         *
         * Không đổi trạng thái ngay.
         *
         * Giữ trạng thái frame trước
         * để tránh rung trạng thái.
         */
        return previousEyeState
    }


    /*
     * =========================================================
     * MOUTH STATE
     * =========================================================
     */
    private fun detectMouthState(
        mar: Float
    ): MouthState {


        /*
         * MAR lớn
         * → miệng đang mở.
         */
        if (
            mar >=
            mouthOpenThreshold
        ) {

            return MouthState.OPEN

        }


        /*
         * MAR nhỏ
         * → miệng đang đóng.
         */
        if (
            mar <=
            mouthClosedThreshold
        ) {

            return MouthState.CLOSED

        }


        /*
         * Giá trị nằm giữa:
         *
         * 0.20 < MAR < 0.30
         *
         * Giữ trạng thái trước.
         */
        return previousMouthState
    }


    /*
     * =========================================================
     * RESET
     * =========================================================
     *
     * Sau này có thể gọi khi:
     *
     * - mất khuôn mặt
     * - camera restart
     * - người dùng calibration lại
     */
    fun reset() {

        previousEyeState =
            EyeState.UNKNOWN


        previousMouthState =
            MouthState.UNKNOWN

    }
}