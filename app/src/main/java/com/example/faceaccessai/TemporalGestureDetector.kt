package com.example.faceaccessai


class TemporalGestureDetector(

    // Thời gian nhắm mắt tối thiểu để không bị xem là nhiễu
    private val minimumBlinkDurationMs: Long = 80L,

    // Thời gian tối đa để một lần nhắm mắt được xem là blink tự nhiên
    private val maximumBlinkDurationMs: Long = 350L,

    // Thời gian giữ mắt nhắm để xác định hành động có chủ ý
    private val intentionalEyeHoldDurationMs: Long = 800L,

    // Thời gian giữ miệng mở để xác định hành động có chủ ý
    private val intentionalMouthOpenDurationMs: Long = 700L

) {

    // Các sự kiện gesture có thể phát hiện
    enum class GestureEvent {

        // Không có gesture mới
        NONE,

        // Chớp mắt tự nhiên
        NATURAL_BLINK,

        // Giữ mắt nhắm có chủ ý
        INTENTIONAL_EYE_CLOSE,

        // Giữ miệng mở có chủ ý
        INTENTIONAL_MOUTH_OPEN
    }


    // Kết quả nhận dạng gesture theo thời gian
    data class TemporalResult(

        val event: GestureEvent,

        // Thời gian mắt đang nhắm hoặc thời gian của blink vừa hoàn thành
        val eyeClosedDurationMs: Long,

        // Thời gian miệng đang mở
        val mouthOpenDurationMs: Long,

        // Trạng thái mắt hiện tại
        val eyeState: FaceStateDetector.EyeState,

        // Trạng thái miệng hiện tại
        val mouthState: FaceStateDetector.MouthState
    )


    // Thời điểm bắt đầu nhắm mắt
    private var eyeClosedStartTimestampMs: Long? =
        null


    // Tránh phát INTENTIONAL_EYE_CLOSE nhiều lần trong cùng một lần nhắm mắt
    private var intentionalEyeEventTriggered =
        false


    // Trạng thái mắt của frame trước
    private var previousEyeState =
        FaceStateDetector.EyeState.UNKNOWN


    // Thời điểm bắt đầu mở miệng
    private var mouthOpenStartTimestampMs: Long? =
        null


    // Tránh phát INTENTIONAL_MOUTH_OPEN nhiều lần trong cùng một lần mở miệng
    private var intentionalMouthEventTriggered =
        false


    // Trạng thái miệng của frame trước
    private var previousMouthState =
        FaceStateDetector.MouthState.UNKNOWN


    // Cập nhật trạng thái khuôn mặt và nhận dạng gesture theo thời gian
    fun update(
        faceState: FaceStateDetector.FaceState,
        timestampMs: Long
    ): TemporalResult {

        val currentEyeState =
            faceState.eyeState


        val currentMouthState =
            faceState.mouthState


        var detectedEvent =
            GestureEvent.NONE


        var closedDurationMs =
            0L


        var mouthOpenDurationMs =
            0L


        // Xử lý khi mắt đang nhắm
        if (
            currentEyeState ==
            FaceStateDetector.EyeState.CLOSED
        ) {

            // Ghi lại thời điểm chuyển từ mở sang nhắm
            if (
                previousEyeState !=
                FaceStateDetector.EyeState.CLOSED
            ) {

                eyeClosedStartTimestampMs =
                    timestampMs


                intentionalEyeEventTriggered =
                    false
            }


            val startTimestamp =
                eyeClosedStartTimestampMs


            if (startTimestamp != null) {

                closedDurationMs =
                    timestampMs - startTimestamp


                // Phát sự kiện khi người dùng giữ mắt nhắm đủ lâu
                if (
                    closedDurationMs >=
                    intentionalEyeHoldDurationMs &&
                    !intentionalEyeEventTriggered
                ) {

                    detectedEvent =
                        GestureEvent.INTENTIONAL_EYE_CLOSE


                    intentionalEyeEventTriggered =
                        true
                }
            }
        }


        // Xử lý khi mắt đang mở
        else if (
            currentEyeState ==
            FaceStateDetector.EyeState.OPEN
        ) {

            // Kiểm tra lần nhắm mắt vừa kết thúc
            if (
                previousEyeState ==
                FaceStateDetector.EyeState.CLOSED
            ) {

                val startTimestamp =
                    eyeClosedStartTimestampMs


                if (startTimestamp != null) {

                    val totalClosedDuration =
                        timestampMs - startTimestamp


                    // Nhận dạng blink tự nhiên nếu thời gian nhắm nằm trong khoảng cho phép
                    if (
                        !intentionalEyeEventTriggered &&
                        totalClosedDuration >=
                        minimumBlinkDurationMs &&
                        totalClosedDuration <=
                        maximumBlinkDurationMs
                    ) {

                        detectedEvent =
                            GestureEvent.NATURAL_BLINK


                        // Giữ lại thời lượng blink để phục vụ log và thực nghiệm
                        closedDurationMs =
                            totalClosedDuration
                    }
                }
            }


            // Reset chu kỳ nhắm mắt sau khi mắt mở lại
            eyeClosedStartTimestampMs =
                null


            intentionalEyeEventTriggered =
                false
        }


        // Xử lý khi miệng đang mở
        if (
            currentMouthState ==
            FaceStateDetector.MouthState.OPEN
        ) {

            // Ghi lại thời điểm chuyển từ đóng sang mở miệng
            if (
                previousMouthState !=
                FaceStateDetector.MouthState.OPEN
            ) {

                mouthOpenStartTimestampMs =
                    timestampMs


                intentionalMouthEventTriggered =
                    false
            }


            val startTimestamp =
                mouthOpenStartTimestampMs


            if (startTimestamp != null) {

                mouthOpenDurationMs =
                    timestampMs - startTimestamp


                // Phát sự kiện khi người dùng giữ miệng mở đủ lâu
                if (
                    mouthOpenDurationMs >=
                    intentionalMouthOpenDurationMs &&
                    !intentionalMouthEventTriggered &&
                    detectedEvent == GestureEvent.NONE
                ) {

                    detectedEvent =
                        GestureEvent.INTENTIONAL_MOUTH_OPEN


                    intentionalMouthEventTriggered =
                        true
                }
            }
        }


        // Reset chu kỳ mở miệng khi miệng đóng lại
        else if (
            currentMouthState ==
            FaceStateDetector.MouthState.CLOSED
        ) {

            mouthOpenStartTimestampMs =
                null


            intentionalMouthEventTriggered =
                false


            mouthOpenDurationMs =
                0L
        }


        // Lưu trạng thái hiện tại để dùng cho frame tiếp theo
        previousEyeState =
            currentEyeState


        previousMouthState =
            currentMouthState


        return TemporalResult(

            event = detectedEvent,

            eyeClosedDurationMs = closedDurationMs,

            mouthOpenDurationMs = mouthOpenDurationMs,

            eyeState = currentEyeState,

            mouthState = currentMouthState
        )
    }


    // Reset trạng thái khi mất khuôn mặt hoặc khởi động lại pipeline
    fun reset() {

        eyeClosedStartTimestampMs =
            null


        intentionalEyeEventTriggered =
            false


        previousEyeState =
            FaceStateDetector.EyeState.UNKNOWN


        mouthOpenStartTimestampMs =
            null


        intentionalMouthEventTriggered =
            false


        previousMouthState =
            FaceStateDetector.MouthState.UNKNOWN
    }
}