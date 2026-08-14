package com.example.faceaccessai


class TemporalGestureDetector(

    // Thời gian nhắm mắt tối thiểu để không bị xem là nhiễu
    private val minimumBlinkDurationMs: Long = 80L,

    // Thời gian tối đa để một lần nhắm mắt được xem là blink tự nhiên
    private val maximumBlinkDurationMs: Long = 350L,

    // Thời gian giữ mắt nhắm để xác định hành động có chủ ý
    private val intentionalEyeHoldDurationMs: Long = 800L

) {

    // Các sự kiện gesture có thể phát hiện
    enum class GestureEvent {

        // Không có gesture mới
        NONE,

        // Chớp mắt tự nhiên
        NATURAL_BLINK,

        // Giữ mắt nhắm có chủ ý
        INTENTIONAL_EYE_CLOSE
    }


    // Kết quả nhận dạng gesture theo thời gian
    data class TemporalResult(

        val event: GestureEvent,

        // Thời gian mắt đang nhắm
        val eyeClosedDurationMs: Long,

        // Trạng thái mắt hiện tại
        val eyeState: FaceStateDetector.EyeState
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


    // Cập nhật trạng thái mắt và nhận dạng gesture theo thời gian
    fun update(
        faceState: FaceStateDetector.FaceState,
        timestampMs: Long
    ): TemporalResult {

        val currentEyeState =
            faceState.eyeState


        var detectedEvent =
            GestureEvent.NONE


        var closedDurationMs =
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
                    }
                }
            }


            // Reset chu kỳ nhắm mắt sau khi mắt mở lại
            eyeClosedStartTimestampMs =
                null


            intentionalEyeEventTriggered =
                false


            closedDurationMs =
                0L
        }


        // Không phát gesture khi trạng thái mắt chưa xác định
        else {

            detectedEvent =
                GestureEvent.NONE
        }


        // Lưu trạng thái hiện tại để dùng cho frame tiếp theo
        previousEyeState =
            currentEyeState


        return TemporalResult(

            event = detectedEvent,

            eyeClosedDurationMs = closedDurationMs,

            eyeState = currentEyeState
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
    }
}